set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y software-properties-common ca-certificates curl wget jq git unzip openjdk-17-jdk-headless
# gnomepy requires exactly Python 3.13; Ubuntu 24.04 ships 3.12.
add-apt-repository -y ppa:deadsnakes/ppa
apt-get update
apt-get install -y python3.13 python3.13-venv

install -d -m 0755 /opt/gnome /etc/gnome /var/log/gnome /var/lib/gnome
python3.13 -m venv /opt/gnome/venv
/opt/gnome/venv/bin/pip install --quiet --upgrade pip

# JPype finds libjvm.so through JAVA_HOME.
ln -sfn "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /usr/lib/jvm/current

curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install --update
rm -rf /tmp/aws /tmp/awscliv2.zip

curl -fsSL https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb -o /tmp/cwa.deb
dpkg -i /tmp/cwa.deb
rm /tmp/cwa.deb
