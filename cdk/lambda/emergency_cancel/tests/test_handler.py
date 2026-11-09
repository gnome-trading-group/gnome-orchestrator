"""What the handler refuses before it reads any secret or reaches any venue."""

import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import handler  # noqa: E402

GOOD = {"exchange": "KALSHI", "secretName": "gnome/accounts/steady-heron-01/trading", "reason": "gateway hung"}


@pytest.mark.parametrize(
    "change, message",
    [
        ({"exchange": "BINANCE"}, "exchange must be one of"),
        ({"secretName": "gnome/api-keys/registry"}, "secretName must be"),
        ({"secretName": "gnome/accounts/steady-heron-01/market-data"}, "secretName must be"),
        ({"reason": "  "}, "reason is required"),
    ],
)
def test_a_request_that_could_cancel_the_wrong_thing_is_refused_and_still_alerted(change, message, monkeypatch):
    alerts = []
    monkeypatch.setattr(handler, "_alert", lambda request, result, request_id: alerts.append(result))

    with pytest.raises(ValueError, match=message):
        handler.handler({**GOOD, **change}, None)

    assert len(alerts) == 1 and alerts[0]["error"].startswith("refused:")


def test_the_request_is_read_with_its_defaults():
    request = handler._request(GOOD)

    assert request["actor"] == "unknown"
    assert request["dryRun"] is False


@pytest.mark.parametrize("value, dry", [(True, True), (False, False), ("true", True), ("false", False), (" False ", False)])
def test_a_dry_run_is_read_from_json_or_from_text(value, dry):
    assert handler._request({**GOOD, "dryRun": value})["dryRun"] is dry


def test_a_dry_run_it_cant_read_is_refused():
    with pytest.raises(ValueError, match="dryRun must be true or false"):
        handler._request({**GOOD, "dryRun": "yes"})


def test_a_failure_after_the_request_was_accepted_still_comes_back_and_is_alerted(monkeypatch):
    alerts = []
    monkeypatch.setattr(handler, "_alert", lambda request, result, request_id: alerts.append(result))

    def no_secret(request, seconds_left):
        raise RuntimeError("secret unreadable")

    monkeypatch.setattr(handler, "_cancel", no_secret)

    result = handler.handler(GOOD, None)

    assert result["error"] == "RuntimeError: secret unreadable"
    assert alerts == [result]
