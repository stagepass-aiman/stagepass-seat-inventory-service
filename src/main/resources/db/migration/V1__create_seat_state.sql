-- V1__create_seat_state.sql
-- Durable per-seat state. Source of truth for committed seat state.
-- Redis is authoritative for hold creation (ADR-006 §3.1).
-- This table is authoritative for BOOKED, BLOCKED, and service-restart recovery.
--
-- Hibernate ddl-auto=validate rules (Phase 3 build log §4):
--   All columns mapped to String → VARCHAR(N) with explicit length
--   All columns mapped to Instant → TIMESTAMPTZ
--   BigDecimal with custom precision → NUMERIC(19,2) (columnDefinition in entity)
--   JSONB → needs columnDefinition = "jsonb" in entity (no mapping here needed)
--   @Version Long → BIGINT NOT NULL DEFAULT 0
--   No CHAR, TEXT, or INET types.

CREATE TABLE seat_state (
    id                   UUID         NOT NULL,
    event_id             UUID         NOT NULL,
    seat_id              UUID         NOT NULL,
    section_id           UUID         NOT NULL,
    row_label            VARCHAR(10),                         -- nullable for GA events
    seat_number          VARCHAR(20),                         -- nullable for GA events
    category             VARCHAR(30)  NOT NULL DEFAULT 'STANDARD',
    price                NUMERIC(19,2) NOT NULL,
    currency             VARCHAR(3)   NOT NULL DEFAULT 'INR', -- ADR-004: VARCHAR not CHAR
    state                VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    booking_id           UUID,                                -- present when HELD or BOOKED
    customer_id          UUID,                                -- for notification routing
    held_until           TIMESTAMPTZ,                         -- informational; Redis is authoritative
    booked_at            TIMESTAMPTZ,
    blocked_by_user_id   UUID,                                -- no FK — audit survives deletion
    blocked_at           TIMESTAMPTZ,
    -- @Version — optimistic locking for Admin ops ONLY.
    -- NEVER used in HoldSeats path — Redis Lua is the lock (ADR-006 §3.2).
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_seat_state PRIMARY KEY (id),
    -- Non-fungibility enforced at DB level: a seat belongs to exactly one event.
    CONSTRAINT uq_seat_state_event_seat UNIQUE (event_id, seat_id),
    -- Prevent invalid state strings reaching the database.
    CONSTRAINT ck_seat_state_state CHECK (state IN ('AVAILABLE','HELD','BOOKED','BLOCKED')),
    CONSTRAINT ck_seat_state_category CHECK (
        category IN ('STANDARD','VIP','WHEELCHAIR','RESTRICTED_VIEW','GA')
    ),
    CONSTRAINT ck_seat_state_currency CHECK (LENGTH(currency) = 3)
);

-- Availability queries: "all AVAILABLE seats for event X" (seat map load)
CREATE INDEX idx_seat_state_event_state ON seat_state (event_id, state);
-- CommitSeats / ReleaseSeats lookup by bookingId
CREATE INDEX idx_seat_state_booking_id ON seat_state (booking_id);
-- Reconciliation: "all HELD seats updated before timestamp T"
CREATE INDEX idx_seat_state_state_updated ON seat_state (state, updated_at)
    WHERE state = 'HELD';
