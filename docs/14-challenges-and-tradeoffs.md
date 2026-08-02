# 14 — Challenges & Trade-offs

The long-form version of README §10, including the approaches that did not survive. Written honestly:
the wrong turns are more informative than the final state, and I would rather walk a reviewer through
what I learned than present a design that appears to have arrived fully formed.

---

## Challenge 1 — The first data model was wrong

### What I built first

```sql
booking_segment (id, booking_id, seat_id, from_station_id, to_station_id, ...)
```

Overlap was computed by joining to `route_stop` to resolve station IDs into positions:

```sql
JOIN route_stop rs_from ON rs_from.station_id = bs.from_station_id
JOIN route_stop rs_to   ON rs_to.station_id   = bs.to_station_id
WHERE rs_from.stop_order < :toOrder AND rs_to.stop_order > :fromOrder
```

It worked. Every test passed.

### Why it was wrong

Two failures, one visible and one not.

**The visible one:** the overlap predicate depends on a join, so it cannot be expressed as an index — and
therefore cannot be an exclusion constraint. Correctness was stuck in application code, which is exactly
the position I was trying to escape.

**The invisible one, which is worse:** route data is mutable. If the department inserts a new halt
between Gampaha and Veyangoda, every `stop_order` after it shifts by one. Existing bookings still store
station IDs, so they still *resolve* — but every previously computed relationship silently changes, and
any cached or denormalised position becomes wrong. Bookings would quietly start meaning something other
than what was sold. Nothing would throw. Nobody would notice until a passenger and a conductor disagreed
on a platform.

### The fix

Two changes, half a day:

1. **Snapshot the topology.** A trip copies its stop list at publication (`trip_stop`), so a route edit
   cannot reach backwards into sold inventory.
2. **Store positions, not references.** `from_seq`/`to_seq` are integers on the booking segment itself,
   so the range is self-contained, indexable, and expressible as `int4range`.

Everything downstream got simpler: the availability query lost two joins, the exclusion constraint became
possible, and the direction flag disappeared entirely.

### What I take from it

**Choose the coordinate system before writing the schema.** The question "what is the stable, immutable
thing I am measuring positions against?" should have been the first one asked, not the third. Passing
tests told me the model was self-consistent; they could not tell me it was measuring the wrong thing.

---

## Challenge 2 — Adjacency, and the boundary condition that defeats the whole point

Closed ranges are the natural first instinct: Fort → Kandy is "stops 1 through 9". So:

```
A = [1, 9]   Fort → Kandy
B = [9, 25]  Kandy → Badulla
A ∩ B = {9}  → overlap → B rejected
```

**The exact scenario the brief asks for fails**, because Kandy belongs to both bookings. A passenger
alights at Kandy and another boards at Kandy; they do not occupy the seat simultaneously, but a closed
interval says they do.

Half-open `[from, to)` fixes it — but only if applied **consistently**, and there are more places than
you would expect: the DDL, the availability query, the seat-map occupancy payload, the waitlist fit
predicate, the reporting aggregation, and the frontend's range rendering. One `<=` where a `<` belongs
and either seats vanish or seats double-sell.

Mitigations, because a convention documented only in prose *will* be violated:

1. The generated column hard-codes `'[)'` — there is no other way to construct the range.
2. `Leg` is a value object with a validating factory; no raw `(int, int)` pairs cross a boundary.
3. An architecture test fails the build if any SQL string contains `'[]'`, `'(]'` or `'()'` bounds, or if
   a conflict predicate uses `<=`.

I found this with a failing test rather than by reasoning, which is itself the lesson: **write the
adjacency test before writing the model**, because it is the one case that distinguishes a correct
implementation from a plausible one.

---

## Challenge 3 — Expired holds versus an immutable constraint predicate

The exclusion constraint's predicate must be immutable, so it cannot reference `now()`:

```sql
WHERE (status IN ('HELD','CONFIRMED'))                  -- ✅ immutable
WHERE (status = 'CONFIRMED' OR expires_at > now())      -- ❌ rejected by PostgreSQL
```

Consequence: an **expired but unswept hold still blocks a legitimate booking.** A passenger who abandoned
checkout ten minutes ago is still holding inventory as far as the constraint is concerned.

### Options I worked through

| Option | Why not |
|---|---|
| Sweeper every second | Wasteful, a contention point on the booking table, and still leaves a sub-second window |
| Drop `HELD` from the predicate | Reopens double-selling during the entire payment window — the problem holds exist to solve |
| `blocks_until` column indexed instead | Same immutability problem, one indirection further away |
| Trigger-maintained `is_active` boolean | Works, but the trigger must fire on a *time* condition, which triggers cannot do |

### The chosen answer: layered

- **Eager** — ShedLock-guarded sweeper every 15 s, batched at 500 rows, emitting `SegmentReleased`.
- **Lazy (in-transaction)** — before inserting, the booking path expires **only the specific conflicting
  holds** in the same transaction, then proceeds with one bounded retry. Surgical, and race-free because
  the expiry and the insert share a transaction.
- **Reads** — availability treats expired holds as free, so the UI is never stale in the *unsafe*
  direction.

Worst case: a seat looks unavailable for a few seconds after a hold dies, self-healing on the next
attempt. It errs toward "looks taken" rather than "looks free", which is the safe direction — the failure
mode is a mild inconvenience, never a double sale.

This is the messiest part of the design and I would not pretend otherwise. It is the price of putting the
invariant in an immutable index predicate, and it is a price worth paying.

---

## Challenge 4 — A deadlock the load test found and reasoning did not

Two families booking the same four seats in opposite orders:

```
T1: acquires 3A, wants 3B     T2: acquires 3B, wants 3A     → 40P01 deadlock
```

I did not predict this. It appeared as intermittent 500s at ~40 bookings/sec in k6, at a rate low enough
(≈0.3%) that a lighter load test would have missed it entirely.

**Fix:** sort seat IDs before insertion so every transaction acquires index entries in the same total
order, making the cycle structurally impossible. Plus a bounded retry (3 attempts, jittered) for the
residual risk from concurrent sweeper activity.

**What I take from it:** I load-tested because the plan said to, not because I expected to find anything.
Concurrency bugs are not reliably discoverable by reading code — and the ones that appear at 0.3% under
synthetic load are the ones that appear constantly on the morning peak tickets are released.

---

## Challenge 5 — Microservices versus "one command from a clean machine"

The brief asks for a real, launchable application; the target organisation builds microservices; and the
project must start in one shot on a clean machine. An honest topology is twelve containers including
Kafka, a schema registry and an identity provider. On a laptop that is a three-minute cold start, several
gigabytes of images, and a meaningful chance that something times out and the reviewer's first impression
is a wall of red.

### What I rejected

- **Ship a monolith and describe the microservices.** Dishonest — the diagram would be aspirational.
- **Ship twelve containers by default.** Optimises for the architecture diagram over the reviewer.
- **Two separate compose files.** They drift. One of them is always broken.

### What I did

**Compose profiles.** `core` (six containers, ~90 s, demonstrates every core requirement) is the default;
`full` brings up the complete topology; `observability` adds tracing. **No code path differs between
profiles** — in `core` the outbox relay writes events transactionally and logs them rather than publishing
to Kafka, controlled by one env var. The pattern is real and inspectable either way.

I would rather a reviewer see the system working in 90 seconds than see a more impressive topology that
times out.

---

## Challenge 6 — Justifying a shared database in a microservices organisation

Putting booking and seat inventory in one service with one database looks, at a glance, like the thing a
microservices shop trained you not to do. It was the decision I spent the most time being sure about.

The argument that settled it: **the alternatives all require accepting a window in which the same
seat-segment is sold twice**, detected later and compensated by cancelling someone's confirmed booking.
An airline prices that in. A state railway selling a LKR 1,200 seat to a passenger already standing on
the platform cannot — "our architecture double-sold your seat" is a service failure, not a compensating
action.

So the boundary follows the invariant, and everything that genuinely tolerates eventual consistency —
pricing, payments, waitlist, reporting, notifications — is a separate service.

**The honest counter-argument**, which I would raise myself in the walkthrough: booking-service is now the
largest and hottest component, and if the department later adds cargo, parcels and dining reservations, it
will need splitting along *those* lines. The design anticipates this — internal packaging is modular by
aggregate and the event contracts are already public — but I am not going to claim the current shape is
permanent.

---

## Challenge 7 — Scope discipline against a 72-hour clock

Five extra-credit options, a Tuesday deadline, and every one of them more fun than writing tests.

The mechanism that worked was a **hard checkpoint**: no extra-credit work begins until the concurrency
proof is green and the README is written. Not "core first" as an intention — a specific gate with a
specific time attached (see [`SPRINT_PLAN.md`](../SPRINT_PLAN.md)).

Two extras were cut outright (real payment gateway, SMS) rather than half-built, and are documented as
future work with a sketch of the approach. A half-integrated payment gateway would have looked like more
work and been worth less than an honest mock with the right seams.

---

## Trade-off summary

| Decision | Gained | Given up | Would I revisit? |
|---|---|---|---|
| GiST exclusion constraint | Unbypassable correctness, free predicate locking | PostgreSQL lock-in | **No.** The lock-in is worth it, and the DDL is 8 lines |
| Booking + inventory in one service | ACID invariant, no sagas on the critical path | Larger service; not textbook decomposition | No — but I would split it if new domains arrive |
| Half-open intervals | Adjacency works; ranges compose | A convention that must be enforced everywhere | No — enforced in 3 places |
| Hold-then-confirm | Humane UX, no locks during payment | Sweeper complexity, squatting surface | No — but hold limits are mandatory, not optional |
| `READ COMMITTED` + constraint | Precise errors, no global cost | Must handle `23P01` explicitly | No |
| Trip topology snapshot | Immutable bookings, self-contained service | Duplicated data, republish on change | No |
| Telescopic fares | Realistic tariff, long-haul protection | More complex; needs the additivity guard | No |
| FIFO waitlist | Defensible fairness | Some utilisation unclaimed | Maybe — a *bounded* best-fit within an arrival window |
| Compose profiles | 90-second first run | Two topologies to keep working | No |
| Signed quotes | No price tampering, no HTTP in the transaction | Key management, TTL edge cases | No |

---

## What I would tell someone starting this task tomorrow

1. **Write the adjacency test first.** `[1,9)` and `[9,25)` on one seat must both succeed. It is the one
   test that distinguishes a correct implementation from a plausible one, and it fails silently in every
   naive model.
2. **Decide your coordinate system before your schema.** Stops, not stations; per trip, not per route.
3. **Put the invariant in the database.** Everything else is a check, and checks get bypassed.
4. **Load-test the write path.** The deadlock will not appear in code review.
5. **Set a hard gate before extras.** The core is what is being assessed; the extras only count if the
   core is solid.

---

**Related:** [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) ·
[`SPRINT_PLAN.md`](../SPRINT_PLAN.md) · [`adr/`](adr/)
