# 04 — Segment Modelling & Concurrency Design

> **This is the core document of the project.** Everything else is scaffolding around the invariant
> defined here. If you read one file besides the README, read this one.

---

## 1. The invariant, stated precisely

> **INV-1.** For any given `(trip_id, seat_id)`, the set of *active* booking segments must be pairwise
> non-overlapping under half-open interval semantics.

Where:
- *active* ≡ `status ∈ {HELD, CONFIRMED}` (a `CANCELLED` or `EXPIRED` segment holds no inventory)
- a segment is the half-open integer range `[from_seq, to_seq)` over the trip's stop sequence
- *overlap* ≡ `a.from_seq < b.to_seq ∧ b.from_seq < a.to_seq`

Everything below is about making INV-1 true at all times, from all writers, forever.

---

## 2. The coordinate system

### 2.1 Stop sequence

Each trip has an ordered stop list. `trip_stop.stop_sequence` is a dense, monotonically increasing
integer starting at 1:

| seq | Station | Code | km from origin |
|---|---|---|---|
| 1 | Colombo Fort | CMB | 0 |
| 2 | Ragama | RGM | 13 |
| 3 | Gampaha | GMP | 29 |
| 4 | Veyangoda | VYG | 38 |
| 5 | Polgahawela | PGW | 74 |
| 6 | Rambukkana | RBK | 84 |
| 7 | Kadugannawa | KDG | 110 |
| 8 | Peradeniya Jn | PDN | 116 |
| 9 | Kandy | KDY | 121 |
| 10 | Gampola | GPL | 133 |
| 11 | Nawalapitiya | NWP | 147 |
| 12 | Hatton | HAT | 178 |
| 13 | Kotagala | KTG | 186 |
| 14 | Talawakele | TWL | 194 |
| 15 | Nanu Oya | NAN | 210 |
| 16 | Ambewela | AMB | 222 |
| 17 | Pattipola | PTP | 227 |
| 18 | Ohiya | OHY | 238 |
| 19 | Idalgashinna | IDG | 250 |
| 20 | Haputale | HPT | 258 |
| 21 | Diyatalawa | DTW | 265 |
| 22 | Bandarawela | BWL | 272 |
| 23 | Ella | ELA | 281 |
| 24 | Demodara | DMD | 288 |
| 25 | Badulla | BDL | 292 |

*(Illustrative seed data — see `docs/01` A9/A10.)*

A journey Fort → Kandy is `[1, 9)`. Kandy → Badulla is `[9, 25)`. Fort → Badulla is `[1, 25)`.

### 2.2 Why per-trip and not per-route

`stop_sequence` is stored on `trip_stop`, snapshotted at trip publication, **not** read live from
`route_stop`. Consequences:

- Inserting a new halt into the route next year does not shift the meaning of existing bookings.
- Up and down services are separate trips with independently ascending sequences — **no direction flag
  anywhere in the codebase**, and no `if (direction == DOWN) swap(from, to)` bug waiting to happen.
- Special workings (a service that skips Ohiya on Sundays) are just a trip with a different stop list.
- The booking service is self-contained: it never calls catalog-service on the hot path.

### 2.3 Why integers, not timestamps

| Model | Fatal problem |
|---|---|
| Timestamps (`occupied 06:15–09:40`) | Delays mutate occupancy; midnight crossing inverts ordering; timetable edits retroactively change inventory; "is this seat free between Kandy and Ella" becomes a clock question rather than a geography question |
| Cumulative km (`[121.0, 292.0)`) | Floating point as a uniqueness key; `numrange` works but km values change when track is re-surveyed, and equality on floats is a bug generator |
| Station IDs with a join | No native ordering; overlap becomes a correlated subquery; cannot be indexed as a range |
| **Dense integers** | **None. Ordered, indexable, exact, cheap.** |

---

## 3. Interval algebra

### 3.1 Half-open `[from, to)` — the single most important convention

```
Overlap(a, b)  ⟺  a.from < b.to  ∧  b.from < a.to
```

Complete truth table for the cases that matter:

| Case | A | B | Overlap | Business meaning |
|---|---|---|---|---|
| Adjacent forward | `[1,9)` | `[9,25)` | **false** | ✅ **The brief's headline scenario.** Fort→Kandy then Kandy→Badulla on one seat |
| Adjacent backward | `[9,25)` | `[1,9)` | **false** | ✅ Symmetric — order of insertion is irrelevant |
| Disjoint with gap | `[1,5)` | `[12,20)` | false | ✅ Seat empty Veyangoda→Hatton, sold twice |
| Partial overlap | `[1,9)` | `[5,15)` | true | ❌ B boards at Polgahawela while A is still seated |
| Containment | `[1,25)` | `[9,11)` | true | ❌ Whole-journey booking blocks everything |
| Reverse containment | `[9,11)` | `[1,25)` | true | ❌ Symmetric |
| Identical | `[1,9)` | `[1,9)` | true | ❌ Duplicate sale |
| Shared start | `[1,9)` | `[1,25)` | true | ❌ |
| Shared end | `[1,25)` | `[9,25)` | true | ❌ |
| Degenerate | `[9,9)` | any | — | ❌ Rejected by `CHECK (from_seq < to_seq)` |
| Inverted | `[9,1)` | any | — | ❌ Rejected by the same `CHECK` |

**If closed ranges `[1,9]` and `[9,25]` had been used, Kandy would belong to both bookings and the
central requirement of the brief would fail.** This is the boundary condition that a naive
implementation gets wrong, and it is why the convention is documented, tested, and lint-enforced.

### 3.2 Enforcing the convention

A convention that is only in a doc will be violated. Three enforcement points:

1. **DDL** — the generated column always uses `'[)'`; there is no other way to write the range.
2. **Domain type** — `Leg` is a value object with a private constructor and a factory that validates
   `from < to`. No raw `(int, int)` pairs cross a service boundary.
3. **Architecture test** (ArchUnit + a SQL grep test) — fails the build if any query string contains
   `'[]'`, `'(]'` or `'()'` range bounds, or if `from_seq`/`to_seq` are compared with `<=` in a
   conflict predicate.

### 3.3 If a turnaround buffer were ever needed

Hotels need cleaning time; trains do not. But if the department later wants "a seat cannot be resold
until one stop after a long-haul passenger alights", it is a single change at the range construction
site:

```sql
int4range(from_seq, to_seq + :buffer_stops, '[)')
```

Noted here so the extension point is obvious and nobody has to rediscover the model to implement it.

---

## 4. The concurrency problem

### 4.1 Why the naive approach fails

```java
// ❌ BROKEN — do not do this
if (bookingRepo.findOverlapping(tripId, seatId, from, to).isEmpty()) {
    bookingRepo.save(new BookingSegment(tripId, seatId, from, to));
}
```

Classic **check-then-act** across a transaction boundary:

```
 t   Thread A (CMB→KDY, [1,9))          Thread B (GMP→NAN, [3,15))
 ─── ─────────────────────────────────  ────────────────────────────────
 1   BEGIN
 2   SELECT overlapping → ∅
 3                                      BEGIN
 4                                      SELECT overlapping → ∅   ← A uncommitted, invisible
 5   INSERT [1,9)
 6                                      INSERT [3,15)
 7   COMMIT  ✅
 8                                      COMMIT  ✅
 ─── Both succeed. Seat double-sold between Gampaha and Kandy.
```

Under `READ COMMITTED` and `REPEATABLE READ` this is a **phantom read** — no row-level lock exists to
take, because the conflicting row *does not exist yet*. You cannot lock a row that isn't there. What you
need is a lock on the *predicate*, and that is exactly what a GiST exclusion constraint provides.

### 4.2 The chosen primitive

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE booking_segment (
    id           UUID PRIMARY KEY,
    booking_id   UUID NOT NULL REFERENCES booking(id),
    trip_id      UUID NOT NULL,
    seat_id      UUID NOT NULL,
    from_seq     INT  NOT NULL,
    to_seq       INT  NOT NULL,
    status       booking_status NOT NULL,
    fare_minor   BIGINT NOT NULL,
    distance_km  NUMERIC(7,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED,

    CONSTRAINT seq_ordered   CHECK (from_seq < to_seq),
    CONSTRAINT seq_positive  CHECK (from_seq >= 1)
);

ALTER TABLE booking_segment
  ADD CONSTRAINT no_overlapping_active_segments
  EXCLUDE USING gist (
      trip_id WITH =,
      seat_id WITH =,
      leg     WITH &&
  )
  WHERE (status IN ('HELD', 'CONFIRMED'));
```

Reading it aloud: *"no two rows may exist where the trip is the same **and** the seat is the same **and**
the legs overlap, among rows whose status is HELD or CONFIRMED."* That sentence is INV-1, verbatim, and
it is now a property of the database rather than a hope about the application.

`btree_gist` is required because `=` on `uuid` is a btree operator that GiST does not natively index;
the extension supplies GiST operator classes for scalar types so they can sit in the same index as the
range's `&&`.

### 4.3 What PostgreSQL actually does

The exclusion constraint is backed by a GiST index. On insert, PostgreSQL:

1. Inserts the index entry speculatively.
2. Scans the index for entries satisfying the exclusion predicate.
3. If a **committed** conflicting row exists → raise `23P01 exclusion_violation`.
4. If an **uncommitted** conflicting row exists → **block on that transaction's xid** until it commits or
   rolls back, then re-evaluate.

Step 4 is the magic. It gives us fine-grained, just-in-time predicate locking that only contends where a
real conflict exists:

```
 t   Thread A ([1,9))                   Thread B ([3,15))
 ─── ─────────────────────────────────  ────────────────────────────────
 1   BEGIN
 2   INSERT [1,9)  → index entry
 3                                      BEGIN
 4                                      INSERT [3,15) → conflict with
 5                                          uncommitted A → **BLOCKS**
 6   COMMIT ✅
 7                                      ⟶ unblocks → 23P01 ❌
 8                                      ROLLBACK → app returns 409
```

And the disjoint case, which is the whole point of the project:

```
 t   Thread A ([1,9))                   Thread C ([9,25))
 1   BEGIN                              BEGIN
 2   INSERT [1,9)                       INSERT [9,25)   ← no overlap, no block
 3   COMMIT ✅                          COMMIT ✅
 ─── Same seat. Two paying passengers. Zero contention.
```

### 4.4 Isolation level

`READ COMMITTED` is sufficient **and preferable**.

The exclusion constraint enforces the invariant at write time regardless of what the transaction's
snapshot could see. We do not need `SERIALIZABLE` because we are not relying on a *read* to make the
decision — we are relying on a *write* that the database refuses to accept. The consequences:

| | `READ COMMITTED` + EXCLUDE | `SERIALIZABLE` |
|---|---|---|
| Correctness | ✅ | ✅ |
| Error on conflict | `23P01` — tells us **which** constraint and lets us fetch the conflicting rows | `40001` — "could not serialize", no attribution |
| Cost to unrelated transactions | None | Predicate-lock tracking on every transaction |
| Retry strategy | Targeted (try another seat) | Blind retry of the whole transaction |
| Behaviour under contention | Blocks then fails precisely | Aborts, retries, can livelock |

We reserve `SERIALIZABLE` for the multi-seat group-booking path, where "all four seats or none" is a
statement about a *set* of rows and the extra guarantee is cheap because the path is rare.

---

## 5. The booking write path

### 5.1 Sequence

```
Client                Gateway         booking-service        pricing-service      Postgres
  │  POST /bookings      │                   │                      │                │
  │  Idempotency-Key ───►│                   │                      │                │
  │                      ├──────────────────►│                      │                │
  │                      │        1. idempotency lookup ────────────────────────────►│
  │                      │           (hit → replay stored response, done)            │
  │                      │                   │  2. verify quote     │                │
  │                      │                   ├─────────────────────►│                │
  │                      │                   │◄─ HMAC + TTL ok ─────┤                │
  │                      │                   │  3. BEGIN ──────────────────────────► │
  │                      │                   │  4. lazily expire conflicting holds   │
  │                      │                   │  5. INSERT booking (HELD)             │
  │                      │                   │  6. INSERT booking_segment(s)         │
  │                      │                   │       ▲ EXCLUDE fires here if overlap │
  │                      │                   │  7. INSERT outbox(SegmentHeld)        │
  │                      │                   │  8. INSERT idempotency record         │
  │                      │                   │  9. COMMIT ─────────────────────────► │
  │◄── 201 + hold TTL ───┤◄──────────────────┤                      │                │
```

Everything from step 3 to step 9 is **one transaction**. There is no network call inside it — the quote
verification is a local HMAC check against a shared secret, not an HTTP round trip, precisely so that no
external latency can hold a database transaction open.

### 5.2 Multi-seat bookings and deadlock avoidance

A family booking seats `3A, 3B, 3C, 3D` in one transaction can deadlock against another transaction
booking the same four seats in a different order:

```
T1 holds 3A, wants 3B    T2 holds 3B, wants 3A    → deadlock, 40P01
```

**Mitigation:** seat IDs are sorted before insertion, so every transaction acquires index entries in the
same total order. Deadlock becomes structurally impossible for the common case. A bounded retry
(3 attempts, jittered backoff) covers the residual risk from concurrent sweeper activity.

This bug was found by the load test, not by reasoning about the code — noted in `docs/14`.

### 5.3 Error translation

| Postgres | Meaning | HTTP | Body |
|---|---|---|---|
| `23P01` on `no_overlapping_active_segments` | Someone took an overlapping leg | `409` | `SEAT_SEGMENT_UNAVAILABLE` + conflicting seats + refreshed availability |
| `23514` on `seq_ordered` | Invalid leg (from ≥ to) | `422` | `INVALID_JOURNEY_LEG` |
| `40P01` deadlock | Concurrent multi-seat | retry ×3, then `409` | `BOOKING_CONTENTION` |
| `40001` serialization | Group path only | retry ×3, then `409` | `BOOKING_CONTENTION` |
| `23505` on idempotency key | Concurrent duplicate submit | `200` | Replay of the winning response |

The 409 body is deliberately rich (RFC 9457 Problem Details with extension members) so the UI can
recover in one click rather than forcing the passenger to start over:

```json
{
  "type": "https://yathra.lk/problems/seat-segment-unavailable",
  "title": "Seat no longer available for this leg",
  "status": 409,
  "code": "SEAT_SEGMENT_UNAVAILABLE",
  "detail": "Seat 3A on trip 1005 is already booked for part of Gampaha → Nanu Oya.",
  "instance": "/api/v1/bookings",
  "conflicts": [
    { "seatId": "…", "seatLabel": "3A", "occupiedLeg": { "fromSeq": 1, "toSeq": 9 } }
  ],
  "suggestedAlternatives": [
    { "seatId": "…", "seatLabel": "3C", "window": true, "fareMinor": 176000 }
  ],
  "availabilityUrl": "/api/v1/trips/…/availability?from=GMP&to=NAN"
}
```

---

## 6. Holds, expiry, and the one genuinely awkward interaction

### 6.1 Why holds exist

A passenger takes 2–8 minutes between choosing a seat and completing payment.

| Option | Failure |
|---|---|
| Reserve nothing until payment succeeds | Two people pay for the same seat; one gets a refund and a ruined trip |
| Hold a DB row lock during payment | A 30 s gateway timeout holds a transaction open; connection pool exhaustion; cascading failure |
| **Hold as durable state with a TTL** | ✅ No lock held, inventory genuinely reserved, self-releasing |

So: `POST /bookings` writes status `HELD` with `expires_at = now() + BOOKING_HOLD_TTL` (default 600 s),
and the exclusion constraint's predicate includes `HELD`.

### 6.2 The awkward part

The constraint predicate must be **immutable** — PostgreSQL will not accept `now()` in a partial index
predicate. So the constraint cannot distinguish "held and live" from "held but expired". An expired hold
therefore still blocks a legitimate booking until something changes its status.

Options considered:

| Option | Verdict |
|---|---|
| Sweeper every 1 s | Wasteful; a contention point; still leaves a window |
| Drop `HELD` from the predicate | Reopens double-selling during the payment window — unacceptable |
| Store a `blocks_until` column and index on it | Same immutability problem |
| **Layered: eager sweeper + lazy in-transaction expiry** | ✅ Chosen |

### 6.3 The layered solution

**Eager (background).** A scheduled job runs every `HOLD_SWEEP_INTERVAL` (15 s), guarded by
**ShedLock** so exactly one replica executes it:

```sql
UPDATE booking_segment SET status = 'EXPIRED'
 WHERE status = 'HELD'
   AND booking_id IN (SELECT id FROM booking WHERE status='HELD' AND expires_at < now())
 RETURNING id, trip_id, seat_id, from_seq, to_seq;   -- feeds SegmentReleased outbox events
```

Batched (`LIMIT 500`) so a backlog cannot produce a giant long-running transaction.

**Lazy (in the booking transaction).** Before inserting, the write path expires *only the specific
conflicting holds*, then proceeds:

```sql
UPDATE booking_segment bs SET status='EXPIRED'
  FROM booking b
 WHERE bs.booking_id = b.id
   AND bs.trip_id = :tripId AND bs.seat_id = :seatId
   AND bs.status = 'HELD' AND b.expires_at < now()
   AND bs.leg && int4range(:from, :to, '[)');
-- then INSERT; one retry if the exclusion constraint still fires
```

This is surgical: it touches at most the handful of rows that are actually in our way, inside the
transaction that needs them gone, so there is no race between "I expired it" and "I inserted".

**Reads.** Availability queries also treat expired holds as free, so the UI never shows a stale block.

**Resulting worst case:** a seat may appear unavailable in the API for up to a few seconds after a hold
dies but before a competing booking triggers lazy expiry. It self-heals, it never causes incorrectness,
and it errs toward "looks taken" rather than "looks free" — the safe direction.

### 6.4 Clock skew

`expires_at` is computed and compared **entirely in the database** (`now()`), never in application JVMs.
Multiple replicas with drifting clocks would otherwise disagree about which holds are live. One clock,
one truth.

---

## 7. Availability queries

```sql
SELECT s.id, s.seat_label, s.is_window, c.coach_number, c.class_code
  FROM trip_seat s
  JOIN trip_coach c ON c.id = s.trip_coach_id
 WHERE s.trip_id = :tripId
   AND c.is_reservable
   AND (:classCode IS NULL OR c.class_code = :classCode)
   AND NOT EXISTS (
        SELECT 1 FROM booking_segment bs
         JOIN booking b ON b.id = bs.booking_id
        WHERE bs.trip_id = s.trip_id
          AND bs.seat_id = s.id
          AND bs.leg && int4range(:fromSeq, :toSeq, '[)')
          AND ( bs.status = 'CONFIRMED'
             OR (bs.status = 'HELD' AND b.expires_at > now()) )
   )
 ORDER BY c.position_index, s.row_index, s.seat_label;
```

The `NOT EXISTS` is answered by the GiST index that already exists for the constraint — the correctness
mechanism and the query index are the same object, which is a pleasing property: they cannot drift apart.

**Occupancy payload for the seat map.** Rather than a boolean per seat, the seat-map endpoint returns
the occupied ranges per seat:

```json
{ "seatId": "…", "label": "3A", "occupied": [[1,9],[15,25]], "availableForRequestedLeg": false }
```

This single response drives the "available / taken / **partially available**" tri-state colouring
described in the extra credit, without a second round trip. It leaks no passenger data — only ranges.

**Caching.** Availability is cached in Redis keyed by `(tripId, fromSeq, toSeq, class)` with a short TTL
and event-driven invalidation on `SegmentHeld` / `SegmentReleased`, plus an `ETag` so the SPA polls
cheaply. The cache is a *performance* device only — the booking path never reads it, so a stale cache can
never cause a double sale, only a 409 the UI already knows how to handle.

---

## 8. Failure mode analysis

| # | Scenario | Behaviour | Why it is safe |
|---|---|---|---|
| F1 | Two identical overlapping requests, same millisecond | One `201`, one `409` | Second blocks on the first's index entry, then violates |
| F2 | Two adjacent-leg requests, same seat, same millisecond | Two `201` | Ranges do not overlap; no index conflict |
| F3 | Service crashes after INSERT, before COMMIT | Booking never existed | Atomicity |
| F4 | Service crashes after COMMIT, before responding | Booking exists; client retry with the same `Idempotency-Key` returns it | Idempotency record is written in the same transaction |
| F5 | Payment succeeds, confirm call is lost | Hold expires and releases; payment reconciliation job refunds | Money is reconciled asynchronously; inventory is never orphaned |
| F6 | Kafka is down | Booking still succeeds | Outbox row is written transactionally; relay drains when Kafka returns |
| F7 | Redis is down | Availability slower, bookings unaffected | Cache is not on the correctness path |
| F8 | Two replicas both run the sweeper | Only one does | ShedLock advisory lock |
| F9 | Clock skew between replicas | No effect | All expiry logic uses DB `now()` |
| F10 | Someone runs a manual `INSERT` in psql that overlaps | Rejected | The constraint does not care who you are |
| F11 | A future bulk-import script forgets the check | Rejected | Same |
| F12 | Deadlock on multi-seat booking | Retried ×3, then a clean 409 | Sorted acquisition order makes it rare |
| F13 | Read replica lag shows a booked seat as free | User gets a 409 on submit | Writes always go to the primary; UI recovers in one click |

Rows F10 and F11 are the argument for the whole approach in miniature: the invariant holds against
writers we have not met yet.

---

## 9. Alternatives, in detail

Summarised in README §5; the reasoning is recorded fully in the ADRs:

- **Materialised seat×segment matrix** — write amplification, route-change fragility, invariant still in
  app code. Good *read* model, bad *write* model. → [ADR-002](adr/ADR-002-exclusion-constraint-concurrency.md)
- **Bitmask occupancy** — caps at 64 stops (the brief says the route may be extended), locks the whole
  seat row and so serialises exactly the disjoint bookings we exist to enable.
- **Redis/Redlock** — makes correctness depend on a second system's availability and clock; redundant
  when the DB is already the shared serialisation point.
- **`SERIALIZABLE` everywhere** — correct but taxes every transaction and returns unattributable errors.
- **Optimistic `version` on seat** — per-seat conflict domain produces false conflicts for disjoint legs;
  livelocks under load.
- **In-JVM locking** — wrong on replica #2, which is the deployment topology we are targeting.

---

## 10. How correctness is proven

| Level | Test | Assertion |
|---|---|---|
| Unit | `IntervalTest` | Full §3.1 truth table, including degenerate and inverted ranges |
| Integration | `SegmentExclusionConstraintIT` | Real Postgres (Testcontainers) rejects `[1,9)`+`[5,15)`, accepts `[1,9)`+`[9,25)` |
| **Race** | `ConcurrentBookingIT` | 50 threads on a `CyclicBarrier`, same seat, overlapping legs ⇒ **exactly 1 × 201, 49 × 409**, and the DB contains exactly one active segment |
| **Race (positive)** | `ConcurrentAdjacentBookingIT` | 2 threads, adjacent legs ⇒ **2 × 201**, both persisted |
| Property | `SegmentInvariantPropertyTest` (jqwik) | 10,000 random interval sets; after replay, no two active segments on one seat overlap |
| Chaos | `HoldExpiryRaceIT` | Sweeper and booking path racing on the same expiring hold ⇒ no lost updates, no double sale |
| Load | `load/booking-contention.js` (k6) | 200 VUs, 5 min, 90/10 read/write on a hot trip ⇒ p95 < 250 ms, zero invariant violations |

The race tests assert on **database state**, not only on HTTP status codes — a system can return
plausible responses and still have written garbage.

---

## 11. Extension points

Things a reviewer might ask me to add live, and where they would go:

| Ask | Change |
|---|---|
| "Add a turnaround buffer between passengers" | `int4range(from, to + :buffer, '[)')` at one construction site (§3.3) |
| "Support seat changes mid-journey" | Already expressible — two segments on different seats in one booking |
| "Allow overbooking by N%" | Add a `virtual_seat` with a no-show probability; the invariant is unchanged |
| "Add a new class of coach" | A row in `coach_layout` + `coach_type`; no code change |
| "Extend the route to Passara" | Rows in `station`/`route_stop`; re-publish future trips |
| "Reserve blocks of seats for the department" | An `INTERNAL_HOLD` booking with a long TTL — the constraint enforces it for free |
| "Two trains share a through coach" | Two trips referencing the same physical coach; segments are trip-scoped so it composes |

---

**Related:** [ADR-001](adr/ADR-001-half-open-interval-model.md) ·
[ADR-002](adr/ADR-002-exclusion-constraint-concurrency.md) ·
[ADR-004](adr/ADR-004-hold-then-confirm.md) ·
[`07-data-model.md`](07-data-model.md) · [`10-testing-strategy.md`](10-testing-strategy.md)
