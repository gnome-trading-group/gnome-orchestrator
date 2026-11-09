"""Each venue client against a small venue of our own on a local socket: it holds open orders, checks every request's
signature with the account's real key, and cancels what it is asked to, so a request built wrong fails here."""

import base64
import hashlib
import hmac
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, padding, rsa

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from venues import OUT_OF_TIME, Http, Kalshi, OpenOrder, PolymarketIntl, PolymarketUs, VenueError  # noqa: E402


def FOREVER():
    return float("inf")


class FakeVenue:
    """A venue's open orders behind a real HTTP server; ``route`` answers each request as the venue would."""

    def __init__(self, route):
        self.route = route
        self.requests = []
        self.rate_limit_next = 0
        venue = self

        class Handler(BaseHTTPRequestHandler):
            def _answer(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                venue.requests.append((self.command, self.path))
                if venue.rate_limit_next > 0:
                    venue.rate_limit_next -= 1
                    self.send_response(429)
                    self.send_header("Retry-After", "0")
                    self.end_headers()
                    return
                status, answer = venue.route(self.command, self.path, self.headers, body)
                payload = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_POST = do_DELETE = _answer

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()


# ----- Kalshi -----

@pytest.fixture
def kalshi_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def kalshi_venue(key, resting):
    public = key.public_key()

    def route(method, path, headers, body):
        signed = headers["KALSHI-ACCESS-TIMESTAMP"] + method + urlparse(path).path
        public.verify(
            base64.b64decode(headers["KALSHI-ACCESS-SIGNATURE"]),
            signed.encode(),
            padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32),
            hashes.SHA256(),
        )
        url = urlparse(path)
        if method == "GET" and url.path == Kalshi.ORDERS_PATH:
            query = parse_qs(url.query)
            assert query["status"] == ["resting"]
            ids = sorted(resting)
            # Two pages, so the cursor is followed.
            start = int(query.get("cursor", ["0"])[0])
            page = ids[start : start + 2]
            cursor = str(start + 2) if start + 2 < len(ids) else ""
            return 200, {"orders": [{"order_id": i, "ticker": resting[i]} for i in page], "cursor": cursor}
        if method == "DELETE" and url.path.startswith(Kalshi.CANCEL_PATH):
            order_id = url.path[len(Kalshi.CANCEL_PATH) :]
            assert parse_qs(url.query)["market_ticker"] == [resting[order_id]]
            del resting[order_id]
            return 200, {"order": {"order_id": order_id, "status": "canceled"}}
        return 404, {"error": "unknown path"}

    return FakeVenue(route)


def test_kalshi_cancels_every_resting_order_across_pages(kalshi_key):
    resting = {"o1": "KXA", "o2": "KXA", "o3": "KXB"}
    venue = kalshi_venue(kalshi_key, resting)
    try:
        client = Kalshi({"apiKey": "k", "privateKey": pem(kalshi_key)}, venue.url)
        before = client.list_open()

        assert sorted(o.order_id for o in before) == ["o1", "o2", "o3"]
        assert client.cancel(before, FOREVER) == []
        assert client.list_open() == []
    finally:
        venue.close()


def test_kalshi_waits_out_a_rate_limit(kalshi_key):
    resting = {"o1": "KXA"}
    venue = kalshi_venue(kalshi_key, resting)
    venue.rate_limit_next = 2
    try:
        client = Kalshi({"apiKey": "k", "privateKey": pem(kalshi_key)}, venue.url)

        assert [o.order_id for o in client.list_open()] == ["o1"]
        assert len(venue.requests) == 3
    finally:
        venue.close()


def pem(key) -> str:
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()


# ----- Polymarket US -----

def test_polymarket_us_cancels_open_orders_in_batches_of_twenty():
    key = ed25519.Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    public = key.public_key()
    open_orders = {f"id{i}": "nba-lal-bos" for i in range(25)}
    batches = []

    def route(method, path, headers, body):
        public.verify(
            base64.b64decode(headers["X-PM-Signature"]),
            (headers["X-PM-Timestamp"] + method + urlparse(path).path).encode(),
        )
        if method == "GET" and path == PolymarketUs.OPEN_ORDERS_PATH:
            return 200, {"orders": [{"id": i, "marketSlug": s} for i, s in open_orders.items()]}
        if method == "POST" and path == PolymarketUs.BATCH_CANCEL_PATH:
            orders = json.loads(body)["orders"]
            batches.append(len(orders))
            for order in orders:
                assert order["marketSlug"] == open_orders.pop(order["orderId"])
            return 200, {"orderIds": [o["orderId"] for o in orders]}
        return 404, {}

    venue = FakeVenue(route)
    try:
        # A key exported with its public half after the seed, as some are.
        client = PolymarketUs({"apiKey": "k", "secret": base64.b64encode(seed + b"\x00" * 32).decode()}, venue.url)
        before = client.list_open()

        assert len(before) == 25
        assert client.cancel(before, FOREVER) == []
        assert batches == [20, 5]
        assert client.list_open() == []
    finally:
        venue.close()


# ----- Polymarket International -----

URL_SAFE_SECRET = "4OHi4-Tl5ufo6err7O3u7_Dx8vP09fb3-Pn6-_z9_v8="


def intl_client(url):
    return PolymarketIntl(
        {"apiKey": "k", "secret": URL_SAFE_SECRET, "passphrase": "p", "signerAddress": "0xabc"}, url
    )


def test_polymarket_intl_signs_as_the_reference_client_does():
    client = intl_client("http://unused")

    # The gateway's PolymarketIntlAuthHeadersTest vectors, generated by py-clob-client-v2's build_hmac_signature.
    assert client.signature("1759350000", "DELETE", "/order", b'{"orderID":"0xabc"}') == (
        "Zp21kYIP4cRdMwMHctMmJSpSjL0XsR2qEHTpP1IzIO0="
    )
    assert client.signature("1759350000", "GET", "/data/order/0xabc", None) == (
        "YMCLmvAW6KCEY6lREO5IngSYsaMh-6dCOMRTR-ozBSY="
    )


def test_polymarket_intl_cancels_each_order_and_reports_one_the_venue_refused():
    secret = base64.urlsafe_b64decode(URL_SAFE_SECRET)
    live = {"0x1": "LIVE", "0x2": "LIVE", "0x3": "LIVE"}

    def route(method, path, headers, body):
        signed_path = urlparse(path).path
        expected = base64.urlsafe_b64encode(
            hmac.new(secret, (headers["POLY_TIMESTAMP"] + method + signed_path).encode() + body, hashlib.sha256)
            .digest()
        ).decode()
        assert headers["POLY_SIGNATURE"] == expected
        assert headers["POLY_ADDRESS"] == "0xabc"
        if method == "GET" and signed_path == PolymarketIntl.ORDERS_PATH:
            cursor = parse_qs(urlparse(path).query).get("next_cursor", [""])[0]
            ids = sorted(live)
            if not cursor:
                return 200, {"data": [{"id": i, "status": live[i]} for i in ids[:2]], "next_cursor": "MQ=="}
            return 200, {"data": [{"id": i, "status": live[i]} for i in ids[2:]], "next_cursor": "LTE="}
        if method == "DELETE" and path == PolymarketIntl.CANCEL_PATH:
            order_id = json.loads(body)["orderID"]
            if order_id == "0x2":
                # The CLOB answers 200 when it refuses too, naming the order under not_canceled.
                return 200, {"canceled": [], "not_canceled": {order_id: "order can't be cancelled yet"}}
            del live[order_id]
            return 200, {"canceled": [order_id], "not_canceled": {}}
        return 404, {}

    venue = FakeVenue(route)
    try:
        client = intl_client(venue.url)
        before = client.list_open()

        assert sorted(o.order_id for o in before) == ["0x1", "0x2", "0x3"]
        failures = client.cancel(before, FOREVER)
        assert len(failures) == 1 and failures[0].startswith("0x2:")
        assert client.list_open() == [OpenOrder("0x2")]
    finally:
        venue.close()


def test_no_cancel_starts_once_the_run_is_nearly_out_of_time(kalshi_key):
    resting = {"o1": "KXA", "o2": "KXA"}
    venue = kalshi_venue(kalshi_key, resting)
    try:
        client = Kalshi({"apiKey": "k", "privateKey": pem(kalshi_key)}, venue.url)
        before = client.list_open()

        failures = client.cancel(before, lambda: 5.0)

        assert sorted(failures) == [f"o1: {OUT_OF_TIME}", f"o2: {OUT_OF_TIME}"]
        assert len(resting) == 2, "nothing was sent"
    finally:
        venue.close()


def test_a_venue_that_never_answers_is_a_venue_error():
    # Nothing listens on this port.
    http = Http("http://127.0.0.1:9")

    with pytest.raises(VenueError, match="got no answer"):
        http.request("GET", "/x", dict)
