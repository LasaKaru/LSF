-- ===========================================================================
-- pricing_db — the fare engine's configuration.
--
-- Every number in the fare formula is a row in one of these tables. Nothing is
-- a constant in code, and publishing new fares creates a NEW effective-dated
-- rule set rather than mutating the old one, so a historical quote stays
-- reproducible months later for an audit or a complaint, and a bad fare change
-- is rolled back by changing an effective date rather than by a deployment.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE class_enum      AS ENUM ('FIRST', 'SECOND', 'THIRD', 'OBSERVATION');
CREATE TYPE coach_type_enum AS ENUM ('RESERVED', 'UNRESERVED', 'OBSERVATION', 'FIRST_AC');

CREATE TABLE fare_rule_set (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            VARCHAR(32) NOT NULL UNIQUE,
    currency           CHAR(3) NOT NULL DEFAULT 'LKR',
    -- Rounding and minimum fare in MINOR units (LKR 10 = 1000 minor).
    rounding_minor     BIGINT NOT NULL DEFAULT 1000,
    minimum_fare_minor BIGINT NOT NULL DEFAULT 2000,
    effective_from     TIMESTAMPTZ NOT NULL,
    effective_to       TIMESTAMPTZ,
    is_published       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rounding_positive CHECK (rounding_minor > 0),
    CONSTRAINT minimum_non_neg   CHECK (minimum_fare_minor >= 0),
    CONSTRAINT effective_ordered CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ---------------------------------------------------------------------------
-- Telescopic bands. Marginal rate per km FALLS as distance rises, computed
-- exactly like income-tax brackets: marginal, cumulative, monotonic.
--
-- upper_km NULL means the open-ended top band.
-- rate_per_km is in MAJOR currency units per km and is NUMERIC, not float:
-- it is a rate, not a monetary amount. Amounts are integer minor units, and
-- the conversion happens once, at the end, after rounding.
-- ---------------------------------------------------------------------------
CREATE TABLE fare_band (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    band_order  INT NOT NULL,
    lower_km    NUMERIC(7,2) NOT NULL,
    upper_km    NUMERIC(7,2),
    rate_per_km NUMERIC(10,4) NOT NULL,
    label       VARCHAR(80) NOT NULL,
    UNIQUE (rule_set_id, band_order),
    CONSTRAINT band_bounds_ordered CHECK (upper_km IS NULL OR upper_km > lower_km),
    CONSTRAINT band_lower_non_neg  CHECK (lower_km >= 0),
    CONSTRAINT band_rate_positive  CHECK (rate_per_km > 0)
);

CREATE TABLE fare_class_multiplier (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    class_code  class_enum NOT NULL,
    multiplier  NUMERIC(6,4) NOT NULL,
    UNIQUE (rule_set_id, class_code),
    CONSTRAINT class_multiplier_positive CHECK (multiplier > 0)
);

-- The coach multiplier is where the brief's injustice is repaired.
-- RESERVED is 1.00: there is no empty-seat surcharge any more, because the
-- seat now resells.
CREATE TABLE fare_coach_multiplier (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    coach_type  coach_type_enum NOT NULL,
    multiplier  NUMERIC(6,4) NOT NULL,
    UNIQUE (rule_set_id, coach_type),
    CONSTRAINT coach_multiplier_positive CHECK (multiplier > 0)
);

-- ---------------------------------------------------------------------------
-- Scenic premium, charged ADDITIVELY and only on the scenic kilometres the
-- passenger actually travels. Multiplicative would apply it to the whole
-- journey and overcharge a Fort->Kandy passenger for a view they never see.
--
-- Stored as STATION CODES, not stop sequences: the same physical stretch has
-- different sequence numbers on UP and DOWN services, so the engine resolves
-- codes against the trip and takes [min, max) -- direction-agnostic by
-- construction rather than by a branch.
-- ---------------------------------------------------------------------------
CREATE TABLE fare_scenic_segment (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id       UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    route_code        VARCHAR(24) NOT NULL,
    from_station_code VARCHAR(8) NOT NULL,
    to_station_code   VARCHAR(8) NOT NULL,
    uplift            NUMERIC(6,4) NOT NULL,
    label             VARCHAR(120) NOT NULL,
    CONSTRAINT scenic_uplift_non_neg CHECK (uplift >= 0)
);

-- Bounded and published. An unbounded black-box yield algorithm is not
-- deployable by a state operator; these tiers are the defensible 80%.
CREATE TABLE fare_demand_tier (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id         UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    lower_occupancy_pct NUMERIC(5,2) NOT NULL,
    upper_occupancy_pct NUMERIC(5,2),
    multiplier          NUMERIC(6,4) NOT NULL,
    CONSTRAINT demand_multiplier_positive CHECK (multiplier > 0)
);

CREATE TABLE fare_advance_tier (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id     UUID NOT NULL REFERENCES fare_rule_set(id) ON DELETE CASCADE,
    min_days_ahead  INT NOT NULL,
    max_days_ahead  INT,
    discount        NUMERIC(6,4) NOT NULL,
    CONSTRAINT advance_discount_range CHECK (discount >= 0 AND discount < 1)
);

-- ---------------------------------------------------------------------------
-- Issued quotes. The quote is also HMAC-signed and self-describing, so
-- booking-service can verify it with a local signature check rather than an
-- HTTP call inside the booking transaction. This table is the audit trail and
-- the replay guard, not the trust anchor.
-- ---------------------------------------------------------------------------
CREATE TABLE quote (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id           UUID NOT NULL,
    from_station_code VARCHAR(8) NOT NULL,
    to_station_code   VARCHAR(8) NOT NULL,
    from_seq          INT NOT NULL,
    to_seq            INT NOT NULL,
    distance_km       NUMERIC(7,2) NOT NULL,
    class_code        class_enum NOT NULL,
    coach_type        coach_type_enum NOT NULL,
    passengers        INT NOT NULL DEFAULT 1,
    unit_fare_minor   BIGINT NOT NULL,
    total_minor       BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'LKR',
    breakdown         JSONB NOT NULL,
    rule_set_version  VARCHAR(32) NOT NULL,
    signature         VARCHAR(255) NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT quote_seq_ordered CHECK (from_seq < to_seq),
    CONSTRAINT quote_total_non_neg CHECK (total_minor >= 0)
);

CREATE INDEX idx_quote_expiry   ON quote (expires_at);
CREATE INDEX idx_rule_set_active ON fare_rule_set (effective_from DESC) WHERE is_published;
