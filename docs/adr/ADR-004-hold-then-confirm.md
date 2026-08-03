# ADR-004 — Two-phase booking: hold with a TTL, then confirm

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

A passenger takes 2–8 minutes between choosing a seat and completing payment. Something has to happen to
that seat in the meantime.

## Options considered

| Option | Failure mode |
|---|---|
| Reserve nothing until payment succeeds | Two passengers pay for the same seat. One gets a refund and a ruined journey. |
| Hold a database row lock during payment | A 30-second gateway timeout holds a transaction open. Connection pool exhausts. Cascading failure across every booking, not just that seat. |
| **Durable hold with a TTL** | **Chosen** — no lock held, inventory genuinely reserved, self-releasing |

## Decision

`POST /bookings` writes a booking with status `HELD` and `expires_at = now() + BOOKING_HOLD_TTL`
(default 600 s). The exclusion constraint's predicate covers `HELD` as well as `CONFIRMED`, so the hold
genuinely reserves inventory — while holding no lock at all.

State machine (explicit transition table, not scattered `if`s):

```
        create                paymentAuthorised
  (none) ────► HELD ──────────────────────────► CONFIRMED
                │                                    │
                │ cancel / expire / paymentFailed    │ cancel
                ▼                                    ▼
          EXPIRED / CANCELLED  ◄────────────────  CANCELLED
```

A `CONFIRMED` booking can be cancelled but can **never** expire: once the passenger has paid, no
background sweeper may take the seat away.

## The awkward part

**The exclusion constraint's predicate must be immutable.** PostgreSQL will not accept `now()` in it:

```sql
WHERE (status IN ('HELD','CONFIRMED'))                 -- ✅ immutable
WHERE (status = 'CONFIRMED' OR expires_at > now())     -- ❌ rejected
```

So the constraint cannot distinguish a live hold from a dead one. **An expired-but-unswept hold still
blocks a legitimate booking.**

### Options for that

| Option | Why not |
|---|---|
| Sweep every second | Wasteful, a contention point on the hottest table, and *still* leaves a sub-second window |
| Drop `HELD` from the predicate | Reopens double-selling across the entire payment window — the exact problem holds exist to solve |
| A `blocks_until` column indexed instead | Same immutability problem, one indirection further away |
| Trigger-maintained `is_active` boolean | Triggers cannot fire on the passage of time |

### The chosen answer: layered

1. **Eager** — a sweeper every 15 s, guarded by **ShedLock** so exactly one replica runs it, batched
   with `FOR UPDATE SKIP LOCKED` so a backlog cannot become one enormous transaction.
2. **Lazy, in-transaction** — before inserting, the booking path expires *only the specific conflicting
   holds*, inside its own transaction. Race-free by construction: the expiry and the insert share a
   transaction, so there is no window between "I expired it" and "I took it".
3. **Reads** — availability treats expired holds as free. Reads have no immutability restriction and
   should show the truth.

## Consequences

**Good**

- No lock is held during payment; a gateway timeout costs one hold, not the connection pool.
- Worst case is a seat appearing taken for a few seconds after a hold dies. It self-heals, and it errs
  toward **"looks taken"** rather than "looks free" — the safe direction. The failure mode is a mild
  inconvenience, never a double sale.
- All expiry logic compares against the **database's** `now()`, never a JVM clock, so replicas with
  drifting clocks cannot disagree about which holds are live.

**Costs**

- This is the least elegant part of the system and I would not pretend otherwise. It is the price of
  putting the invariant in an immutable index predicate, and it is worth paying.
- **Holds are an attack surface.** A mechanism that reserves inventory without payment is a free
  inventory-freezing weapon if handed out without limit. `MAX_ACTIVE_HOLDS_PER_CONTACT` is therefore
  mandatory, not optional, and is enforced *inside the booking transaction* where changing IP address
  cannot evade it. Any design that introduces holds without hold limits has built a DoS tool.

---

**Related:** [ADR-002](ADR-002-exclusion-constraint-concurrency.md) ·
[`docs/04 §6`](../04-segment-concurrency-design.md)
