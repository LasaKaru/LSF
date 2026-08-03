# =============================================================================
# Render image: all three services in one image, selected at runtime.
#
# Deliberately NOT the same shape as services/Dockerfile, which takes a
# `SERVICE` build arg. Render's Blueprint spec has no field for Docker build
# arguments, so a per-service build arg is not expressible in render.yaml.
# Building all three jars once and choosing with an environment variable needs
# no build-arg support at all, and the same image can back all three services.
#
# The cost is an image carrying three jars instead of one -- about 60 MB of
# duplication. On a free tier that is cheaper than the alternatives, and far
# cheaper than three near-identical Dockerfiles drifting apart.
# =============================================================================

# ---------- build -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first so the dependency layer survives a source-only change.
COPY services/pom.xml .
COPY services/common/pom.xml           common/pom.xml
COPY services/catalog-service/pom.xml  catalog-service/pom.xml
COPY services/pricing-service/pom.xml  pricing-service/pom.xml
COPY services/booking-service/pom.xml  booking-service/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY services/common          common
COPY services/catalog-service catalog-service
COPY services/pricing-service pricing-service
COPY services/booking-service booking-service

# Tests are a CI gate, not an image-build step: the integration suite needs a
# real PostgreSQL, which a Render build host does not have.
RUN mvn -B -q package -DskipTests \
 && mkdir -p /build/jars \
 && cp booking-service/target/*.jar /build/jars/booking-service.jar \
 && cp catalog-service/target/*.jar /build/jars/catalog-service.jar \
 && cp pricing-service/target/*.jar /build/jars/pricing-service.jar

# ---------- runtime ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl \
 && addgroup -S yathra && adduser -S -G yathra yathra

WORKDIR /app
COPY --from=build --chown=yathra:yathra /build/jars/ /app/
COPY --chown=yathra:yathra deploy/render/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER yathra

# 512 MB on Render's free instances. 70% of that is a ~358 MB heap, which is
# comfortable for these services; SerialGC because a single-core free instance
# gains nothing from a concurrent collector and pays for its threads.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["/app/entrypoint.sh"]
