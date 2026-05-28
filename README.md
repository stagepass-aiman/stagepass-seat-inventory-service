# stagepass-seat-inventory-service

**Tier:** T1 — Critical (99.9% SLO, 43 min/month downtime budget)

The Seat Inventory Service is the authoritative source of truth for seat state across the StagePass platform. It owns every seat's lifecycle from `AVAILABLE` through `HELD`, `BOOKED`, and `BLOCKED`.

It is a **gRPC-only service** — there is no REST surface, no API Gateway route, and no browser ever calls it directly. Its two external interfaces are:

| Interface | Protocol | Consumer |
|---|---|---|
| `HoldSeats` / `CommitSeats` / `ReleaseSeats` / `ExtendHold` / `CheckAvailability` | gRPC (port 9090) | Booking Service only |
| `flash-sale.hold-requests` consumer | Kafka | (Booking Service publishes, SI consumes) |

Spring Actuator runs on port 8090 for health probes and Prometheus scrape. This port is never API Gateway-routed.

---

## Architecture

The service uses a **two-store** concurrency model (ADR-006):

- **Redis** (Lua SETNX, 600 s TTL) — atomic mutual exclusion for seat holds. Prevents oversell under concurrent booking attempts. NFR-PERF-001: p99 < 500 ms.
- **PostgreSQL** (SERIALIZABLE isolation on `CommitSeats` only) — durable source of truth. `HoldSeats` writes to Postgres asynchronously after the Redis lock succeeds.

Applying SERIALIZABLE to `HoldSeats` would serialise all concurrent holds through the Postgres lock manager, violating NFR-PERF-001. Redis Lua is the lock; Postgres is the ledger.

```
Booking Service ──gRPC──▶ SeatInventoryGrpcService
                               │
                    ┌──────────┴──────────────┐
                    │                         │
             HoldSeatsService           CommitSeatsService
                    │                         │
            Redis Lua SETNX         SERIALIZABLE transaction
            (atomic, p99 < 500ms)   (durable commit)
                    │                         │
              seat:{eid}:{sid}        seat_state table
              hold TTL = 600s         outbox + audit
```

---

## Running Locally (< 30 minutes from clone)

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Maven 3.9+
- Docker Desktop (for dependencies)

### 1. Start dependencies

```bash
cp .env.example .env        # copy example env — no secrets needed for local dev
docker compose up -d        # starts PostgreSQL (5440) + Redis (6390)
```

### 2. Run the service

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The service starts with:
- gRPC server on port 9090
- Spring Actuator on port 8090

### 3. Verify

```bash
# Health check (Actuator HTTP — NOT gRPC)
curl http://localhost:8090/actuator/health
# Expected: {"status":"UP"}

# Prometheus metrics
curl http://localhost:8090/actuator/prometheus | grep grpc
```

For gRPC calls you need a gRPC client such as [grpcurl](https://github.com/fullstorydev/grpcurl):

```bash
grpcurl -plaintext -proto src/main/proto/stagepass/seat_inventory/v1/seat_inventory.proto \
  localhost:9090 \
  stagepass.seat_inventory.v1.SeatInventoryService/CheckAvailability
```

### 4. Run tests

```bash
# Unit tests only (no Docker required)
mvn test

# Integration tests (Testcontainers — requires Docker)
mvn verify -Dsurefire.skip=true
```

---

## Dependencies

### This service calls

None. The Seat Inventory Service is a **leaf service** in the call graph. It does not make synchronous calls to any other service.

### This service is called by

- **Booking Service** — gRPC on port 9090 (`HoldSeats`, `CommitSeats`, `ReleaseSeats`, `ExtendHold`, `CheckAvailability`)
- **Booking Service** — Kafka producer on `flash-sale.hold-requests` topic (SI consumes)
- **Prometheus** — scrapes `/actuator/prometheus` on port 8090

### Kafka topics

| Topic | Role | Group |
|---|---|---|
| `flash-sale.hold-requests` | Consumer | `seat-inventory-service-consumer` |
| `flash-sale.hold-results` | Producer | — |
| `seat.state-changed` | Producer | — |
| `seat.hold-expired` | Producer | — |

DLQ topics follow the pattern `<topic>.dlq`.

---

## Environment Variables

Variable names are listed here. Values come from Vault in production and from `.env` for local dev. **Never commit a `.env` file with real secrets.**

| Variable | Description |
|---|---|
| `SEAT_INVENTORY_DB_URL` | JDBC URL for PostgreSQL |
| `SEAT_INVENTORY_DB_USER` | Database username |
| `SEAT_INVENTORY_DB_PASSWORD` | Database password (Vault) |
| `SEAT_INVENTORY_REDIS_HOST` | Redis host |
| `SEAT_INVENTORY_REDIS_PORT` | Redis port |
| `KAFKA_BROKERS` | Comma-separated Kafka broker list |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry collector endpoint |

See `application.yml` for all variables with their defaults.

---

## Health Check Endpoints

Both endpoints are on **port 8090** (Spring Actuator management port — never API Gateway-routed).

| Endpoint | Semantics |
|---|---|
| `GET /actuator/health/liveness` | Is the JVM process alive? No dependency checks. |
| `GET /actuator/health/readiness` | Ready to serve gRPC traffic? Checks: PostgreSQL connectivity, Redis connectivity, gRPC server running. |

---

## Protobuf Contract

The `.proto` file is the authoritative interface contract. The canonical copy lives in:

```
stagepass-shared-contracts/proto/stagepass/seat_inventory/v1/seat_inventory.proto
```

The copy in `src/main/proto/` is for local compilation. It is kept in sync with `stagepass-shared-contracts` at every release.

---

## Links

- [ADR-006: Seat Inventory Concurrency Strategy](https://github.com/stagepass-aiman/stagepass-docs/blob/main/docs/adr/ADR-006-seat-inventory-concurrency-strategy.md)
- [ADR-003: Service Communication Patterns](https://github.com/stagepass-aiman/stagepass-docs/blob/main/docs/adr/ADR-003-service-communication-patterns.md)
- [AsyncAPI Schema: flash-sale topics](https://github.com/stagepass-aiman/stagepass-docs/blob/main/docs/async-api/)
- [Prometheus Alert Runbooks](https://github.com/stagepass-aiman/stagepass-docs/blob/main/docs/runbooks/)
