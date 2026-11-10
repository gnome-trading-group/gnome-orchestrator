# An order gateway's container: a Java 17 runtime, the AWS CLI its start script uses, and that script. The release runs
# from its own jar, whose manifest carries the module access it needs.
FROM eclipse-temurin:17-jre-jammy

# wget is not used by the start script: the ECS container health check runs it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl wget jq unzip ca-certificates \
    && curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip \
    && unzip -q /tmp/awscliv2.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/aws /tmp/awscliv2.zip /var/lib/apt/lists/*

COPY gateway-start.sh /opt/gnome/gateway-start.sh
RUN chmod +x /opt/gnome/gateway-start.sh

ENTRYPOINT ["/opt/gnome/gateway-start.sh"]
