"""Cancels every open order on one venue account, without the order gateway.

For when the gateway itself is broken or hung: every live order goes through it, so then nothing else can cancel
them, and Kalshi and Polymarket US cancel nothing on their own. Invoked by hand (the runbook's `aws lambda invoke`) or
from the controller's Treasury page. It takes the account's exchange and trading secret by name rather than looking
them up in the registry, which may be down too. It must run in the region the exchange trades from (the registry's
sm.exchange.region, where the account's order gateway runs): a venue refuses orders, and cancels, from anywhere else,
so a call from the wrong region comes back as failures.

Event: {"exchange": "KALSHI" | "POLYMARKET_US" | "POLYMARKET_INTL", "secretName": "gnome/accounts/<label>/trading",
        "reason": "...", "actor": "...", "accountId": 4 (optional, for the alert), "dryRun": false}
Returns what was open before, what failed to cancel, and what is still open after. Every call raises an
EMERGENCY_CANCEL alert. Never retried on its own: the caller decides whether to run it again.
"""

import datetime
import json
import os
import uuid

import boto3

from venues import VENUES, VenueError

BASE_URL_ENV = {"KALSHI": "KALSHI_API_URL", "POLYMARKET_US": "POLYMARKET_US_API_URL",
                "POLYMARKET_INTL": "POLYMARKET_INTL_CLOB_URL"}
SECRET_PREFIX = "gnome/accounts/"
# Alerts go to the account's alerting bus, which lives in its primary region.
ALERT_REGION = "us-east-1"
ALERT_BUS = "gnome-alerts"
MAX_LISTED = 50


def handler(event, context):
    request_id = getattr(context, "aws_request_id", None) or str(uuid.uuid4())
    try:
        request = _request(event)
    except ValueError as error:
        # Refused before it touched anything: still on the record, as whoever asked expects an answer there.
        _alert(_what_was_asked(event), {"error": f"refused: {error}"}, request_id)
        raise
    result = {
        "exchange": request["exchange"],
        "region": os.environ.get("AWS_REGION", ""),
        "secretName": request["secretName"],
        "dryRun": request["dryRun"],
    }
    try:
        result.update(_cancel(request, _seconds_left(context)))
    except Exception as error:  # noqa: BLE001 - whatever went wrong, the alert must say so and the result come back
        result["error"] = f"{type(error).__name__}: {error}"
    print(json.dumps({"message": "EmergencyCancel result", **result}))
    _alert(request, result, request_id)
    return result


def _cancel(request: dict, seconds_left) -> dict:
    """Lists the account's open orders, cancels each unless a dry run, and lists them again."""
    secret = boto3.client("secretsmanager").get_secret_value(SecretId=request["secretName"])["SecretString"]
    venue = VENUES[request["exchange"]](json.loads(secret), os.environ[BASE_URL_ENV[request["exchange"]]])
    before = venue.list_open()
    failures = [] if request["dryRun"] else venue.cancel(before, seconds_left)
    after = venue.list_open()
    return {
        "openBefore": len(before),
        "failedCount": len(failures),
        "failed": failures[:MAX_LISTED],
        "openAfter": len(after),
        "stillOpen": [order.order_id for order in after][:MAX_LISTED],
    }


def _seconds_left(context):
    """How long the run has left; unbounded outside Lambda."""
    if context is None or not hasattr(context, "get_remaining_time_in_millis"):
        return lambda: float("inf")
    return lambda: context.get_remaining_time_in_millis() / 1000.0


def _request(event) -> dict:
    exchange = event.get("exchange")
    if exchange not in VENUES:
        raise ValueError(f"exchange must be one of {sorted(VENUES)}, not {exchange!r}")
    secret_name = event.get("secretName") or ""
    # Only a trading account's secret: this function's role can read nothing else, and a name says which was meant.
    if not (secret_name.startswith(SECRET_PREFIX) and secret_name.endswith("/trading")):
        raise ValueError(f"secretName must be {SECRET_PREFIX}<label>/trading, not {secret_name!r}")
    reason = (event.get("reason") or "").strip()
    if not reason:
        raise ValueError("reason is required: every emergency cancel is on the record")
    return {
        "exchange": exchange,
        "secretName": secret_name,
        "reason": reason,
        "actor": (event.get("actor") or "unknown").strip(),
        "accountId": event.get("accountId"),
        "dryRun": _dry_run(event.get("dryRun", False)),
    }


def _dry_run(value) -> bool:
    """True or false, as JSON or as the text a command line gives; anything else is refused, not guessed at."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in ("true", "false"):
        return value.strip().lower() == "true"
    raise ValueError(f"dryRun must be true or false, not {value!r}")


def _what_was_asked(event) -> dict:
    """A refused request as far as it can be read, for its alert."""
    event = event if isinstance(event, dict) else {}
    return {
        "exchange": str(event.get("exchange") or "?"),
        "secretName": str(event.get("secretName") or ""),
        "reason": str(event.get("reason") or "").strip() or "(none given)",
        "actor": str(event.get("actor") or "unknown"),
        "accountId": event.get("accountId") if isinstance(event.get("accountId"), int) else None,
        "dryRun": event.get("dryRun") is True,
    }


def _alert(request: dict, result: dict, request_id: str) -> None:
    """Puts the call on the record as an alert; a failure to is logged, never allowed to hide the result."""
    name = request["secretName"]
    label = name[len(SECRET_PREFIX) : -len("/trading")] if name.startswith(SECRET_PREFIX) else (name or "?")
    if "error" in result:
        outcome = f"failed: {result['error']}"
    else:
        outcome = (f"{result['openBefore']} open before, {result['failedCount']} failed to cancel, "
                   f"{result['openAfter']} still open")
    detail = {
        "action": "FIRING",
        "key": f"emergency-cancel:{request_id}",
        "type": "EMERGENCY_CANCEL",
        "source": "ingest",
        "title": f"Emergency cancel{' (dry run)' if request['dryRun'] else ''} on {label} ({request['exchange']})",
        "body": f"{outcome}. Reason: {request['reason']}",
        "actor": request["actor"],
        "accountId": request["accountId"],
        "mode": "live",
        "labels": {"exchange": request["exchange"], "account": label, "dryRun": str(request["dryRun"]).lower()},
        "stateAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }
    try:
        answer = boto3.client("events", region_name=ALERT_REGION).put_events(Entries=[{
            "Source": "gnome.alerts",
            "DetailType": "Alert",
            "EventBusName": ALERT_BUS,
            "Detail": json.dumps(detail),
        }])
        if answer.get("FailedEntryCount"):
            raise RuntimeError(str(answer.get("Entries")))
    except Exception as error:  # noqa: BLE001 - the cancel already happened; its result must still come back
        print(json.dumps({"message": "EMERGENCY_CANCEL alert not sent", "error": str(error), "detail": detail}))
