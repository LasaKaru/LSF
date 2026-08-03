# 16 — Demo & Walkthrough Preparation

> The brief says: *"you should expect to walk through your design and extend it live afterwards."*
> This document is my preparation for that session. It is included in the repository deliberately —
> being ready to be questioned is part of the work.

---

## 1. Fifteen-minute demo script

### 0:00 — The problem in one sentence (1 min)

> "A reserved seat is sold as one unit for the whole 292 km route, but most passengers travel a fraction
> of it. The rest of that seat evaporates at departure, and the short-leg passenger is charged double to
> cover it. The unit of sale doesn't match the unit of consumption — so I changed the unit of sale."

### 1:00 — The core behaviour, live (3 min)

```bash
./scripts/demo-segment-resale.sh
```

1. Book seat 3A, Colombo Fort → Kandy → **201**, LKR 470.
2. Book seat 3A, **Kandy → Badulla** → **201**, LKR 680. *Same physical seat, sold twice.*
3. Book seat 3A, Fort → Badulla → **409 SEAT_SEGMENT_UNAVAILABLE**.
4. Book seat 3A, Gampaha → Nanu Oya (straddles Kandy) → **409**.

> "Two passengers, one seat, LKR 1,150 instead of LKR 760 — and the short-leg passenger paid 38% less
> than they do today."

### 4:00 — Why it is *correct*, not just working (4 min)

Open `V3__booking_segment.sql` and read the constraint aloud:

```sql
EXCLUDE USING gist (trip_id WITH =, seat_id WITH =, leg WITH &&)
  WHERE (status IN ('HELD','CONFIRMED'))
```

> "That sentence *is* the business rule. It isn't a check in a service method that a second replica, a
> background job, or a support script could bypass — it's a property of the database."

Then the proof:

```bash
./scripts/concurrency-proof.sh
```

50 threads, one seat, overlapping legs → **1 × 201, 49 × 409**, and one active segment in the database.
Then the mirror test: 2 threads, adjacent legs → **2 × 201**.

> "Both tests matter. If only the first passed, I'd have reinvented whole-seat locking."

### 8:00 — The UI (3 min)

Seat map for Fort → Kandy: green, grey, **amber**.

> "Amber means partially available — free for part of your leg. That state doesn't exist in
> whole-journey booking. It's the visible signature of segment inventory."

Two browser windows racing for one seat: one wins; the other flashes red, shows the recovery banner, and
rebooks the suggested alternative in one click without retyping anything.

### 11:00 — The admin view (2 min)

> "Leadership *believes* revenue is being left on the table. This is the instrument that shows it."

Occupancy heatmap — where the train empties out. Seat-km utilisation next to conventional load factor:
100% of seats sold, 34% of seat-km used. That gap is the entire problem, in one number.

### 13:00 — Trade-offs I'd defend (2 min)

Booking + inventory in one database, and why splitting them would mean accepting double-sold seats.

---

## 2. Questions I expect, and my answers

**"Why not just lock the seat row?"**
Because that blocks the exact thing we are building. Two people booking Fort→Kandy and Kandy→Badulla
would serialise against each other despite having no real conflict. The conflict domain must be the
interval, not the seat.

**"Isn't an exclusion constraint PostgreSQL lock-in?"**
Yes, and it is worth it. The alternative is enforcing the invariant in application code, which is
bypassable by any writer that is not that code path. If we ever had to move databases, the invariant is
eight lines of DDL to reimplement — and no other mainstream engine gives it to us for free anyway.

**"Why one database instead of proper microservices?"**
Because a transactional invariant defines a service boundary. Splitting it means a saga, which means
eventual consistency, which means a window where a seat is double-sold and we resolve it by cancelling
a confirmed booking. That is an acceptable trade for an airline; it is not for a state railway. I split
everything that *does* tolerate eventual consistency.

**"What happens if two people book adjacent legs at the exact same instant?"**
Both succeed. The ranges do not overlap, so there is no index conflict and no blocking. There is a test
for precisely this — it is the positive half of the concurrency proof.

**"How do you handle expired holds?"**
That is the messiest part of the design, and I would not claim otherwise. The constraint predicate must
be immutable so it cannot reference `now()`, which means an expired hold still blocks. Layered fix: a
ShedLock-guarded sweeper every 15 seconds, plus surgical in-transaction expiry of the specific
conflicting holds on the booking path. Worst case is a few seconds of false unavailability, and it errs
toward "looks taken" rather than "looks free".

**"How does this scale?"**
The volume is small — about 1.2M bookings a year (`docs/01` §5). The hard part is contention, not
throughput: on the morning peak tickets are released, thousands converge on hundreds of seats. Contention
is per `(seat, range)`, so disjoint bookings never contend. The next step for peak days is a fair-
admission queue rather than more capacity.

**"What if the department adds a station?"**
Insert into `station` and `route_stop`, then re-publish future trips. Existing bookings are unaffected
because each trip snapshots its own stop list at publication — which is exactly why the snapshot exists.

**"Why not a materialised seat×segment table?"**
Great read model, bad write model: 24 rows written per booking, the whole set invalidated by a route
change, and the invariant back in application code. It is documented as the CQRS option in future work,
where it belongs.

**"What would you do differently?"**
Choose the coordinate system before writing the schema. My first model stored station IDs and joined to
resolve order; it passed every test and was quietly wrong, because route edits would have silently
changed what existing bookings meant. Half a day to rewrite, and everything downstream got simpler.

**"What is the weakest part of this?"**
Two things. The hold-expiry interaction with the immutable constraint predicate — it works, but it is the
part I would want a second pair of eyes on. And booking-service is a large service; I have argued why,
but if the department adds cargo and dining it needs splitting along those lines.

---

## 3. Live extension — rehearsed scenarios

I have rehearsed each of these so I can implement it during the session rather than describe it.

### 3.1 "Add a turnaround buffer — a seat can't be resold for one stop after a long journey"

One change at the range construction site:

```sql
leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq + :buffer, '[)')) STORED
```

Then a test: `[1,9)` and `[9,25)` now conflict with buffer 1; `[1,9)` and `[10,25)` do not. ~10 minutes,
including the migration and the test.

### 3.2 "Add a new coach type with a 2+1 layout"

Insert a `coach_layout` row with the grid JSON, reference it from `train_composition`, re-publish a trip.
**Zero code changes** — frontend or backend. This is the cleanest demonstration of the configurability
requirement and the one I would offer first.

### 3.3 "Add a fare rule — 20% off for children"

New row in a `fare_passenger_multiplier` table, applied in `FareCalculator`, plus a `passengerType` on
the quote request. Property tests for additivity still pass. ~20 minutes.

### 3.4 "Show which seats are free for *any* part of a leg, not just all of it"

The seat-map endpoint already returns occupancy ranges, so this is a frontend filter plus an optional
`?partial=true` query parameter on availability. ~15 minutes.

### 3.5 "Add a 'seats near me' preference to auto-assignment"

A new `SeatSelectionStrategy` implementation registered by name. The strategy interface exists precisely
so this is an additive change. ~20 minutes.

### 3.6 "Prevent a single user from holding more than 6 seats at once"

A count check inside the booking transaction against active holds for the contact, returning
`429 HOLD_LIMIT_EXCEEDED`. Also the mitigation for the hold-squatting threat in `docs/11` §4. ~15 minutes.

---

## 4. Code reading order

If a reviewer wants to read rather than watch, this is the order that makes the design legible:

1. `db/migrations/booking/V3__booking_segment.sql` — **the invariant. Start here.**
2. `booking-service/.../domain/Leg.java` — the interval value object
3. `booking-service/.../BookingService.java` — the write path
4. `booking-service/.../SegmentAvailabilityService.java` — the read path
5. `booking-service/src/test/.../ConcurrentBookingIT.java` — **the proof**
6. `pricing-service/.../FareCalculator.java` — telescopic bands
7. `web/passenger-app/src/components/SeatMap.tsx` — the tri-state UI
8. `docs/04-segment-concurrency-design.md` — the reasoning behind all of it

Everything else is competent scaffolding around those eight files.

---

## 5. Honest self-assessment

**Strongest:** the invariant is unbypassable and proved by tests that assert on database state; the
adjacency case — the one most implementations get wrong — is explicitly handled and tested from both
directions; the fare model repairs the actual injustice in the brief rather than just enabling resale;
and the reasoning is documented well enough to be argued with.

**Weakest:** the hold-expiry interaction is the least elegant part of the system; booking-service is
large; the fare figures are illustrative and would need calibration against real tariffs; and the extra
credit is deliberately narrower than it could have been because the core came first.

**What I would build next if this were real:** the reserved/unreserved rebalancing analysis
(`docs/15` §1.1). It is the second half of the problem the brief describes, and the data to answer it now
exists for the first time.
