-- ===========================================================================
-- booking_db, part 2 — bookings and THE INVARIANT.
--
-- INV-1. For any given (trip_id, seat_id), the set of ACTIVE booking segments
--        must be pairwise non-overlapping under half-open interval semantics.
--
--        active  = status IN ('HELD','CONFIRMED')
--        segment = the half-open integer range [from_seq, to_seq)
--        overlap = a.from_seq < b.to_seq AND b.from_seq < a.to_seq
--
-- Half-open is the whole game. With closed ranges [1,9] and [9,25], Kandy
-- belongs to both bookings and the brief's headline scenario -- one seat sold
-- Fort->Kandy and again Kandy->Badulla -- fails. With [1,9) and [9,25) the two
-- ranges are adjacent, not overlapping, and both sales succeed.
--
-- This file is where correctness actually lives. Everything in the Java layer
-- is a translation of the error this constraint raises. See docs/04.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TYPE booking_status AS ENUM ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED');

CREATE TABLE booking (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference         VARCHAR(16) NOT NULL UNIQUE,
    trip_id           UUID NOT NULL REFERENCES trip(id),
    status            booking_status NOT NULL,
    channel           VARCHAR(16) NOT NULL DEFAULT 'WEB',
    contact_name      VARCHAR(160),
    contact_email     CITEXT,
    contact_phone     VARCHAR(32),
    passenger_count   INT NOT NULL DEFAULT 1,
    quote_id          UUID,
    fare_rule_version VARCHAR(32),
    total_fare_minor  BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'LKR',
    expires_at        TIMESTAMPTZ,
    confirmed_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT held_has_expiry
        CHECK (status <> 'HELD' OR expires_at IS NOT NULL),
    CONSTRAINT confirmed_has_timestamp
        CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL),
    CONSTRAINT fare_non_negative CHECK (total_fare_minor >= 0),
    CONSTRAINT passenger_count_positive CHECK (passenger_count >= 1)
);

CREATE TABLE booking_segment (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    trip_id           UUID NOT NULL REFERENCES trip(id),
    seat_id           UUID NOT NULL REFERENCES trip_seat(id),
    from_seq          INT NOT NULL,
    to_seq            INT NOT NULL,
    from_station_code VARCHAR(8) NOT NULL,
    to_station_code   VARCHAR(8) NOT NULL,
    distance_km       NUMERIC(7,2) NOT NULL,
    fare_minor        BIGINT NOT NULL,
    status            booking_status NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Generated, so there is literally no other way to write the range: the
    -- half-open convention cannot be violated by a future INSERT that forgets.
    leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED,

    CONSTRAINT seq_ordered       CHECK (from_seq < to_seq),
    CONSTRAINT seq_positive      CHECK (from_seq >= 1),
    CONSTRAINT distance_positive CHECK (distance_km > 0),
    CONSTRAINT fare_non_negative CHECK (fare_minor >= 0)
);

-- ===========================================================================
-- *** THE INVARIANT ***
--
-- Read aloud: "no two rows may exist where the trip is the same AND the seat
-- is the same AND the legs overlap, among rows whose status is HELD or
-- CONFIRMED."  That sentence is INV-1 verbatim.
--
-- Why the database and not the application:
--
--   * An application `if (overlaps) throw` is a CHECK, not an invariant. It is
--     bypassed by a second replica, a background job, a data migration, a
--     support script, or a developer with psql. This cannot be bypassed.
--
--   * It gives predicate locking for free. Inserting a range that overlaps an
--     UNCOMMITTED range blocks on that transaction's xid rather than failing;
--     when the first commits, the second gets 23P01; when it rolls back, the
--     second succeeds. You cannot take a row lock on a row that does not exist
--     yet, which is exactly why check-then-act loses this race.
--
--   * The lock granularity is exactly right. Locking the seat would block the
--     disjoint sales this project exists to enable. Locking the trip would
--     serialise the whole train. Range overlap contends only on real conflicts.
--
--   * btree_gist is required because `=` on uuid is a btree operator that GiST
--     does not index natively; the extension supplies the operator classes so
--     the scalar equality and the range overlap can share one index.
-- ===========================================================================
ALTER TABLE booking_segment
    ADD CONSTRAINT no_overlapping_active_segments
    EXCLUDE USING gist (
        trip_id WITH =,
        seat_id WITH =,
        leg     WITH &&
    )
    WHERE (status IN ('HELD', 'CONFIRMED'));

-- ---------------------------------------------------------------------------
-- booking.status and booking_segment.status must never disagree: the exclusion
-- predicate reads the segment's status, so a stale segment status would mean
-- the constraint is evaluating the wrong state. The trigger makes the booking
-- the single source of truth and the segment a projection of it.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_segment_status() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        UPDATE booking_segment SET status = NEW.status WHERE booking_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER booking_status_propagates
    AFTER UPDATE OF status ON booking
    FOR EACH ROW EXECUTE FUNCTION sync_segment_status();

CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER booking_touch_updated_at
    BEFORE UPDATE ON booking
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ---------------------------------------------------------------------------
-- Indexes.
--
-- Note what is ABSENT: there is no btree on booking_segment(from_seq) or
-- (to_seq). Range containment is answered by the GiST index that the exclusion
-- constraint already maintains -- the correctness mechanism and the
-- availability query's index are the same object, so they cannot drift apart.
-- A btree on either endpoint alone would go unused and slow every insert.
-- ---------------------------------------------------------------------------
CREATE INDEX idx_segment_trip_seat ON booking_segment (trip_id, seat_id);
CREATE INDEX idx_segment_booking   ON booking_segment (booking_id);

-- Partial, so the sweeper's scan stays proportional to live holds rather than
-- to every booking ever made.
CREATE INDEX idx_booking_expiring  ON booking (expires_at)
    WHERE status = 'HELD';
CREATE INDEX idx_booking_contact   ON booking (contact_email, created_at DESC);
CREATE INDEX idx_booking_trip      ON booking (trip_id, status);
