# Yathra — Segment-Based Rail Seat Booking

> A booking system for the **Colombo Fort ⇄ Badulla** up-country line, in which a single reserved seat
> can be sold independently for multiple non-overlapping legs of the same journey.

[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)]()
[![React](https://img.shields.io/badge/React-18-61dafb)]()

---

## Contents

1. [The problem](#1-the-problem)
2. [Quick start](#2-quick-start)
3. [Core design decisions](#3-core-design-decisions)
4. [Alternatives considered and rejected](#4-alternatives-considered-and-rejected)
5. [Architecture](#5-architecture)
6. [Fares](#6-fares)
7. [Proving correctness](#7-proving-correctness)
8. [Configuration — nothing is hardcoded](#8-configuration--nothing-is-hardcoded)
9. [Challenges, and five things the docs got wrong](#9-challenges-and-five-things-the-docs-got-wrong)
10. [Extra credit](#10-extra-credit)
11. [What is and is not built](#11-what-is-and-is-not-built)
12. [Documentation index](#12-documentation-index)

---

## 1. The problem

Sri Lanka Railways sells a reserved seat on the Badulla line as **one indivisible unit for the entire
route**. A passenger travelling Colombo Fort → Kandy occupies that seat for 121 km of a 292 km journey,
and the remaining 171 km is dead inventory. The department compensates by pricing the reserved partial
leg at roughly **double** the unreserved fare — so the short-leg passenger pays for the empty seat
behind them. Meanwhile the five unreserved coaches are packed, partly *because* that pricing pushes
short-leg passengers into them.

The pathology is a mismatch between the **unit of sale** and the **unit of consumption**. Strip the
trains away and it is a well-known computer science problem:

> Given a resource (a seat) and a totally ordered set of points (the stops), sell **half-open intervals**
> over those points such that no two sold intervals on the same resource overlap — correctly, under
> concurrency, across horizontally scaled replicas, with money attached.

That is interval packing, and it is the same shape as hotel rooms (nights), meeting rooms (times) and
car rental (periods). Recognising it matters, because the hotel case tells you two things immediately:
adjacency must not count as overlap (a guest checking out on the 5th does not block a guest checking in
on the 5th), and PostgreSQL already ships a correct primitive for exactly this.

---

## 2. Quick start

**Prerequisites:** Docker 24+ and Docker Compose v2. Nothing else — no JDK, no Node, no local Postgres.

```bash
git clone https://github.com/LasaKaru/LSF.git
cd LSF
cp .env.example .env          # placeholders only; .env is gitignored
docker compose up --build
```

| Surface | URL |
|---|---|
| Passenger web app | http://localhost:3000 |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |

Seed data — 25 up-country stations with Sinhala and Tamil names, 2 trains, an 8-coach consist
(3 reserved + 5 unreserved), 3 seat layouts, a full fare rule set, and 30 days of published trips — is
loaded by each service's own Flyway migrations and is idempotent. `docker compose up` twice is safe.

### Verify the headline behaviour in 30 seconds

```bash
./scripts/demo-segment-resale.sh
```

```
  Colombo Fort -> Kandy      201     LKR 470   seat 1B [1,9)    OK
  Kandy -> Badulla           201     LKR 680   seat 1B [9,25)   OK     <- same seat, sold twice
  Colombo Fort -> Badulla    409  SEAT_SEGMENT_UNAVAILABLE      OK
  Gampaha -> Nanu Oya        409  SEAT_SEGMENT_UNAVAILABLE      OK     <- straddles Kandy

  One seat, two passengers:      LKR 1,150
  Same seat sold whole-journey:  LKR 1,040
  Resale uplift on this seat:    +11%
```

### Prove the invariant under concurrency

```bash
./scripts/concurrency-proof.sh          # live, against the running stack
cd services && mvn verify               # the rigorous suite (Testcontainers)
```

```
1. Negative: 50 threads, one seat, all OVERLAPPING   -> exactly 1 x 201
2. Positive: 2 threads, one seat, ADJACENT legs      -> 2 x 201
3. Invariant check: 0 overlapping active segments
```

Both halves matter. **A system that passes only the first has reimplemented whole-seat locking** and
defeated the entire point of the project.

---

## 3. Core design decisions

### 3.1 A booking is a half-open interval `[from_seq, to_seq)` over the trip's stop sequence

Every stop on a trip has a dense, monotonically increasing integer `stop_sequence`. A booking segment
stores `from_seq` and `to_seq` and means the **half-open** range `[from_seq, to_seq)`.

Two segments conflict **iff** `a.from < b.to AND b.from < a.to`. That single line of arithmetic is the
entire business rule, and half-open is why adjacency works:

| Booking A | Booking B | Overlap? | Meaning |
|---|---|---|---|
| `[1,9)` Fort→Kandy | `[9,25)` Kandy→Badulla | **No** | ✅ The brief's headline scenario |
| `[1,9)` | `[5,15)` | Yes | ❌ B boards before A alights |
| `[1,25)` | `[9,11)` | Yes | ❌ A whole-journey sale blocks everything |
| `[9,9)` | — | — | ❌ Rejected by `CHECK (from_seq < to_seq)` |

**Why sequence integers rather than station IDs, timestamps or kilometres?**

- *Station IDs* have no ordering — every conflict check becomes a join and a comparison function.
- *Timestamps* are the most tempting and the most wrong. They drift with delays, they invert across
  midnight, and a timetable edit retroactively changes what inventory was sold. Occupancy is a
  **topological** fact, not a temporal one.
- *Kilometre markers* are floats. Float ranges as a uniqueness key is a mistake you make once.
- Integers are ordered, exact, indexable and free to compare.

**Direction needs no flag.** An up service and a down service are two different trips, each with its own
ascending stop sequence. There is no `if (direction == DOWN) swap(from, to)` anywhere in the codebase —
searching `BDL → CMB` simply matches the DOWN trips because only they serve those stations in that
order. This is verifiable in the running system.

### 3.2 The invariant is enforced by PostgreSQL, not by application code

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED

ALTER TABLE booking_segment
  ADD CONSTRAINT no_overlapping_active_segments
  EXCLUDE USING gist (trip_id WITH =, seat_id WITH =, leg WITH &&)
  WHERE (status IN ('HELD', 'CONFIRMED'));
```

Read it aloud: *"no two rows may exist where the trip is the same and the seat is the same and the legs
overlap, among rows whose status is HELD or CONFIRMED."* That sentence **is** the business rule.

This is the most important decision in the project:

1. **It is an invariant, not a check.** An application-level `if (overlaps) throw` is bypassable by a
   second replica, a background job, a data migration, a support script, or anyone with `psql`. This is
   not.
2. **It gives predicate locking for free.** Inserting a range that overlaps an *uncommitted* range blocks
   on that transaction rather than failing; when the first commits the second gets `23P01`; when it rolls
   back the second succeeds. You cannot take a row lock on a row that does not exist yet, which is
   precisely why check-then-act loses this race.
3. **The lock granularity is exactly right.** Locking the *seat* would block the disjoint sales this
   project exists to enable. Locking the *trip* would serialise the whole train. Range overlap contends
   only where a genuine conflict exists.
4. **It scales horizontally.** Anything living in one JVM's memory is wrong on replica #2. The database
   is the only shared serialisation point that already exists.

I spiked this in raw SQL **before writing a line of Java**, because if the primitive had not behaved as
expected I needed to know in hour two rather than on day three. Measured: an overlapping insert blocked
2.37 s on the uncommitted transaction then failed `23P01`; the adjacent insert completed in 0.087 s with
zero contention.

### 3.3 Booking and seat inventory are one service and one database

The reflex in a microservices shop is to split `inventory-service` from `booking-service`. I deliberately
did not, and this is the decision I most expect to be challenged on:

> **A hard transactional invariant defines a service boundary. You do not draw a boundary through the
> middle of one.**

Split them and "no two overlapping segments on one seat" becomes a *distributed* invariant, enforceable
only by two-phase commit (operationally miserable) or a saga with compensations. A saga means eventual
consistency, which means a window in which the same seat-segment is sold twice, which means resolving it
by **cancelling a real passenger's confirmed ticket**. An airline prices that in as denied boarding. A
state railway selling a LKR 1,200 seat to someone already standing on the platform cannot.

Everything that genuinely tolerates eventual consistency is separable, and the route catalog and pricing
*are* separate services. Two things that the design doc lists on that side of the line are not, and the
reason is worth stating: **reporting** reads the booking tables directly rather than a projection, because a
read replica is a deployment decision and not an architectural one; and the **waitlist** stayed in-process
because its matcher creates a real `HELD` booking, and putting a network call between that insert and the
exclusion constraint that makes it safe would buy a deployment boundary at the cost of the guarantee. It
consumes `SegmentReleased` through the outbox exactly as a remote consumer would, so the seam is real even
though the process boundary is not.

**The honest counter-argument**, which I would raise myself: booking-service is now the largest component,
and if the department adds cargo, parcels and dining it will need splitting along *those* lines. The
internal packaging is already modular by aggregate and the event contracts are public, so that extraction
is mechanical — but I would not claim the current shape is permanent.

### 3.4 Two-phase booking: `HELD` with a TTL, then `CONFIRMED`

A passenger takes 2–8 minutes between choosing a seat and paying. Reserving nothing until payment
succeeds sells the same seat twice; holding a database lock during payment means a gateway timeout
exhausts the connection pool. So a booking is created immediately as `HELD` with
`expires_at = now() + TTL`, and the exclusion constraint covers `HELD` as well as `CONFIRMED` — the hold
genuinely reserves inventory without holding a lock.

### 3.5 Trips snapshot their topology at publication

A trip does **not** point at the live route tables. When published, the stop list, distances, consist and
seat inventory are *copied* into `trip_stop` / `trip_coach` / `trip_seat`. If the department inserts a new
halt next March, every existing booking's `from_seq`/`to_seq` would otherwise silently shift by one and
thousands of tickets would quietly refer to the wrong stations. Nothing would throw. Nobody would notice
until a passenger and a conductor disagreed on a platform.

### 3.6 Fares are server-computed and returned as a signed, expiring quote

The client never computes or submits a price. It requests a quote; pricing-service returns an itemised
breakdown plus an HMAC-SHA256 signature with a 10-minute TTL, bound to the exact
`(trip, fromSeq, toSeq, class, coach, passengers)`. booking-service verifies it with a **local** signature
check — never an HTTP call inside a database transaction, so pricing latency can never hold a booking
transaction open. The canonical payload lives in a shared module so the signer and verifier cannot drift.

### 3.7 Every mutating endpoint is idempotent

`POST /bookings` requires an `Idempotency-Key`. The key plus a SHA-256 of the body is stored **in the same
transaction as the booking**, so there is no instant where the booking exists but a retry would create a
second one. This is not gold-plating: the target user is on a congested mobile network, double-taps
"Confirm", and the gateway retries on 5xx.

---

## 4. Alternatives considered and rejected

Design is mostly a record of what you decided *not* to do. These were real candidates.

**Materialised seat × segment matrix.** Pre-create one row per `(trip, seat, segment)`; booking is an
`UPDATE ... WHERE booked = false` and you check the affected row count. Rejected as a *write* model: one
booking writes up to 24 rows instead of 1, the row count explodes with route length × booking horizon, a
route change invalidates the entire set, and correctness still depends on the app checking `rowCount` —
which is the application-enforced invariant I was trying to escape. It is a *good read model*, and it is
where I would go if availability read volume ever justified it.

**Bitmask occupancy** (one bit per segment in a `BIGINT`). Caps out at 64 stops — the seed uses 25, which
is exactly the trap, because the brief explicitly says the route may be extended. Worse, it locks the whole
**seat row** for any leg, so two people booking genuinely disjoint legs serialise against each other. That
is precisely the anti-pattern this project exists to remove.

**Redis / Redlock.** Makes correctness depend on a second system's availability and clock. Redlock's safety
under partition is famously contested, and a GC pause longer than the lock TTL produces two lock holders.
It is also redundant: the database is already a strongly consistent shared resource every replica talks to.

**`SERIALIZABLE` everywhere.** Correct, and genuinely tempting. Rejected because it taxes every transaction
for a guarantee only the booking write path needs, and because failures surface as `40001 could not
serialize` — no attribution, so the app cannot tell the user *which* seat conflicted or offer an
alternative. The exclusion constraint fails with a precise `23P01` at `READ COMMITTED`.

**Optimistic locking with a `version` column on the seat.** Same fatal flaw as the bitmask: the conflict
domain is the seat, so two non-overlapping legs produce a false conflict, and under load the retry loop
turns disjoint bookings into livelock. The conflict domain must be the *interval*.

**Timestamp-based occupancy** (*"the seat is busy 06:15–09:40"*). The most common first instinct and the
most quietly broken — see §3.1.

**An ORM for the write path.** JPA was the default choice and the plan assumed it. Rejected because the
entire booking write path is one carefully shaped `INSERT` whose *failure mode* is the design: it must
reach PostgreSQL as written, fail with `23P01`, and carry back enough detail to build a recoverable 409.
An ORM puts generated SQL and flush-timing between me and that, for a mapping problem I do not have —
there are perhaps eight tables and no object graph worth managing. `NamedParameterJdbcTemplate` keeps the
SQL legible in the file where it matters. The cost is real and worth naming: no dirty-checking, no lazy
loading, and every column mapped by hand. Recorded as **ADR-002**.

**A separate `waitlist-service`.** The design documents put the waitlist in its own deployable, and it
consumes events, so the seam is genuine. Rejected because the matcher's promotion *is* a real `HELD`
booking: it must go through the same exclusion constraint as every other booking, or the waitlist becomes
a way to double-sell a seat. Splitting it puts a network call between that insert and the constraint that
makes it safe — buying a process boundary at the cost of the guarantee. It consumes `SegmentReleased`
through the outbox exactly as a remote consumer would, so the split remains available later as a
deployment change.

**A greedy, utilisation-maximising waitlist matcher.** When a stretch frees up, scan the queue for the
entry that best *fills* it rather than the oldest that fits — strictly better seat-km, which is the metric
this whole project is arguing for. Rejected anyway. A passenger who watches three later arrivals promoted
ahead of them has been treated unfairly by any ordinary standard, and *"our optimiser preferred their
journey shape"* is not an answer a state operator can give. This is a values decision wearing an
algorithm's clothing, so it is stated rather than buried; the utilisation left on the table is the price.

**`disabled` on unavailable seats.** The obvious way to stop someone booking a taken seat, and it was the
original implementation. Rejected once the seat map claimed `role="grid"`: a `disabled` button cannot be
focused, so keyboard and screen-reader users could not reach a taken seat *at all* — and with segment
inventory the interesting information is precisely *why* it is unavailable, since an amber seat is free
for part of your journey. Now `aria-disabled`, focusable, announcing its occupancy range.

---

## 5. Architecture

```
                    ┌──────────────────────────────────┐
                    │   React SPA (passenger)          │
                    └────────────────┬─────────────────┘
                                     │ same-origin JSON
                    ┌────────────────▼─────────────────┐
                    │  nginx gateway  :8080 / :3000    │
                    │  routing · rate limits · CORS    │
                    └───┬───────────┬──────────┬───────┘
                        │           │          │
              ┌─────────▼──┐  ┌─────▼────┐  ┌──▼───────┐
              │  booking   │  │ catalog  │  │ pricing  │
              │  ★ OWNS    │  │ stations │  │ quotes   │
              │  THE       │  │ routes   │  │ fare     │
              │  INVARIANT │  │ trips    │  │ rules    │
              └─────┬──────┘  └────┬─────┘  └────┬─────┘
                    │              │             │
              ┌─────▼──────┐ ┌─────▼─────┐ ┌─────▼─────┐
              │ booking_db │ │catalog_db │ │pricing_db │
              │ ★ GiST     │ │           │ │           │
              │   EXCLUDE  │ │           │ │           │
              └────────────┘ └───────────┘ └───────────┘
```

**Database per service, strictly.** No service reads another's tables. catalog publishes trips by POSTing
a snapshot to booking-service; pricing reads trip topology over the API. They share one Postgres *instance*
because running three for a demo would be theatre — each service only ever sees its own JDBC URL, so
splitting them in production is a config change.

**Trip publication is self-healing.** catalog-service publishes the horizon on startup and retries with
backoff until booking-service is reachable, so a cold start needs no orchestration. Publication is
idempotent at two independent levels: catalog records what it published, and booking-service returns
`ALREADY_PUBLISHED` for a trip it already holds. Either alone would suffice; both means a restart can never
*rebuild* a trip whose seats already carry sold bookings.

The gateway is nginx rather than another JVM. It serves the SPA and the API from **one origin**, so the
browser makes same-origin requests and CORS never enters the picture — fewer moving parts and one less
thing to misconfigure. In production this role is WSO2 API Manager; the OpenAPI specs import directly and
nothing in the services depends on which gateway sits in front of them.

---

## 6. Fares

The brief's central grievance is a *pricing* grievance, and it is an artefact of non-resellable inventory.
Once a seat resells, the surcharge has no economic justification, so the fare becomes what it should always
have been: **a function of distance travelled**.

```
fare = round( max( MINIMUM,  telescopic_base(km)
                             × class × coach × demand × (1 − advance_discount)
                           + scenic_surcharge(overlapping km only) ) )
```

Note what is **absent**: there is no reserved-coach surcharge. Reserved is `×1.00`, unreserved `×0.80`.

- **Telescopic banding** — marginal rate per km *falls* with distance (0–50 km @ 4.30, 50–150 @ 3.655,
  150+ @ 3.010), computed like tax brackets. Mirrors real tariff practice and protects long-haul fares.
- **Scenic premium** — the Nanu Oya → Ella stretch carries a 15% uplift, charged **additively and only on
  the scenic kilometres actually travelled**, so a Fort→Kandy passenger pays nothing for a view they never
  see. Configured by station *codes* and resolved per trip, so it finds the same physical stretch on up and
  down services with no direction branch.
- **Demand multiplier** — bounded `[0.90, 1.40]`, driven by *segment* occupancy (a capability that could not
  exist without segment inventory). **Off by default**: silently varying public transport fares by demand,
  without the department having chosen that, would be inappropriate regardless of how well it works.

### The numbers

| | Today | With segment booking |
|---|---|---|
| Fort → Kandy, reserved | ~LKR 760 (≈2× unreserved) | **LKR 470** (−38%) |
| Ratio to unreserved, same leg | ~2.0× | **1.24×** |
| Fort → Badulla, reserved | LKR 1,040 | LKR 1,040 (unchanged) |
| **One seat carrying only a Fort→Kandy passenger** | **LKR 760**, then 171 km dead | **LKR 1,150 (+51%)** |

Read the last row twice: **the department earns more while the short-leg passenger pays less.** That is not
a pricing trick — it is what happens when you stop destroying 171 seat-km at every departure.

Against a seat that *would* have sold whole-journey anyway, resale adds ~11% (measured by the demo script:
1,150 vs 1,040). Against a seat that today carries only a short leg, +51%. Against one that goes unsold,
100% of whatever it earns. **The size of the prize therefore depends on today's partial-sale and empty-seat
rates — numbers the department does not currently measure**, which is why the admin reporting is not a
nice-to-have.

---

## 7. Proving correctness

| Layer | What it proves | Result |
|---|---|---|
| **Unit** (`LegTest`) | The full overlap truth table, plus an exhaustive brute-force cross-check of the arithmetic predicate against set intersection over **all 80,000+ leg pairs** on the 25-stop route | 19 pass |
| **Integration** (`SegmentExclusionConstraintIT`) | Real PostgreSQL, real migrations: adjacency accepted, overlap rejected `23P01`, cancelled segments release, status propagation, constraint *definition* asserted so a future migration that drops it fails the build | 9 pass |
| **Race** (`ConcurrentBookingIT`) | 50 threads on a `CyclicBarrier` → exactly 1 × 201, 49 × 409; 2 threads on adjacent legs → 2 × 201; idempotent replay; concurrent duplicate submits | 7 pass |
| **Race** (`MultiSeatDeadlockIT`) | 16 concurrent group bookings for the same four seats, half submitting them in reverse order → no lock cycle. Asserts on `pg_stat_database.deadlocks`, **not** on status codes, because the retry hides this bug from the API entirely: 0 deadlocks with sorted acquisition, 47 without, and no caller sees an error either way (§9.9) | 1 pass |
| **Race** (`WaitlistIT`) | Promotion on release, FIFO order, containment vs seatability, a lapsed offer keeping its queue position, at-least-once redelivery not promoting twice | 7 pass |
| **API** (`ApiErrorMappingIT`) | An unmatched URL is 404 and a wrong verb is 405 — neither is a 500 with a stack trace (§9.5) | 2 pass |
| **Fare** (`FareCalculatorTest`) | Published worked example reproduced exactly, band boundaries, subadditivity and monotonicity over ~42,000 split points | 16 pass |

**61 tests, green in CI.** Every race test asserts on **database state** as well as HTTP status codes,
because a system can return an entirely plausible set of responses and still have written garbage — and
in the deadlock case the status codes are actively misleading.

Two gaps in the concurrency suite, stated rather than left to be discovered: there is no dedicated test
for the sweeper expiring a hold at the exact instant a booking takes that seat, nor for confirm and
cancel arriving simultaneously (`docs/10 §4.4`). Neither can violate INV-1 — the exclusion constraint
protects the seat regardless — so they are risks to a booking's own state, not to double-selling.

The suite runs against a real PostgreSQL 16 — **never H2**. The whole correctness argument rests on a GiST
exclusion constraint that only PostgreSQL has; testing against H2 would exercise a different system and
pass for the wrong reason.

---

## 8. Configuration — nothing is hardcoded

The brief asks for coaches, seats-per-coach and stations to be configurable. They are **data**, not
constants:

- **Stations & distances** — rows in `station` / `route_stop`. Extending the route to Passara is an INSERT
  plus a re-publish; existing bookings are untouched because each trip snapshots its own stop list.
- **Consist** — a `train_composition` row per coach. "3 reserved + 5 unreserved" is data. A `CHECK`
  constraint (`reservable_needs_layout`) makes it impossible to create a bookable coach without telling the
  system what its seats look like — the configurability is *guarded*, not merely permitted.
- **Seat layouts** — one row with a JSON grid (rows, columns, aisle position, seat labels, window columns,
  blanks). The backend materialises seats from it and **the React seat map renders directly from it**, so a
  new coach type needs zero code change on either side.
- Hold TTL, sweep interval, cutoff, horizon, fare bands, demand bounds, rate limits — environment variables
  with documented defaults (`docs/17`).

Each service **validates its configuration at startup and refuses to boot** rather than degrading. This is
not theoretical: it fired during development when a test set the sweep interval above its documented
maximum.

**Secrets** are never committed. `.gitignore` covering `.env`, `*.pem`, `*.key` was the *first commit*,
before any env file could exist. `.env.example` contains placeholders only, `docker-compose.yml` contains
no literal credential, and gitleaks runs in CI over the **full history**, not just the diff.

---

## 9. Challenges, and five things the docs got wrong

The design documents in `docs/` were written before the code. Building it proved five of their claims
wrong — four technical, and one about the documents themselves: they described tests and measurements
that had never been run (§9.8). I have kept the original documents and corrected them in place, marking
what was planned against what was built, rather than quietly editing history.

### 9.1 `FARE-1` is false under rounding

`docs/05` §3.1 asserts that splitting a ticket can never be cheaper than buying it whole, and that this
"holds automatically for any concave, monotonically increasing fare function". **It does not survive
rounding.**

Below 50 km the fare sits entirely in band 1, where the rate is constant and the base fare is therefore
*exactly linear*. Concavity contributes **zero** headroom, so rounding is the only term left — and rounding
the whole journey up while rounding both halves down costs one increment. Concretely: two 8 km legs are
LKR 30 each (LKR 60); one 16 km leg is LKR 70.

The property now holds *exactly* on unrounded fares and *to within one rounding increment* on rounded ones,
with the worst observed case pinned by a test. I did not "fix" it, and that is deliberate: the leak is
bounded at LKR 10 per split, cannot compound (concavity and the minimum fare dominate long before a
splitting strategy pays), and **no rounding mode removes it** — the granularity itself is the cause.
Distorting a published public tariff to close a LKR 10 gap would be the wrong trade for an operator whose
fares must be explainable.

### 9.2 Monthly partitioning is incompatible with the invariant

`docs/07` §6 says `booking_segment` is "declared with monthly range partitioning on `created_at` from day
one" because retro-fitting partitioning is painful. PostgreSQL disagrees:

```
ERROR: exclusion constraints are not supported on partitioned tables
```

Worse than the error: even creating the constraint per-partition would be **silently wrong**. Two segments
for the same `(trip, seat)` booked in different calendar months would land in different partitions, so a
per-partition constraint would never compare them — a seat could be double-sold with every constraint
reporting healthy. `booking_segment` is therefore **not partitioned**, and it must not be until the
partition key is changed to hash-on-`trip_id` (which keeps a trip's segments together). The scale analysis
says a single table has years of headroom, so this costs nothing today.

### 9.3 An inverted leg fails one layer earlier than expected

`[9,1)` is rejected by `int4range()` itself while evaluating the generated column — SQLSTATE `22000`
`data_exception` — before the `CHECK (from_seq < to_seq)` constraint (`23514`) can fire. Both reject it,
which is the property that matters, but anything mapping database errors to HTTP responses needs to know
they surface differently.

### 9.4 The published worked example rounds intermediates

`docs/05` §5.1 shows `71 × 3.655 = 259.51` and sums the displayed values to `474.51`. The exact product is
`259.505` and the exact base is `474.505`. The engine keeps full precision and rounds once at the end —
which is what `docs/05` §2 itself mandates. Final fares are identical (LKR 470); the table is a display
artefact.

### 9.5 Two real bugs the concurrency tests caught

**Nullable bind parameters.** `(:classCode IS NULL OR ...)` gives PostgreSQL no type to infer when the
value is null. The conflict path passes a null class, so it died with a grammar error and surfaced as a
**500 where a 409 belonged** — the single worst place to return an opaque error, since it is exactly when
the UI needs structured recovery data.

**Duplicate submits reported as conflicts.** Two identical requests sharing an idempotency key raced; the
loser blocked on the winner's index entry and failed `23P01` on the *segment* insert, which happens
*before* the idempotency record is written — so the unique-violation path never ran and the loser was told
"that seat is taken" about a booking that was its own. The error path now re-checks the idempotency store
first. **A duplicate submit is not a conflict.**

### 9.6 The resale-uplift counterfactual was inverted

Building the admin UI exposed a modelling error in the single number the feature exists to produce.

The counterfactual was *"every occupied seat sold at the full through fare"*. That credits the old regime
with selling a 292 km ticket to a passenger travelling 29 km to Gampaha — someone who, under any regime,
would have gone unreserved rather than buy the whole line. On seeded data it inflated the baseline to
LKR 4,160 against LKR 3,150 actual and reported resale **losing 24%**: the exact opposite of the truth.

The counterfactual is now *"each seat sold **once**, to whoever booked it first"* — the defining
constraint of whole-journey-only ticketing. It requires no assumption about historic pricing at all, so it
isolates what resale earned. The same data now reads a LKR 2,100 baseline and **+50.0%**, consistent with
the +51% docs/05 §5.3 predicts for a seat carrying only a short leg.

The lesson generalises: a counterfactual is a *model*, and a model that flatters or damns the thing it
measures is worse than no number at all. This one would have been quoted at leadership.

### 9.7 Expired holds versus an immutable constraint predicate

The messiest part of the design, and I would not pretend otherwise. An exclusion constraint's predicate must
be immutable, so it cannot reference `now()` and cannot distinguish a live hold from a dead one — an
abandoned checkout keeps blocking a legitimate booking. Dropping `HELD` from the predicate reopens
double-selling during payment; sweeping every second is wasteful and still leaves a window.

The answer is layered: a **ShedLock-guarded sweeper** every 15 s (batched, `FOR UPDATE SKIP LOCKED`, so a
backlog cannot become one giant transaction), *plus* **surgical in-transaction expiry** of the specific
conflicting holds on the booking path — race-free because the expiry and the insert share a transaction.
Worst case is a seat looking taken for a few seconds after a hold dies. It self-heals, and it errs toward
"looks taken" rather than "looks free", which is the safe direction.

### 9.8 Documents that described work which had not happened

The one I would most want a reviewer to know I found myself.

`docs/14 §4` narrated a deadlock discovered by a k6 load test — *"intermittent 500s at ~40 bookings/sec,
at a rate low enough (≈0.3%) that a lighter load test would have missed it"*. `docs/10 §6` listed
`MultiSeatDeadlockIT` and stated it *"exists because the load test found that bug. It is now
regression-protected."* `docs/04` tabulated seven test classes as though all seven were written.

None of it was true. There is no k6 script in this repository, no load test was ever run, `MultiSeatDeadlockIT`
did not exist, and three of those seven class names were never used. The design documents were written
before the implementation and describe intent in the past tense; the fix in the code was real, but the
*provenance* and the *figures* were invented.

This is worse than an ordinary documentation drift, because the fabricated detail is the persuasive part.
A reviewer who greps for `MultiSeatDeadlockIT`, finds nothing, and then rereads the 2.37 s constraint
measurement in §3.2 has no way to tell which numbers in this repository were measured and which were
imagined. One unbacked claim devalues every honest one.

**What I did about it.** Wrote the test for real, corrected `docs/04`, `docs/10` and `docs/14` to
distinguish built from planned, and marked the k6 scenario explicitly as a specification rather than a
result. `docs/04`'s test table now carries an **As built** column, because the useful thing about a plan
is where it diverged.

### 9.9 A retry layer that hid the bug it was catching

Writing that test properly turned out to be the interesting part. The obvious assertion — *"no caller saw
a 500"* — **passed with the fix removed.** The bounded retry catches `40P01` and the next attempt
succeeds, so every caller gets a clean 201 or 409 either way. A regression test that passes without the
code it guards is worth nothing.

The bug is real, though. Asking PostgreSQL directly, with `pg_stat_reset()` first:

| Seat acquisition | Deadlocks | Callers seeing 500 | Wall clock |
|---|---|---|---|
| Sorted (as shipped) | **0** | 0 | ~12 s |
| Unsorted | **47** | 0 | ~51 s |

So the assertion moved to `pg_stat_database.deadlocks`. **A retry is a correctness win and an
observability hazard at once** — it converted a hard failure into a silent 4× latency cost that no
test written against the API surface could see. Worth keeping; worth knowing about.

One more honesty note, in the test's own comments: detection is reliable in only one direction. With the
fix present the lock cycle cannot form, so the test never fails spuriously. With the fix absent, whether a
cycle *forms* depends on interleaving — one observed run of the unsorted code produced zero deadlocks and
passed. It catches a regression usually, not certainly, and says so.

### 9.10 The test harness fighting itself, twice

Both of these cost real time and both looked like product bugs.

**ShedLock's in-JVM lock registry.** Integration tests call `relay.drain()` directly, so ShedLock
intercepts them exactly as it would a scheduled firing — and a lock it declines to grant makes the call a
silent no-op, no exception, no log line. I "fixed" that by truncating the `shedlock` table between tests,
which made it permanently worse: `AbstractStorageBasedLockProvider` keeps an in-memory set of lock names
it has already inserted and thereafter issues only `UPDATE`s, so truncating behind its back leaves every
future acquisition matching zero rows. The symptom reads as *"the waitlist matcher does not match"*.

**Background timers versus `TRUNCATE`.** Removing ShedLock from the tests then exposed something it had
been accidentally suppressing: the waitlist offer sweeper deadlocking against a test's `TRUNCATE` in CI —
the sweeper takes `AccessShare` on `booking` through its subquery, `TRUNCATE` wants `AccessExclusive`.
`@EnableScheduling` was an annotation on the application class, so *every* test context started the
timers, and a `fixedDelay` task fires immediately at startup regardless of its interval — which is why
pushing the intervals out to an hour had done nothing. It now sits behind `yathra.scheduling.enabled`,
default on, off in tests.

The lesson both times: **when a test fails, ask what the harness is doing before assuming the product is
wrong.** I twice concluded the feature was broken when the feature was fine.

---

## 10. Extra credit

**Seat map with tri-state colouring.** A per-coach grid rendered from the layout JSON, coloured for the
*selected leg*: green available, grey taken, **amber "free for part of your leg"** — a state that cannot
exist in whole-journey booking and which a boolean availability API could not express. The seat-map endpoint
returns occupancy **ranges** per seat rather than booleans, which is what makes the amber state and the
hover detail possible in one round trip. It exposes ranges only: **no names, no references** — the UI could
not leak passenger data because the endpoint never had it. Verified live: a seat sold Fort→Kandy reports
`partiallyAvailable: true` against a Fort→Badulla search.

**One-click conflict recovery.** A `409` carries the conflicting seats, their occupied ranges, and
alternatives ranked by the passenger's window/aisle preference and coach. The banner offers a real seat and
resubmits with their details intact and a fresh idempotency key. Losing a race costs one click, not a
restart.

**Admin dashboard** (`/admin`). The brief says leadership *believes* revenue is being left on the table.
The headline metric is **seat-km utilisation**, deliberately shown next to conventional load factor —
because a train with every seat sold for a tenth of the route reports as 100% full under the conventional
metric, which is exactly what hid the problem. **The gap between those two numbers is the problem, made
numeric.** Plus an occupancy heatmap answering "where does the train empty out?", revenue by segment, and
**segments-per-seat** (if it is 1.0, seats are not reselling and the change delivered nothing).

**Resale uplift** compares actual revenue against *each seat sold once, to whoever booked it first* — the
defining constraint of whole-journey-only ticketing. That formulation needs no assumption about historic
pricing, so it isolates what resale earned rather than conflating it with the separate fare-policy change.
An earlier version compared against the full through fare and reported resale *losing* 24%; see §9.7.

**Segment waitlisting.** Join a queue for a leg that is sold out, and get offered the seat automatically
when one frees up. Four decisions carry it:

*It is event-driven, not polled.* Cancellations and hold expiries already emit `SegmentReleased` into the
outbox; the matcher is a consumer. Promotion happens within a second of the release rather than on the next
sweep, and the waitlist adds no periodic load to the booking tables.

*An offer is a real hold.* The matcher creates an ordinary `HELD` booking with a 30-minute TTL — longer than
a checkout hold, because the passenger is reacting to a notification rather than sitting at a form. It goes
through the same exclusion constraint as every other booking, so **no waitlist bug can produce a double
sale.** The worst this code can do is offer a seat to the wrong person or fail to offer it at all. That
containment is why the feature was safe to add late.

*Matching asks "can this person actually be seated?", not "does their leg fit in the released range?"* Those
differ, and the difference is the whole segment story. A passenger shortening Fort→Badulla to Fort→Kandy
cancels `[1,25)` and rebooks `[1,9)` — so the release event names the *whole* `[1,25)` even though only
`[9,25)` came free. A containment-only match would hand that release to someone waiting on `[1,15)`, whose
promotion then dies on the constraint, and the release is consumed with nobody seated. The query carries a
`NOT EXISTS` against live segments on that seat, so it picks the oldest entry that can actually be seated.
The constraint is still the backstop for genuine races; the check just removes the predictable losses.

*Strictly FIFO, at a measurable cost.* A greedy matcher would pick whichever waiting entry best *fills* the
freed stretch, maximising seat-km — and this one does not. A passenger who has watched three later arrivals
promoted ahead of them has been treated unfairly by any reasonable standard, and "our optimiser preferred
their journey shape" is not an answer a public operator can give. Likewise a lapsed offer returns the entry
to its **original** queue position: `created_at` is never rewritten, so missing one notification does not
cost your place. Both are values decisions disguised as algorithm choices, so they are stated rather than
buried.

*In the UI, "sold out" stops being a dead end.* The disabled button on a full train becomes **"Sold out ·
join waitlist"**, and the panel behind it is really three screens: join, your position in the queue, and
— when a seat frees — an offer with a live countdown, rendered from the server's expiry rather than a
locally counted duration so a sleeping laptop cannot show more time than there is. The copy says the seat is
*held*, not that one is available to go and race for, because that is the truth and a passenger who assumed
otherwise would lose it. The entry id is kept in `localStorage`: there is no login here, and losing your
place because you closed a tab is exactly the unfairness the FIFO rules exist to prevent.

Seven integration tests cover the backend, including at-least-once redelivery not promoting twice, and the
whole journey was driven end to end over HTTP and then again through a real browser — sell out a train,
join, cancel a booking, watch the offer arrive, confirm it, land on a ticket.

**Fare logic beyond distance.** Telescopic bands, scenic premium on kilometres actually travelled, bounded
and published demand tiers, advance-purchase discounts, signed quotes, and the subadditivity guard —
see §6 and §9.1.

**Fragmentation-minimising seat assignment.** When a passenger does not pick a seat, the default strategy is
best-fit: prefer the seat where this leg fits most *tightly* between already-sold stretches. Booking
Kandy→Badulla onto a seat already carrying Fort→Kandy consumes a stretch that was hard to sell anyway;
booking it onto an empty seat punches a 121 km hole. It is a heuristic and deliberately so — optimal
interval packing is NP-hard, and an offline optimum is worthless when bookings arrive online. Strategies are
pluggable by name (`SEAT_SELECTION_STRATEGY`).

**Trilingual data.** Station names are stored and served in English, Sinhala and Tamil. Translations live
with the data rather than in the frontend bundle, so adding a station translates it everywhere with no
deployment.

---

## 11. What is and is not built

Stated plainly so nothing reads as a claim it is not.

**Built and verified end to end:** the segment invariant and its proof; leg-scoped availability; seat map
with occupancy ranges and full keyboard navigation; hold → confirm with TTL, sweeper and in-transaction expiry; idempotency; the
telescopic fare engine with signed quotes; trip publication with snapshotting; the passenger web app;
admin reporting endpoints; the segment waitlist with event-driven promotion; nginx gateway;
docker-compose; CI.

**Designed and documented but not built** — described in `docs/` as part of the full topology, and absent
here so that the default startup stays honest and fast:

| Not built | Note |
|---|---|
| Kafka | The **transactional outbox is real** — events are written in the same transaction and are inspectable in `outbox_event`. The waitlist matcher consumes them through an in-process relay that presents the same contract a broker subscription would (event type, event id, JSON payload), so moving it onto Kafka is a deployment change rather than a rewrite. Only the transport is absent. |
| Waitlist as a **separate service** | The waitlist itself is built and tested (§10). It runs inside the booking service rather than as its own deployable, because splitting it would put the matcher's `HELD` insert on the far side of a network call from the constraint that makes it safe. |
| Waitlist **notifications** | Promotion publishes a `WaitlistPromoted` event carrying the contact address; nothing sends the email or SMS. The offer is still discoverable by polling the entry. |
| Payment gateway | Confirm is a state transition; no money moves. A half-integrated gateway would look like more work and be worth less than an honest seam. |
| SSE live availability | The seat map polls rather than streams. |
| Redis, Keycloak, observability stack | Config surface exists; the containers do not. |
| i18n UI switcher | The **data** is trilingual; the interface strings are English. |

**Known gap:** admin endpoints are **unauthenticated** in the compose profile. Gating them would mean
standing up an identity provider to look at a dashboard. In any real deployment they sit behind
`admin:read` — occupancy and revenue are commercially sensitive. This is the first thing to close before
production.

---

## 12. Documentation index

| Document | Covers |
|---|---|
| [`docs/01-problem-analysis.md`](docs/01-problem-analysis.md) | Domain research, requirement decomposition, assumptions, scale estimation |
| [`docs/02-architecture.md`](docs/02-architecture.md) | C4 views, service catalogue, sync vs async, communication patterns |
| [`docs/03-domain-model.md`](docs/03-domain-model.md) | Bounded contexts, aggregates, invariants, ubiquitous language, state machines |
| [`docs/04-segment-concurrency-design.md`](docs/04-segment-concurrency-design.md) | **The core doc.** Interval algebra, exclusion constraint, lock analysis, failure modes |
| [`docs/05-fare-engine.md`](docs/05-fare-engine.md) | Fare formula, telescopic bands, worked examples, revenue modelling |
| [`docs/06-api-contract.md`](docs/06-api-contract.md) | Every endpoint, error catalogue, idempotency, versioning |
| [`docs/07-data-model.md`](docs/07-data-model.md) | Full DDL, indexes, migration strategy, retention |
| [`docs/08-microservices-and-deployment.md`](docs/08-microservices-and-deployment.md) | Compose profiles, 12-factor, Kubernetes, CI/CD, WSO2 fit |
| [`docs/09-frontend-design.md`](docs/09-frontend-design.md) | Screens, seat map, conflict UX, accessibility, performance budgets |
| [`docs/10-testing-strategy.md`](docs/10-testing-strategy.md) | Test pyramid, the concurrency proof, coverage targets |
| [`docs/11-security-and-secrets.md`](docs/11-security-and-secrets.md) | Secrets, OWASP, domain threat model, PII |
| [`docs/12-observability-and-ops.md`](docs/12-observability-and-ops.md) | Logs/metrics/traces, SLOs, alerts, runbooks |
| [`docs/13-extra-credit.md`](docs/13-extra-credit.md) | Problem → solution → design write-ups |
| [`docs/14-challenges-and-tradeoffs.md`](docs/14-challenges-and-tradeoffs.md) | Long-form challenges, including rejected attempts |
| [`docs/15-future-work.md`](docs/15-future-work.md) | Roadmap, ranked by value to the department |
| [`docs/16-demo-and-walkthrough.md`](docs/16-demo-and-walkthrough.md) | Demo script, expected questions, live-extension rehearsals |
| [`docs/17-configuration.md`](docs/17-configuration.md) | Every environment variable and config surface |
| [`docs/18-glossary.md`](docs/18-glossary.md) | Ubiquitous language |
| [`PROJECT_PLAN.md`](PROJECT_PLAN.md) · [`SPRINT_PLAN.md`](SPRINT_PLAN.md) | Delivery plan, risk register, execution timeline |

### Where to start reading the code

1. `services/booking-service/src/main/resources/db/migration/V2__booking_and_segment_invariant.sql` — **the invariant**
2. `services/common/src/main/java/lk/yathra/common/domain/Leg.java` — the interval value object
3. `services/booking-service/.../booking/BookingTransaction.java` — the write path
4. `services/booking-service/.../availability/AvailabilityService.java` — the read path
5. `services/booking-service/src/test/.../ConcurrentBookingIT.java` — **the proof**
6. `web/passenger-app/src/components/SeatMap.tsx` — the tri-state UI
7. `services/booking-service/.../waitlist/WaitlistRepository.java` — matching on *seatability*, not containment

---

## Licence

MIT. Seed distances, station lists and fare figures are **illustrative** and must be validated against
official Sri Lanka Railways tariff and timetable data before any real deployment.
