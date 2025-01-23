# Stage 1: Build the JAR
FROM maven:3.9.4-eclipse-temurin-17 AS builder

WORKDIR /app

ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

RUN mkdir -p /root/.m2 \
    && mkdir /root/.m2/repository
COPY settings.xml /root/.m2
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Step 2: Run the container
FROM azul/zulu-openjdk:17

WORKDIR /app

COPY --from=builder /app/target/gnome-orchestrator-*.jar app.jar

ENV MAIN_CLASS=""
ENTRYPOINT java "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -cp app.jar ${MAIN_CLASS}
