-- V6__create_idempotency_keys.sql
-- gRPC call idempotency. booking_id is the saga's natural idempotency anchor.
-- Why PostgreSQL not Redis? gRPC responses include holdId + heldUntil that must
-- be returned verbatim on retry and survive Redis restarts (Phase 4 ER diagram §3.6).

CREATE TABLE idempotency_keys (
    id                UUID         NOT NULL,
    -- booking_id (UUID string). UNIQUE(idempotency_key, operation) allows one
    -- record per operation per booking (HOLD, COMMIT, RELEASE are independent).
    idempotency_key   VARCHAR(36)  NOT NULL,
    operation         VARCHAR(50)  NOT NULL,
    result            VARCHAR(20)  NOT NULL,
    response_payload  JSONB        NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_key_op UNIQUE (idempotency_key, operation),
    CONSTRAINT ck_idempotency_operation CHECK (
        operation IN ('HOLD_SEATS','EXTEND_HOLD','COMMIT_SEATS','RELEASE_SEATS')
    ),
    CONSTRAINT ck_idempotency_result CHECK (
        result IN ('SUCCESS','UNAVAILABLE','NOT_FOUND')
    )
);

-- O(1) idempotency check at gRPC handler entry
CREATE UNIQUE INDEX idx_idempotency_key_op ON idempotency_keys (idempotency_key, operation);
-- Cleanup scheduler: DELETE WHERE expires_at < NOW()
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
