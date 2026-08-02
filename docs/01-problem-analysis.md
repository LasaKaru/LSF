# 01 — Problem Analysis

> Read this first if you want to understand *why* the system is shaped the way it is.
> The engineering follows from the domain, not the other way round.

---

## 1. The domain in plain language

The Colombo Fort ⇄ Badulla line is a ~292 km up-country route operated with a mixed consist:

| Coach type | Count | Allocation model | Current problem |
|---|---|---|---|
| Reserved | 3 | Seat assigned in advance, whole journey | Under-occupied for long stretches |
| Unreserved | 5 | First-come-first-served, no seat | Frequently overcrowded |

The pathology is a **mismatch between the unit of sale and the unit of consumption**.

- **Unit of consumption:** a passenger consumes a seat over a *sub-interval* of the route. Most
  passengers alight well before Badulla — commuters at Gampaha and Polgahawela, pilgrims and domestic
  travellers at Kandy/Peradeniya, tourists at Nanu Oya and Ella.
- **Unit of sale:** the whole seat, for the whole route, to one person.

Every time those two disagree, inventory is destroyed. A Fort→Kandy passenger consumes 121 of 292
seat-km but is sold all 292. The remaining **171 seat-km evaporate at departure** — they cannot be
recovered, exactly like an unsold airline seat at gate close.

### The pricing consequence

The department's stated reasoning — the reserved partial-leg fare is ~2× the unreserved fare because
"the fare has to cover the seat sitting empty for the rest of the journey" — is internally consistent
*given the constraint*. It is a rational response to non-resellable inventory.

But it produces two distortions:

1. **Cross-subsidy without consent.** The Fort→Kandy passenger pays for 171 km they do not travel and
   nobody else uses. There is no economic activity behind that charge; it is pure deadweight.
2. **Demand displacement.** Because the reserved partial fare is punitive, short-leg passengers are
   pushed into unreserved coaches, which is *why* those five coaches are overcrowded. The pricing rule
   designed to protect reserved revenue is actively manufacturing the crowding problem next door.

**Therefore:** this is not primarily a "build a booking system" problem. It is an **inventory
granularity** problem, and the fare fix and the crowding fix are both downstream of it. That framing
drives every subsequent decision.

---

## 2. Reframing as a computer science problem

Strip the trains away:

> Given a resource (seat) and a totally ordered set of points (stops), sell **half-open intervals** over
> those points such that no two sold intervals on the same resource overlap. Do this correctly under
> concurrency, across horizontally scaled stateless replicas, with money attached.

This is **interval scheduling with resource assignment**, and it is the same problem as:

| Domain | Resource | Interval |
|---|---|---|
| Hotels | Room | Nights `[check-in, check-out)` |
| Airlines (multi-leg) | Seat | Flight legs on a through service |
| Meeting rooms | Room | `[start, end)` time |
| Car rental | Vehicle | Rental period |
| **This system** | **Seat on a trip** | **`[from_stop, to_stop)`** |

Recognising the isomorphism matters because the hotel case tells us two things immediately:

1. The **half-open** convention is not a stylistic choice. Hotels do not double-book a room when one
   guest checks out on the 5th and another checks in on the 5th. Adjacency must not be overlap.
2. There is a **well-known correct primitive** for this in PostgreSQL — range types plus GiST exclusion
   constraints — designed for precisely this class of problem. We should use it rather than reinvent it.

One notable difference from hotels: hotels need a **turnaround buffer** (cleaning). Trains do not — the
alighting passenger and the boarding passenger swap at the same station stop. If the department ever
wanted a buffer (e.g. a seat cannot be resold for one stop after a long-haul passenger leaves), it is a
one-line change to how the range is constructed, and the doc notes where.

---

## 3. Requirement decomposition

### 3.1 Explicit requirements (from the brief)

| # | Requirement | Where it is addressed |
|---|---|---|
| R1 | A seat vacated partway becomes available to someone else | §4.1 README; `docs/04` |
| R2 | Each passenger charged only for distance actually travelled | `docs/05-fare-engine.md` |
| R3 | Model segment occupancy — and justify the model | `docs/03`, `docs/04`, ADR-001 |
| R4 | Calculate fares | `docs/05`, ADR-010 |
| R5 | Guarantee correctness under concurrent overlapping/adjacent bookings | `docs/04`, ADR-002 |
| R6 | API managing stations, seats, bookings | `docs/06-api-contract.md` |
| R7 | Correct handling of concurrent attempts on the same seat | `docs/04`, `docs/10` |
| R8 | Frontend: select origin/destination, see leg-specific availability, book | `docs/09-frontend-design.md` |
| R9 | Production-grade, not a throwaway PoC | Whole repository; `docs/11`, `docs/12` |
| R10 | Coaches, seats-per-coach, stations configurable | `docs/17-configuration.md`, ADR-005 |
| R11 | One-command clean-machine startup | README §3; `docs/08` |
| R12 | Version control with visible progression | README §Commit history; `PROJECT_PLAN.md` |
| R13 | No committed secrets | `docs/11-security-and-secrets.md` |
| R14 | README with decisions, alternatives, challenges, extras | `README.md` |

### 3.2 Implicit requirements (inferred, and worth stating)

| # | Inferred requirement | Why I inferred it |
|---|---|---|
| I1 | The system must be **defensible to a public body** — fares must be explainable and auditable | It is a state railway; a black-box yield algorithm is not deployable |
| I2 | **Never cancel a confirmed booking** to resolve an internal inconsistency | Public transport; a confirmed ticket is a promise. Drives §4.3 of README |
| I3 | Must work on **poor mobile connectivity** | Sri Lankan 3G; drives idempotency, small payloads, optimistic UI |
| I4 | Must support **Sinhala, Tamil, English** | Statutory language policy; low cost to honour |
| I5 | Route and consist **will** change | The brief says so explicitly; drives snapshotting and config-as-data |
| I6 | Reviewer will **extend it live** | The brief says so; drives clean seams, ADRs, and `docs/16` |

### 3.3 Explicitly out of scope

Stated so that absence reads as a decision rather than an omission:

- Real payment gateway integration (a mock service with the correct port/adapter shape is provided).
- Ticket inspection / on-board validation hardware.
- Season tickets, concessions, student/military fares, group discounts.
- Multi-train itineraries with connections (single-trip bookings only).
- Refund and rebooking policy engine (cancellation releases inventory; the money side is stubbed).
- Real-time train tracking / delay propagation.
- Unreserved-coach capacity management beyond a headcount counter.

---

## 4. Assumptions

Every assumption is a question I would ask the department. Recorded so that they can be corrected cheaply.

| # | Assumption | Risk if wrong | Mitigation built in |
|---|---|---|---|
| A1 | Stop order on a trip is fixed and known at booking time | Medium | Trip topology is snapshotted; re-publish handles changes |
| A2 | A passenger occupies exactly one seat for one contiguous stretch | Low | Multi-segment itineraries on one seat = two booking segments; already supported |
| A3 | Seats cannot be moved mid-journey by staff | Low | Would be modelled as two segments on different seats |
| A4 | Distances are cumulative and monotonic from origin | Low | `CHECK` constraint enforces monotonicity on `route_stop` |
| A5 | Fares are distance-based with class/coach modifiers | Medium | Fare rules are data; engine is strategy-based |
| A6 | Reserved coaches require a seat; unreserved do not | Low | Coach type drives allocation strategy |
| A7 | Same-day cancellation releases inventory immediately | Medium | Configurable cancellation cut-off |
| A8 | Overbooking is not permitted | Low | Would be a future extension with a per-segment no-show model |
| A9 | The Kandy stop appears on the Badulla service | **Worth flagging** | Operationally, up-country services run via Peradeniya Junction and Kandy is a branch terminus. The brief uses Kandy as its example, so seed data includes it. The model is indifferent — it is just an ordered stop list — but real deployment must use the actual SLR stopping pattern. |
| A10 | Seed distances/fares are illustrative | Low | Flagged in README and seed file header |

---

## 5. Scale estimation

Sizing decisions should be based on numbers, even rough ones.

```
Stops on route (seed / full):        25 major halts / ~60+ including minor
Segments per trip:                   24 / ~60
Reserved coaches:                    3
Seats per reserved coach:            ~60 (2+2 layout, 15 rows)
Reserved seats per trip:             ~180
Trips per day (both directions):     ~6
Booking horizon:                     30 days
```

Derived:

```
Active trips at any time            ≈ 6 × 30           = 180
Seat-inventory rows (snapshot)      ≈ 180 × 180        = 32,400
Theoretical max booking segments    ≈ 180 × 180 × 3    ≈ 97,000   (assume ~3 sales per seat)
Bookings per year                   ≈ 6 × 180 × 3 × 365 ≈ 1.2M
```

**Conclusion: this is a small-data problem.** A single well-indexed PostgreSQL instance handles it with
enormous headroom. That conclusion is load-bearing — it is why §5.6 of the README defers CQRS, why we do
not shard, and why we can afford a strongly consistent write path. The interesting engineering is
**contention**, not volume: on the morning that Poya-weekend Ella tickets open, thousands of users
converge on a few hundred seats in seconds. Optimising for throughput would be solving the wrong problem;
optimising for *correct behaviour under a thundering herd on a narrow key range* is the right one.

---

## 6. Success criteria

How I would judge whether this succeeded, in the department's terms rather than mine:

| Metric | Baseline (today) | Target |
|---|---|---|
| Seat-km utilisation, reserved coaches | Unknown — not measured | Measured, and > 65% |
| Revenue per reserved coach per trip | Fixed by whole-route pricing | +15–25% via resale |
| Fare for a short reserved leg | ~2× unreserved | ≤ 1.25× unreserved |
| Unreserved coach crowding | Chronic | Reduced as short legs migrate to reserved |
| Double-sold seat incidents | N/A | **Zero** — enforced by constraint, not policy |

The first row is the quietest and most important: today the department *cannot see* the problem. The
admin console (extra credit §11.3) exists to make the loss visible before it makes it smaller.

---

**Next:** [`02-architecture.md`](02-architecture.md)
