#!/bin/bash
# Starts one strategy session from /etc/gnome/session.json, written by the launcher's user data. Runs as the
# gnome-strategy systemd unit; whatever happens, the unit's ExecStopPost shuts the instance down afterwards.
# Only the EC2-specific setup lives here; start-session.sh is the part shared with the local Docker image.
set -euo pipefail

SESSION_FILE=/etc/gnome/session.json

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

exec /opt/gnome/start-session.sh
