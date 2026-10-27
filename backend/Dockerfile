# syntax=docker/dockerfile:1

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package

FROM ${JRE_IMAGE}

RUN addgroup -S -g 10001 pulseops \
    && adduser -S -D -H -u 10001 -G pulseops -h /app -s /sbin/nologin pulseops \
    && mkdir -p /app \
    && chown pulseops:pulseops /app

WORKDIR /app
COPY --from=build --chown=pulseops:pulseops /workspace/target/pulseops-backend-*.jar /app/app.jar

USER pulseops:pulseops
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
