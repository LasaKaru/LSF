# 17 — Configuration Reference

Two levels of configuration, deliberately separated:

- **Environment variables** — deployment concerns (endpoints, credentials, timeouts, feature flags).
- **Database rows** — domain concerns (stations, coaches, seat layouts, fare rules).

The brief's requirement that *"the number of coaches, seats per coach, and stations [be] configurable
rather than hardcoded"* is satisfied by the second category. Those are **data**, not settings — a coach
is a thing the department owns, not a tuning parameter, and modelling it as a row means adding one is an
INSERT rather than a deployment.

---

## 1. Environment variables

**Legend:** 🔒 = secret, never committed, sourced from the platform secret store in production.

### 1.1 Database

| Variable | Default | Scope | Description |
|---|---|---|---|
| `POSTGRES_HOST` | `postgres` | all | Hostname |
| `POSTGRES_PORT` | `5432` | all | Port |
| `POSTGRES_USER` | `yathra` | all | Username |
| `POSTGRES_PASSWORD` 🔒 | — | all | **No default. Startup fails if unset** |
| `BOOKING_DB_NAME` | `booking_db` | booking | |
| `CATALOG_DB_NAME` | `catalog_db` | catalog | |
| `PRICING_DB_NAME` | `pricing_db` | pricing | |
| `DB_POOL_MAX_SIZE` | `20` | all | Must satisfy `replicas × pool ≤ max_connections × 0.8` |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | `3000` | all | Fail fast rather than queue |
| `FLYWAY_ENABLED` | `true` (compose) / `false` (k8s) | all | In Kubernetes, migrations run as a Job |

`POSTGRES_PASSWORD` having no default is intentional. A default database password is how a development
convenience becomes a production incident.

### 1.2 Booking behaviour — the domain knobs

| Variable | Default | Description |
|---|---|---|
| `BOOKING_HOLD_TTL_SECONDS` | `600` | How long a hold reserves inventory. Longer = kinder UX, more squatting surface |
| `HOLD_SWEEP_INTERVAL_SECONDS` | `15` | Eager expiry cadence |
| `HOLD_SWEEP_BATCH_SIZE` | `500` | Bounds sweeper transaction size |
| `BOOKING_CUTOFF_MINUTES` | `30` | Booking closes this long before departure |
| `BOOKING_HORIZON_DAYS` | `30` | How far ahead trips are published |
| `MAX_SEATS_PER_BOOKING` | `6` | Group size cap |
| `MAX_ACTIVE_HOLDS_PER_CONTACT` | `6` | **Anti-squatting control** (`docs/11` §4) |
| `BOOKING_RETRY_MAX_ATTEMPTS` | `3` | Retries on `40P01` / `40001` |
| `BOOKING_RETRY_BACKOFF_MS` | `50` | Base for jittered backoff |
| `SEAT_SELECTION_STRATEGY` | `FRAGMENTATION_MINIMISING` | `FIRST_AVAILABLE` \| `PREFERENCE_MATCHING` \| `GROUP_ADJACENCY` \| `FRAGMENTATION_MINIMISING` |
| `SEGMENT_TURNAROUND_BUFFER_STOPS` | `0` | Stops a seat stays blocked after alighting. `0` for trains; the hook exists (`docs/04` §3.3) |

### 1.3 Pricing

| Variable | Default | Description |
|---|---|---|
| `QUOTE_SIGNING_KEY` 🔒 | — | HMAC-SHA256 key. `openssl rand -base64 48` |
| `QUOTE_PREVIOUS_SIGNING_KEY` 🔒 | — | Enables zero-downtime key rotation |
| `QUOTE_TTL_SECONDS` | `600` | Quote validity |
| `FARE_RULE_SET_VERSION` | `latest` | Pin for testing/rollback |
| `CURRENCY` | `LKR` | |
| `FARE_ROUNDING_MINOR` | `1000` | Round to nearest LKR 10 (minor units) |
| `DEMAND_PRICING_ENABLED` | `false` | Off by default — enabling it is a **policy** decision, not a technical one |
| `DEMAND_MULTIPLIER_MIN` / `_MAX` | `0.90` / `1.40` | Hard bounds; the engine will not exceed them |

`DEMAND_PRICING_ENABLED` defaults to `false` deliberately: shipping a system that silently varies public
transport fares by demand, without the department having chosen that, would be inappropriate regardless
of how well the feature works.

### 1.4 Security

| Variable | Default | Description |
|---|---|---|
| `JWT_ISSUER_URI` | — | OIDC issuer. Points to Keycloak locally, WSO2 Identity Server in production |
| `JWT_AUDIENCE` | `yathra-api` | |
| `ADMIN_BOOTSTRAP_EMAIL` | `admin@yathra.lk` | Local only |
| `ADMIN_BOOTSTRAP_PASSWORD` 🔒 | — | Local only; production uses the IdP |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowlist. **Never `*`** |
| `RATE_LIMIT_BOOKING_PER_MINUTE` | `10` | Per identity |
| `RATE_LIMIT_READ_PER_MINUTE` | `120` | Per identity |
| `PII_ENCRYPTION_KEY` 🔒 | — | At-rest encryption for contact details |

### 1.5 Messaging, cache, observability

| Variable | Default | Description |
|---|---|---|
| `OUTBOX_TRANSPORT` | `log` (core) / `kafka` (full) | **The only difference between profiles.** No code path branches on it beyond the adapter |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | |
| `OUTBOX_RELAY_INTERVAL_MS` | `500` | Poll cadence |
| `yathra.scheduling.enabled` | `true` | Master switch for all background timers (hold sweeper, outbox relay, waitlist offer sweeper) **and** ShedLock's `@SchedulerLock` processing. Only ever set `false` by the integration tests, which call those methods directly so they can assert on the next line; a `fixedDelay` task fires immediately at startup regardless of its interval, so lengthening the intervals is not a substitute for switching it off |
| `REDIS_URL` | `redis://redis:6379` | Cache, rate limits, idempotency index |
| `AVAILABILITY_CACHE_TTL_SECONDS` | `5` | Short — availability is volatile |
| `SSE_ENABLED` | `true` | Falls back to ETag polling |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` (prod) / `1.0` (local) | |
| `LOG_LEVEL` | `INFO` | |
| `LOG_FORMAT` | `json` | `console` for local readability |

### 1.6 Feature flags

| Variable | Default | Description |
|---|---|---|
| `FEATURE_WAITLIST_ENABLED` | `true` | |
| `FEATURE_SEAT_MAP_ENABLED` | `true` | |
| `FEATURE_ADMIN_CONSOLE_ENABLED` | `true` | |
| `FEATURE_MULTILINGUAL_ENABLED` | `true` | |
| `FEATURE_SCENIC_SURCHARGE_ENABLED` | `true` | |

---

## 2. Domain configuration (database rows)

### 2.1 Stations and route — R10, first part

Add a station: one `station` row plus one `route_stop` row with `stop_order` and cumulative distance,
then re-publish future trips.

```sql
INSERT INTO station (id, code, name_en, name_si, name_ta)
VALUES (gen_random_uuid(), 'PSR', 'Passara', 'පස්සර', 'பஸ்ஸரா');

INSERT INTO route_stop (id, route_id, station_id, stop_order, distance_from_origin_km)
VALUES (gen_random_uuid(), :routeId, :stationId, 26, 310.0);
```

Existing bookings are unaffected — each trip snapshots its own stop list at publication.

### 2.2 Coaches — R10, second part

```sql
INSERT INTO train_composition
  (id, train_id, coach_number, coach_type, class_code, layout_id, position_index, is_reservable)
VALUES
  (gen_random_uuid(), :trainId, 'R4', 'RESERVED', 'SECOND', :layoutId, 9, TRUE);
```

The `reservable_needs_layout` constraint prevents creating a bookable coach without a seat layout — the
configurability is guarded, not just permitted.

### 2.3 Seat layouts — R10, third part

A layout is one row with a JSON grid:

```json
{
  "rows": 15,
  "columns": 4,
  "aisleAfterColumn": 2,
  "seatPattern": ["A", "B", "C", "D"],
  "windowColumns": [1, 4],
  "facing": "FORWARD",
  "blanks": [{ "row": 15, "col": 4 }]
}
```

The seat-map component renders directly from this JSON, so **a new layout requires no frontend change.**
Shipped layouts: `SECOND_2x2_60`, `FIRST_AC_2x2_48`, `OBSERVATION_2x1_36`.

### 2.4 Fare rules

Seven tables (`docs/05` §7), all effective-dated and versioned. Publishing new fares creates a **new
rule set version** rather than mutating the old one, so historical quotes stay reproducible and a bad
change is rolled back by changing an effective date.

---

## 3. Precedence

```
1. Environment variables            (highest — deployment)
2. Database configuration rows      (domain)
3. application.yml defaults         (lowest — sensible fallbacks)
```

No hard-coded values for anything in categories 1 or 2. An architecture test fails the build if a
magic number appears where a configuration value belongs.

---

## 4. Validation at startup

Every service validates its configuration on boot and **refuses to start** rather than degrading:

- All required variables present (missing secret ⇒ hard fail, never a default).
- Numeric ranges sane (`BOOKING_HOLD_TTL_SECONDS` between 60 and 3600).
- `DEMAND_MULTIPLIER_MIN < DEMAND_MULTIPLIER_MAX`.
- Database reachable and Flyway schema at the expected version.
- `CORS_ALLOWED_ORIGINS` does not contain `*`.
- Signing keys are at least 32 bytes.

Failures are logged with the offending variable name and a one-line remedy. A service that starts with
bad configuration and fails mysteriously three hours later is worse than one that refuses to start.

---

**Related:** [`11-security-and-secrets.md`](11-security-and-secrets.md) ·
[`08-microservices-and-deployment.md`](08-microservices-and-deployment.md)
