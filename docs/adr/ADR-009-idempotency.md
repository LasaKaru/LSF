# ADR-009 — Require idempotency keys on all mutating endpoints

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

The target user is on a congested Sri Lankan mobile network. They double-tap "Confirm" because nothing
visibly happened. They hit refresh mid-request. The connection drops after the server committed but
before the response arrived. The gateway retries on 5xx.

Every one of those, without protection, sells a second seat and takes a second payment.

This is not a theoretical hardening exercise — it is the single most likely real-world failure in the
whole system, because it requires no concurrency and no adversary, just a bad signal.

## Decision

`POST /bookings` **requires** an `Idempotency-Key` header. Missing ⇒ `400`.

```sql
CREATE TABLE idempotency_record (
    key             VARCHAR(128) PRIMARY KEY,
    request_hash    CHAR(64) NOT NULL,     -- SHA-256 of the canonicalised body
    response_status INT NOT NULL,
    response_body   JSONB NOT NULL,
    booking_id      UUID,
    expires_at      TIMESTAMPTZ NOT NULL   -- 24 h
);
```

Semantics:

| Case | Result |
|---|---|
| Key unseen | Proceed; store key + body hash + response **in the booking's transaction** |
| Same key, **same** body | Replay the original response, `Idempotency-Replayed: true` |
| Same key, **different** body | `422 IDEMPOTENCY_KEY_REUSED` |
| Concurrent duplicates | One wins; the loser returns the winner's response |

**Required, not optional.** Making it optional means the clients most likely to need it — the ones on
flaky connections — are the ones most likely to omit it.

**Stored in the same transaction as the booking.** There must be no instant at which the booking exists
but its idempotency record does not; that state is precisely where a retry double-books.

**The frontend mints the key once when the booking form is rendered** and reuses it for every retry of
that attempt. A new key is minted only for a genuinely new attempt (e.g. after re-quoting).

## A bug this decision's tests caught

The concurrent-duplicate case did not work as designed, and the reason is worth recording because it is
non-obvious:

Two identical requests race. The loser **blocks on the winner's exclusion-constraint index entry**, and
when the winner commits, the loser fails with `23P01` on the **segment insert** — which happens *before*
the idempotency record is written. So the unique-violation path never ran, and the loser was told
`SEAT_SEGMENT_UNAVAILABLE`: "that seat is taken", about a booking that was its own.

The fix is that the error path now re-checks the idempotency store **first**, before classifying the
failure as anything else. It is deterministic: the loser only unblocked because the winner's transaction
completed, so the winner's idempotency record is committed and visible to the new snapshot that read
takes.

> **A duplicate submit is not a conflict.** Ordering of failure modes inside a transaction matters as
> much as the failure modes themselves.

## Consequences

**Good**

- A double-tapped Confirm on a dropped connection is harmless.
- `GET`/`DELETE` are naturally idempotent; `confirm` is guarded by a state machine that rejects an
  illegal transition, so the property holds across the API rather than only on create.

**Costs**

- Clients must generate keys. Documented, and the reference frontend demonstrates it.
- Storage, bounded by 24-hour retention and an index on `expires_at`.
- Only the exact same body replays. A client that regenerates its payload — including a fresh quote id —
  gets `422` rather than a replay. That is the correct behaviour (silently returning the first response
  would let the caller believe a *different* booking had been made) but it is a sharp edge worth knowing
  about, and it caught me in my own tests before it could catch a client.

---

**Related:** [ADR-002](ADR-002-exclusion-constraint-concurrency.md) ·
[`docs/06 §4`](../06-api-contract.md)
