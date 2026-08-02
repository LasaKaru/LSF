# 13 — Extra Credit Features

Each feature is documented as **Problem → Solution → Design → Trade-offs**, as the brief requests.

Sequencing note: none of this was started until the core was complete and the concurrency suite was
green. The brief is explicit that a focused core beats a long list of extras, and the plan in
[`SPRINT_PLAN.md`](../SPRINT_PLAN.md) enforced that with a hard checkpoint.

---

## 1. Seat map visualisation

### Problem
A list of seat codes (`3A, 3C, 7B…`) is not how anyone chooses a seat. Passengers want a window, the
correct side for the view on the up-country climb, or a seat next to the person they are travelling with.
More specifically for *this* system: in segment-based inventory a seat is not simply free or taken — it
can be free for *part* of your journey, and no list representation can express that.

### Solution
A per-coach SVG grid, coloured for the **selected leg**, with four meaningful states:

| State | Colour | Meaning |
|---|---|---|
| Available | Green | Free for your entire leg |
| Taken | Grey | Occupied for some part of your leg |
| **Partially available** | **Amber** | Free for *part* of your leg — the state that only exists here |
| Selected | Blue | Your choice |

Hovering any seat shows a miniature route bar with the stretches on which it is occupied.

### Design

**One request, not N.** The seat-map endpoint returns occupancy *ranges* per seat rather than a boolean:

```json
{ "seatId": "…", "label": "3A", "occupied": [[1,9],[15,25]], "availableForRequestedLeg": false }
```

This single payload drives the tri-state colouring, the hover detail and the "shorten your search"
suggestion without a second round trip.

**Layout is data.** The component is a pure function of `(layout, occupancy)`, where `layout` is the
`coach_layout.grid` JSON. A 2+2 second-class coach, a 2+1 observation saloon and a 3+2 commuter coach are
three database rows — **adding a coach type requires zero frontend changes.** This is the UI half of the
brief's configurability requirement.

**Privacy by construction.** The endpoint returns ranges only. No names, no references, nothing
identifying — so the UI cannot leak passenger data even if a future developer tries to display it.

**Accessibility.** `role="grid"` with roving tabindex and arrow-key navigation; `aria-label` on each seat
carrying every fact the colour conveys; a list-view toggle offering identical functionality without
spatial navigation. An inaccessible seat map is a broken seat map on a public service.

### Trade-offs
Returning occupancy ranges is a slightly larger payload than booleans (~8 KB for 180 seats, gzipped to
~1.5 KB) and reveals coarse booking patterns. Both are acceptable: the payload is small, and occupancy
patterns are visible to anyone who walks through the train.

---

## 2. Waitlisting for fully booked segments

### Problem
On peak dates every seat is sold for the popular Nanu Oya → Ella stretch. Cancellations and expired
holds *do* free inventory — often several per hour — but it is invisible. Passengers either refresh
obsessively or give up. The department loses the sale, and the seat may go empty anyway.

### Solution
A FIFO waitlist per `(trip, leg, class)`. When any segment is released, the oldest compatible entry is
automatically offered a real hold and notified.

### Design

**Event-driven, not polled.** `waitlist-service` consumes `SegmentReleased`, which booking-service emits
on cancellation, hold expiry, and departmental block release. Promotion happens within seconds instead of
on the next poll cycle.

**Fit, not equality.** An entry matches if its leg fits *inside* the released leg:

```
released = [9,25)  →  matches [9,25), [9,15), [12,20), [15,25)
                   →  does not match [1,15)  (extends before the release)
```

**The offer is a real hold.** The matcher creates an ordinary `HELD` booking with a 30-minute TTL,
subject to the same exclusion constraint as any other booking. Nothing about the waitlist bypasses the
invariant, which means the waitlist cannot introduce a double-sale even if its matching logic is wrong.

**Idempotent.** Kafka is at-least-once, so the same `SegmentReleased` may arrive twice. The consumer
dedupes on `eventId` in `processed_event`; a duplicate cannot promote two people onto one seat.

**Missing one offer does not cost your place.** If an offer lapses, the entry returns to `WAITING` at its
**original position**. Punishing a passenger who was asleep when the notification fired is not fair
queueing.

**Fairness over utilisation — deliberately.** A greedy matcher would scan the whole queue for the entry
that best *fills* the released stretch, maximising seat-km. We do not. We take the oldest entry that
fits. This leaves some utilisation on the table, and it is the right call: a passenger who has watched
three later arrivals promoted ahead of them has been treated unfairly by any reasonable standard, and
"our optimiser preferred their journey shape" is not an answer a public operator can give. The trade-off
is stated here rather than buried, because it is a values decision disguised as an algorithm choice.

### Trade-offs
FIFO leaves utilisation unclaimed. A future *bounded* compromise — scan only the oldest N entries, or
prefer better fits only within the same arrival minute — would recover much of it without visible
queue-jumping. Documented in [`15-future-work.md`](15-future-work.md).

---

## 3. Admin view — occupancy and revenue

### Problem
The brief says *"Leadership believes there's revenue being left on the table."* Believes. The department
has no instrument that shows where the train empties out, how much inventory evaporates at departure, or
what resale actually earns. Without measurement, the change cannot be justified, tuned, or defended.

### Solution
An admin console driven by a CQRS read model:

1. **Segment occupancy heatmap** — stops × coaches, colour = occupancy. The visual answer to *"where does
   the train empty out?"*
2. **Seat-km utilisation** — `sold seat-km ÷ available seat-km`.
3. **Revenue by segment** — where along the line the money is earned.
4. **Resale uplift** — actual revenue vs the whole-journey-only counterfactual.
5. **Segments-per-seat distribution** — how often seats actually resell.

### Design

**Seat-km is the correct metric, and this is the point of the whole feature.** Conventional load factor —
seats sold ÷ seats available — reports a train as 100% full when every seat was sold for a tenth of the
route. That metric is precisely what hid the problem in the first place. Seat-km utilisation:

```
utilisation = Σ(segment distance × seats sold on it) / Σ(total distance × total seats)
```

A train that reports 100% seats sold but 34% seat-km utilisation is exactly the pathology the brief
describes, made numeric.

**Resale uplift is a counterfactual, and is labelled as one.** For each trip we compute what the same
passengers would have paid under whole-journey-only ticketing (each occupied seat sold once at the
full-route reserved fare) and compare it with actual revenue. It is an estimate with stated assumptions —
presenting a modelled figure as measured fact would be dishonest, and would not survive contact with a
finance department.

**Read model, never the write path.** Projections are built from booking events into `reporting_db`;
heavy aggregates are materialised views refreshed on a schedule. An analyst running a year-long query can
never slow down a passenger trying to book.

### Trade-offs
The read model is eventually consistent (seconds behind). For occupancy analytics that is correct —
and a live-consistent dashboard would couple reporting to the transactional path, which is exactly what
we are avoiding. Where a live figure is genuinely needed (current holds), the console queries
booking-service directly and labels it as live.

---

## 4. Clearer handling of booking conflicts in the UI

### Problem
Availability is a snapshot. Between rendering it and the passenger clicking "Book", someone else can take
the seat. The naive outcome is an error dialog, a lost selection, and a restart — with the passenger's
carefully entered details gone.

### Solution
Three layers: live availability, an informative conflict response, and one-click recovery.

### Design

**Live availability (SSE).** The seat map subscribes to `/trips/{id}/availability/stream`. Seats grey out
as others book them, with a brief highlight so the change is perceptible. Falls back to ETag polling. The
stream is a hint — it never gates submission.

**Rich conflict responses.** The 409 carries machine-readable recovery data: the conflicting seats, their
occupied ranges, ranked alternatives matched to the passenger's stated preferences, and a fresh
availability URL. The UI's error handling is data-driven rather than string-matching on a message.

**One-click recovery.** Lost seat flashes red → settles grey. Non-modal banner: *"Seat 3A was just taken.
Seat 3C is also a window seat at the same fare."* One click re-submits. **Passenger details are
preserved.** Target cost of losing a race: one click, about three seconds.

**Honest optimism.** Seat selection is optimistic; fare display is optimistic and reconciled on hold;
**booking submission is never optimistic.** Optimistic UI is appropriate when the client can predict the
outcome. A booking outcome depends on other people, so pretending to know it is a lie the UI will have to
retract.

**Hold countdown.** A live timer on the hold, escalating at 2 minutes. On expiry the UI explains what
happened and offers to re-select the same seat if it is still free — rather than failing silently at
payment.

**Double-submit protection.** The `Idempotency-Key` is generated once when the form is rendered and
reused for every retry of that attempt.

### Trade-offs
SSE adds a persistent connection per active seat-map viewer. Bounded by trip-scoped fan-out and short
session lengths; the polling fallback exists for constrained environments.

---

## 5. Fare logic beyond simple distance-based pricing

Fully documented in [`05-fare-engine.md`](05-fare-engine.md). Summary of what goes beyond `rate × km`:

| Feature | Problem it solves |
|---|---|
| **Telescopic bands** | Marginal rate tapers with distance, matching real tariff practice and protecting long-haul affordability |
| **Additivity guard (FARE-1)** | Property-tested proof that splitting a ticket is never cheaper than buying it whole — closing a revenue leak that telescopic pricing would otherwise open |
| **Scenic surcharge** | Nanu Oya → Ella premium, charged **only on kilometres actually travelled** — a Fort→Kandy passenger pays nothing for a view they never see |
| **Bounded demand multiplier** | Yield management driven by **segment** occupancy — a capability that literally could not exist without segment inventory. Bounded `[0.90, 1.40]` and published, because unexplainable pricing is undeployable for a public operator |
| **Advance-purchase discount** | Shifts demand earlier, improving both consist planning and the occupancy signal |
| **Signed, expiring quotes** | The price the passenger saw is the price they are charged; tampering is impossible; historical fares are reproducible for audit |
| **Removal of the reserved surcharge** | The brief's actual grievance. The ~2× multiplier existed to cover the empty seat; the seat now resells, so the multiplier goes |

The last row is the one that matters: the extra credit here is not a pricing gimmick, it is the fix to
the unfairness the brief describes.

---

## 6. Trilingual interface (own initiative)

### Problem
Sri Lanka Railways serves Sinhala, Tamil and English speakers. An English-only booking system is a
smaller product than the one the department needs, and language access on a state service is not a
nice-to-have.

### Solution
Station names stored in all three scripts and served per `Accept-Language`; UI strings in all three;
runtime switching.

### Design
Translations for **data** (station names) live in the database beside the data, not in the frontend
bundle — so adding a station adds its names, and no deployment is required. UI strings use standard
i18n resource files. Fonts are subset per script and lazily loaded, so an English user never downloads
Sinhala glyphs.

### Trade-offs
Low cost, and it makes the seed data slightly larger. The alternative was worse.

---

## 7. Explicitly not built

Recorded so absence reads as a decision rather than an oversight:

| Not built | Why |
|---|---|
| Real payment gateway (PayHere / LankaPay) | Requires merchant credentials and a sandbox account; a mock with the correct port/adapter shape proves the design without pretending to be integrated |
| SMS notifications | Provider account required; the notification service and its contract exist, the transport is stubbed |
| Group booking with adjacency optimisation | Transactionally supported already; the optimiser is a genuine piece of work and was out of time budget |
| Overbooking model | Needs historical no-show data the system has not collected yet — building it now would be modelling a guess |
| Mobile apps | Responsive web covers the requirement |

Each is in [`15-future-work.md`](15-future-work.md) with a sketch of the approach.

---

**Related:** [`05-fare-engine.md`](05-fare-engine.md) · [`09-frontend-design.md`](09-frontend-design.md) ·
[`15-future-work.md`](15-future-work.md)
