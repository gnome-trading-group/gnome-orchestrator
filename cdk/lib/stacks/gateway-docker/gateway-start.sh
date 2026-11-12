#!/bin/bash
# Starts an order gateway: downloads its release (pinned per gateway by the registry's gateways/ensure), attaches its
# Elastic IP (a live one; a paper gateway has none), and runs it.
set -euo pipefail
: "${GATEWAY_KEY:?GATEWAY_KEY must be set in the gateway's task definition}"
: "${ORCHESTRATOR_VERSION:?ORCHESTRATOR_VERSION must be set in the gateway's task definition}"

APP_JAR=/opt/gnome/app.jar
# How often the JVM's /health is asked, and how long without an answer means it is frozen.
HEALTH_INTERVAL_SECONDS=5
HEALTH_SILENCE_SECONDS=15

secret() {
  aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text 2>/dev/null \
    || aws secretsmanager get-secret-value --region us-east-1 --secret-id "$1" --query SecretString --output text
}
raw=$(secret gnomepy/gh-token)
GH_TOKEN=$(echo "$raw" | jq -r '.token? // empty' 2>/dev/null || true)
GH_TOKEN=${GH_TOKEN:-$raw}
echo "gateway-start: fetching gnome-orchestrator ${ORCHESTRATOR_VERSION}"
curl -fsSL -u "gnome:${GH_TOKEN}" -o "$APP_JAR" \
  "https://maven.pkg.github.com/gnome-trading-group/gnome-orchestrator/group/gnometrading/gnome-orchestrator/${ORCHESTRATOR_VERSION}/gnome-orchestrator-${ORCHESTRATOR_VERSION}.jar"
unset GH_TOKEN raw

# The gateway's Elastic IP (allocated by gateways/ensure, tagged with its key) follows it to whichever host it runs on:
# a venue may count its limit per IP, and an allowlist would name it. A paper gateway trades with no venue, and its
# task role may not look addresses up.
attach_address() {
  imds_token=$(curl -fsSX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
  instance=$(curl -fsSH "X-aws-ec2-metadata-token: ${imds_token}" http://169.254.169.254/latest/meta-data/instance-id)
  found=$(aws ec2 describe-addresses --filters "Name=tag:gnome:order-gateway,Values=${GATEWAY_KEY}" \
    --query 'Addresses[0].[AllocationId,PublicIp]' --output text)
  read -r allocation address <<< "$found"
  if [ -n "$allocation" ] && [ "$allocation" != "None" ]; then
    aws ec2 associate-address --allocation-id "$allocation" --instance-id "$instance" --allow-reassociation >/dev/null
    # The association returns before the host's traffic moves to the new address, and a connection opened while it
    # moves can go unanswered, so the gateway waits until the metadata names it.
    for _ in $(seq 1 30); do
      current=$(curl -fsH "X-aws-ec2-metadata-token: ${imds_token}" \
        http://169.254.169.254/latest/meta-data/public-ipv4 || true)
      [ "$current" = "$address" ] && break
      sleep 1
    done
    if [ "$current" = "$address" ]; then
      echo "gateway-start: ${GATEWAY_KEY} on Elastic IP ${allocation} (${address})"
    else
      echo "gateway-start: ${GATEWAY_KEY} attached Elastic IP ${allocation} (${address}), but the host still shows ${current:-no public address} after 30 s; starting anyway"
    fi
  else
    echo "gateway-start: ${GATEWAY_KEY} has no Elastic IP; trading from the host's own address"
  fi
}
if [ "${GATEWAY_KEY%%:*}" = "paper" ]; then
  echo "gateway-start: ${GATEWAY_KEY} is a paper gateway; no Elastic IP"
else
  attach_address
fi

# ZGC collects concurrently, so a collection doesn't stop the lanes and readers the way a G1 pause does. Its threads
# share the host's cores with them: two of a live host's, the one core of a paper host.
if [ "$(nproc)" -le 1 ]; then gc_threads=1; else gc_threads=2; fi
# A paper gateway's simulator allocates for every record each session's books take, so its heap gets half the host
# (a quarter by default); what the rest holds is the media driver's buffers, outside the heap.
heap=()
if [ "${GATEWAY_KEY%%:*}" = "paper" ]; then heap=(-XX:MaxRAMPercentage=50); fi
echo "gateway-start: starting ${GATEWAY_KEY} (${gc_threads} concurrent GC thread(s))"
java -XX:+UseZGC -XX:ConcGCThreads="$gc_threads" "${heap[@]}" -jar "$APP_JAR" gateway &
java_pid=$!

# A JVM that stops answering /health altogether (frozen, so its own watchdog can't act) is killed here, so ECS starts it
# again at once on the same host, ownership log intact. Left to ECS's health check, it would take a minute to notice
# and then wait out the stop timeout, which is long for the planned stop, for a SIGTERM the frozen JVM never acts on.
# A stop asked of the container goes to Java at once, and ends the watching.

# Still running: a JVM that has exited stays a zombie until reaped, which kill -0 can't tell apart.
running() {
  [ -r "/proc/${java_pid}/status" ] && ! grep -q '^State:[[:space:]]*Z' "/proc/${java_pid}/status"
}
stopping=0
trap 'stopping=1; kill -TERM "$java_pid" 2>/dev/null || true' TERM INT
# SECONDS when /health last answered; empty until it first does (the health server isn't up yet, and ECS's start period
# covers a start that hangs).
last_answer=
while running; do
  sleep "$HEALTH_INTERVAL_SECONDS" &
  wait $! || true
  [ "$stopping" = 1 ] && break
  running || break
  if wget --spider --quiet --timeout=2 --tries=1 http://localhost:8080/health; then
    last_answer=$SECONDS
  elif [ -n "$last_answer" ] && [ $((SECONDS - last_answer)) -ge "$HEALTH_SILENCE_SECONDS" ]; then
    echo "gateway-start: ${GATEWAY_KEY} hasn't answered /health for $((SECONDS - last_answer)) s; killing it"
    kill -KILL "$java_pid" 2>/dev/null || true
    break
  fi
done
# A wait a trapped signal interrupts returns while Java still runs; bash may also have reaped it already, and keeps its
# status for this wait.
while true; do
  status=0
  wait "$java_pid" || status=$?
  running || break
done
exit "$status"
