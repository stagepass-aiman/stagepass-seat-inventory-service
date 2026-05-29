# ─────────────────────────────────────────────────────────────────────────────
# StagePass Seat Inventory Service — Multi-stage Dockerfile
#
# Stage 1: Build     maven:3.9-eclipse-temurin-21   (Debian — NOT Alpine)
# Stage 2: Runtime   gcr.io/distroless/java21-debian12:nonroot
#
# WHY DEBIAN FOR THE BUILDER (not Alpine):
#   protoc-gen-grpc-java (io.grpc:protoc-gen-grpc-java) is dynamically linked
#   against glibc. Alpine uses musl libc.
#
#   Attempt 1 — no compat layer:
#     "program not found or is not executable"
#     The musl dynamic linker refuses to load a glibc binary.
#
#   Attempt 2 — libc6-compat / gcompat:
#     "terminate called after throwing an instance of 'std::system_error'
#      Plugin killed by signal 6."
#     gcompat is a thin shim — not full glibc. The binary loads but crashes
#     on C++ system primitives that gcompat does not implement.
#
#   Fix: use maven:3.9-eclipse-temurin-21 (Debian, full glibc).
#   The builder stage is discarded. Only the distroless runtime ends up in
#   the final image. Builder image size has no effect on production image size.
#
#   protoc (com.google.protobuf:protoc) is statically linked — runs on any libc.
#   protoc-gen-grpc-java is dynamically linked — requires glibc.
#
# RULE-37: Any Java service Dockerfile using protobuf-maven-plugin with
# compile-custom (gRPC stub generation) MUST use a glibc-based builder image.
# Use maven:3.9-eclipse-temurin-21 (Debian), not the -alpine variant.
#
# Phase 3 build log §13: jarmode syntax is -Djarmode=tools (not layertools).
# The --destination flag is REQUIRED. Without it, tools extract creates a
# directory named after the JAR file, breaking the COPY steps below.
#
# Ports:
#   9090 — gRPC (Booking Service only, NetworkPolicy enforced in k8s)
#   8090 — Spring Actuator (health/live, health/ready, /metrics)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────
# Debian-based: required for protoc-gen-grpc-java (glibc binary).
# Do NOT switch back to -alpine without re-testing gRPC stub generation.
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Cache dependency layer separately from source.
# Copy pom.xml first; Maven resolves dependencies; Docker caches this layer.
# Source changes (step below) only invalidate from that layer onward.
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Copy source and build the fat JAR (skip tests — tests run in CI before this)
COPY src ./src
COPY src/main/proto ./src/main/proto
RUN mvn package -Dmaven.test.skip=true -B --no-transfer-progress

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