set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

# An instance lives for one session and gets its updates from AMI rebuilds. Ubuntu's automatic upgrades, followed by
# needrestart restarting every service they touched, would otherwise restart a running session mid-trade.
# --now also stops one already running at boot, and the purge waits out the apt lock it may still hold.
systemctl mask --now apt-daily.timer apt-daily-upgrade.timer apt-daily.service apt-daily-upgrade.service
apt-get -o DPkg::Lock::Timeout=300 purge -y unattended-upgrades
install -d -m 0755 /etc/needrestart/conf.d
echo "\$nrconf{restart} = 'l';" > /etc/needrestart/conf.d/90-gnome-no-restart.conf

apt-get update
apt-get install -y software-properties-common ca-certificates curl wget jq git unzip openjdk-17-jdk-headless
# gnomepy requires exactly Python 3.13; Ubuntu 24.04 ships 3.12.
add-apt-repository -y ppa:deadsnakes/ppa
apt-get update
apt-get install -y python3.13 python3.13-venv

install -d -m 0755 /opt/gnome /etc/gnome /var/log/gnome /var/lib/gnome
python3.13 -m venv /opt/gnome/venv
/opt/gnome/venv/bin/pip install --quiet --upgrade pip
# start-session.sh installs a Python session's research dependencies with uv: seconds instead of minutes.
/opt/gnome/venv/bin/pip install --quiet uv
# The journal shipper starts at boot, before a session installs anything, and must not change when one does.
python3.13 -m venv /opt/gnome/shipper-venv
/opt/gnome/shipper-venv/bin/pip install --quiet "boto3>=1.36.18,<2.0.0" "zstandard>=0.23.0,<0.24.0"

# JPype finds libjvm.so through JAVA_HOME.
ln -sfn "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /usr/lib/jvm/current

curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install --update
rm -rf /tmp/aws /tmp/awscliv2.zip

curl -fsSL https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb -o /tmp/cwa.deb
dpkg -i /tmp/cwa.deb
rm /tmp/cwa.deb
