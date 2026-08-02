# ADR-008 — Server-computed, HMAC-signed, expiring fare quotes

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

Fares live in `pricing-service`; bookings live in `booking-service` (ADR-003). When a booking is
written, the price must be known — and must be the price the passenger actually saw.

Two hard constraints:

1. **A client-supplied price is an underpayment vulnerability.** Anything the browser sends, an attacker
   can change.
2. **No synchronous call may happen inside the booking transaction** (ADR-003). Calling pricing while
   holding index entries on a contended seat would make pricing latency into transaction duration, and a
   pricing outage into a booking outage.

Those two together rule out the obvious options.

## Options considered

| Option | Why not |
|---|---|
| Client sends the price | Trivially tampered. |
| booking-service calls pricing inside the transaction | Violates constraint 2. Pricing's p99 becomes the booking transaction's p99. |
| booking-service calls pricing *before* the transaction | Better, but still a hard runtime dependency: pricing down ⇒ booking down. And it needs a nonce to prevent replay anyway, which is most of the way to a signed quote. |
| booking-service reads `pricing_db` directly | Violates database-per-service outright. |
| Duplicate the fare engine into booking-service | Two implementations of money. They will diverge, and the divergence will be discovered by a passenger. |
| **Signed, expiring quote carried by the client** | **Chosen** |

## Decision

`POST /quotes` returns an itemised price plus an HMAC-SHA256 signature. The client passes the quote back
with the booking. booking-service **verifies it locally** — no network call.

The signed payload binds the price to exactly what is being sold:

```
quoteId | tripId | fromSeq | toSeq | classCode | coachType | passengers
        | totalMinor | currency | ruleSetVersion | expiresAt(epoch)
```

Four checks before the signature is even examined: trip matches, leg matches, passenger count matches,
TTL not elapsed. Then a constant-time signature comparison.

**The canonical payload lives in the shared `common` module.** pricing signs and booking verifies; if
the two ever disagreed about the byte sequence, every booking would fail with an invalid-quote error
that looked like a key problem. Sharing the canonicalisation makes drift impossible rather than merely
unlikely.

## Consequences

**Good**

- **The client never sends a price**, so a tampered payload cannot underpay.
- **The binding matters as much as the signature.** Without it a validly-signed cheap Fort→Kandy quote
  could be redeemed for a Fort→Badulla booking. The four equality checks close that.
- **No HTTP inside the transaction.** Verification is microseconds of local CPU.
- The 10-minute TTL stops a stale quote being replayed after a fare change.
- `ruleSetVersion` is recorded on the booking, so any historical fare is reproducible months later when
  a passenger disputes a charge. For a public operator that is a regulatory requirement in disguise.
- Itemised breakdown means the passenger can see *why* they are charged what they are charged — the
  fare UI shows they are paying for 121 km, not for a whole train.

**Costs**

- **Key management.** `QUOTE_SIGNING_KEY` must be identical in both services, at least 32 bytes, and
  both refuse to start otherwise. Rotation needs overlapping validity (verify against current *and*
  previous key) so in-flight quotes are not invalidated — the config surface exists
  (`QUOTE_PREVIOUS_SIGNING_KEY`) though the dual-key verification path is not yet implemented.
- A shared secret is a coupling between two services. Milder than a runtime dependency, but real.
- Quotes are **not single-use** in the current implementation. A quote is a price for a leg, not a claim
  on a seat, so reuse within the TTL is harmless — fifty passengers asking the Fort→Kandy fare
  legitimately get the same answer. If a future audit requirement demanded one-quote-one-booking, the
  `quote` table already records issuance and would need a redemption flag.
- Constant-time comparison is used deliberately (`MessageDigest.isEqual`): a short-circuiting
  comparison leaks how many leading bytes were correct, which is enough to forge a signature byte by
  byte given enough attempts.

---

**Related:** [ADR-003](ADR-003-service-boundaries.md) · [ADR-010](ADR-010-telescopic-fare-model.md) ·
[`docs/05 §6`](../05-fare-engine.md)
