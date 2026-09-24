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
FROM --platform=linux/amd64 python:3.13-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1 \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1

# trixie ships libstdc++6 with GLIBCXX_3.4.32, required by libNativeSockets.so
# (bookworm's GCC 12 only goes to GLIBCXX_3.4.31)
RUN echo "deb http://deb.debian.org/debian trixie main" > /etc/apt/sources.list.d/trixie.list \
    && apt-get update && apt-get install -y --no-install-recommends \
        git \
        ca-certificates \
        openjdk-17-jre-headless \
    && apt-get install -y --no-install-recommends -t trixie libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

RUN ln -sf "$(dirname "$(dirname "$(readlink -f "$(which java)")")")" /usr/lib/jvm/current
ENV JAVA_HOME=/usr/lib/jvm/current

WORKDIR /app

COPY --from=builder /app/target/gnome-orchestrator-*.jar app.jar

# GNOME_JARS tells gnomepy's _classpath.py where the orchestrator uber JAR is
ENV GNOME_JARS=/app/app.jar

# Defaults to latest gnomepy from PyPI; pin with --build-arg GNOMEPY_VERSION=x.y.z
ARG GNOMEPY_VERSION=""
RUN if [ -n "$GNOMEPY_VERSION" ]; then pip install "gnomepy[strategy]==${GNOMEPY_VERSION}"; else pip install "gnomepy[strategy]"; fi

ENV MAIN_CLASS="group.gnometrading.trading.TradingOrchestrator"

COPY docker-entry.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
