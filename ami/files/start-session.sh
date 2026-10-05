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

# The launcher always pins exact versions. Left unset (local Docker), the JAR baked into the image and the
# gnomepy already installed are used instead.
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
  "$VENV/bin/pip" install --quiet "gnomepy[strategy]==${GNOMEPY_VERSION}"
fi

if [ "${STRATEGY_TYPE:-java}" = "python" ]; then
  RESEARCH_DIR=/opt/gnome/gnomepy-research
  gh_token
  git clone --filter=blob:none --quiet \
    "https://x-access-token:${GH_TOKEN}@github.com/gnome-trading-group/gnomepy-research.git" "$RESEARCH_DIR"
  git -C "$RESEARCH_DIR" checkout --quiet "${RESEARCH_COMMIT:-main}"
  git -C "$RESEARCH_DIR" --no-pager log -1 --oneline
  "$VENV/bin/pip" install --quiet --no-deps "$RESEARCH_DIR"
  unset GH_TOKEN
  echo "start-session: starting python strategy ${STRATEGY_CLASS}"
  exec "$VENV/bin/python" -m gnomepy.java.strategy.runner
fi

unset GH_TOKEN
# JVM flags live in gnomepy so the Java and Python launch paths cannot drift apart.
mapfile -t JVM_ARGS < <("$VENV/bin/python" -c 'from gnomepy.java._jvm import STRATEGY_JVM_ARGS; print("\n".join(STRATEGY_JVM_ARGS))')
echo "start-session: starting ${MAIN_CLASS} ${STRATEGY_CLASS:-}"
exec java "${JVM_ARGS[@]}" -cp "$APP_JAR" "$MAIN_CLASS"
