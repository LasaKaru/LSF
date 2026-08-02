# ADR-001 — Model bookings as half-open integer ranges over trip stop sequence

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

A passenger consumes a seat over a *sub-interval* of the route, not over the whole route. To sell
inventory at that granularity we need a coordinate system for "where on the journey" — something we can
store, compare, and index.

Whatever we choose becomes the meaning of every ticket ever sold, so it has to be stable against the
things that change: timetables slip, routes gain halts, track gets re-surveyed.

## Options considered

### 1. Station ID pairs, resolved by joining to the route

`booking_segment(seat_id, from_station_id, to_station_id)`, with overlap computed by joining to
`route_stop` to turn station IDs into positions.

This was **the model I built first**, and it worked — every test passed. It has two problems, one
visible and one not:

- *Visible*: the overlap predicate depends on a join, so it cannot be expressed as an index, and
  therefore cannot be an exclusion constraint. Correctness stays stuck in application code.
- *Invisible, and worse*: route data is mutable. Insert a halt between Gampaha and Veyangoda and every
  `stop_order` after it shifts by one. Existing bookings still *resolve*, but every previously computed
  relationship silently changes. Nothing throws. Nobody notices until a passenger and a conductor
  disagree on a platform.

### 2. Timestamps — "the seat is occupied 06:15–09:40"

The most natural-sounding option and the most quietly broken. Delays mutate occupancy. A service
crossing midnight inverts the ordering. A timetable edit retroactively changes what inventory was sold.
And "is this seat free between Kandy and Ella" becomes a question about clocks rather than about
geography.

Occupancy is a **topological** fact, not a temporal one.

### 3. Cumulative kilometres — `numrange [121.0, 292.0)`

PostgreSQL supports `numrange`, so this is mechanically possible. Rejected because distances are
floating-point-ish measurements that change when track is re-surveyed, and float equality as part of a
uniqueness key is a decision you regret exactly once.

### 4. Dense integer stop sequence, snapshotted per trip — **chosen**

Each trip has an ordered stop list; `trip_stop.stop_sequence` is a dense integer starting at 1. A
booking stores `from_seq` and `to_seq`.

## Decision

A booking segment is the **half-open interval `[from_seq, to_seq)`** over the trip's stop sequence.

Two segments conflict iff `a.from < b.to AND b.from < a.to`.

**Half-open is the load-bearing part.** With closed ranges `[1,9]` and `[9,25]`, Kandy belongs to both
bookings and the central requirement of the brief fails — the alighting and boarding passengers are
treated as occupying the seat simultaneously when in fact they swap at the same stop. It is the same
reason a hotel does not double-book a room when one guest checks out on the 5th and another checks in
on the 5th.

| A | B | Overlap? | |
|---|---|---|---|
| `[1,9)` | `[9,25)` | **false** | ✅ the brief's headline scenario |
| `[1,9)` | `[5,15)` | true | ❌ B boards before A alights |
| `[1,25)` | `[9,11)` | true | ❌ whole-journey blocks everything |
| `[9,9)` | — | — | ❌ rejected by `CHECK (from_seq < to_seq)` |

## Consequences

**Good**

- Integers are ordered, exact, cheap to compare, and indexable as `int4range` — which is what makes
  ADR-002 possible at all.
- **Direction needs no flag.** An up service and a down service are separate trips, each with its own
  ascending sequence. There is no `if (direction == DOWN) swap(from, to)` anywhere in the codebase, and
  searching `BDL → CMB` simply matches the DOWN trips because only they serve those stations in that
  order.
- Special workings compose naturally: a service that skips Ohiya on Sundays is just a trip with a
  different stop list.

**Costs**

- The convention must be applied *consistently* — DDL, availability query, seat-map payload, waitlist
  matcher, reporting. One `<=` where `<` belongs and either seats vanish or seats double-sell.
  Enforced in three places: the generated column hard-codes `'[)'`; `Leg` is a value object with a
  validating factory so no raw `(int,int)` pair crosses a boundary; and the truth table is unit-tested
  including an exhaustive brute-force cross-check over all ~80,000 leg pairs on the seeded route.
- Sequence numbers are trip-scoped, so they must be resolved from station codes on every request
  (see ADR-005 for why they are snapshotted rather than read live).

**Extension point.** If the department ever wants a turnaround buffer ("a seat cannot be resold until
one stop after a long-haul passenger alights"), it is one change at the range construction site:
`int4range(from_seq, to_seq + :buffer, '[)')`.

## What I would tell someone starting this tomorrow

**Choose your coordinate system before you write the schema.** "What is the stable, immutable thing I
am measuring positions against?" should be the first question asked, not the third. Passing tests told
me my first model was self-consistent; they could not tell me it was measuring the wrong thing.

---

**Related:** [ADR-002](ADR-002-exclusion-constraint-concurrency.md) ·
[ADR-005](ADR-005-trip-topology-snapshot.md) · [`docs/04`](../04-segment-concurrency-design.md)
