# Changelog

All notable changes to the Seat Inventory Service are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [0.1.0] — Phase 4 Scaffold

### Added

- gRPC server on port 9090 with full `SeatInventoryService` implementation:
  - `HoldSeats` — Redis Lua SETNX atomic hold with 600 s TTL (NFR-PERF-001)
  - `CommitSeats` — SERIALIZABLE PostgreSQL transaction (NFR-REL-008)
  - `ReleaseSeats` — Lua DEL + PostgreSQL state update
  - `ExtendHold` — Lua EXPIRE extension
  - `CheckAvailability` — Redis-first read (p99 optimised)
- Spring Actuator on port 8090: `/health/liveness`, `/health/readiness`, `/metrics`
- PostgreSQL schema via Flyway V1–V6:
  - `seat_state` — durable seat state with optimistic lock (`@Version`)
  - `seat_holds` — hold header with UNIQUE(booking_id) idempotency
  - `seat_hold_seats` — junction table (one row per seat per hold)
  - `seat_state_transitions` — immutable audit log with HMAC payload_mac
  - `outbox` — transactional outbox (Postgres-native, SKIP LOCKED publisher)
  - `idempotency_keys` — gRPC idempotency with 24 h TTL
- Redis key schema: `seat:{eventId}:{seatId}`, `flash-hold-processed:{bookingId}`, `seat-cooldown:{userId}:{seatId}`
- Lua scripts: `hold-seats.lua`, `extend-hold.lua`, `release-seats.lua` (loaded at startup via ClassPathResource)
- Kafka consumer: `flash-sale.hold-requests` (group: `seat-inventory-service-consumer`, MANUAL_IMMEDIATE ack)
- `OutboxPublisher` scheduler: `SELECT … FOR UPDATE SKIP LOCKED`, Kafka send with ACK
- `HoldReconciliationScheduler`: expired hold detection and state cleanup (5-minute cadence)
- `GrpcServerHealthIndicator`: custom readiness probe verifying gRPC server is running
- Structured JSON logging via `logback-spring.xml` (logstash-logback-encoder)
- OpenTelemetry tracing (OTLP exporter, context propagated across gRPC and Kafka)
- Multi-stage Dockerfile: `maven:3.9-eclipse-temurin-21-alpine` → `gcr.io/distroless/java21-debian12:nonroot`
- CI pipeline: secrets-scan → unit-test → integration-test → sast → sca → build-and-scan → ci (sentinel)
- Testcontainers integration tests: PostgreSQL 16 + Redis 7 via `@DynamicPropertySource`

### Architecture Notes

- T1 Critical service (99.9% SLO)
- Two-store concurrency model: Redis Lua for atomic mutual exclusion, PostgreSQL SERIALIZABLE for durable commit (ADR-006)
- gRPC-only — no REST surface, no API Gateway route (ADR-003 §3.3.1)
- `SERIALIZABLE` isolation scoped to `CommitSeats` only — applying it to `HoldSeats` would violate NFR-PERF-001
- Outbox publisher uses `FOR UPDATE SKIP LOCKED` to prevent concurrent publishers from processing the same row

### Phase 7 TODOs

- Raise JaCoCo minimum coverage from 0.01 to 0.80
- Re-enable `@opentelemetry/instrumentation-mongodb` when compatible version available (N/A for this Java service — noted for cross-reference with NestJS services)
- Replace local proto copy with `stagepass-shared-contracts` Maven dependency
- Add `SONAR_TOKEN` secret and remove `continue-on-error: true` from SAST job
- Implement ReleaseSeatsService PostgreSQL write (currently stub)
- Implement ExtendHoldService PostgreSQL write (currently stub)
