# ADR-002 — Enforce the overlap invariant with a PostgreSQL GiST exclusion constraint

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

> This is the most important decision in the project.

---

## Context

**INV-1.** For any `(trip_id, seat_id)`, the set of *active* booking segments must be pairwise
non-overlapping.

The obvious implementation is check-then-act:

```java
// ❌ BROKEN
if (repo.findOverlapping(tripId, seatId, from, to).isEmpty()) {
    repo.save(new BookingSegment(tripId, seatId, from, to));
}
```

It loses this race:

```
 t   Thread A ([1,9))              Thread B ([3,15))
 1   BEGIN
 2   SELECT overlapping → ∅
 3                                 BEGIN
 4                                 SELECT overlapping → ∅   ← A uncommitted, invisible
 5   INSERT [1,9)
 6                                 INSERT [3,15)
 7   COMMIT ✅                      COMMIT ✅
 ─── seat double-sold between Gampaha and Kandy
```

Under `READ COMMITTED` *and* `REPEATABLE READ` this is a **phantom read**. There is no row-level lock to
take, because the conflicting row does not exist yet. **You cannot lock a row that isn't there.** What
is needed is a lock on the *predicate*.

## Options considered

| Option | Verdict |
|---|---|
| Application-level check | Loses the race above. Also bypassable by a second replica, a background job, a migration, a support script, or anyone with `psql`. |
| `SELECT … FOR UPDATE` on the seat row | Correct, but the conflict domain becomes the **seat**, so two passengers booking genuinely disjoint legs serialise against each other. That is precisely the behaviour this project exists to remove. |
| `SERIALIZABLE` isolation | Correct. Rejected: taxes every transaction for a guarantee only this write path needs, and fails with `40001 could not serialize` — no attribution, so the app cannot tell the user *which* seat conflicted or offer an alternative. |
| Redis / Redlock | Makes correctness depend on a second system's availability and clock. Redlock's safety under partition is contested, and a GC pause longer than the lock TTL yields two holders. Redundant when the database is already a shared strongly-consistent resource. |
| Bitmask on the seat row | Caps at 64 stops — the seed uses 25, which is the trap, because the brief says the route may be extended. Also locks the whole seat row, same flaw as `FOR UPDATE`. |
| **GiST exclusion constraint** | **Chosen** |

## Decision

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED

ALTER TABLE booking_segment
  ADD CONSTRAINT no_overlapping_active_segments
  EXCLUDE USING gist (trip_id WITH =, seat_id WITH =, leg WITH &&)
  WHERE (status IN ('HELD', 'CONFIRMED'));
```

Read aloud: *"no two rows may exist where the trip is the same and the seat is the same and the legs
overlap, among rows whose status is HELD or CONFIRMED."* That sentence **is** INV-1, verbatim.

`btree_gist` is required because `=` on `uuid` is a btree operator GiST does not index natively; the
extension supplies the operator classes so scalar equality and range overlap can share one index.

### What PostgreSQL actually does

On insert it adds the index entry speculatively, then scans for conflicts. If a **committed** conflict
exists → `23P01`. If an **uncommitted** conflict exists → it **blocks on that transaction's xid**, then
re-evaluates when that transaction commits or rolls back.

Measured before any Java was written:

- overlapping insert against an uncommitted transaction: **blocked 2.37 s**, then `23P01`
- adjacent insert `[9,25)` after `[1,9)`: **accepted in 0.087 s, zero contention**

## Consequences

**Good**

- **It is an invariant, not a check.** It holds against writers we have not met — a future bulk-import
  script, a support engineer in psql, replica #2.
- **Predicate locking for free**, with exactly the right granularity: contention occurs only where a
  genuine conflict exists.
- **The correctness mechanism and the availability index are the same object**, so they cannot drift
  apart. A seat can never be reported free by a query using a different definition of "free" from the
  one the write path enforces.
- `READ COMMITTED` is sufficient, and preferable: we are not relying on a *read* to make the decision,
  we are relying on a *write* the database refuses to accept. The resulting `23P01` is precise and
  attributable, so the API can return conflicting seats and ranked alternatives rather than a generic
  retry.

**Costs**

- **PostgreSQL lock-in.** Accepted deliberately. The alternative is enforcing the invariant in
  application code, which is bypassable by definition, and no other mainstream engine offers this. If
  we ever had to move, it is eight lines of DDL to reimplement.
- **The predicate must be immutable**, so it cannot reference `now()` and cannot distinguish a live
  hold from an expired one. This is the awkward part of the design and is addressed in
  [ADR-004](ADR-004-hold-then-confirm.md).
- **It forecloses partitioning.** PostgreSQL rejects exclusion constraints on partitioned tables, so
  `docs/07 §6`'s monthly partitioning plan cannot be applied to `booking_segment`. Worse, doing it
  per-partition would be *silently wrong*: two segments for one seat booked in different months would
  land in different partitions and never be compared. Documented in README §9.2.
- Multi-seat bookings can deadlock on index entries. Mitigated by sorting seat IDs so every transaction
  acquires in the same total order, plus a bounded jittered retry.

## Verification

- `SegmentExclusionConstraintIT` asserts the constraint's **definition**, so a future migration that
  drops or weakens it fails the build loudly rather than quietly.
- `ConcurrentBookingIT`: 50 threads on a `CyclicBarrier` → exactly 1 × 201 and 49 × 409, asserted
  against **database state** as well as HTTP status.
- The mirror test — 2 threads, adjacent legs → 2 × 201 — is equally required. A system passing only the
  first has reimplemented whole-seat locking.

---

**Related:** [ADR-001](ADR-001-half-open-interval-model.md) · [ADR-004](ADR-004-hold-then-confirm.md) ·
[`docs/04`](../04-segment-concurrency-design.md)
