-- V4__create_seat_state_transitions.sql
-- Immutable audit trail. NEVER updated or deleted in production.
-- STRIDE THR-SEAT-02: tamper-evidence via payload_mac column.
-- BIGSERIAL PK: append-only ordering without secondary ORDER BY.
-- No FK columns: audit record must survive seat/booking deletion.

CREATE TABLE seat_state_transitions (
    id                   BIGSERIAL    NOT NULL,
    seat_id              UUID         NOT NULL,   -- no FK — audit survives deletion
    event_id             UUID         NOT NULL,
    booking_id           UUID,
    customer_id          UUID,
    from_state           VARCHAR(20)  NOT NULL,
    to_state             VARCHAR(20)  NOT NULL,
    transition_reason    VARCHAR(50)  NOT NULL,
    performed_by_service VARCHAR(50)  NOT NULL,
    performed_by_user_id UUID,
    -- HMAC-SHA256(seat_id || booking_id || from_state || to_state || occurred_at)
    -- Secret from Vault. Detects post-insertion tampering (THR-SEAT-02).
    payload_mac          VARCHAR(64)  NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_seat_state_transitions PRIMARY KEY (id),
    CONSTRAINT ck_sst_from_state CHECK (
        from_state IN ('AVAILABLE','HELD','BOOKED','BLOCKED')
    ),
    CONSTRAINT ck_sst_to_state CHECK (
        to_state IN ('AVAILABLE','HELD','BOOKED','BLOCKED')
    ),
    CONSTRAINT ck_sst_reason CHECK (
        transition_reason IN (
            'HOLD_CREATED','HOLD_RELEASED','HOLD_EXPIRED',
            'BOOKING_COMMITTED','BOOKING_CANCELLED',
            'ADMIN_BLOCKED','ADMIN_UNBLOCKED'
        )
    ),
    CONSTRAINT ck_sst_service CHECK (
        performed_by_service IN (
            'BOOKING_SERVICE','SEAT_INVENTORY_SCHEDULER',
            'ADMIN_COMMAND','FLASH_SALE_CONSUMER'
        )
    )
);

-- Full history of a seat — descending (most recent first)
CREATE INDEX idx_sst_seat_occurred ON seat_state_transitions (seat_id, occurred_at DESC);
-- All seat transitions for a booking (saga audit)
CREATE INDEX idx_sst_booking ON seat_state_transitions (booking_id)
    WHERE booking_id IS NOT NULL;
-- All transitions for an event (event-level audit)
CREATE INDEX idx_sst_event_occurred ON seat_state_transitions (event_id, occurred_at DESC);
-- Analytics: how many HOLD_EXPIRED per hour
CREATE INDEX idx_sst_reason_occurred ON seat_state_transitions (transition_reason, occurred_at);
