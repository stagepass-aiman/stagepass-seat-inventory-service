# ─────────────────────────────────────────────────────────────────────────────
# StagePass Seat Inventory Service — Multi-stage Dockerfile
#
# Stage 1: Build     maven:3.9-eclipse-temurin-21-alpine
# Stage 2: Runtime   gcr.io/distroless/java21-debian12:nonroot
#
# Phase 3 build log §13: jarmode syntax is -Djarmode=tools (not layertools).
# The --destination flag is REQUIRED. Without it, tools extract creates a
# directory named after the JAR file, breaking the COPY steps below.
#
# Health checks use the Actuator HTTP server on port 8090 (not the gRPC port).
# distroless has no shell — the health check runs from the HOST side via
# an HTTP GET probe. This is why CMD-SHELL is not used here.
# Docker HEALTHCHECK is not used for distroless — Kubernetes uses the
# HTTP readiness probe against /actuator/health/readiness (port 8090).
#
# Ports:
#   9090 — gRPC (Booking Service only, NetworkPolicy enforced in k8s)
#   8090 — Spring Actuator (health/live, health/ready, /metrics)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache dependency layer separately from source.
# Copy pom.xml first; Maven resolves dependencies; Docker caches this layer.
# Source changes (step below) only invalidate from that layer onward.
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Copy source and build the fat JAR (skip tests — tests run in CI before this)
COPY src ./src
COPY src/main/proto ./src/main/proto
RUN mvn package -DskipTests -B --no-transfer-progress

# Phase 3 build log §13: -Djarmode=tools replaces deprecated -Djarmode=layertools.
# --destination /build/target/extracted is REQUIRED — without it, tools extract
# creates a subdirectory named after the JAR file, not extracted/.
RUN java -Djarmode=tools \
         -jar /build/target/stagepass-seat-inventory-service-*.jar \
         extract \
         --layers \
         --launcher \
         --destination /build/target/extracted

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

WORKDIR /app

# Copy Spring Boot layered JAR in dependency order (each layer cached separately).
# If only application code changes, only the last COPY layer is invalidated.
COPY --from=builder /build/target/extracted/dependencies/          ./
COPY --from=builder /build/target/extracted/spring-boot-loader/    ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/           ./

# Expose both ports — document both explicitly.
# 9090: gRPC business traffic (NetworkPolicy restricts to Booking Service in k8s)
# 8090: Spring Actuator (health probes + Prometheus scrape)
EXPOSE 9090
EXPOSE 8090

# nonroot user (UID 65532) from distroless base image.
# Never run as root in production containers.
USER nonroot:nonroot

# Spring Boot 3.x layered JAR launcher entry point.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
