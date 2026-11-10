"""What the handler refuses before it reads any secret or reaches any venue, and how it waits out a lagging list."""

import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import handler  # noqa: E402
import venues  # noqa: E402

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


class LaggingList:
    """A venue whose open-orders list still shows cancelled orders for a few lists, as Kalshi's did."""

    def __init__(self, open_ids, stale_lists, keeps=()):
        self.open_ids = list(open_ids)
        self.stale_lists = stale_lists
        self.keeps = set(keeps)
        self.lists = 0

    def list_open(self):
        self.lists += 1
        if self.lists <= self.stale_lists:
            return [venues.OpenOrder(i) for i in self.open_ids]
        return [venues.OpenOrder(i) for i in self.open_ids if i in self.keeps]


@pytest.fixture
def no_pause(monkeypatch):
    monkeypatch.setattr(handler, "SETTLE_PAUSE_SECONDS", 0.0)


def test_orders_a_lagging_list_still_shows_are_listed_again_until_they_go(no_pause):
    venue = LaggingList(["o1", "o2", "o3"], stale_lists=3)

    assert handler._settled(venue, lambda: 300.0) == []
    assert venue.lists == 4


def test_an_order_that_stays_open_is_reported_once_the_wait_is_over(no_pause, monkeypatch):
    monkeypatch.setattr(handler, "SETTLE_SECONDS", 0.05)
    venue = LaggingList(["o1", "o2"], stale_lists=0, keeps={"o2"})

    assert [order.order_id for order in handler._settled(venue, lambda: 300.0)] == ["o2"]


def test_no_list_again_once_the_run_is_nearly_out_of_time(no_pause):
    venue = LaggingList(["o1"], stale_lists=5)

    assert [order.order_id for order in handler._settled(venue, lambda: handler.SETTLE_FLOOR_SECONDS)] == ["o1"]
    assert venue.lists == 1
