# Local image built from the same scripts as the strategy AMI, so a session started here runs the way it does on
# EC2. Only the EC2 wrapper (session.json, instance metadata, CloudWatch, CPU isolation) is left out: the
# environment comes from docker-compose instead.

# Stage 1: build the JAR from this checkout.
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

# Your own Maven settings (GitHub Packages credentials) are mounted for this step only, so they never land in an
# image layer or its history. docker-compose passes ~/.m2/settings.xml as the maven_settings secret.
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2/repository \
    mvn -B clean package -DskipTests

# Stage 2: the AMI's runtime.
FROM --platform=linux/amd64 ubuntu:24.04

COPY ami/scripts/install-runtime.sh /tmp/install-runtime.sh
RUN bash /tmp/install-runtime.sh && rm -rf /tmp/install-runtime.sh /var/lib/apt/lists/*

# Pinned with --build-arg GNOMEPY_VERSION=x.y.z, or a session can install its own via the GNOMEPY_VERSION env var.
ARG GNOMEPY_VERSION=""
RUN /opt/gnome/venv/bin/pip install --quiet "gnomepy[strategy]${GNOMEPY_VERSION:+==$GNOMEPY_VERSION}"

COPY --from=builder /app/target/gnome-orchestrator-*.jar /opt/gnome/app.jar
COPY ami/files/start-session.sh /opt/gnome/start-session.sh

# The same environment the gnome-strategy systemd unit sets on EC2.
ENV JAVA_HOME=/usr/lib/jvm/current \
    MAIN_CLASS=group.gnometrading.trading.TradingOrchestrator \
    PYTHONUNBUFFERED=1 \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1

WORKDIR /opt/gnome
ENTRYPOINT ["/opt/gnome/start-session.sh"]
