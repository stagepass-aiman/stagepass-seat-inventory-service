-- V5__create_outbox.sql
-- Transactional Outbox for seat.state-changes Kafka publishing (NFR-REL-005).
-- outbox row + seat_state UPDATE are in the SAME PostgreSQL transaction.
-- Publisher: @Scheduled, SELECT ... FOR UPDATE SKIP LOCKED, ORDER BY created_at ASC.
-- The outbox.id is used as the Kafka message messageId (consumer deduplication key,
-- NFR-REL-002). Maps to the messageId field in seat_async.yaml.

CREATE TABLE outbox (
    id              UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL DEFAULT 'SEAT_STATE',
    aggregate_id    UUID         NOT NULL,    -- seat_state.seat_id
    -- Kafka partition key: event_id (ADR-003 §3.4.2 — 24 partitions, key=eventId)
    partition_key   UUID         NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,

    CONSTRAINT pk_outbox PRIMARY KEY (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0 AND attempts <= 3)
);

-- Publisher hot path: oldest PENDING rows first, SKIP LOCKED for concurrent-safe polling
CREATE INDEX idx_outbox_status_created ON outbox (status, created_at ASC)
    WHERE status = 'PENDING';
-- Correlation: all outbox rows for a seat
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_id);
