# 07 — Data Model

PostgreSQL 16. Database per service. This document covers the schemas in dependency order; the
`booking_db` section is the important one.

---

## 1. ERD (logical)

```
                        catalog_db
  station ──< route_stop >── route ──< train ──< train_composition >── coach_layout
                                          │                    │
                                          └──< schedule ──< trip_plan
                                                              │
                                                   TripPublished event
                                                              │
                        booking_db                            ▼
  trip ──< trip_stop                                     (snapshot)
   │  └──< trip_coach ──< trip_seat
   │                          │
   └──< booking ──< booking_segment  ★ EXCLUDE USING gist
                          │
        idempotency_record │  outbox_event  │  processed_event

                        pricing_db
  fare_rule_set ──< fare_band | fare_class_multiplier | fare_coach_multiplier
                  | fare_scenic_segment | fare_demand_tier | fare_advance_tier
  quote

                        waitlist_db          reporting_db (read model)
  waitlist_entry                             trip_segment_occupancy_mv
                                             trip_revenue_mv
```

---

## 2. `catalog_db` — network and schedule

```sql
CREATE TABLE station (
    id           UUID PRIMARY KEY,
    code         VARCHAR(8) NOT NULL UNIQUE,        -- CMB, KDY, BDL
    name_en      VARCHAR(120) NOT NULL,
    name_si      VARCHAR(120),
    name_ta      VARCHAR(120),
    latitude     NUMERIC(9,6),
    longitude    NUMERIC(9,6),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE route (
    id           UUID PRIMARY KEY,
    code         VARCHAR(24) NOT NULL UNIQUE,       -- MAIN_UPCOUNTRY
    name_en      VARCHAR(160) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE route_stop (
    id               UUID PRIMARY KEY,
    route_id         UUID NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    station_id       UUID NOT NULL REFERENCES station(id),
    stop_order       INT  NOT NULL,
    distance_from_origin_km NUMERIC(7,2) NOT NULL,
    UNIQUE (route_id, stop_order),
    UNIQUE (route_id, station_id),
    CONSTRAINT stop_order_positive CHECK (stop_order >= 1),
    CONSTRAINT distance_non_negative CHECK (distance_from_origin_km >= 0)
);
```

Monotonicity of distance cannot be expressed as a row-level `CHECK`, so it is enforced by a
`CONSTRAINT TRIGGER` on `route_stop` that verifies the whole route after each statement. Worth the
trigger: a non-monotonic distance would silently produce negative fares.

```sql
CREATE TABLE coach_layout (
    id            UUID PRIMARY KEY,
    code          VARCHAR(32) NOT NULL UNIQUE,      -- SECOND_2x2_60
    description   VARCHAR(160),
    row_count     INT NOT NULL,
    column_count  INT NOT NULL,
    aisle_after_column INT,
    grid          JSONB NOT NULL,                   -- seat labels, window/aisle, facing, blanks
    seat_count    INT NOT NULL,
    CONSTRAINT grid_is_object CHECK (jsonb_typeof(grid) = 'object')
);

CREATE TABLE train (
    id         UUID PRIMARY KEY,
    code       VARCHAR(16) NOT NULL UNIQUE,          -- 1005
    name_en    VARCHAR(120) NOT NULL,                -- Podi Menike
    route_id   UUID NOT NULL REFERENCES route(id)
);

CREATE TABLE train_composition (
    id             UUID PRIMARY KEY,
    train_id       UUID NOT NULL REFERENCES train(id) ON DELETE CASCADE,
    coach_number   VARCHAR(8) NOT NULL,              -- R1, U3
    coach_type     coach_type_enum NOT NULL,         -- RESERVED|UNRESERVED|OBSERVATION|FIRST_AC
    class_code     class_enum NOT NULL,
    layout_id      UUID REFERENCES coach_layout(id), -- NULL for unreserved
    position_index INT NOT NULL,
    is_reservable  BOOLEAN NOT NULL,
    UNIQUE (train_id, coach_number),
    UNIQUE (train_id, position_index),
    CONSTRAINT reservable_needs_layout
      CHECK (NOT is_reservable OR layout_id IS NOT NULL)
);
```

`reservable_needs_layout` is the constraint that makes **R10 (configurability)** safe: you cannot create
a bookable coach without telling the system what its seats look like.

---

## 3. `booking_db` — the important one

### 3.1 Snapshotted topology

```sql
CREATE TABLE trip (
    id            UUID PRIMARY KEY,
    train_id      UUID NOT NULL,
    train_code    VARCHAR(16) NOT NULL,
    route_code    VARCHAR(24) NOT NULL,
    service_date  DATE NOT NULL,
    direction     direction_enum NOT NULL,           -- UP | DOWN
    status        trip_status NOT NULL,              -- SCHEDULED|PUBLISHED|DEPARTED|CLOSED
    departs_at    TIMESTAMPTZ NOT NULL,
    booking_cutoff_at TIMESTAMPTZ NOT NULL,
    published_at  TIMESTAMPTZ,
    UNIQUE (train_id, service_date, direction)
);

CREATE TABLE trip_stop (
    id            UUID PRIMARY KEY,
    trip_id       UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    stop_sequence INT  NOT NULL,
    station_id    UUID NOT NULL,
    station_code  VARCHAR(8) NOT NULL,
    station_name_en VARCHAR(120) NOT NULL,
    station_name_si VARCHAR(120),
    station_name_ta VARCHAR(120),
    distance_km   NUMERIC(7,2) NOT NULL,
    scheduled_arrival   TIMESTAMPTZ,
    scheduled_departure TIMESTAMPTZ,
    UNIQUE (trip_id, stop_sequence),
    UNIQUE (trip_id, station_code),
    CONSTRAINT seq_positive CHECK (stop_sequence >= 1)
);

CREATE TABLE trip_coach (
    id             UUID PRIMARY KEY,
    trip_id        UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    coach_number   VARCHAR(8) NOT NULL,
    coach_type     coach_type_enum NOT NULL,
    class_code     class_enum NOT NULL,
    is_reservable  BOOLEAN NOT NULL,
    position_index INT NOT NULL,
    layout         JSONB,
    capacity       INT NOT NULL,       -- headcount cap for unreserved coaches
    UNIQUE (trip_id, coach_number)
);

CREATE TABLE trip_seat (
    id             UUID PRIMARY KEY,
    trip_id        UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    trip_coach_id  UUID NOT NULL REFERENCES trip_coach(id) ON DELETE CASCADE,
    seat_label     VARCHAR(8) NOT NULL,
    row_index      INT NOT NULL,
    column_index   INT NOT NULL,
    is_window      BOOLEAN NOT NULL DEFAULT FALSE,
    is_aisle       BOOLEAN NOT NULL DEFAULT FALSE,
    facing         facing_enum NOT NULL DEFAULT 'FORWARD',
    is_bookable    BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (trip_coach_id, seat_label)
);
```

The `trip_id` column on `trip_seat` is technically redundant (reachable via `trip_coach`) and is
**denormalised deliberately**: it lets the availability query and the exclusion constraint work without
a join, and a `CHECK`-backed trigger keeps it consistent. Denormalisation on the hot path, justified in
one line rather than discovered later in a slow query log.

### 3.2 Bookings and the invariant

```sql
CREATE TYPE booking_status AS ENUM ('HELD','CONFIRMED','CANCELLED','EXPIRED');

CREATE TABLE booking (
    id             UUID PRIMARY KEY,
    reference      VARCHAR(16) NOT NULL UNIQUE,
    trip_id        UUID NOT NULL REFERENCES trip(id),
    status         booking_status NOT NULL,
    channel        VARCHAR(16) NOT NULL DEFAULT 'WEB',   -- WEB|COUNTER|ADMIN|WAITLIST
    contact_name   VARCHAR(160),
    contact_email  CITEXT,
    contact_phone  VARCHAR(32),
    passenger_count INT NOT NULL DEFAULT 1,
    quote_id       UUID,
    fare_rule_version VARCHAR(32),
    total_fare_minor BIGINT NOT NULL,
    currency       CHAR(3) NOT NULL DEFAULT 'LKR',
    expires_at     TIMESTAMPTZ,
    confirmed_at   TIMESTAMPTZ,
    cancelled_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT held_has_expiry
      CHECK (status <> 'HELD' OR expires_at IS NOT NULL),
    CONSTRAINT confirmed_has_timestamp
      CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL),
    CONSTRAINT fare_non_negative CHECK (total_fare_minor >= 0)
);

CREATE TABLE booking_segment (
    id           UUID PRIMARY KEY,
    booking_id   UUID NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    trip_id      UUID NOT NULL REFERENCES trip(id),
    seat_id      UUID NOT NULL REFERENCES trip_seat(id),
    from_seq     INT NOT NULL,
    to_seq       INT NOT NULL,
    from_station_code VARCHAR(8) NOT NULL,
    to_station_code   VARCHAR(8) NOT NULL,
    distance_km  NUMERIC(7,2) NOT NULL,
    fare_minor   BIGINT NOT NULL,
    status       booking_status NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED,

    CONSTRAINT seq_ordered   CHECK (from_seq < to_seq),
    CONSTRAINT seq_positive  CHECK (from_seq >= 1),
    CONSTRAINT distance_positive CHECK (distance_km > 0),
    CONSTRAINT fare_non_negative CHECK (fare_minor >= 0)
);

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ★★★ THE INVARIANT ★★★
ALTER TABLE booking_segment
  ADD CONSTRAINT no_overlapping_active_segments
  EXCLUDE USING gist (
      trip_id WITH =,
      seat_id WITH =,
      leg     WITH &&
  )
  WHERE (status IN ('HELD','CONFIRMED'));
```

A trigger keeps `booking_segment.status` in step with `booking.status` so the two can never disagree —
if they could, the exclusion predicate would be evaluating stale state.

### 3.3 Supporting tables

```sql
CREATE TABLE idempotency_record (
    key            VARCHAR(128) PRIMARY KEY,
    request_hash   CHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body  JSONB NOT NULL,
    booking_id     UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id  UUID NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    partition_key VARCHAR(64) NOT NULL,      -- trip_id: per-trip ordering
    payload       JSONB NOT NULL,
    correlation_id VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

The partial index `idx_outbox_unpublished` keeps the relay's poll query O(unpublished) rather than
O(all events ever) — a small detail that decides whether the outbox pattern scales or slowly dies.

---

## 4. Indexes and why each exists

| Index | Purpose |
|---|---|
| `no_overlapping_active_segments` (GiST) | **The invariant** *and* the availability query's index. One object, two jobs — they cannot drift apart |
| `booking_segment (trip_id, seat_id)` | Seat history, seat map assembly |
| `booking_segment (booking_id)` | Aggregate load |
| `booking (status, expires_at) WHERE status='HELD'` | Sweeper scan; partial so it stays tiny |
| `booking (reference)` unique | Lookup by reference |
| `booking (contact_email, created_at DESC)` | "My bookings" |
| `trip (service_date, route_code, status)` | Trip search |
| `trip_stop (trip_id, station_code)` | Station-code → sequence resolution on every request |
| `trip_seat (trip_id, trip_coach_id)` | Seat map |
| `outbox_event (id) WHERE published_at IS NULL` | Relay poll |
| `idempotency_record (expires_at)` | Cleanup |

**No index on `booking_segment(from_seq)` or `(to_seq)`.** Range queries go through the GiST index; a
btree on either endpoint alone would be unused and would slow every insert. Noting the *absent* indexes
matters as much as the present ones.

---

## 5. Migrations

**Flyway**, one migration directory per service, forward-only, `V{n}__{description}.sql`.

Zero-downtime rules, applied without exception:

1. **Expand → migrate → contract.** Add nullable, backfill in batches, start writing both, switch reads,
   then drop. Never in one release.
2. **`CREATE INDEX CONCURRENTLY`** on non-empty tables. Note the wrinkle:
   `ALTER TABLE … ADD CONSTRAINT EXCLUDE` takes an `ACCESS EXCLUSIVE` lock and cannot be built
   concurrently. On a live table the procedure is: `CREATE INDEX CONCURRENTLY` the GiST index, then
   `ADD CONSTRAINT … USING INDEX`. Documented here because getting this wrong locks the booking table on
   a production deploy.
3. **Never rename or drop in the same release that stops using a column.** Two releases minimum.
4. **Enums are extended, never reordered.** `ALTER TYPE … ADD VALUE` only.
5. Every migration is tested against a **restored production-shaped dump** in CI, not an empty database —
   an empty database will happily accept a migration that would take a 40-minute lock on real data.

Rollback is by **compensating forward migration**, not by down-scripts. Down-scripts encourage the
fiction that a destructive change is reversible.

---

## 6. Data lifecycle

| Data | Retention | Then |
|---|---|---|
| Bookings & segments | 7 years (financial record) | Archive to cold storage, partition detach |
| Trips | 2 years hot | Archive |
| Idempotency records | 24 h | Delete |
| Outbox events | 7 days after publish | Delete |
| Processed events | 30 days | Delete |
| Audit log | 7 years | Archive, immutable |
| PII (contact details) | Anonymised 12 months after travel | Hashed, booking record retained |

`trip`, `booking` and `booking_segment` are declared with **monthly range partitioning** on
`service_date` / `created_at` from day one. Retro-fitting partitioning to a live booking table is
painful; declaring it up front costs nothing.

---

## 7. Seed data

`db/seed/` (idempotent, `ON CONFLICT DO NOTHING`), loaded by the `bootstrap` container:

- 25 up-country stations with en/si/ta names and cumulative distances
- 1 route (both directions), 2 trains (1005 Podi Menike, 1015 Udarata Menike)
- 8-coach composition: **3 reserved + 5 unreserved**, exactly as the brief specifies
- 3 coach layouts: `SECOND_2x2_60`, `FIRST_AC_2x2_48`, `OBSERVATION_2x1_36`
- 30 days of published trips
- 1 fare rule set with bands, multipliers, scenic segment, demand and advance tiers
- ~40 demo bookings, deliberately including **adjacent-leg pairs on the same seat** so the resale
  behaviour is visible on first load rather than requiring the reviewer to create it

Seed values are illustrative — see `docs/01` A9/A10.

---

## 8. Backup and recovery

| Aspect | Approach |
|---|---|
| Backups | Nightly base backup + continuous WAL archiving (PITR) |
| RPO / RTO | ≤ 5 min / ≤ 30 min |
| Restore testing | Automated monthly restore into a scratch environment; a backup that has never been restored is a hypothesis |
| HA | Synchronous standby; async read replicas for reporting only — **never for booking reads** |

The last point is load-bearing: serving availability from a lagging replica would show seats that are
already gone. Under this design that produces a clean 409 rather than a double sale, but it degrades the
user experience, so availability reads go to the primary and only the reporting service uses replicas.

---

**Related:** [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) ·
[`03-domain-model.md`](03-domain-model.md)
