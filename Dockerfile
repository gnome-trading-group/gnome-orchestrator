# Stage 1: Build the JAR
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# To test local SNAPSHOT dependencies (e.g. gnome-schemas), copy your Maven cache and uncomment:
# $ cp -r ~/.m2 .
# RUN mkdir -p /root/.m2
# COPY .m2 /root/.m2

COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: Runtime — unified Java + Python image
FROM --platform=linux/amd64 python:3.13-slim

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        ca-certificates \
        openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

RUN ln -sf "$(dirname "$(dirname "$(readlink -f "$(which java)")")")" /usr/lib/jvm/current
ENV JAVA_HOME=/usr/lib/jvm/current

WORKDIR /app

COPY --from=builder /app/target/gnome-orchestrator-*.jar app.jar

# GNOME_JARS tells gnomepy's _classpath.py where the orchestrator uber JAR is
ENV GNOME_JARS=/app/app.jar

# Defaults to latest gnomepy from PyPI; pin with --build-arg GNOMEPY_VERSION=x.y.z
ARG GNOMEPY_VERSION=""
RUN if [ -n "$GNOMEPY_VERSION" ]; then pip install "gnomepy==${GNOMEPY_VERSION}"; else pip install gnomepy; fi

ENV MAIN_CLASS="group.gnometrading.trading.TradingOrchestrator"

COPY docker-entry.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
