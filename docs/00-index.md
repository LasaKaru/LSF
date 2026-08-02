# Yathra — Segment-Based Rail Seat Booking Platform

> Reference implementation for the **Colombo Fort ⇄ Badulla** up-country line, in which a single
> reserved seat can be sold independently for multiple non-overlapping legs of the same journey.

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey)]()

---

## Table of Contents

1. [The problem in one paragraph](#1-the-problem-in-one-paragraph)
2. [What this system does](#2-what-this-system-does)
3. [Quick start (one command)](#3-quick-start-one-command)
4. [Core design decisions and reasoning](#4-core-design-decisions-and-reasoning)
5. [Alternatives considered and rejected](#5-alternatives-considered-and-rejected)
6. [Architecture at a glance](#6-architecture-at-a-glance)
7. [Fare model](#7-fare-model)
8. [Concurrency correctness — how we prove it](#8-concurrency-correctness--how-we-prove-it)
9. [Configuration — nothing is hardcoded](#9-configuration--nothing-is-hardcoded)
10. [Challenges faced](#10-challenges-faced)
11. [Extra credit features](#11-extra-credit-features)
12. [What I would do next](#12-what-i-would-do-next)
13. [Documentation index](#13-documentation-index)
14. [Repository layout](#14-repository-layout)

---

## 1. The problem in one paragraph

Sri Lanka Railways sells a reserved seat on the Badulla line as **one indivisible unit for the entire
route**. A passenger travelling Colombo Fort → Kandy occupies that seat for 121 km of a 292 km journey,
but the seat is dead inventory for the remaining 171 km. The department compensates by pricing the
reserved partial leg at roughly **double** the unreserved fare — the passenger effectively subsidises
the empty seat behind them. Meanwhile the five unreserved coaches are packed. This is a classic
**interval packing** problem dressed as a ticketing problem: the unit of inventory is not the seat, it
is the **(seat × contiguous stretch of the route)** pair. Once you model it that way, the revenue and
the fairness problem both dissolve — and the entire engineering difficulty collapses into one question:
*how do you guarantee that two overlapping intervals are never sold on the same seat, under concurrency,
across horizontally scaled service replicas?*

That question is what this repository answers, and everything else is scaffolding around it.

---

## 2. What this system does

| Capability | Status |
|---|---|
| Model a route as an ordered stop list with cumulative distances | ✅ Core |
| Sell the same physical seat to `Fort→Kandy` and `Kandy→Badulla` independently | ✅ Core |
| Reject any overlapping sale on the same seat, **guaranteed at the database level** | ✅ Core |
| Distance-proportional fares — you pay for what you travel | ✅ Core |
| Availability query scoped to a *specific leg*, not the whole train | ✅ Core |
| Hold → pay → confirm flow with TTL and automatic inventory release | ✅ Core |
| Idempotent booking API safe against client retries and double-submits | ✅ Core |
| Coaches, seats-per-coach, layouts, stations and distances fully configurable | ✅ Core |
| Interactive seat map coloured per selected leg | ⭐ Extra credit |
| FIFO waitlist with automatic promotion when a segment is released | ⭐ Extra credit |
| Admin console — seat-km utilisation, revenue per segment, resale uplift | ⭐ Extra credit |
| Live availability push (SSE) + graceful 409 conflict UX | ⭐ Extra credit |
| Telescopic (tapering) banded fares + scenic-segment premium + demand multiplier | ⭐ Extra credit |
| Trilingual UI (English / සිංහල / தமிழ்) | ⭐ Extra credit |

---

## 3. Quick start (one command)

**Prerequisites:** Docker 24+ and Docker Compose v2. Nothing else. No JDK, no Node, no local Postgres.

```bash
git clone https://github.com/<your-handle>/yathra-segment-booking.git
cd yathra-segment-booking
cp .env.example .env          # sane non-secret defaults; generates dev credentials
docker compose up --build
```

That is the whole setup. When the logs settle:

| Surface | URL | Notes |
|---|---|---|
| Passenger web app | http://localhost:3000 | React + Vite |
| API gateway | http://localhost:8080 | Spring Cloud Gateway (stand-in for WSO2 API Manager) |
| OpenAPI / Swagger UI | http://localhost:8080/swagger-ui.html | Aggregated across services |
| Admin console | http://localhost:3000/admin | Login: `admin@yathra.lk` / value of `ADMIN_BOOTSTRAP_PASSWORD` |
| Grafana (traces/metrics) | http://localhost:3001 | Optional `observability` profile |
| Mailpit (notification sink) | http://localhost:8025 | Optional `full` profile |

Seed data (25 up-country stations, 8 coaches — 3 reserved / 5 unreserved, 30 days of trips) is loaded by
a one-shot `bootstrap` container. It is idempotent; re-running `docker compose up` will not duplicate it.

**Profiles** — because "microservices" and "runs in one shot" pull in opposite directions:

```bash
docker compose --profile core up          # gateway + catalog + booking + pricing + web + postgres  (default, ~6 containers)
docker compose --profile full up          # + waitlist, admin-reporting, notification, payment-mock, Kafka
docker compose --profile observability up # + OTel collector, Tempo, Prometheus, Grafana
```

**Verify the headline behaviour in 30 seconds** (proves segment resale works):

```bash
./scripts/demo-segment-resale.sh
# 1. Books seat 3A on trip T-1005 for CMB→KDY  → 201 Created, LKR 470
# 2. Books seat 3A on the SAME trip for KDY→BDL → 201 Created, LKR 680   (same seat, sold twice)
# 3. Books seat 3A for CMB→BDL                  → 409 SEAT_SEGMENT_UNAVAILABLE
# 4. Books seat 3A for GMP→NAN (straddles KDY)  → 409 SEAT_SEGMENT_UNAVAILABLE
```

**Prove the concurrency guarantee** (50 threads, same seat, overlapping legs, exactly one winner):

```bash
./scripts/concurrency-proof.sh     # runs the Testcontainers race-condition suite and prints the tally
```

Full instructions, troubleshooting, and the manual (non-Docker) path are in
[`docs/08-microservices-and-deployment.md`](docs/08-microservices-and-deployment.md).

---

## 4. Core design decisions and reasoning

### 4.1 A booking is a half-open interval `[from_seq, to_seq)` over the trip's stop sequence

Every stop on a trip has a monotonically increasing integer `stop_sequence`. A booking segment stores
`from_seq` and `to_seq` and is interpreted as the **half-open range** `[from_seq, to_seq)`.

Two segments conflict **iff** `a.from < b.to AND b.from < a.to`.

This single line of arithmetic is the entire business rule, and half-open is the reason adjacency works:

| Booking A | Booking B | Overlap? | Meaning |
|---|---|---|---|
| `[1,9)` Fort→Kandy | `[9,25)` Kandy→Badulla | **No** | ✅ The exact scenario in the brief — same seat, sold twice |
| `[1,9)` | `[5,15)` | Yes | ❌ B boards before A alights |
| `[1,25)` | `[9,11)` | Yes | ❌ B's leg is inside A's |
| `[1,9)` | `[1,9)` | Yes | ❌ Duplicate |
| `[9,9)` | — | — | ❌ Rejected by `CHECK (from_seq < to_seq)` |

**Why sequence integers and not station IDs, timestamps, or km markers?**

- *Station IDs* have no ordering — you would need a join and a comparison function on every conflict check.
- *Timestamps* look tempting (a seat is occupied from 06:15 to 09:40) but they are wrong: they drift with
  delays, they force you to re-derive occupancy when a timetable changes, and they silently break when a
  train crosses midnight. Occupancy is a **topological** fact, not a temporal one.
- *Kilometre markers* are floats. Float ranges as a uniqueness key is a decision you regret exactly once.
- Integers are indexable, exclusion-constraint-friendly, and comparison is free.

**Direction is handled by the trip, not by a flag.** An up-country service and a down-country service are
two different trips, each with its own ascending stop sequence. There is no "reverse" special case anywhere
in the code — a nice property that falls out of sequencing per trip.

See [ADR-001](docs/adr/ADR-001-half-open-interval-model.md).

### 4.2 The overlap invariant is enforced by PostgreSQL, not by application code

The `booking_segment` table carries a **GiST exclusion constraint**:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking_segment
  ADD COLUMN leg int4range
    GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED;

ALTER TABLE booking_segment
  ADD CONSTRAINT no_overlapping_active_segments
  EXCLUDE USING gist (
      trip_id  WITH =,
      seat_id  WITH =,
      leg      WITH &&
  )
  WHERE (status IN ('HELD', 'CONFIRMED'));
```

This is the most important decision in the project, and it is worth being explicit about *why*:

1. **It is a true invariant, not a check.** An application-level `if (overlaps) throw` is a *check* — it
   can be bypassed by a second replica, a background job, a data migration, a support script, or a
   developer with `psql`. An exclusion constraint cannot be bypassed by anything.
2. **It gives us predicate locking for free.** When transaction T2 tries to insert a range that overlaps
   T1's *uncommitted* range, PostgreSQL blocks T2 on T1's index entry. When T1 commits, T2 fails with
   `SQLSTATE 23P01`. When T1 rolls back, T2 succeeds. That is precisely the semantics we want, and we get
   it without `SERIALIZABLE`, without retry storms, and without holding a lock for the duration of a
   user's payment.
3. **It scales horizontally.** WSO2-style deployments run *n* replicas behind a gateway. Any solution
   that lives in one JVM's memory (`synchronized`, a local lock striping map, an in-process cache) is
   wrong on replica #2. The database is the only shared serialisation point that already exists.
4. **The lock granularity is exactly right.** Locking the *seat* would falsely block the very scenario
   we are building for. Locking the *trip* would serialise the entire train. Range-overlap locking
   contends only where a real conflict exists.
5. **It survives us.** In five years someone will write a bulk-import script for group bookings. The
   constraint will still be there.

The application still catches `23P01` and translates it into a clean `409 Problem Details` response with
refreshed availability — the constraint is the *floor*, not the user experience.

See [ADR-002](docs/adr/ADR-002-exclusion-constraint-concurrency.md) and
[`docs/04-segment-concurrency-design.md`](docs/04-segment-concurrency-design.md).

### 4.3 Booking and seat inventory live in **one** bounded context with **one** database

WSO2 shops are microservice shops, and the reflex is to split `inventory-service` from `booking-service`.
I deliberately did not, and this is the decision I most expect to be challenged on, so here is the reasoning
in full:

> **A hard transactional invariant defines a service boundary. You do not draw a service boundary
> through the middle of an invariant.**

If seat inventory and bookings were separate services with separate databases, "no two overlapping
segments on one seat" would become a *distributed* invariant, and the only ways to enforce it are:

- a two-phase commit across services (operationally miserable, blocks on coordinator failure), or
- a saga with compensations — which means the system is **eventually** consistent, which means there is a
  window in which the same seat-segment is sold twice, which means we must *detect* double-sales and
  *compensate* by cancelling a real passenger's real ticket. For an airline that is a business decision
  ("involuntary denied boarding"). For a commuter railway selling a LKR 1,200 seat, cancelling a
  confirmed booking to fix our own architecture is not acceptable.

So: **booking + segment inventory = one service, one Postgres database, one ACID transaction.** Everything
that is *not* part of that invariant — route catalog, pricing rules, payments, waitlist, reporting,
notifications — is a separate service, communicating over REST for queries and Kafka events for state
changes. The result is a system that is genuinely microservice-shaped at the seams that matter, and
stubbornly monolithic exactly where physics requires it.

See [ADR-003](docs/adr/ADR-003-service-boundaries.md).

### 4.4 Two-phase booking: `HELD` (TTL) → `CONFIRMED`

A passenger picking a seat and then entering payment details takes 2–8 minutes. Two bad options:

- Hold nothing until payment succeeds → two passengers pay for the same seat, one gets a refund and a bad day.
- Hold a database row lock during payment → a payment gateway timeout holds a lock for 30 seconds and the
  connection pool dies.

Instead a booking is created immediately in status `HELD` with `expires_at = now() + BOOKING_HOLD_TTL`
(default 10 min). The exclusion constraint covers `HELD` **and** `CONFIRMED`, so the hold genuinely
reserves the segment. Two independent release paths:

- **Lazy:** any availability or booking query treats `HELD AND expires_at < now()` as free.
- **Eager:** a sweeper (`ShedLock`-guarded, runs every 15 s) transitions expired holds to `EXPIRED`,
  emits `SegmentReleased`, and wakes the waitlist matcher.

Both are needed. Lazy alone leaves stale rows blocking the exclusion constraint; eager alone leaves a
15-second window of falsely-unavailable seats. Together the worst case is a seat that looks taken for a
few seconds after a hold dies — acceptable and self-correcting.

See [ADR-004](docs/adr/ADR-004-hold-then-confirm.md).

### 4.5 Trips snapshot their topology at publication time

A `trip` (train on a date) does **not** point at the live route table. When a trip is published, the
current stop list, distances, coach composition and seat inventory are **copied** into `trip_stop` and
`trip_seat`. Why: if the department inserts a new halt into the route next March, every existing booking's
`from_seq`/`to_seq` would silently shift by one and thousands of tickets would quietly point at the wrong
stations. Snapshotting makes bookings immutable against future route edits and makes the booking service
self-contained (it does not have to call the catalog service on the hot path).

See [ADR-005](docs/adr/ADR-005-trip-topology-snapshot.md).

### 4.6 Fares are computed server-side and returned as a signed, expiring quote

The client never computes or submits a price. It requests a quote; pricing-service returns a priced
breakdown plus an HMAC-signed `quoteToken` with a 10-minute TTL; booking-service verifies the signature
and the TTL before writing. This kills client-side price tampering, makes the price the passenger saw
legally identical to the price charged, and gives us an audit trail of exactly which fare rules applied.

See [ADR-008](docs/adr/ADR-008-signed-fare-quotes.md).

### 4.7 Every mutating endpoint is idempotent

`POST /bookings` requires an `Idempotency-Key` header. The key + a hash of the request body is stored with
the response. A replay returns the original response instead of creating a second booking. This is not
gold-plating: mobile users on Sri Lankan 3G double-tap "Confirm" constantly, and the gateway retries on
5xx. Without idempotency, a network blip sells a passenger two seats.

See [ADR-009](docs/adr/ADR-009-idempotency.md).

---

## 5. Alternatives considered and rejected

Design is mostly a record of what you decided *not* to do. These were real candidates.

### 5.1 Materialised seat × segment availability matrix

**The idea:** pre-create one row per `(trip, seat, segment)` — 24 segments × 90 seats × 3 coaches ≈ 6,480
rows per trip. Booking a leg = `UPDATE ... SET booked = true WHERE segment BETWEEN a AND b AND booked = false`,
and check the affected row count.

**Why not:**
- Write amplification — one booking touches up to 24 rows instead of 1.
- Row count explodes with route length and trip horizon (30 days × 6,480 = ~195k rows for one train).
- Adding a station to the route invalidates the entire materialised set.
- Correctness still depends on the app checking `rowCount == expected` and rolling back — an
  application-enforced invariant, which is what I was trying to escape.
- No natural way to express "which contiguous stretch is free" for the seat map without re-aggregating.

**Where it *would* win:** if we needed sub-millisecond availability at very high read volume, this is a
great **read model** (see §5.6). It is a bad write model.

### 5.2 Bitmask occupancy (`BIGINT`, one bit per segment)

**The idea:** each seat row carries a bitmask; a leg is a contiguous run of bits; booking is
`mask & legMask == 0` then `mask |= legMask` under a row lock.

**Why not:**
- **It caps out.** The full Main Line + up-country route has well over 64 scheduled halts. Our seed uses
  25 major stations, so it *would* fit today — which is exactly the trap. The brief explicitly says the
  department may extend the route. `BIT VARYING` removes the cap but loses the cheap integer ops and is
  awkward to index.
- Locks the whole **seat row** for any leg, so two people booking genuinely disjoint legs serialise
  against each other. That is the exact anti-pattern this project exists to avoid.
- The invariant lives in application code again.
- Debugging a production incident by reading `0b0000111100000000` is not a thing I want to do to my
  on-call colleague.

### 5.3 Redis distributed lock / Redlock

**The idea:** `SET seat:{trip}:{seat} NX PX 5000` around the booking transaction.

**Why not:** it introduces a second system whose *availability* becomes a precondition for *correctness*.
Redlock's safety under partition and clock skew is famously contested; a GC pause longer than the lock TTL
produces two lock holders. And it is redundant — the database is already a strongly consistent shared
resource that all replicas talk to. Adding Redis to protect a Postgres invariant is strictly worse than
letting Postgres protect its own invariant. (We *do* use Redis — for the availability cache, rate limiting,
and idempotency records. Never for correctness.)

### 5.4 `SERIALIZABLE` isolation everywhere

Correct, and genuinely tempting. Rejected because it makes every transaction in the service pay for a
guarantee only the booking write path needs, and because serialisation failures surface as
`40001 could not serialize access` — which the app must retry generically, with no idea *which* seat
conflicted, so it cannot offer the user an intelligent alternative. The exclusion constraint fails with a
precise, attributable error at `READ COMMITTED`. We use `READ COMMITTED` + constraint, and reserve
`SERIALIZABLE` for the (rare) multi-seat group-booking path.

### 5.5 Optimistic locking with a `version` column on `seat`

Same fatal flaw as the bitmask: the version is per-seat, so two non-overlapping legs on one seat produce a
false `OptimisticLockException`. Under load, the retry loop turns disjoint bookings into a livelock. The
conflict domain must be the *interval*, not the seat.

### 5.6 CQRS with a separate availability read store from day one

Deferred, not rejected. The write model (`booking_segment` + GiST) answers availability queries fine at
this scale — a `NOT EXISTS` against a GiST index over one trip's segments is a sub-millisecond operation.
We ship a Redis cache with event-driven invalidation and an ETag, which is 90% of the benefit at 10% of
the complexity. The design keeps the door open: `SegmentBooked`/`SegmentReleased` events are already
published, so a denormalised availability projection (essentially §5.1 as a read model) can be added later
without touching the write path. Documented in [`docs/15-future-work.md`](docs/15-future-work.md).

### 5.7 Ballerina instead of Java/Spring Boot

Genuinely considered given the WSO2 context — Ballerina is purpose-built for integration and its
network-primitives-as-language-constructs model is a good fit for a service mesh. Rejected for this
exercise because: (a) the hard part of this problem is transactional data modelling, where Spring Data +
JPA + Flyway + Testcontainers give me the fastest path to a *provably* correct implementation inside the
time budget; (b) the reviewer needs to read the code quickly, and Spring Boot is the lingua franca; and
(c) the design is deliberately framework-agnostic — the invariant lives in DDL, so a Ballerina
reimplementation of the service layer would inherit the correctness for free. I would be happy to port
the booking service to Ballerina as a follow-up.

### 5.8 Modelling occupancy in time instead of stops

Rejected in §4.1, but worth restating because it is the most common first instinct: it seems natural to
say "the seat is busy 06:15–09:40". It couples inventory to the timetable, breaks on delays and midnight
crossings, and makes "is this seat free between Kandy and Ella" a question about clocks rather than about
geography. Stops are the stable coordinate system.

---

## 6. Architecture at a glance

```
                       ┌──────────────────────────────────────────┐
                       │  React SPA (passenger)  │  Admin console │
                       └────────────────┬─────────────────────────┘
                                        │ HTTPS / JSON + SSE
                       ┌────────────────▼─────────────────────────┐
                       │  API Gateway  (WSO2 API Manager in prod;  │
                       │  Spring Cloud Gateway in compose)         │
                       │  authn/authz · rate limit · CORS · trace  │
                       └───┬───────┬────────┬─────────┬───────┬────┘
                           │       │        │         │       │
            ┌──────────────▼─┐ ┌───▼─────┐ ┌▼───────┐ ┌▼──────┐ ┌▼─────────────┐
            │ booking-service│ │ catalog │ │pricing │ │payment│ │admin-reporting│
            │  ★ INVARIANT   │ │ service │ │service │ │ mock  │ │   (CQRS read) │
            │ availability   │ │stations │ │ quotes │ │       │ │ occupancy/rev │
            │ hold/confirm   │ │ trips   │ │ rules  │ │       │ │               │
            └───┬────────┬───┘ └────┬────┘ └───┬────┘ └───┬───┘ └──────▲────────┘
                │        │          │          │          │            │
         ┌──────▼──┐  ┌──▼──────────▼──────────▼──────────▼────────────┴──┐
         │ booking │  │            Kafka  (transactional outbox)           │
         │   _db   │  │  BookingConfirmed · SegmentReleased · HoldExpired  │
         │ ★ GiST  │  └──────────────────────┬─────────────────────────────┘
         │  EXCLUDE│                         │
         └─────────┘                 ┌───────▼────────┐  ┌──────────────┐
                                     │waitlist-service│  │ notification │
                                     └────────────────┘  └──────────────┘
```

- **Synchronous** (REST/JSON, OpenAPI 3.1) on the query and booking path — the passenger is waiting.
- **Asynchronous** (Kafka, transactional outbox, idempotent consumers) for everything downstream —
  waitlist promotion, reporting projections, emails/SMS.
- **Every service** is a 12-factor container: config from env, `/healthz` + `/readyz`, structured JSON
  logs with a propagated `X-Correlation-Id`, OpenTelemetry traces.
- **No service reaches into another service's database.** Ever.

Detail: [`docs/02-architecture.md`](docs/02-architecture.md) ·
[`docs/08-microservices-and-deployment.md`](docs/08-microservices-and-deployment.md).

---

## 7. Fare model

The brief's core injustice — *"pays roughly double … because the seat sits empty"* — is an artefact of
non-resellable inventory. Once a seat resells, the surcharge has no economic justification and the fare
becomes what it should always have been: **a function of distance travelled**.

```
fare = round₁₀( max( MINIMUM_FARE,
          telescopic_base(km)
            × class_multiplier × coach_multiplier
            × demand_multiplier(segment_occupancy)
            × (1 − advance_discount(days_ahead))
          + scenic_surcharge(overlapping_km_only)
       ) )
```

Note what is **absent**: there is no reserved-coach surcharge. It existed only to cover the empty seat,
and the empty seat no longer exists. Reserved is `×1.00`; unreserved is `×0.80`.

Key properties:

- **Telescopic banding.** Marginal rate per km *decreases* with distance (0–50 km at full rate, 50–150 km
  at 0.85×, 150+ km at 0.70×), mirroring real railway tariff practice and preserving the long-haul
  passenger's discount. Computed like income tax brackets, so it is monotonic and additive.
- **Additivity guard.** A property-based test asserts `fare(A→B) + fare(B→C) ≥ fare(A→C)` for all
  A<B<C. Without it, telescopic banding creates an arbitrage where splitting a ticket is cheaper than
  buying it whole — a real bug in real fare systems.
- **Scenic premium.** The Nanu Oya → Ella stretch carries a configurable multiplier; it is the segment
  tourists specifically pay for, and it is where demand-based yield actually exists.
- **Demand multiplier.** Bounded `[0.90, 1.40]`, driven by *segment* occupancy, not train occupancy —
  a train that is full to Kandy and empty afterwards should price those two stretches differently.
  Bounded and published so it is defensible to a public transport regulator.
- **Revenue proof.** With illustrative seed distances (Fort→Kandy 121 km, Kandy→Badulla 171 km,
  Fort→Badulla 292 km), a seat that today carries only a Fort→Kandy passenger earns **LKR 760** and then
  runs empty for 171 km. Under segment booking the same seat earns **LKR 470 + LKR 680 = LKR 1,150
  (+51%)** — *while the short-leg passenger pays 38% less* (LKR 470 instead of ~LKR 760), and the
  full-journey fare is unchanged at LKR 1,040. The operator earns more and the passenger pays less,
  because 171 seat-km stopped being destroyed at every departure. Full derivation in
  [`docs/05-fare-engine.md`](docs/05-fare-engine.md) §5; the admin console reports this as **resale
  uplift**.

Full derivation, worked examples, and the rounding/edge-case rules:
[`docs/05-fare-engine.md`](docs/05-fare-engine.md) and [ADR-010](docs/adr/ADR-010-telescopic-fare-model.md).

---

## 8. Concurrency correctness — how we prove it

Claiming correctness is cheap. The repository proves it four ways:

| Layer | What it proves | Where |
|---|---|---|
| **Unit** | `Interval.overlaps()` truth table, including adjacency and degenerate ranges | `booking-service/src/test/.../IntervalTest.java` |
| **Integration (Testcontainers)** | The real DDL on real PostgreSQL rejects overlaps and accepts adjacency | `SegmentExclusionConstraintIT.java` |
| **Race condition** | 50 concurrent threads × same seat × overlapping legs ⇒ **exactly 1 success, 49 × HTTP 409**; and 2 threads × adjacent legs ⇒ **2 successes** | `ConcurrentBookingIT.java` |
| **Property-based (jqwik)** | For 10,000 randomly generated interval sets, the persisted state never contains two overlapping active segments on one seat | `SegmentInvariantPropertyTest.java` |
| **Load (k6)** | 200 VUs, 5 min, mixed read/write: p95 booking latency, conflict rate, zero invariant violations | `load/booking-contention.js` |

The race-condition test is the one to read first. It uses a `CyclicBarrier` so all threads hit the
endpoint within the same millisecond, and it asserts on the **database state** afterwards, not just on the
HTTP responses — because a system can return the right status codes and still have written garbage.

Details and the failure-mode analysis (deadlocks, hold expiry races, clock skew, sweeper contention):
[`docs/04-segment-concurrency-design.md`](docs/04-segment-concurrency-design.md).

---

## 9. Configuration — nothing is hardcoded

The brief asks for coaches, seats-per-coach and stations to be configurable. They are **data**, not
constants, and the model goes further:

- **Stations & distances** — rows in `station` / `route_stop`, loaded from a seed YAML. Adding
  Badulla → Passara later is an INSERT plus a re-publish of future trips.
- **Coach composition** — a `train_composition` row per train: coach number, type (`RESERVED`,
  `UNRESERVED`, `OBSERVATION`, `FIRST_CLASS_AC`), position, and a **layout reference**.
- **Seat layouts** — a `coach_layout` record with a JSON grid (`rows`, `columns`, aisle position, seat
  labels, window/aisle flags, facing direction). A 2+2 second-class coach, a 2+1 observation saloon, and
  a 3+2 commuter coach are three rows in a table, not three code paths. The seat map UI renders directly
  from this JSON, so a new layout needs zero frontend changes.
- **Everything else** — hold TTL, sweeper interval, fare bands, demand bounds, page sizes, CORS origins,
  rate limits — environment variables with documented defaults.

The full matrix (variable, default, scope, whether it is a secret) is in
[`docs/17-configuration.md`](docs/17-configuration.md).

**Secrets** are never committed. `.env.example` contains placeholders only; `.env` is gitignored;
`gitleaks` runs in CI and as a pre-commit hook; production reads from the platform's secret store
(Kubernetes Secrets / WSO2 Choreo secure config) via the same env-var interface, so the code is identical
in every environment. See [`docs/11-security-and-secrets.md`](docs/11-security-and-secrets.md).

---

## 10. Challenges faced

Honest ones, including the things I got wrong first.

**1. My first data model was wrong.** I initially stored bookings as `(seat_id, from_station_id,
to_station_id)` and computed overlaps with a join to the route table. It worked, and it was subtly
broken: route edits retroactively changed what existing bookings meant, and the overlap predicate could
not be expressed as an index. Rewriting to trip-scoped sequence integers with a snapshot cost half a day
and made everything downstream simpler. Lesson re-learned: **choose the coordinate system before you
write the schema.**

**2. Adjacency is a boundary condition that bites.** With closed ranges `[1,9]` and `[9,25]`, Kandy is
"occupied" by both bookings and the very scenario in the brief fails. It took a failing test to notice.
The half-open convention `[from, to)` fixes it, but it must be applied *consistently* — the API, the
seat map, the waitlist matcher and the reporting queries all have to agree. I documented the convention
prominently and added a lint-style test that fails if any query uses `'[]'` range bounds.

**3. Expired holds versus the exclusion constraint.** The constraint's `WHERE status IN ('HELD','CONFIRMED')`
predicate cannot know about `expires_at` — a partial index predicate has to be immutable, and `now()` is
not. So an expired-but-unswept hold blocks a legitimate booking. I considered making the sweeper run every
second (wasteful, and a contention point), or dropping `HELD` from the predicate (which reopens
double-selling during payment). The chosen answer is layered: the sweeper runs every 15 s under a
`ShedLock` so only one replica does it; the booking path opportunistically expires *the specific
conflicting holds* inside the same transaction before retrying once. Worst case is a few seconds of
false unavailability, and it self-heals.

**4. Deadlocks on multi-seat bookings.** A family booking 4 seats in one transaction can deadlock against
another family booking the same 4 seats in a different order. Fix: sort seat IDs before insertion so all
transactions acquire in a consistent order, plus a bounded retry on `40P01`. Found by a load test, not by
reasoning — which is the argument for load-testing the write path.

**5. Balancing "microservices" against "one command from a clean machine."** Twelve containers is an
honest microservice topology and a hostile first-run experience. Compose profiles resolved it: the
default `core` profile runs the six containers needed to demonstrate every core requirement; `full`
brings up the complete topology. I would rather a reviewer see the system working in 90 seconds than see
a more impressive diagram that times out.

**6. Deciding what *not* to build.** With a Tuesday deadline, the temptation was to build all five extra
credits shallowly. I froze the core at a fixed checkpoint (see [`SPRINT_PLAN.md`](SPRINT_PLAN.md)) and
only started extras once the concurrency proof was green and the README was written. Two extras were cut
deliberately (payment gateway integration, SMS notifications) and are listed in future work rather than
half-implemented.

---

## 11. Extra credit features

Each is written up as *problem → solution → design* per the brief. Full detail in
[`docs/13-extra-credit.md`](docs/13-extra-credit.md).

### 11.1 Seat map visualisation

**Problem.** A list of available seat codes tells a passenger nothing. They want a window seat, on the
correct side for the view, near their travelling companion.

**Solution.** A per-coach SVG grid rendered from the `coach_layout` JSON, coloured for the *selected leg*:
available (green), taken for this leg (grey), **partially available** (amber — free for part of your
requested stretch, which is the state that only exists in a segment-based system), your selection (blue).
Hovering a seat shows the occupied stretches as a mini route bar.

**Design.** Layout is data, so the component is a pure function of `(layout, availabilityForLeg)` and
supports any future coach type without a code change. Availability arrives as a compact
`{seatId: [[from,to],…]}` occupancy payload rather than a boolean, which is what makes the amber state
and the hover detail possible with one request. Keyboard-navigable and screen-reader labelled.

### 11.2 Waitlisting for fully booked segments

**Problem.** On peak dates every seat is sold for the popular Nanu Oya → Ella stretch, and cancellations
are invisible to the passenger who wanted it.

**Solution.** A FIFO waitlist per `(trip, leg, class)`. When any segment is released — cancellation or
hold expiry — `waitlist-service` consumes `SegmentReleased` and finds the oldest entry whose requested
leg **fits inside** the freed leg, creates a `HELD` booking with a 30-minute TTL, and notifies the
passenger. If they do not confirm, the hold expires and the matcher runs again for the next entry.

**Design.** The matcher is an event consumer, not a poller, so promotion is near-instant. It is
idempotent (dedupe on event ID) because Kafka is at-least-once. Fairness is timestamp FIFO with an
explicit *no queue-jumping* rule: a shorter leg later in the queue does **not** get promoted ahead of a
longer leg that also fits — the alternative maximises utilisation but is indefensible to a passenger
who watched three people skip past them. That trade-off is stated in the doc rather than hidden.

### 11.3 Admin console — occupancy and revenue

**Problem.** Leadership "believes there's revenue being left on the table" but has no instrument to see it.

**Solution.** A dashboard driven by a CQRS read model: per-trip **seat-km utilisation** (sold seat-km ÷
available seat-km — the correct load metric for segment inventory, since 100% of seats sold for 10% of
the route is not a full train), a segment occupancy heatmap showing exactly where the train empties out,
revenue per segment, and the headline **resale uplift** figure: revenue actually earned versus revenue
the same trip would have earned under whole-journey-only ticketing.

**Design.** Read-only projection built from booking events, so analytics queries never touch the
transactional path. Materialised views refreshed on a schedule for the heavy aggregates.

### 11.4 Conflict handling in the UI

**Problem.** Availability is a snapshot; between rendering it and clicking "Book", someone else can take
the seat. Naïve UIs show a stack trace.

**Solution.** Optimistic UI with a real recovery path: the seat map subscribes to an **SSE** availability
stream and greys out seats live; the confirm button shows a pending state and is disabled on submit; a
`409 SEAT_SEGMENT_UNAVAILABLE` returns the *conflicting seats and current availability* in the problem
detail, so the UI can immediately re-render, flash the lost seat red, and suggest the nearest equivalent
seat (same window/aisle preference) — one click to recover instead of starting over. Held bookings show a
live countdown so the passenger knows the clock is running.

**Design.** The principle is **confirm at write time, not at read time**: the frontend never treats
availability as authoritative, and the backend never trusts a client-side check. RFC 9457 Problem Details
carry machine-readable conflict data so error handling is data-driven rather than string-matching.

### 11.5 Fare logic beyond simple distance

Covered in §7 — telescopic banding, scenic premium, bounded segment-demand multiplier, advance-purchase
discount, additivity guard, signed quotes.

### 11.6 Trilingual UI

Sri Lanka Railways serves Sinhala, Tamil and English speakers. Station names are stored in all three
scripts and the UI switches at runtime. Low effort, and the alternative — an English-only public
transport system — is not one I would ship here.

---

## 12. What I would do next

Ranked by value, with reasoning, in [`docs/15-future-work.md`](docs/15-future-work.md). The top five:

1. **Dynamic reserved/unreserved rebalancing** — the brief's other half. If reserved coaches now resell,
   the optimal reserved:unreserved split is no longer 3:5. Model it and let the department tune it.
2. **Group/companion booking** with adjacency preference — currently supported transactionally but not
   optimised for seating people together.
3. **CQRS availability projection** for the read path once traffic justifies it (§5.6).
4. **Real payment gateway** (PayHere / LankaPay) behind the existing `payment-service` port.
5. **Overbooking model** with a statistically derived no-show rate per segment — the natural next
   revenue lever once segment data exists.

---

## 13. Documentation index

| Document | What it covers |
|---|---|
| [`docs/01-problem-analysis.md`](docs/01-problem-analysis.md) | Domain research, requirement decomposition, explicit assumptions, out-of-scope list |
| [`docs/02-architecture.md`](docs/02-architecture.md) | C4 context/container/component views, service catalogue, sync vs async, failure modes |
| [`docs/03-domain-model.md`](docs/03-domain-model.md) | Bounded contexts, aggregates, invariants, ubiquitous language, state machines |
| [`docs/04-segment-concurrency-design.md`](docs/04-segment-concurrency-design.md) | **The core doc.** Interval algebra, exclusion constraint, lock analysis, race walkthroughs |
| [`docs/05-fare-engine.md`](docs/05-fare-engine.md) | Fare formula, telescopic bands, worked examples, revenue modelling, additivity proof |
| [`docs/06-api-contract.md`](docs/06-api-contract.md) | OpenAPI overview, every endpoint, error catalogue, idempotency, pagination, versioning |
| [`docs/07-data-model.md`](docs/07-data-model.md) | Full DDL, ERD, indexes, migration strategy, retention |
| [`docs/08-microservices-and-deployment.md`](docs/08-microservices-and-deployment.md) | Service decomposition, Compose/K8s, CI/CD, WSO2 platform fit |
| [`docs/09-frontend-design.md`](docs/09-frontend-design.md) | Screens, state management, seat map component, conflict UX, a11y, i18n |
| [`docs/10-testing-strategy.md`](docs/10-testing-strategy.md) | Test pyramid, the concurrency proof, contract tests, load profile, coverage targets |
| [`docs/11-security-and-secrets.md`](docs/11-security-and-secrets.md) | AuthN/Z, secret management, OWASP checklist, PII and data protection |
| [`docs/12-observability-and-ops.md`](docs/12-observability-and-ops.md) | Logs/metrics/traces, SLOs, alerts, runbooks |
| [`docs/13-extra-credit.md`](docs/13-extra-credit.md) | Full problem→solution→design write-ups |
| [`docs/14-challenges-and-tradeoffs.md`](docs/14-challenges-and-tradeoffs.md) | Long-form version of §10, with the rejected attempts |
| [`docs/15-future-work.md`](docs/15-future-work.md) | Roadmap beyond the take-home |
| [`docs/16-demo-and-walkthrough.md`](docs/16-demo-and-walkthrough.md) | Interview demo script + live-extension rehearsal |
| [`docs/17-configuration.md`](docs/17-configuration.md) | Every env var, default, and config surface |
| [`docs/18-glossary.md`](docs/18-glossary.md) | Ubiquitous language |
| [`docs/adr/`](docs/adr/) | 10 architecture decision records |
| [`PROJECT_PLAN.md`](PROJECT_PLAN.md) | Delivery plan, scope control, risk register, DoD |
| [`SPRINT_PLAN.md`](SPRINT_PLAN.md) | 72-hour execution plan + 6-sprint product roadmap |

---

## 14. Repository layout

```
yathra-segment-booking/
├── README.md
├── PROJECT_PLAN.md
├── SPRINT_PLAN.md
├── docker-compose.yml              # profiles: core | full | observability
├── .env.example                    # placeholders only — never real secrets
├── docs/                           # see index above
│   └── adr/                        # ADR-001 … ADR-010
├── services/
│   ├── booking-service/            # ★ owns the segment invariant
│   ├── catalog-service/
│   ├── pricing-service/
│   ├── waitlist-service/
│   ├── admin-reporting-service/
│   ├── payment-mock-service/
│   └── gateway/
├── web/
│   ├── passenger-app/
│   └── admin-app/
├── db/migrations/                  # Flyway, per service schema
├── scripts/                        # demo-segment-resale.sh, concurrency-proof.sh, seed
├── load/                           # k6 scenarios
└── .github/workflows/              # build, test, gitleaks, image publish
```

---

## Commit history

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) and are deliberately small and
sequential so the progression is legible: schema → invariant → proof → API → UI → extras → docs. There is
no single "initial commit" dump. `git log --oneline --graph` tells the story of how the design evolved,
including the model rewrite described in §10.1.

## Licence

MIT. Seed distances, station lists and fare figures are **illustrative** and must be validated against
official Sri Lanka Railways tariff and timetable data before any real deployment.
