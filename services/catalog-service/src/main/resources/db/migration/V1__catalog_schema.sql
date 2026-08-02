-- ===========================================================================
-- catalog_db — the network, the fleet and the timetable.
--
-- This database answers "what stations exist, in what order, how far apart,
-- and which coaches does a train carry". It is deliberately NOT involved in
-- selling anything: booking_db snapshots what it needs at trip publication
-- (see ADR-005) so that editing the route next March cannot retroactively
-- change what an existing ticket means.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE coach_type_enum  AS ENUM ('RESERVED', 'UNRESERVED', 'OBSERVATION', 'FIRST_AC');
CREATE TYPE class_enum       AS ENUM ('FIRST', 'SECOND', 'THIRD', 'OBSERVATION');
CREATE TYPE direction_enum   AS ENUM ('UP', 'DOWN');

-- ---------------------------------------------------------------------------
-- Stations. Names in all three official scripts live with the data rather than
-- in a frontend bundle, so adding a station translates it everywhere at once.
-- ---------------------------------------------------------------------------
CREATE TABLE station (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(8)   NOT NULL UNIQUE,
    name_en     VARCHAR(120) NOT NULL,
    name_si     VARCHAR(120),
    name_ta     VARCHAR(120),
    latitude    NUMERIC(9,6),
    longitude   NUMERIC(9,6),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE route (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       VARCHAR(24)  NOT NULL UNIQUE,
    name_en    VARCHAR(160) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE
);

-- stop_order is the canonical ordering of the route in the UP direction.
-- distance_from_origin_km is cumulative from the UP origin.
CREATE TABLE route_stop (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                UUID NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    station_id              UUID NOT NULL REFERENCES station(id),
    stop_order              INT  NOT NULL,
    distance_from_origin_km NUMERIC(7,2) NOT NULL,
    UNIQUE (route_id, stop_order),
    UNIQUE (route_id, station_id),
    CONSTRAINT stop_order_positive   CHECK (stop_order >= 1),
    CONSTRAINT distance_non_negative CHECK (distance_from_origin_km >= 0)
);

-- Monotonicity of distance along a route cannot be expressed as a row-level
-- CHECK, and a non-monotonic route silently produces negative distances and
-- therefore negative fares. A deferred constraint trigger validates the whole
-- route once per statement instead.
CREATE OR REPLACE FUNCTION assert_route_distance_monotonic() RETURNS TRIGGER AS $$
DECLARE
    offending RECORD;
BEGIN
    SELECT r.route_id, r.stop_order INTO offending
      FROM (SELECT route_id, stop_order, distance_from_origin_km,
                   LAG(distance_from_origin_km) OVER (PARTITION BY route_id ORDER BY stop_order) AS prev
              FROM route_stop) r
     WHERE r.prev IS NOT NULL AND r.distance_from_origin_km <= r.prev
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'route % is not monotonically increasing in distance at stop_order %',
            offending.route_id, offending.stop_order
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER route_stop_monotonic
    AFTER INSERT OR UPDATE ON route_stop
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_route_distance_monotonic();

-- ---------------------------------------------------------------------------
-- Seat layouts. A coach layout is one row with a JSON grid; the seat-map UI
-- renders straight from it, so a new coach type needs no code change in either
-- the backend or the frontend. This is requirement R10 (configurability).
-- ---------------------------------------------------------------------------
CREATE TABLE coach_layout (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(32)  NOT NULL UNIQUE,
    description        VARCHAR(160),
    row_count          INT NOT NULL,
    column_count       INT NOT NULL,
    aisle_after_column INT,
    grid               JSONB NOT NULL,
    seat_count         INT NOT NULL,
    CONSTRAINT grid_is_object     CHECK (jsonb_typeof(grid) = 'object'),
    CONSTRAINT row_count_positive CHECK (row_count > 0),
    CONSTRAINT col_count_positive CHECK (column_count > 0),
    CONSTRAINT seat_count_positive CHECK (seat_count > 0)
);

CREATE TABLE train (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code     VARCHAR(16)  NOT NULL UNIQUE,
    name_en  VARCHAR(120) NOT NULL,
    name_si  VARCHAR(120),
    name_ta  VARCHAR(120),
    route_id UUID NOT NULL REFERENCES route(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

-- The consist. "3 reserved and 5 unreserved" is data in this table, not a
-- constant in code: adding a fourth reserved coach is one INSERT.
CREATE TABLE train_composition (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    train_id       UUID NOT NULL REFERENCES train(id) ON DELETE CASCADE,
    coach_number   VARCHAR(8) NOT NULL,
    coach_type     coach_type_enum NOT NULL,
    class_code     class_enum NOT NULL,
    layout_id      UUID REFERENCES coach_layout(id),
    position_index INT NOT NULL,
    is_reservable  BOOLEAN NOT NULL,
    capacity       INT,
    UNIQUE (train_id, coach_number),
    UNIQUE (train_id, position_index),
    -- You cannot create a bookable coach without saying what its seats look
    -- like. Configurability is guarded, not merely permitted.
    CONSTRAINT reservable_needs_layout
        CHECK (NOT is_reservable OR layout_id IS NOT NULL),
    -- An unreserved coach has no seat map, so it needs a headcount cap.
    CONSTRAINT unreserved_needs_capacity
        CHECK (is_reservable OR capacity IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- Timetable. One schedule per (train, direction).
--
-- Scheduled stop times are DERIVED at publication from distance, an average
-- speed and a per-stop dwell, rather than stored per stop. That is an explicit
-- simplification: a real deployment replaces this with imported SLR timetable
-- data, and the derivation lives in one place (TripPublisher) so swapping it
-- is a local change. Timings are presentational only — inventory is indexed by
-- stop sequence, never by clock time (docs/04 §2.3).
-- ---------------------------------------------------------------------------
CREATE TABLE schedule (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    train_id      UUID NOT NULL REFERENCES train(id) ON DELETE CASCADE,
    direction     direction_enum NOT NULL,
    departs_time  TIME NOT NULL,
    avg_speed_kmh NUMERIC(5,2) NOT NULL DEFAULT 33.00,
    dwell_minutes INT NOT NULL DEFAULT 2,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (train_id, direction),
    CONSTRAINT speed_positive CHECK (avg_speed_kmh > 0)
);

-- Records which service dates have already been snapshotted into booking_db,
-- so publication is idempotent and restart-safe.
CREATE TABLE trip_publication (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id   UUID NOT NULL REFERENCES schedule(id) ON DELETE CASCADE,
    service_date  DATE NOT NULL,
    trip_id       UUID NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (schedule_id, service_date)
);

CREATE INDEX idx_route_stop_route_order ON route_stop (route_id, stop_order);
CREATE INDEX idx_station_code           ON station (code);
CREATE INDEX idx_trip_publication_date  ON trip_publication (service_date);
