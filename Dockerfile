# Stage 1: Build the JAR
FROM maven:3.9.4-eclipse-temurin-17 AS builder

WORKDIR /app

ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

RUN mkdir -p /root/.m2
# When testing locally, make sure to copy the m2 folder into the local repo's on any local updates
# Within the current directory:
# $ cp -r ~/.m2 .
COPY .m2 /root/.m2
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Step 2: Run the container
FROM --platform=linux/amd64 ubuntu:24.04

RUN apt-get update && apt-get install -y openjdk-17-jdk

WORKDIR /app

COPY --from=builder /app/target/gnome-orchestrator-*.jar app.jar

ENV MAIN_CLASS=""

COPY docker-entry.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]