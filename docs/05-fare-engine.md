# 05 — Fare Engine

> ⚠️ **All monetary figures in this document are illustrative.** They are internally consistent and
> chosen to demonstrate the model's behaviour. Real deployment must be calibrated against official
> Sri Lanka Railways tariff schedules and approved by the department.

---

## 1. Why fares are part of the core, not an afterthought

The brief's central grievance is a **pricing** grievance:

> *"a passenger booking a reserved seat from Colombo Fort to Kandy pays roughly double what a passenger
> pays for that same partial leg in an unreserved coach — the department's reasoning being that the fare
> has to cover the seat sitting empty for the rest of the journey."*

That surcharge is not greed; it is a **rational hedge against non-resellable inventory**. The moment
inventory becomes resellable, the hedge is unnecessary, and keeping it would be indefensible. So the
fare model is not a bolt-on feature — it is the mechanism by which the engineering change becomes a
public benefit.

The design goal is therefore explicit:

> **Charge for distance consumed. Recover the empty-seat risk through resale, not through the
> short-leg passenger's wallet.**

---

## 2. The formula

```
segmentFare =
    round₁₀(
      max( MINIMUM_FARE,
           telescopicBase(km)
             × classMultiplier
             × coachMultiplier
             × demandMultiplier(segmentOccupancy)
             × (1 − advanceDiscount(daysAhead))
           + scenicSurcharge(leg)
      )
    )

bookingTotal = Σ segmentFare
```

Ordering matters and is deliberate:

- **Multiplicative** factors (class, coach, demand, advance) scale with distance — a 1st-class ticket
  should cost proportionally more on a long leg than a short one.
- **Scenic surcharge is additive** and computed only on the scenic kilometres actually travelled. If it
  were multiplicative it would be applied to the entire journey, overcharging a Fort→Kandy passenger for
  a view they never see.
- **Minimum fare** is applied before rounding so a 3 km hop is never priced below the cost of issuing
  the ticket.
- **Rounding to LKR 10** happens last, once, on the final figure — never on intermediate values, which
  would compound rounding error across bands.

---

## 3. Telescopic (tapering) bands

Real railway tariffs are **telescopic**: the marginal rate per kilometre falls as distance rises. It
reflects genuine cost structure (fixed per-ticket costs amortise over distance) and it protects long-haul
affordability.

Implemented exactly like income tax brackets — marginal, cumulative, monotonic:

| Band | Range | Multiplier | Rate (2nd class, LKR/km) |
|---|---|---|---|
| 1 | 0 – 50 km | 1.00 | 4.30 |
| 2 | 50 – 150 km | 0.85 | 3.655 |
| 3 | 150 km + | 0.70 | 3.010 |

```
telescopicBase(km):
    d1 = min(km, 50)
    d2 = clamp(km − 50, 0, 100)
    d3 = max(km − 150, 0)
    return d1·4.30 + d2·3.655 + d3·3.010
```

Bands are **rows in `fare_band`**, effective-dated and versioned. Adding a fourth band or changing a rate
is a data change with an audit trail, not a deployment.

### 3.1 The additivity trap

Telescopic banding creates an arbitrage hazard. If the taper is steep enough, `fare(A→C)` can be
*cheaper* than `fare(A→B) + fare(B→C)` — which is fine — but the reverse must never happen, or
passengers would be able to buy a long ticket and use only part of it more cheaply than the short ticket.
More subtly, if splitting were *cheaper*, the department would leak revenue to ticket-splitting apps.

**Invariant FARE-1 (subadditivity of the whole journey):**

```
∀ A < B < C :   fare(A→C)  ≤  fare(A→B) + fare(B→C)
```

This holds automatically for any *concave, monotonically increasing* fare function, which a
non-increasing marginal-rate band structure is. But "it holds by construction" is exactly the kind of
claim that stops being true after someone adds a fourth band with a rate *higher* than the third.

So it is a **property-based test**, not a comment:

```java
@Property
void wholeJourneyIsNeverMoreExpensiveThanTheSumOfItsParts(
        @ForAll @IntRange(min=1, max=24) int a,
        @ForAll @IntRange(min=1, max=25) int b,
        @ForAll @IntRange(min=2, max=25) int c) {
    assume(a < b && b < c);
    assertThat(fare(a, c)).isLessThanOrEqualTo(fare(a, b).plus(fare(b, c)));
}
```

There is a second, related test asserting `fare` is **monotonic** in distance — a longer leg is never
cheaper than a shorter one it contains.

---

## 4. Multipliers

### 4.1 Class

| Class | Multiplier | Notes |
|---|---|---|
| 1st class (AC / sleeper) | 1.60 | |
| 2nd class | 1.00 | Reference |
| 3rd class | 0.70 | |
| Observation saloon | 1.35 | Applied via coach, not class |

### 4.2 Coach type — where the brief's injustice is repaired

| Coach type | Multiplier | Rationale |
|---|---|---|
| Reserved | **1.00** | Guaranteed seat. **No empty-seat surcharge — the seat now resells.** |
| Unreserved | **0.80** | No seat guarantee; standing risk |
| Observation | 1.35 | Premium product |

This single row is the fare-side fix. Reserved short legs go from **~2× unreserved** to **1.25×
unreserved**, which is a defensible premium for a guaranteed seat rather than a subsidy for empty space.

### 4.3 Scenic surcharge

The Nanu Oya → Ella stretch (`stop_sequence` 15 → 23, 71 km) is the internationally famous section and
the one with genuine willingness-to-pay.

```
scenicSurcharge(leg) = overlapKm(leg, scenicRange) × marginalRateAt(thoseKm) × SCENIC_UPLIFT   (0.15)
```

Only the **overlapping** kilometres are charged, so a Fort→Kandy passenger pays nothing. Scenic ranges
are configuration rows (`fare_scenic_segment`), so the department can add the Demodara Loop or remove the
premium entirely without a code change.

### 4.4 Demand multiplier

Bounded and published — this is a state operator, and an unbounded black-box yield algorithm is not
deployable.

| Segment occupancy | Multiplier |
|---|---|
| < 40% | 0.90 |
| 40 – 70% | 1.00 |
| 70 – 90% | 1.15 |
| > 90% | 1.40 (hard ceiling) |

Two properties that matter:

1. **It is driven by *segment* occupancy, not train occupancy.** A train that is full to Kandy and empty
   afterwards should price those two stretches differently — which is only expressible at all because
   inventory is now segment-granular. This is a capability the old model literally could not have.
2. **Occupancy is sampled, not live.** The tier is refreshed from `SegmentOccupancyChanged` events on a
   schedule, so a passenger who reloads the page twice does not see the price twitch. Price stability is
   a fairness property.

### 4.5 Advance-purchase discount

| Days before departure | Discount |
|---|---|
| ≥ 14 | 10% |
| 7 – 13 | 5% |
| < 7 | 0% |

Shifts demand earlier, which improves the department's ability to plan consists — and improves our
occupancy signal, which makes §4.4 more accurate.

---

## 5. Worked example — the brief's exact scenario

Seed distances: Colombo Fort `seq 1, 0 km`, Kandy `seq 9, 121 km`, Badulla `seq 25, 292 km`.
2nd class reserved, no demand or advance adjustment (multiplier 1.00), for clarity.

### 5.1 Telescopic base

| Leg | km | Band 1 | Band 2 | Band 3 | Base (LKR) |
|---|---|---|---|---|---|
| Fort → Kandy `[1,9)` | 121 | 50 × 4.30 = 215.00 | 71 × 3.655 = 259.51 | — | **474.51** |
| Kandy → Badulla `[9,25)` | 171 | 215.00 | 100 × 3.655 = 365.50 | 21 × 3.010 = 63.21 | **643.71** |
| Fort → Badulla `[1,25)` | 292 | 215.00 | 365.50 | 142 × 3.010 = 427.42 | **1,007.92** |

### 5.2 With scenic surcharge and rounding

| Leg | Base | Scenic km | Scenic surcharge | Total | Rounded |
|---|---|---|---|---|---|
| Fort → Kandy | 474.51 | 0 | 0.00 | 474.51 | **LKR 470** |
| Kandy → Badulla | 643.71 | 71 | 71 × 3.010 × 0.15 = 32.06 | 675.77 | **LKR 680** |
| Fort → Badulla | 1,007.92 | 71 | 32.06 | 1,039.98 | **LKR 1,040** |

Additivity check: `470 + 680 = 1,150 ≥ 1,040` ✅ (FARE-1 holds).

Unreserved equivalents (× 0.80): Fort → Kandy **LKR 380**, Fort → Badulla **LKR 830**.

### 5.3 Before and after

| | Today | With segment booking | Change |
|---|---|---|---|
| Fort → Kandy, **reserved** (passenger view) | ~LKR 760 (≈2× the LKR 380 unreserved fare) | **LKR 470** | **−38%** for the passenger |
| Ratio to unreserved for the same leg | ~2.0× | **1.24×** | Premium now reflects a guaranteed seat, not dead space |
| Fort → Badulla, reserved | ~LKR 1,040 | LKR 1,040 | Unchanged — long-haul passengers are not penalised |
| **Revenue from one seat carrying only a Fort→Kandy passenger** | **LKR 760**, then 171 km dead | **LKR 470 + 680 = LKR 1,150** | **+51%** |
| Revenue from one seat sold whole-journey | LKR 1,040 | LKR 1,040 | Unchanged |

Read the fourth row twice: **the department earns more while the short-leg passenger pays less.** That is
not a pricing trick — it is what happens when you stop destroying 171 seat-km at every departure. The
gain is created by the resale, and it is shared between the operator and the passenger rather than being
extracted from one of them.

### 5.4 Where the money actually comes from

Honesty about the model's limits:

- Against a seat that **would have sold whole-journey anyway**, resale adds ~10% (1,150 vs 1,040) — real
  but modest.
- Against a seat that today carries **only a short leg**, resale adds **51%**.
- Against a seat that today goes **entirely unsold**, resale adds 100% of whatever it earns.

So the size of the benefit depends entirely on today's partial-sale and empty-seat rates — numbers the
department does not currently measure. **That is why the admin console's seat-km utilisation report is
not a nice-to-have; it is the instrument that sizes the prize.** Building the pricing model without
building the measurement would be assuming the answer.

---

## 6. Quotes: how a price becomes binding

```
POST /api/v1/quotes
{ "tripId": "...", "fromStation": "CMB", "toStation": "KDY", "classCode": "SECOND", "coachType": "RESERVED", "passengers": 1 }

200 OK
{
  "quoteId": "qte_01J...",
  "tripId": "...", "fromSeq": 1, "toSeq": 9, "distanceKm": 121.0,
  "currency": "LKR",
  "breakdown": [
    { "code": "BASE_BAND_1",  "label": "First 50 km @ 4.30",       "amountMinor": 21500 },
    { "code": "BASE_BAND_2",  "label": "Next 71 km @ 3.655",       "amountMinor": 25951 },
    { "code": "COACH_MULT",   "label": "Reserved coach ×1.00",     "amountMinor": 0 },
    { "code": "ROUNDING",     "label": "Rounded to nearest LKR 10", "amountMinor": -451 }
  ],
  "totalMinor": 47000,
  "ruleSetVersion": "2026.08.01",
  "issuedAt": "2026-08-01T09:14:22Z",
  "expiresAt": "2026-08-01T09:24:22Z",
  "signature": "v1:HMAC-SHA256:9f2c…"
}
```

Properties, each solving a specific real problem:

| Property | Problem it solves |
|---|---|
| **Server-computed** | The client never sends a price, so a tampered payload cannot underpay |
| **HMAC-signed** | booking-service verifies the quote locally — no HTTP call inside the booking transaction |
| **10-minute TTL** | A stale quote cannot be replayed after a fare change |
| **Itemised breakdown** | The passenger sees exactly why they are charged what they are charged |
| **`ruleSetVersion` recorded** | Any historical fare is reproducible months later for an audit or a complaint |
| **Bound to `(trip, fromSeq, toSeq, class, coach)`** | A cheap Fort→Kandy quote cannot be redeemed for a Fort→Badulla booking |

If the quote is missing, expired, or its signature fails, the booking is rejected `422
QUOTE_INVALID` with a fresh quote in the response so the UI can re-price in one step.

---

## 7. Configuration surface

Everything above is data. Nothing is a constant in code.

| Table | Contents |
|---|---|
| `fare_rule_set` | Version, effective from/to, currency, rounding increment, minimum fare |
| `fare_band` | Rule set, lower/upper km, rate per km |
| `fare_class_multiplier` | Class code → multiplier |
| `fare_coach_multiplier` | Coach type → multiplier |
| `fare_scenic_segment` | Rule set, from/to stop sequence, uplift |
| `fare_demand_tier` | Occupancy lower/upper bound → multiplier |
| `fare_advance_tier` | Days-ahead lower/upper bound → discount |

Rule sets are **effective-dated and immutable**: publishing new fares creates a new version rather than
mutating the old one, so historical quotes remain reproducible and a bad fare change can be rolled back
by changing an effective date.

---

## 8. Edge cases and how they are handled

| Case | Behaviour |
|---|---|
| Same origin and destination | `422 INVALID_JOURNEY_LEG` before pricing is attempted |
| Reversed leg (`to` before `from`) | `422` — the trip's stop order is the only ordering |
| Station not served by this trip | `422 STATION_NOT_ON_TRIP` |
| Distance below minimum fare threshold | `MINIMUM_FARE` applied |
| Fare rules change between quote and booking | Old quote honoured until TTL; after that, re-quote |
| Zero-distance route data error | Rejected by the `route_stop` monotonicity `CHECK`, never reaches pricing |
| Currency rounding | All arithmetic in **minor units (cents) as `BIGINT`**. Never floating point. `NUMERIC` for distances only |
| Multi-passenger booking | Fare computed once per segment, multiplied by passenger count, rounded once at the end |

**Money is never a `double`.** It is `BIGINT` minor units end to end — database, API, and JSON. This is
non-negotiable and is enforced by a static analysis rule.

---

## 9. Alternatives considered

| Alternative | Why not |
|---|---|
| **Flat per-km rate** | Simplest, and it is the fallback if the department rejects tapering. Rejected as the default because it overcharges long-haul passengers relative to current practice and abandons a well-established tariff convention |
| **Zone-based fares** (fare zones rather than distance) | Common in commuter networks and easy to explain, but coarse over a 292 km intercity route — a Nanu Oya→Ella passenger and a Nanu Oya→Badulla passenger would pay the same |
| **Station-pair fare matrix** | Maximum control, and how legacy railway systems actually work. Rejected: 25 stations = 300 pairs to maintain, 60 stations = 1,770, and extending the route becomes a data-entry project. The engine can *emit* such a matrix for printing, which is the useful half |
| **Full dynamic/ML yield pricing** | Highest revenue in theory. Rejected outright: unexplainable pricing is not acceptable for a public operator, and it would be politically fatal. The bounded, published demand tier is the defensible 80% |
| **Auction / bidding for scarce legs** | Interesting, wrong domain |

---

## 10. Testing

| Test | Assertion |
|---|---|
| `FareBandTest` | Table-driven, every band boundary and off-by-one (49/50/51 km, 149/150/151 km) |
| `FareAdditivityPropertyTest` | FARE-1 across all A<B<C on the seeded route |
| `FareMonotonicityPropertyTest` | Longer leg is never cheaper |
| `ScenicSurchargeTest` | Zero outside the scenic range; pro-rata on partial overlap; full on full overlap |
| `RoundingTest` | Rounding applied once, at the end; no intermediate rounding drift |
| `QuoteSignatureTest` | Tampered payload, expired quote, and leg-substitution attacks all rejected |
| `MoneyTypeArchTest` | Fails the build if any monetary field is `double`/`float` |

---

**Related:** [ADR-008](adr/ADR-008-signed-fare-quotes.md) · [ADR-010](adr/ADR-010-telescopic-fare-model.md) ·
[`13-extra-credit.md`](13-extra-credit.md)
