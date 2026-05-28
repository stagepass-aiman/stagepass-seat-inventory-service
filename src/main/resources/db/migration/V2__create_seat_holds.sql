-- V2__create_seat_holds.sql
-- Hold header per booking. Expiry tracking and reconciliation anchor.
-- UNIQUE (booking_id) enforces idempotency: INSERT ... ON CONFLICT (booking_id) DO NOTHING
-- on retry. Never resets expires_at — ADR-006 §3.8 idempotency rule.

CREATE TABLE seat_holds (
    id           UUID         NOT NULL,
    -- UNIQUE: one hold record per booking saga invocation — idempotency anchor.
    booking_id   UUID         NOT NULL,
    event_id     UUID         NOT NULL,
    customer_id  UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'HELD',
    hold_source  VARCHAR(20)  NOT NULL DEFAULT 'GRPC',
    expires_at   TIMESTAMPTZ  NOT NULL,
    -- Denormalised: avoids COUNT(*) subquery in hot path when checking hold size.
    seat_count   INTEGER      NOT NULL DEFAULT 0,
    committed_at TIMESTAMPTZ,
    released_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_seat_holds PRIMARY KEY (id),
    CONSTRAINT uq_seat_holds_booking UNIQUE (booking_id),
    CONSTRAINT ck_seat_holds_status CHECK (status IN ('HELD','BOOKED','RELEASED','EXPIRED')),
    CONSTRAINT ck_seat_holds_source CHECK (hold_source IN ('GRPC','FLASH_SALE'))
);

-- O(1) idempotency check on HoldSeats retry
CREATE UNIQUE INDEX idx_seat_holds_booking ON seat_holds (booking_id);
-- Reconciliation: "all HELD holds for event X"
CREATE INDEX idx_seat_holds_event_status ON seat_holds (event_id, status);
-- Reconciliation: "expired HELD holds needing PG sync"
CREATE INDEX idx_seat_holds_expires_status ON seat_holds (expires_at, status)
    WHERE status = 'HELD';
-- Notification routing lookup
CREATE INDEX idx_seat_holds_customer ON seat_holds (customer_id);
