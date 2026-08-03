# ADR-010 — Telescopic banded fares with a subadditivity guard

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

The brief's central grievance is a **pricing** grievance:

> *a passenger booking a reserved seat from Colombo Fort to Kandy pays roughly double what a passenger
> pays for that same partial leg in an unreserved coach — because the fare has to cover the seat sitting
> empty for the rest of the journey*

That surcharge is not greed. It is a **rational hedge against non-resellable inventory**. Which means
the moment inventory becomes resellable, the hedge is unnecessary — and keeping it would be
indefensible.

So the fare model is not a bolt-on feature. It is the mechanism by which the engineering change becomes
a public benefit.

## Decision

```
fare = round( max( MINIMUM,
                   telescopic_base(km)
                     × class × coach × demand × (1 − advance_discount)
                 + scenic_surcharge(overlapping km only) ) )
```

**The reserved-coach multiplier is 1.00.** That single row is the fix: reserved short legs go from ~2×
unreserved to **1.24×**, which is a defensible premium for a guaranteed seat rather than a subsidy for
dead space.

### Telescopic bands

Marginal rate per km *falls* with distance (0–50 km @ 4.30, 50–150 @ 3.655, 150+ @ 3.010), computed like
tax brackets. This mirrors real railway tariff practice, reflects genuine cost structure (fixed
per-ticket costs amortise over distance), and protects long-haul affordability.

**Alternatives:** a flat per-km rate is simpler and is the fallback if the department rejects tapering,
but it overcharges long-haul passengers relative to current practice. Zone fares are coarse over 292 km.
A station-pair matrix is how legacy systems work and is 300 pairs today, 1,770 at 60 stations — it makes
extending the route a data-entry project. The engine can *emit* such a matrix for printing, which is the
useful half.

### Ordering is deliberate

- **Multiplicative** factors scale with distance — first class should cost proportionally more on a long
  leg.
- **Scenic is additive** and charged only on scenic kilometres actually travelled. Multiplicative would
  apply it to the whole journey and overcharge a Fort→Kandy passenger for a view they never see.
- **Minimum fare** before rounding, so a 3 km hop is never priced below the cost of issuing the ticket.
- **Rounding last, once.** Rounding intermediates compounds error across bands.

### Everything is data

Seven tables, effective-dated and versioned. Publishing new fares creates a **new rule set version**
rather than mutating the old one, so historical quotes stay reproducible and a bad change is rolled back
by changing a date rather than by a deployment.

### Demand pricing is off by default

Bounded `[0.90, 1.40]` and driven by **segment** occupancy — a capability that literally could not exist
without segment inventory. But `DEMAND_PRICING_ENABLED=false` ships as the default: silently varying
public transport fares by demand, without the department having chosen that, would be inappropriate
regardless of how well the feature works. Full dynamic/ML yield pricing was rejected outright —
unexplainable pricing is undeployable for a public operator.

## FARE-1, and where the design docs were wrong

`docs/05 §3.1` asserts the subadditivity invariant:

```
∀ A < B < C :   fare(A→C) ≤ fare(A→B) + fare(B→C)
```

and claims it "holds automatically for any concave, monotonically increasing fare function". Splitting a
ticket must never be cheaper than buying it whole, or the department leaks revenue to ticket-splitting
apps.

**Testing showed the claim is false once rounding is applied.**

Below 50 km the fare sits entirely in band 1, where the rate is constant — so the base fare is *exactly
linear* and concavity contributes **zero** headroom. Rounding is then the only term left, and rounding
the whole up while rounding both halves down costs one increment. At the seeded rates, two 8 km legs are
LKR 30 each (LKR 60) against LKR 70 for one 16 km leg.

**I did not "fix" it**, and that is the decision:

- The leak is bounded at **one rounding increment per split** (LKR 10), against the cost and friction of
  issuing an extra ticket.
- It **cannot compound**. Splitting repeatedly runs into concavity and the minimum fare, both of which
  grow far faster — the 292 km journey split into 24 legs costs well over the through fare.
- **No rounding mode removes it.** FLOOR merely moves which pairs violate it; the granularity itself is
  the cause.

Distorting a published public tariff to close a LKR 10 gap would be the wrong trade for an operator
whose fares must be explainable. So the property is now stated **exactly** on unrounded fares and
**bounded by one increment** on rounded ones, with the worst observed case pinned by a test so a future
rate change that widens it fails loudly.

## Consequences

**The numbers** (illustrative seed data):

| | Today | With segment booking |
|---|---|---|
| Fort → Kandy, reserved | ~LKR 760 | **LKR 470** (−38%) |
| Ratio to unreserved | ~2.0× | **1.24×** |
| Fort → Badulla | LKR 1,040 | 1,040 (unchanged) |
| **Seat carrying only a Fort→Kandy passenger** | 760, then 171 km dead | **1,150 (+51%)** |

**The department earns more while the short-leg passenger pays less**, because 171 seat-km stopped being
destroyed at every departure.

**Costs**

- More complex than flat per-km, and requires the additivity guard to stay honest.
- Money is `BIGINT` minor units end to end, with `BigDecimal` for intermediate arithmetic — never
  floating point. Rates are `NUMERIC` because a rate is not a monetary amount.
- All figures are **illustrative** and must be calibrated against official SLR tariffs before deployment.

---

**Related:** [ADR-008](ADR-008-signed-fare-quotes.md) · [`docs/05`](../05-fare-engine.md)
