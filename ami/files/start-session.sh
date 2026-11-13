#!/bin/bash
# Fetches the session's pinned artifacts and starts it. Shared by EC2 (run-strategy.sh execs it after reading
# session.json) and the local Docker image (its entrypoint), so both start a session the same way. Everything
# arrives through the environment.
set -euo pipefail

APP_JAR=/opt/gnome/app.jar
VENV=/opt/gnome/venv

secret() {
  aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text 2>/dev/null \
    || aws secretsmanager get-secret-value --region us-east-1 --secret-id "$1" --query SecretString --output text
}
# Only fetched when something private is downloaded; a local run of a locally built jar needs no token.
gh_token() {
  if [ -z "${GH_TOKEN:-}" ]; then
    local raw
    raw=$(secret gnomepy/gh-token)
    GH_TOKEN=$(echo "$raw" | jq -r '.token? // empty' 2>/dev/null || true)
    GH_TOKEN=${GH_TOKEN:-$raw}
  fi
}

# The launcher pins the exact releases the session's commit names (gnomepy only for a python strategy). Left unset
# (local Docker), the JAR baked into the image and the gnomepy already installed are used instead.
if [ -n "${ORCHESTRATOR_VERSION:-}" ]; then
  echo "start-session: fetching gnome-orchestrator ${ORCHESTRATOR_VERSION}"
  gh_token
  curl -fsSL -u "gnome:${GH_TOKEN}" -o "$APP_JAR" \
    "https://maven.pkg.github.com/gnome-trading-group/gnome-orchestrator/group/gnometrading/gnome-orchestrator/${ORCHESTRATOR_VERSION}/gnome-orchestrator-${ORCHESTRATOR_VERSION}.jar"
else
  echo "start-session: using the gnome-orchestrator jar already at ${APP_JAR}"
fi
export GNOME_JARS=$APP_JAR

if [ -n "${GNOMEPY_VERSION:-}" ]; then
  echo "start-session: installing gnomepy ${GNOMEPY_VERSION}"
  "$VENV/bin/pip" install --quiet "gnomepy[strategy,live-data]==${GNOMEPY_VERSION}"
fi

if [ "${STRATEGY_TYPE:-java}" = "python" ]; then
  RESEARCH_DIR=/opt/gnome/gnomepy-research
  gh_token
  git clone --filter=blob:none --quiet \
    "https://x-access-token:${GH_TOKEN}@github.com/gnome-trading-group/gnomepy-research.git" "$RESEARCH_DIR"
  git -C "$RESEARCH_DIR" checkout --quiet "${RESEARCH_COMMIT:-main}"
  git -C "$RESEARCH_DIR" --no-pager log -1 --oneline
  unset GH_TOKEN
  # The strategy's package declares the dependency extras its code needs (gnomepy-research's
  # gnomepy_research/dependencies.py); constraints.txt pins them to the versions research's tests ran on.
  EXTRAS=$("$VENV/bin/python" "$RESEARCH_DIR/gnomepy_research/dependencies.py" extras "$STRATEGY_CLASS")
  echo "start-session: installing gnomepy-research${EXTRAS:+[$EXTRAS]}"
  "$VENV/bin/uv" pip install --quiet --python "$VENV/bin/python" \
    ${GNOMEPY_VERSION:+"gnomepy[strategy,live-data]==${GNOMEPY_VERSION}"} \
    "${RESEARCH_DIR}${EXTRAS:+[$EXTRAS]}" --constraint "$RESEARCH_DIR/constraints.txt"
  echo "start-session: starting python strategy ${STRATEGY_CLASS}"
  exec "$VENV/bin/python" -m gnomepy.java.strategy.runner
fi

# A Java strategy run by commit: the commit's strategies/ compiled against this jar, which is the release the commit's
# strategies/pom.xml pins (platform.orchestrator). A session given STRATEGY_CLASSES already (a local run of a local
# build) uses those classes as they are.
if [ -n "${STRATEGY_COMMIT:-}" ] && [ -z "${STRATEGY_CLASSES:-}" ]; then
  STRATEGIES_DIR=/opt/gnome/gnome-strategies
  gh_token
  git clone --filter=blob:none --quiet \
    "https://x-access-token:${GH_TOKEN}@github.com/gnome-trading-group/gnome-strategies.git" "$STRATEGIES_DIR"
  git -C "$STRATEGIES_DIR" checkout --quiet "$STRATEGY_COMMIT"
  git -C "$STRATEGIES_DIR" --no-pager log -1 --oneline
  PINNED=$(sed -n 's#.*<platform.orchestrator>\([^<]*\)</platform.orchestrator>.*#\1#p' "$STRATEGIES_DIR/strategies/pom.xml" | head -n 1)
  if [ -z "$PINNED" ]; then
    echo "start-session: strategy commit ${STRATEGY_COMMIT} has no platform.orchestrator in strategies/pom.xml" >&2
    exit 1
  fi
  if [ -n "${ORCHESTRATOR_VERSION:-}" ] && [ "$PINNED" != "$ORCHESTRATOR_VERSION" ]; then
    echo "start-session: strategy commit ${STRATEGY_COMMIT} runs on gnome-orchestrator ${PINNED}; this session fetched ${ORCHESTRATOR_VERSION}" >&2
    exit 1
  fi
  STRATEGY_CLASSES=/opt/gnome/strategy-classes
  mkdir -p "$STRATEGY_CLASSES"
  find "$STRATEGIES_DIR/strategies/src/main/java" -name '*.java' > "$STRATEGIES_DIR/sources.txt"
  echo "start-session: compiling $(wc -l < "$STRATEGIES_DIR/sources.txt" | tr -d ' ') strategy sources at ${STRATEGY_COMMIT} against gnome-orchestrator ${PINNED}"
  javac --release 17 -parameters -nowarn -cp "$APP_JAR" -d "$STRATEGY_CLASSES" "@$STRATEGIES_DIR/sources.txt"
  if [ -d "$STRATEGIES_DIR/strategies/src/main/resources" ]; then
    cp -R "$STRATEGIES_DIR/strategies/src/main/resources/." "$STRATEGY_CLASSES/"
  fi
  export STRATEGY_CLASSES
fi

unset GH_TOKEN
# The jar's manifest gives it the module access it needs (java -jar applies it); its session-jvm-options holds the
# rest, which gnomepy's runner reads from the same jar, so the Java and Python launch paths cannot drift apart.
if ! OPTIONS=$(unzip -p "$APP_JAR" session-jvm-options); then
  echo "start-session: ${APP_JAR} has no session-jvm-options; pin a gnome-orchestrator release that ships it" >&2
  exit 1
fi
mapfile -t JVM_OPTIONS < <(printf '%s\n' "$OPTIONS" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | grep -v -e '^#' -e '^$')
echo "start-session: starting ${ORCHESTRATOR_ROLE:-session} ${STRATEGY_CLASS:-}"
exec java "${JVM_OPTIONS[@]}" -jar "$APP_JAR" "${ORCHESTRATOR_ROLE:-session}"
