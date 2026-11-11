#!/bin/bash
# Starts an order gateway: downloads its release (pinned per gateway by the registry's gateways/ensure), attaches its
# Elastic IP, and runs it.
set -euo pipefail
: "${GATEWAY_KEY:?GATEWAY_KEY must be set in the gateway's task definition}"
: "${ORCHESTRATOR_VERSION:?ORCHESTRATOR_VERSION must be set in the gateway's task definition}"

APP_JAR=/opt/gnome/app.jar

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
# a venue may count its limit per IP, and an allowlist would name it.
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

# ZGC collects concurrently, so a collection doesn't stop the lanes and readers the way a G1 pause does.
echo "gateway-start: starting ${GATEWAY_KEY}"
exec java -XX:+UseZGC -XX:ConcGCThreads=2 -jar "$APP_JAR" gateway
