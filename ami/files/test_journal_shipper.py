"""Tests for journal-shipper.py. Run with: python -m unittest discover -s ami/files -p 'test_*.py'"""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

import zstandard

SPEC = importlib.util.spec_from_file_location("journal_shipper", Path(__file__).with_name("journal-shipper.py"))
journal_shipper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(journal_shipper)


class FakeS3:
    """Keeps what is uploaded to it, failing the first `failures` uploads."""

    def __init__(self, failures: int = 0):
        self.failures = failures
        self.objects: dict[tuple[str, str], bytes] = {}
        self.order: list[str] = []

    def upload_file(self, filename: str, bucket: str, key: str) -> None:
        if self.failures > 0:
            self.failures -= 1
            raise ConnectionError("unavailable")
        self.objects[(bucket, key)] = Path(filename).read_bytes()
        self.order.append(key)


class ShipperTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.session = self.root / "session-1"
        self.session.mkdir()
        (self.session / "journal.json").write_text(
            json.dumps({"bucket": "gnome-journals-dev", "keyPrefix": "session-1", "region": "us-east-1"})
        )
        self.s3 = FakeS3()
        self.regions: list[str] = []
        self.shipper = journal_shipper.Shipper(self.root, self._client)

    def tearDown(self):
        self._tmp.cleanup()

    def _client(self, region: str):
        self.regions.append(region)
        return self.s3

    def _segment(self, name: str, content: bytes) -> Path:
        path = self.session / name
        path.write_bytes(content)
        return path

    def _uploaded(self, index: int) -> bytes:
        compressed = self.s3.objects[("gnome-journals-dev", f"session-1/segment-{index:05d}.zst")]
        return zstandard.ZstdDecompressor().stream_reader(compressed).read()

    def test_a_sealed_segment_is_uploaded_compressed_then_deleted(self):
        segment = self._segment("segment-00000.done", b"records" * 1000)

        left = self.shipper.ship_pending(include_open=False, now=0.0)

        self.assertEqual(0, left)
        self.assertEqual(b"records" * 1000, self._uploaded(0))
        self.assertFalse(segment.exists())
        self.assertEqual([], [p.name for p in self.session.iterdir() if p.name != "journal.json"])
        self.assertEqual(["us-east-1"], self.regions)

    def test_an_open_segment_waits_until_the_session_has_ended(self):
        open_segment = self._segment("segment-00001.bin", b"open" + bytes(64))

        self.shipper.ship_pending(include_open=False, now=0.0)
        self.assertTrue(open_segment.exists())
        self.assertEqual({}, self.s3.objects)

        self.shipper.ship_pending(include_open=True, now=0.0)
        self.assertFalse(open_segment.exists())
        self.assertEqual(b"open" + bytes(64), self._uploaded(1))

    def test_segments_ship_in_journal_order(self):
        self._segment("segment-00002.bin", b"c")
        self._segment("segment-00010.done", b"b")
        self._segment("segment-00001.done", b"a")

        self.shipper.ship_pending(include_open=True, now=0.0)

        self.assertEqual(
            ["session-1/segment-00001.zst", "session-1/segment-00002.zst", "session-1/segment-00010.zst"],
            self.s3.order,
        )

    def test_a_failed_upload_keeps_the_segment_and_retries_after_a_backoff(self):
        segment = self._segment("segment-00000.done", b"records")
        self.s3.failures = 2

        self.assertEqual(1, self.shipper.ship_pending(include_open=False, now=0.0))
        self.assertTrue(segment.exists())
        self.assertEqual(1, self.shipper.ship_pending(include_open=False, now=0.5), "too soon to retry")
        self.assertEqual(1, self.s3.failures, "the backoff skipped an attempt")

        self.assertEqual(1, self.shipper.ship_pending(include_open=False, now=1.0))
        self.assertEqual(1, self.shipper.ship_pending(include_open=False, now=2.5), "the backoff doubles")
        self.assertEqual(0, self.shipper.ship_pending(include_open=False, now=3.0))
        self.assertFalse(segment.exists())
        self.assertEqual(b"records", self._uploaded(0))
        self.assertEqual([], [p.name for p in self.session.iterdir() if p.suffix == ".part"])

    def test_a_directory_without_a_manifest_is_left_alone(self):
        stray = self.root / "not-a-session"
        stray.mkdir()
        (stray / "segment-00000.done").write_bytes(b"x")

        self.shipper.ship_pending(include_open=True, now=0.0)

        self.assertTrue((stray / "segment-00000.done").exists())
        self.assertEqual({}, self.s3.objects)


if __name__ == "__main__":
    unittest.main()
