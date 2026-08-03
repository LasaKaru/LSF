# =============================================================================
# One Dockerfile for all three Spring Boot services.
#
# The dependency layer is resolved from the POMs alone, before any source is
# copied, so editing a Java file re-uses the cached dependency layer instead of
# re-downloading the world. That is the difference between a 20-second rebuild
# and a three-minute one, and on a reviewer's first run it is the difference
# between "this works" and "this timed out".
#
# Build with:  docker build --build-arg SERVICE=booking-service .
# =============================================================================

# ---------- build -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first: this layer is invalidated only when a dependency actually changes.
COPY pom.xml .
COPY common/pom.xml           common/pom.xml
COPY catalog-service/pom.xml  catalog-service/pom.xml
COPY pricing-service/pom.xml  pricing-service/pom.xml
COPY booking-service/pom.xml  booking-service/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY common          common
COPY catalog-service catalog-service
COPY pricing-service pricing-service
COPY booking-service booking-service

ARG SERVICE
RUN test -n "$SERVICE" || (echo "SERVICE build-arg is required" >&2; exit 1)
# Tests are a CI gate, not an image-build step: a build that runs Testcontainers
# would need a Docker daemon inside the build, which is a bad trade for a
# reviewer whose first command is `docker compose up`.
RUN mvn -B -q -pl "$SERVICE" -am package -DskipTests \
 && cp "$SERVICE"/target/*.jar /build/app.jar

# ---------- runtime ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl is needed by the compose healthcheck; without it the check silently fails
# and every dependent service waits forever on an unhealthy dependency.
RUN apk add --no-cache curl \
 && addgroup -S yathra && adduser -S -G yathra yathra

WORKDIR /app
COPY --from=build --chown=yathra:yathra /build/app.jar app.jar

USER yathra
EXPOSE 8081 8082 8083

# Container-aware heap sizing, and a fast-starting random source: the default
# blocking entropy pool can add seconds to JVM start inside a container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
