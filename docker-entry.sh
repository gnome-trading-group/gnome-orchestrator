#!/bin/sh
set -e

exec java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp app.jar "$MAIN_CLASS"