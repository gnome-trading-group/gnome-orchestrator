#!/bin/sh
set -e

STRATEGY_TYPE="${STRATEGY_TYPE:-java}"

if [ "$STRATEGY_TYPE" = "python" ]; then
    : "${STRATEGY_CLASS:?STRATEGY_CLASS is required for Python strategies}"
    RESEARCH_COMMIT="${RESEARCH_COMMIT:-main}"

    echo "entrypoint: fetching gh-token from Secrets Manager"
    GH_TOKEN=$(python3 - <<'PY'
import boto3, json
client = boto3.client("secretsmanager")
secret = client.get_secret_value(SecretId="gnomepy/gh-token")
val = secret["SecretString"]
try:
    print(json.loads(val)["token"])
except (json.JSONDecodeError, KeyError):
    print(val.strip())
PY
)

    REPO_DIR=/opt/gnomepy-research

    echo "entrypoint: cloning gnomepy-research..."
    git clone --filter=blob:none --quiet \
        "https://x-access-token:${GH_TOKEN}@github.com/gnome-trading-group/gnomepy-research.git" \
        "$REPO_DIR"

    echo "entrypoint: checking out ${RESEARCH_COMMIT}"
    git -C "$REPO_DIR" checkout --quiet "$RESEARCH_COMMIT"
    git -C "$REPO_DIR" --no-pager log -1 --oneline

    echo "entrypoint: installing gnomepy-research"
    pip install --quiet --no-deps "$REPO_DIR"

    unset GH_TOKEN

    echo "entrypoint: starting Python strategy runner"
    exec python -m gnomepy.java.strategy.runner
else
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
              -XX:+UseZGC -XX:ConcGCThreads=2 \
              -cp app.jar "$MAIN_CLASS"
fi
