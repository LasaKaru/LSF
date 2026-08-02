-- ===========================================================================
-- booking_db, part 1 — the trip topology snapshot.
--
-- A trip does NOT point at the live catalog tables. When a trip is published,
-- the stop list, distances, consist and seat inventory are COPIED here
-- (ADR-005). Two reasons, both load-bearing:
--
--   1. Correctness. If the department inserts a new halt into the route next
--      March, every existing booking's from_seq/to_seq would silently shift by
--      one and thousands of tickets would quietly point at the wrong stations.
--      A snapshot makes bookings immutable against future route edits.
--
--   2. Isolation. booking-service never calls catalog-service on the hot path;
--      everything the booking transaction needs is in its own database.
--
-- Note there is no `direction` flag used in any query. An UP service and a
-- DOWN service are two trips, each with its own ascending stop_sequence, so
-- reversal is handled by the data rather than by a branch in the code.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TYPE coach_type_enum AS ENUM ('RESERVED', 'UNRESERVED', 'OBSERVATION', 'FIRST_AC');
CREATE TYPE class_enum      AS ENUM ('FIRST', 'SECOND', 'THIRD', 'OBSERVATION');
CREATE TYPE direction_enum  AS ENUM ('UP', 'DOWN');
CREATE TYPE trip_status     AS ENUM ('SCHEDULED', 'PUBLISHED', 'DEPARTED', 'CLOSED');
CREATE TYPE facing_enum     AS ENUM ('FORWARD', 'BACKWARD');

CREATE TABLE trip (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    train_id          UUID NOT NULL,
    train_code        VARCHAR(16)  NOT NULL,
    train_name_en     VARCHAR(120) NOT NULL,
    route_code        VARCHAR(24)  NOT NULL,
    service_date      DATE NOT NULL,
    direction         direction_enum NOT NULL,
    status            trip_status NOT NULL DEFAULT 'PUBLISHED',
    departs_at        TIMESTAMPTZ NOT NULL,
    arrives_at        TIMESTAMPTZ NOT NULL,
    booking_cutoff_at TIMESTAMPTZ NOT NULL,
    published_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (train_id, service_date, direction)
);

-- stop_sequence is THE coordinate system of this system: a dense, monotonically
-- increasing integer per trip, starting at 1. Every booking is an interval over
-- it. See docs/04 §2.
CREATE TABLE trip_stop (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id             UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    stop_sequence       INT  NOT NULL,
    station_id          UUID NOT NULL,
    station_code        VARCHAR(8)   NOT NULL,
    station_name_en     VARCHAR(120) NOT NULL,
    station_name_si     VARCHAR(120),
    station_name_ta     VARCHAR(120),
    distance_km         NUMERIC(7,2) NOT NULL,
    scheduled_arrival   TIMESTAMPTZ,
    scheduled_departure TIMESTAMPTZ,
    UNIQUE (trip_id, stop_sequence),
    UNIQUE (trip_id, station_code),
    CONSTRAINT seq_positive       CHECK (stop_sequence >= 1),
    CONSTRAINT distance_non_neg   CHECK (distance_km >= 0)
);

CREATE TABLE trip_coach (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id        UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    coach_number   VARCHAR(8) NOT NULL,
    coach_type     coach_type_enum NOT NULL,
    class_code     class_enum NOT NULL,
    is_reservable  BOOLEAN NOT NULL,
    position_index INT NOT NULL,
    layout         JSONB,
    capacity       INT,
    UNIQUE (trip_id, coach_number),
    UNIQUE (trip_id, position_index)
);

CREATE TABLE trip_seat (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- trip_id is reachable via trip_coach and is denormalised DELIBERATELY:
    -- it lets the availability query and the exclusion constraint work without
    -- a join. A trigger keeps it consistent with the parent coach.
    trip_id       UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    trip_coach_id UUID NOT NULL REFERENCES trip_coach(id) ON DELETE CASCADE,
    seat_label    VARCHAR(8) NOT NULL,
    row_index     INT NOT NULL,
    column_index  INT NOT NULL,
    is_window     BOOLEAN NOT NULL DEFAULT FALSE,
    is_aisle      BOOLEAN NOT NULL DEFAULT FALSE,
    facing        facing_enum NOT NULL DEFAULT 'FORWARD',
    is_bookable   BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (trip_coach_id, seat_label)
);

-- Guards the denormalisation above: a seat's trip_id must match its coach's.
CREATE OR REPLACE FUNCTION assert_seat_trip_matches_coach() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.trip_id IS DISTINCT FROM (SELECT trip_id FROM trip_coach WHERE id = NEW.trip_coach_id) THEN
        RAISE EXCEPTION 'trip_seat.trip_id % does not match its coach''s trip', NEW.trip_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trip_seat_trip_consistency
    BEFORE INSERT OR UPDATE ON trip_seat
    FOR EACH ROW EXECUTE FUNCTION assert_seat_trip_matches_coach();

CREATE INDEX idx_trip_search      ON trip (service_date, route_code, status);
CREATE INDEX idx_trip_stop_lookup ON trip_stop (trip_id, station_code);
CREATE INDEX idx_trip_seat_coach  ON trip_seat (trip_id, trip_coach_id);
