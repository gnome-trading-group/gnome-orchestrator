#!/bin/sh
set -e

# tcpdump -i any -w /tmp/capture.pcap > /dev/null 2>&1 &

exec java --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
          --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
          --add-exports=java.base/jdk.internal.util=ALL-UNNAMED \
          --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
          --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
          --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
          --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED \
          --add-opens=java.base/java.io=ALL-UNNAMED \
          --add-opens=java.base/java.lang=ALL-UNNAMED \
          --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
          --add-opens=java.base/java.util=ALL-UNNAMED \
          --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED \
          -cp app.jar "$MAIN_CLASS"