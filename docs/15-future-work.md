# 15 — Future Work

Ranked by value to the department, not by how interesting they are to build. Each entry states the
problem, a sketch of the approach, and the effort.

---

## Tier 1 — Directly extends the brief's thesis

### 1.1 Dynamic reserved/unreserved rebalancing ⭐ *the obvious next move*

**Problem.** The brief describes 3 reserved + 5 unreserved coaches, reserved under-occupied and
unreserved overcrowded. Segment booking fixes the *utilisation* of the three reserved coaches — but the
3:5 split itself was chosen under the old economics. If reserved seats now resell, reserved coaches earn
more per coach-km than they used to, and the optimal split has almost certainly moved.

**Approach.** Use the seat-km utilisation and revenue-per-segment data the admin console now collects to
model revenue and crowding under different splits. Start with a decision-support tool (*"on a Poya
Friday, 4:4 would have earned X% more and reduced unreserved density by Y"*), because a recommendation
backed by the department's own data is far more likely to be adopted than an automated reallocation.
Later, per-service composition planning by day type.

**Effort:** Medium. **Value: highest** — it is the second half of the problem the brief describes, and
the data to answer it now exists for the first time.

### 1.2 Overbooking with a per-segment no-show model

**Problem.** Some booked passengers do not travel. Today that inventory is lost twice — once by the
no-show, and once because there is no mechanism to resell it.

**Approach.** Estimate no-show probability per segment, day type, and lead time from historical data.
Allow controlled oversell within a confidence bound, with a defined denied-boarding policy (downgrade to
unreserved plus a fare refund, which is a far gentler remedy than an airline's). Requires 6–12 months of
data first — **building it now would be modelling a guess**, which is why it is not in the current scope.

**Effort:** Medium. **Value:** High, once data exists.

### 1.3 Group booking with adjacency optimisation

**Problem.** Four people travelling together currently get four seats, possibly scattered. Transactionally
this already works; it just is not *optimised*.

**Approach.** Extend `SeatSelectionStrategy` with a constraint solver over the coach grid: prefer
same-row, then adjacent-row, then same-coach — while still minimising fragmentation of the remaining free
stretches. The two objectives genuinely conflict, so it needs an explicit weighting the department can
tune.

**Effort:** Medium. **Value:** High — families are a large share of up-country traffic.

---

## Tier 2 — Production readiness

### 2.1 Real payment gateway
PayHere or LankaPay behind the existing `payment-service` port. The adapter shape, saga, and
reconciliation path already exist; this is integration plus merchant onboarding, not design.
**Effort:** Small–Medium.

### 2.2 CQRS availability projection
Currently availability is served from the write model, which is comfortably fast at this scale
(`docs/01` §5). If read volume grows 10×, add a denormalised projection built from
`SegmentBooked`/`SegmentReleased` — essentially the materialised matrix rejected as a *write* model in
README §5.1, used correctly as a *read* model. The events already exist, so the write path does not
change. **Effort:** Medium. **Trigger:** availability p95 > 150 ms sustained.

### 2.3 Fair-admission queue for peak releases
When Poya-weekend Ella tickets open, thousands converge on hundreds of seats. Today they contend
directly and most receive 409s. A pre-booking virtual queue (token, position, estimated wait) converts a
thundering herd into an orderly line and is far kinder than an implicit lottery.
**Effort:** Medium. **Value:** High on peak days, zero otherwise.

### 2.4 Ticket inspection
Offline-verifiable QR (signed payload with seat, leg and trip), a conductor app that works without
connectivity, and reconciliation on reconnect. **Effort:** Medium.

### 2.5 Notification transports
SMS (local provider), email, and push. The notification service and its contracts exist; only the
transports are stubbed. **Effort:** Small.

---

## Tier 3 — Product breadth

| Item | Note | Effort |
|---|---|---|
| Multi-leg itineraries with connections | Book across two trains with a guaranteed-connection policy | Large |
| Season tickets and concessions | Student, senior, military — needs entitlement verification | Medium |
| Refund and change policy engine | Rules-based cancellation fees by lead time; partial-leg changes | Medium |
| Unreserved capacity management | Segment-based headcount caps to relieve crowding directly | Medium |
| Native mobile apps | Only if PWA proves insufficient | Large |
| Loyalty | Frequent-traveller benefits | Medium |

---

## Tier 4 — Engineering

| Item | Rationale | Effort |
|---|---|---|
| **Bounded best-fit waitlist matching** | Recover some of the utilisation FIFO leaves on the table without visible queue-jumping — scan only the oldest N entries, or prefer better fits within the same arrival minute | Small |
| Extract a scheduling service | Only if trip planning grows into rostering and crew management | Large |
| Multi-region read replicas | Only if tourist traffic from abroad justifies it | Medium |
| Chaos engineering in staging | Automated fault injection against the failure-mode table in `docs/04` §8 | Medium |
| Formal verification of the interval invariant | TLA+ specification of the booking state machine. Arguably unnecessary given the constraint enforces it, but it would be a genuinely rigorous proof of the state transitions | Medium |
| Blue/green database migration tooling | Currently expand/contract by discipline; tooling would make it enforced | Medium |

---

## Deliberately *not* on this list

| Idea | Why not |
|---|---|
| ML-driven dynamic pricing | Unexplainable pricing is undeployable for a public operator. The bounded, published demand tier is the defensible version, and it is already built |
| Auction/bidding for scarce legs | Wrong domain. Public transport is not a resale market, and building one would be a policy decision far above an engineering choice |
| Sharding the booking database | The scale analysis (`docs/01` §5) says a single instance has years of headroom. Sharding a system with a cross-row invariant would be a large amount of complexity purchased to solve a problem that does not exist |
| Blockchain ticketing | No |

---

**Related:** [`14-challenges-and-tradeoffs.md`](14-challenges-and-tradeoffs.md) ·
[`13-extra-credit.md`](13-extra-credit.md)
