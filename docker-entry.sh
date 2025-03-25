#!/bin/sh
set -e

# tcpdump -i any -w /tmp/capture.pcap > /dev/null 2>&1 &

exec java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp app.jar "$MAIN_CLASS"