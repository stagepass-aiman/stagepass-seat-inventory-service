-- V3__create_seat_hold_seats.sql
-- Junction table: one row per seat per hold.
-- Why not UUID[] in seat_holds? GIN indexes on arrays are much slower than
-- B-tree indexes on scalar FK columns (Phase 4 ER diagram §3.3).
-- Individual seat status (HELD→COMMITTED) requires per-row tracking here.

CREATE TABLE seat_hold_seats (
    id         UUID         NOT NULL,
    hold_id    UUID         NOT NULL,
    seat_id    UUID         NOT NULL,
    event_id   UUID         NOT NULL,   -- denormalised for query convenience
    status     VARCHAR(20)  NOT NULL DEFAULT 'HELD',
    created_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_seat_hold_seats PRIMARY KEY (id),
    -- A seat cannot appear twice in the same hold (all-or-nothing integrity)
    CONSTRAINT uq_seat_hold_seats_hold_seat UNIQUE (hold_id, seat_id),
    CONSTRAINT fk_seat_hold_seats_hold
        FOREIGN KEY (hold_id) REFERENCES seat_holds (id) ON DELETE CASCADE,
    CONSTRAINT ck_seat_hold_seats_status CHECK (status IN ('HELD','COMMITTED','RELEASED'))
);

-- All seats in a hold (CommitSeats / ReleaseSeats)
CREATE INDEX idx_seat_hold_seats_hold ON seat_hold_seats (hold_id);
-- All active holds for a specific seat (reconciliation)
CREATE INDEX idx_seat_hold_seats_seat_status ON seat_hold_seats (seat_id, status);
-- All held seats for an event (seat map reconciliation)
CREATE INDEX idx_seat_hold_seats_event_status ON seat_hold_seats (event_id, status);
