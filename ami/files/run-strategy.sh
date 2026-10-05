#!/bin/bash
# Starts one strategy session from /etc/gnome/session.json, written by the launcher's user data. Runs as the
# gnome-strategy systemd unit; whatever happens, the unit's ExecStopPost shuts the instance down afterwards.
set -euo pipefail

SESSION_FILE=/etc/gnome/session.json
APP_JAR=/opt/gnome/app.jar
VENV=/opt/gnome/venv

# Marks the instance as used so the boot guard shuts it down if it ever reboots: user data only runs once.
touch /var/lib/gnome/session-ran

# Validated first: a jq failure inside the process substitution below would not trip set -e, and the session
# would start with an empty environment.
jq -e 'type == "object"' "$SESSION_FILE" >/dev/null
while IFS= read -r -d '' key && IFS= read -r -d '' value; do
  export "$key=$value"
done < <(jq -j 'to_entries[] | .key, "\u0000", (.value | tostring), "\u0000"' "$SESSION_FILE")

IMDS_TOKEN=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
imds() { curl -fsS -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" "http://169.254.169.254/latest/meta-data/$1"; }
INSTANCE_ID=$(imds instance-id)
# AWS_REGION rather than REGION: every env var becomes an orchestrator property, and "region" is a session key.
export AWS_REGION=$(imds placement/region)
export AWS_DEFAULT_REGION=$AWS_REGION

cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<CWA
{
  "logs": {
    "force_flush_interval": 1,
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/gnome/orchestrator.log",
            "log_group_name": "/gnome/orchestrator/${AWS_REGION}",
            "log_stream_name": "ec2/${INSTANCE_ID}"
          }
        ]
      }
    }
  }
}
CWA
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json

echo "run-strategy: session ${SESSION_ID} on ${INSTANCE_ID} (${AWS_REGION})"

LATENCY_PROFILE=${LATENCY_PROFILE:-low_latency}
ISOLATED=$(cat /sys/devices/system/cpu/isolated)
if [ "$LATENCY_PROFILE" = "low_latency" ] && [ -n "$ISOLATED" ]; then
  # The kernel's own record of isolated CPUs is the source of truth, so the AMI's isolcpus layout can change
  # without touching the launcher or the orchestrator.
  export CPU_AFFINITY_ENABLED=true
  export CPU_ISOLATED=$ISOLATED
  export CPU_HOUSEKEEPING=$(python3 - "$(cat /sys/devices/system/cpu/online)" "$ISOLATED" <<'PY'
import sys
def cpus(spec):
    out = set()
    for part in filter(None, spec.split(",")):
        lo, _, hi = part.partition("-")
        out.update(range(int(lo), int(hi or lo) + 1))
    return out
housekeeping = sorted(cpus(sys.argv[1]) - cpus(sys.argv[2]))
# Only checkable on the real instance size, not in the AMI test: OS noise on a hot thread's physical core.
for cpu in housekeeping:
    with open(f"/sys/devices/system/cpu/cpu{cpu}/topology/thread_siblings_list") as f:
        shared = cpus(f.read().strip()) & cpus(sys.argv[2])
    if shared:
        print(f"run-strategy: WARNING housekeeping cpu{cpu} shares a physical core with isolated cpus {sorted(shared)}",
              file=sys.stderr)
print(",".join(str(c) for c in housekeeping))
PY
)
  echo "run-strategy: isolated cpus ${CPU_ISOLATED}, housekeeping cpus ${CPU_HOUSEKEEPING}"
fi

secret() {
  aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text 2>/dev/null \
    || aws secretsmanager get-secret-value --region us-east-1 --secret-id "$1" --query SecretString --output text
}
GH_SECRET=$(secret gnomepy/gh-token)
GH_TOKEN=$(echo "$GH_SECRET" | jq -r '.token? // empty' 2>/dev/null || true)
GH_TOKEN=${GH_TOKEN:-$GH_SECRET}

echo "run-strategy: fetching gnome-orchestrator ${ORCHESTRATOR_VERSION}"
curl -fsSL -u "gnome:${GH_TOKEN}" -o "$APP_JAR" \
  "https://maven.pkg.github.com/gnome-trading-group/gnome-orchestrator/group/gnometrading/gnome-orchestrator/${ORCHESTRATOR_VERSION}/gnome-orchestrator-${ORCHESTRATOR_VERSION}.jar"
export GNOME_JARS=$APP_JAR

echo "run-strategy: installing gnomepy ${GNOMEPY_VERSION}"
"$VENV/bin/pip" install --quiet "gnomepy[strategy]==${GNOMEPY_VERSION}"

if [ "${STRATEGY_TYPE:-java}" = "python" ]; then
  RESEARCH_DIR=/opt/gnome/gnomepy-research
  git clone --filter=blob:none --quiet \
    "https://x-access-token:${GH_TOKEN}@github.com/gnome-trading-group/gnomepy-research.git" "$RESEARCH_DIR"
  git -C "$RESEARCH_DIR" checkout --quiet "${RESEARCH_COMMIT:-main}"
  git -C "$RESEARCH_DIR" --no-pager log -1 --oneline
  "$VENV/bin/pip" install --quiet --no-deps "$RESEARCH_DIR"
  unset GH_TOKEN GH_SECRET
  echo "run-strategy: starting python strategy ${STRATEGY_CLASS}"
  exec "$VENV/bin/python" -m gnomepy.java.strategy.runner
fi

unset GH_TOKEN GH_SECRET
# JVM flags live in gnomepy so the Java and Python launch paths cannot drift apart.
mapfile -t JVM_ARGS < <("$VENV/bin/python" -c 'from gnomepy.java._jvm import STRATEGY_JVM_ARGS; print("\n".join(STRATEGY_JVM_ARGS))')
echo "run-strategy: starting java strategy ${STRATEGY_CLASS:-}"
exec java "${JVM_ARGS[@]}" -cp "$APP_JAR" "$MAIN_CLASS"
