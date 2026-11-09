"""Each venue's open orders and how to cancel them, on its plain REST API.

This is the break-glass for a broken order gateway, so it shares no code with it: a bug there can't reach here. It
calls only endpoints the gateway already uses against the live venues, the same way: no venue-wide cancel-all, whose
behaviour isn't verified, but the account's open orders listed and each cancelled, then listed again.
"""

import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, padding

# Some venues sit behind Cloudflare, which refuses the default Python agent.
USER_AGENT = "gnome-emergency-cancel/1"
REQUEST_TIMEOUT_SECONDS = 10
# A venue that rate limits us is asked again after a pause, a few times: the gateway may still be spending the budget.
RATE_LIMIT_RETRIES = 5
RATE_LIMIT_PAUSE_SECONDS = 1.0
# The longest a venue's Retry-After is waited, so one answer can't spend the run's time.
MAX_RETRY_AFTER_SECONDS = 5.0
CANCEL_THREADS = 8
# No new cancel starts with less than this left of the run, so its last cancels are answered and its result reported.
STOP_SECONDS = 20.0
OUT_OF_TIME = "not tried: the run was out of time"


class VenueError(Exception):
    """The venue answered something other than success."""


@dataclass(frozen=True)
class OpenOrder:
    """One open order: the venue's id, and the market it rests on where the cancel must name it."""

    order_id: str
    market: str = ""


@dataclass(frozen=True)
class Response:
    status: int
    body: object


class Http:
    """Blocking JSON over HTTPS to one venue host, retrying a rate-limited request."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(self, method: str, path: str, headers_for, body: bytes | None = None) -> Response:
        """Sends the request; ``headers_for()`` signs it afresh on every attempt, as signatures carry a timestamp."""
        for attempt in range(RATE_LIMIT_RETRIES + 1):
            headers = {"User-Agent": USER_AGENT, "Accept": "application/json", **headers_for()}
            if body is not None:
                headers["Content-Type"] = "application/json"
            request = urllib.request.Request(self.base_url + path, data=body, method=method, headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as answer:
                    return Response(answer.status, _json(answer.read()))
            except urllib.error.HTTPError as error:
                text = error.read()
                if error.code == 429 and attempt < RATE_LIMIT_RETRIES:
                    pause = _retry_after(error) or RATE_LIMIT_PAUSE_SECONDS * (attempt + 1)
                    time.sleep(min(pause, MAX_RETRY_AFTER_SECONDS))
                    continue
                raise VenueError(f"{method} {path} answered {error.code}: {text[:300]!r}") from error
            except (urllib.error.URLError, OSError) as error:
                # Refused, reset or timed out: the venue never answered, which is as much a failure as a refusal.
                raise VenueError(f"{method} {path} got no answer: {error}") from error
        raise VenueError(f"{method} {path} still rate limited after {RATE_LIMIT_RETRIES} retries")


def _json(raw: bytes):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return raw.decode("utf-8", "replace")


def _retry_after(error: urllib.error.HTTPError) -> float | None:
    value = error.headers.get("Retry-After") if error.headers else None
    try:
        return float(value) if value else None
    except ValueError:
        return None


def _cancel_each(cancel_one, orders: list[OpenOrder], seconds_left) -> list[str]:
    """Cancels each order on a few threads, starting none once the run is nearly out of time; returns why each that
    wasn't cancelled wasn't."""
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=CANCEL_THREADS) as pool:
        for order, error in zip(orders, pool.map(_try(cancel_one, seconds_left), orders)):
            if error is not None:
                failures.append(f"{order.order_id}: {error}")
    return failures


def _try(cancel_one, seconds_left):
    def attempt(order: OpenOrder) -> str | None:
        if seconds_left() < STOP_SECONDS:
            return OUT_OF_TIME
        try:
            cancel_one(order)
            return None
        except Exception as error:  # noqa: BLE001 - every order gets its own answer; one failure stops no other
            return str(error)

    return attempt


class Kalshi:
    """Kalshi: RSA-PSS (SHA-256, 32-byte salt) over timestamp millis + METHOD + path without its query."""

    ORDERS_PATH = "/trade-api/v2/portfolio/orders"
    CANCEL_PATH = "/trade-api/v2/portfolio/events/orders/"
    PAGE_LIMIT = 200

    def __init__(self, credentials: dict, base_url: str):
        self.api_key = credentials["apiKey"]
        self.private_key = serialization.load_pem_private_key(credentials["privateKey"].encode(), password=None)
        self.http = Http(base_url)

    def headers(self, method: str, path: str) -> dict:
        timestamp = str(int(time.time() * 1000))
        signature = self.private_key.sign(
            (timestamp + method + path.split("?")[0]).encode(),
            padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=padding.PSS.DIGEST_LENGTH),
            hashes.SHA256(),
        )
        return {
            "KALSHI-ACCESS-KEY": self.api_key,
            "KALSHI-ACCESS-TIMESTAMP": timestamp,
            "KALSHI-ACCESS-SIGNATURE": base64.b64encode(signature).decode(),
        }

    def list_open(self) -> list[OpenOrder]:
        orders: list[OpenOrder] = []
        cursor = ""
        while True:
            path = f"{self.ORDERS_PATH}?limit={self.PAGE_LIMIT}&status=resting"
            if cursor:
                path += "&cursor=" + urllib.parse.quote(cursor, safe="")
            page = self.http.request("GET", path, lambda p=path: self.headers("GET", p)).body or {}
            for order in page.get("orders") or []:
                orders.append(OpenOrder(order["order_id"], order.get("ticker", "")))
            cursor = page.get("cursor") or ""
            if not cursor:
                return orders

    def cancel(self, orders: list[OpenOrder], seconds_left) -> list[str]:
        return _cancel_each(self._cancel_one, orders, seconds_left)

    def _cancel_one(self, order: OpenOrder) -> None:
        # The ticker routes the cancel to the market's shard; an order id alone can't name it.
        path = self.CANCEL_PATH + urllib.parse.quote(order.order_id, safe="")
        if order.market:
            path += "?market_ticker=" + urllib.parse.quote(order.market, safe="")
        self.http.request("DELETE", path, lambda: self.headers("DELETE", path))


class PolymarketUs:
    """Polymarket US: Ed25519 over timestamp millis + METHOD + path without its query."""

    OPEN_ORDERS_PATH = "/v1/orders/open"
    BATCH_CANCEL_PATH = "/v1/orders/batched/cancel"
    # A batch of up to 20 counts once against the account's rate limit.
    MAX_BATCH = 20
    SEED_LENGTH = 32

    def __init__(self, credentials: dict, base_url: str):
        self.api_key = credentials["apiKey"]
        # Some key exports carry the seed and the public key; the first 32 bytes are the private key.
        seed = base64.b64decode(credentials["secret"])[: self.SEED_LENGTH]
        self.private_key = ed25519.Ed25519PrivateKey.from_private_bytes(seed)
        self.http = Http(base_url)

    def headers(self, method: str, path: str) -> dict:
        timestamp = str(int(time.time() * 1000))
        signature = self.private_key.sign((timestamp + method + path.split("?")[0]).encode())
        return {
            "X-PM-Access-Key": self.api_key,
            "X-PM-Timestamp": timestamp,
            "X-PM-Signature": base64.b64encode(signature).decode(),
        }

    def list_open(self) -> list[OpenOrder]:
        page = self.http.request(
            "GET", self.OPEN_ORDERS_PATH, lambda: self.headers("GET", self.OPEN_ORDERS_PATH)
        ).body or {}
        return [OpenOrder(order["id"], order.get("marketSlug", "")) for order in page.get("orders") or []]

    def cancel(self, orders: list[OpenOrder], seconds_left) -> list[str]:
        failures: list[str] = []
        for start in range(0, len(orders), self.MAX_BATCH):
            batch = orders[start : start + self.MAX_BATCH]
            if seconds_left() < STOP_SECONDS:
                failures.extend(f"{o.order_id}: {OUT_OF_TIME}" for o in batch)
                continue
            body = json.dumps(
                {"orders": [{"orderId": o.order_id, "marketSlug": o.market} for o in batch]}, separators=(",", ":")
            ).encode()
            try:
                self.http.request(
                    "POST", self.BATCH_CANCEL_PATH, lambda: self.headers("POST", self.BATCH_CANCEL_PATH), body
                )
            except Exception as error:  # noqa: BLE001 - one batch failing stops no other; each order gets its answer
                failures.extend(f"{o.order_id}: {error}" for o in batch)
        return failures


class PolymarketIntl:
    """Polymarket International's CLOB L2 auth: URL-safe base64 HMAC-SHA256 over timestamp seconds + METHOD + path
    + body, under the API key's URL-safe base64 secret."""

    ORDERS_PATH = "/data/orders"
    CANCEL_PATH = "/order"
    # The cursor the CLOB returns after the last page.
    END_CURSOR = "LTE="
    WORKING = {"LIVE", "DELAYED", "UNMATCHED"}

    def __init__(self, credentials: dict, base_url: str):
        self.api_key = credentials["apiKey"]
        self.secret = base64.urlsafe_b64decode(credentials["secret"])
        self.passphrase = credentials["passphrase"]
        self.address = credentials["signerAddress"]
        self.http = Http(base_url)

    def signature(self, timestamp: str, method: str, path: str, body: bytes | None) -> str:
        message = (timestamp + method + path).encode() + (body or b"")
        return base64.urlsafe_b64encode(hmac.new(self.secret, message, hashlib.sha256).digest()).decode()

    def headers(self, method: str, path: str, body: bytes | None = None) -> dict:
        timestamp = str(int(time.time()))
        return {
            "POLY_API_KEY": self.api_key,
            "POLY_SIGNATURE": self.signature(timestamp, method, path, body),
            "POLY_TIMESTAMP": timestamp,
            "POLY_PASSPHRASE": self.passphrase,
            "POLY_ADDRESS": self.address,
        }

    def list_open(self) -> list[OpenOrder]:
        orders: list[OpenOrder] = []
        cursor = ""
        while True:
            path = self.ORDERS_PATH + ("?next_cursor=" + urllib.parse.quote(cursor, safe="") if cursor else "")
            # The CLOB verifies the signature over the path without its query.
            page = self.http.request("GET", path, lambda: self.headers("GET", self.ORDERS_PATH)).body or {}
            for order in page.get("data") or []:
                if order.get("status", "LIVE") in self.WORKING:
                    orders.append(OpenOrder(order["id"]))
            cursor = page.get("next_cursor") or ""
            if not cursor or cursor == self.END_CURSOR:
                return orders

    def cancel(self, orders: list[OpenOrder], seconds_left) -> list[str]:
        return _cancel_each(self._cancel_one, orders, seconds_left)

    def _cancel_one(self, order: OpenOrder) -> None:
        body = json.dumps({"orderID": order.order_id}, separators=(",", ":")).encode()
        answer = self.http.request(
            "DELETE", self.CANCEL_PATH, lambda: self.headers("DELETE", self.CANCEL_PATH, body), body
        ).body or {}
        # The CLOB answers 200 even when it refuses, naming the order under not_canceled instead.
        if order.order_id not in (answer.get("canceled") or []):
            raise VenueError(f"not cancelled: {answer.get('not_canceled') or answer}")


# By the registry's exchange code: which client, and so which signing, an account's key needs.
VENUES = {"KALSHI": Kalshi, "POLYMARKET_US": PolymarketUs, "POLYMARKET_INTL": PolymarketIntl}
