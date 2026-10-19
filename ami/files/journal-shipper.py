"""Ships a session's journal segments to S3, from outside the trading process.

The orchestrator writes each session's journal under JOURNAL_ROOT/<sessionId>/, with a journal.json naming the
bucket, key prefix and region. A segment is renamed from segment-NNNNN.bin to segment-NNNNN.done once finished.
Compressing and uploading allocate and wait on the network, which is why they run here and not in the trading JVM.

While running, sealed (.done) segments are shipped as they appear. On SIGTERM, which systemd sends once the strategy
unit has stopped, the open (.bin) segments are shipped too: a crashed process leaves its records in the file, and
readers stop at the zeros after the last one.

Runs as the gnome-journal-shipper systemd unit, in /opt/gnome/shipper-venv (boto3, zstandard).
"""
from __future__ import annotations

import json
import logging
import os
import re
import signal
import sys
import time
from pathlib import Path

import boto3
import zstandard

JOURNAL_ROOT = Path(os.environ.get("JOURNAL_ROOT", "/var/lib/gnome/journal"))
MANIFEST = "journal.json"
POLL_SECONDS = 2.0
FIRST_RETRY_SECONDS = 1.0
MAX_RETRY_SECONDS = 60.0
# A low level: shipping keeps up easily, and every core cycle spent here is one the session's housekeeping lacks.
ZSTD_LEVEL = 3

SEGMENT = re.compile(r"segment-(\d{5})\.(done|bin)")

log = logging.getLogger("journal-shipper")


class Shipper:
    def __init__(self, root: Path, client_for_region):
        self._root = root
        self._client_for_region = client_for_region
        self._clients = {}
        self._retry_at: dict[Path, float] = {}
        self._retry_delay: dict[Path, float] = {}

    def ship_pending(self, include_open: bool, now: float) -> int:
        """Ships every finished segment, and the open ones too when the session has ended; returns how many are left."""
        left = 0
        for directory in sorted(self._root.glob("*/")):
            manifest = directory / MANIFEST
            if not manifest.exists():
                continue
            target = json.loads(manifest.read_text())
            for segment in segments(directory, include_open):
                if not self._ship(segment, target, now):
                    left += 1
        return left

    def _ship(self, segment: Path, target: dict, now: float) -> bool:
        if now < self._retry_at.get(segment, 0.0):
            return False
        index = int(SEGMENT.fullmatch(segment.name).group(1))
        key = f"{target['keyPrefix']}/segment-{index:05d}.zst"
        compressed = segment.with_name(segment.name + ".zst.part")
        try:
            compress(segment, compressed)
            self._client(target["region"]).upload_file(str(compressed), target["bucket"], key)
        # Whatever went wrong, the segment stays on disk for the next pass.
        except Exception as e:
            delay = min(self._retry_delay.get(segment, FIRST_RETRY_SECONDS / 2) * 2, MAX_RETRY_SECONDS)
            self._retry_delay[segment] = delay
            self._retry_at[segment] = now + delay
            log.error("failed to ship %s to s3://%s/%s, retrying in %.0f s: %s", segment, target["bucket"], key, delay, e)
            return False
        finally:
            compressed.unlink(missing_ok=True)
        segment.unlink()
        self._retry_at.pop(segment, None)
        self._retry_delay.pop(segment, None)
        log.info("shipped %s to s3://%s/%s", segment, target["bucket"], key)
        return True

    def _client(self, region: str):
        if region not in self._clients:
            self._clients[region] = self._client_for_region(region)
        return self._clients[region]


def segments(directory: Path, include_open: bool) -> list[Path]:
    """The directory's segments in journal order, the open ones only when asked for."""
    found = []
    for path in directory.iterdir():
        match = SEGMENT.fullmatch(path.name)
        if match and (match.group(2) == "done" or include_open):
            found.append((int(match.group(1)), path))
    return [path for _, path in sorted(found)]


def compress(source: Path, destination: Path) -> None:
    with source.open("rb") as src, destination.open("wb") as dst:
        zstandard.ZstdCompressor(level=ZSTD_LEVEL).copy_stream(src, dst)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    stopping = False

    def stop(signum, frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    shipper = Shipper(JOURNAL_ROOT, lambda region: boto3.client("s3", region_name=region))
    log.info("watching %s", JOURNAL_ROOT)
    while not stopping:
        shipper.ship_pending(include_open=False, now=time.monotonic())
        time.sleep(POLL_SECONDS)

    # systemd stops this unit after the strategy, so nothing writes the open segments any more. Keep trying until
    # everything is shipped; systemd's stop timeout bounds the wait.
    log.info("strategy stopped, shipping what is left")
    while shipper.ship_pending(include_open=True, now=time.monotonic()) > 0:
        time.sleep(FIRST_RETRY_SECONDS)
    log.info("journal shipped")


if __name__ == "__main__":
    main()
