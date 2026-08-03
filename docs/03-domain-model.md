# 03 — Domain Model

---

## 1. Bounded contexts

| Context | Core question it answers | Aggregate roots | Service |
|---|---|---|---|
| **Network & Schedule** | What stations exist, in what order, on what trains, on what days? | `Route`, `Train`, `Trip` | catalog-service |
| **Seat Inventory & Booking** ★ | Is `(seat, leg)` free, and who holds it? | `Trip` (inventory), `Booking` | booking-service |
| **Pricing** | What does this leg cost, and why? | `FareRuleSet`, `Quote` | pricing-service |
| **Payment** | Has money moved? | `PaymentIntent` | payment-mock-service |
| **Waitlist** | Who is next for this leg? | `WaitlistEntry` | waitlist-service |
| **Insight** | How full is the train and how much did it earn? | read models only | admin-reporting-service |

The context map is deliberately **customer/supplier with anti-corruption layers**: booking-service
consumes `TripPublished` and translates it into its *own* snapshot model rather than importing catalog's
schema. If catalog restructures how a route is stored, booking is unaffected.

---

## 2. Ubiquitous language

Precise terms, used identically in code, database, API and this documentation.

| Term | Definition | Not to be confused with |
|---|---|---|
| **Station** | A physical stopping place with a code, e.g. `KDY` | A stop |
| **Route** | An ordered list of stations, e.g. Colombo Fort ⇄ Badulla | A trip |
| **Route Stop** | A station's position and cumulative distance on a route | A trip stop |
| **Train** | A named service pattern, e.g. *1005 Podi Menike* | A trip |
| **Trip** | One train on one calendar date, in one direction. **The unit that inventory belongs to.** | A train |
| **Trip Stop** | A snapshot of a route stop for a specific trip, carrying `stop_sequence` | A route stop |
| **Stop Sequence** | Dense ascending integer position of a stop within a trip | Station ID, km marker |
| **Coach** | A carriage in the consist, with a type and a layout | A class |
| **Coach Layout** | The seating grid template: rows, columns, aisle, labels | A coach |
| **Seat** | A numbered position in a coach on a trip | A booking |
| **Leg** | A half-open range `[from_seq, to_seq)` over a trip's stops. **The unit of inventory.** | A journey |
| **Booking Segment** | One `(seat, leg)` sale. The row the exclusion constraint guards | A booking |
| **Booking** | A passenger's order: one or more segments, one payment, one reference | A segment |
| **Hold** | A booking in `HELD` status with a TTL; occupies inventory | A reservation |
| **Quote** | A signed, expiring priced offer for a specific leg and class | A fare |
| **Resale** | Selling a seat again on a leg vacated by a previous passenger. **The point of the project.** | Overbooking |
| **Seat-km** | Distance × seats, the correct unit for utilisation | Seats sold |

Rule enforced in review: **if a concept is not in this table, it does not appear in a class name.**

---

## 3. Aggregates and invariants

### 3.1 `Trip` (inventory aggregate, booking-service)

Owns the snapshotted topology and the seat set.

```
Trip
├── id, trainId, serviceDate, direction, status(SCHEDULED|PUBLISHED|DEPARTED|CLOSED)
├── TripStop[]  { stopSequence, stationId, stationCode, distanceKm, sched arr/dep }
└── TripCoach[] { coachNumber, coachType, classCode, isReservable, positionIndex, layout }
      └── TripSeat[] { seatLabel, rowIndex, colIndex, isWindow, isAisle, facing }
```

Invariants:
- **TRIP-1** `stopSequence` is dense, ascending, starting at 1.
- **TRIP-2** `distanceKm` is strictly monotonically increasing with `stopSequence`.
- **TRIP-3** Topology is **immutable once `PUBLISHED`**. Changes require a new trip version.
- **TRIP-4** Seat labels are unique within a coach; coach numbers unique within a trip.
- **TRIP-5** Only coaches where `isReservable` participate in seat-level booking.

TRIP-3 is the one that matters: it is what makes a booking's `from_seq`/`to_seq` mean the same thing
forever.

### 3.2 `Booking` (transactional aggregate, booking-service)

```
Booking
├── id, reference (human-readable, e.g. YTH-7K2QD9), tripId
├── status(HELD|CONFIRMED|CANCELLED|EXPIRED), expiresAt, confirmedAt
├── contact { name, email, phone }, passengers[]
├── quoteId, quoteVersion, totalFareMinor, currency
└── BookingSegment[] { seatId, fromSeq, toSeq, distanceKm, fareMinor, status }
```

Invariants:
- **BKG-1** A booking has ≥ 1 segment.
- **BKG-2** Every segment satisfies `fromSeq < toSeq` and both are within the trip's stop range.
- **BKG-3** All segments belong to the same trip.
- **BKG-4 (INV-1)** No two *active* segments overlap on the same `(trip, seat)` — **enforced by the
  database exclusion constraint** (see `docs/04`).
- **BKG-5** `HELD` ⇒ `expiresAt` is set; `CONFIRMED` ⇒ `expiresAt` is cleared and `confirmedAt` set.
- **BKG-6** `totalFareMinor` = Σ segment fares; recomputed server-side, never trusted from the client.
- **BKG-7** Status transitions follow §4 strictly; illegal transitions throw and are logged as alerts.
- **BKG-8** A booking is only `CONFIRMED` after a matching `PaymentAuthorised`.

BKG-4 is the only invariant that spans *rows the aggregate does not own* (other passengers' segments),
which is precisely why it cannot live in application code and must be a database constraint.

### 3.3 `Quote` (pricing-service)

```
Quote { id, tripId, fromSeq, toSeq, classCode, coachType, distanceKm,
        breakdown[], totalMinor, ruleSetVersion, issuedAt, expiresAt, signature }
```

- **QUO-1** Immutable once issued.
- **QUO-2** `signature = HMAC-SHA256(payload, QUOTE_SIGNING_KEY)`.
- **QUO-3** Valid for `QUOTE_TTL` (default 600 s).
- **QUO-4** Records `ruleSetVersion` so any historical price is reproducible and auditable.

QUO-4 is a regulatory requirement in disguise: a state operator must be able to answer "why was I
charged this?" months later.

### 3.4 `WaitlistEntry` (waitlist-service)

```
WaitlistEntry { id, tripId, fromSeq, toSeq, classCode, contact,
                status(WAITING|OFFERED|CONVERTED|EXPIRED|CANCELLED),
                position, createdAt, offeredAt, offerExpiresAt, offeredBookingId }
```

- **WL-1** FIFO by `createdAt` within `(tripId, classCode)`.
- **WL-2** An entry is only offered if its leg **fits inside** a released leg
  (`released.from ≤ entry.from ∧ entry.to ≤ released.to`).
- **WL-3** One live offer per entry; the offer is a real `HELD` booking with its own TTL.
- **WL-4** A lapsed offer returns the entry to `WAITING` at its **original** position — a passenger who
  misses one notification does not go to the back of the queue.

---

## 4. State machines

### Booking

| From | Event | To | Side effects |
|---|---|---|---|
| — | `create` | `HELD` | Segments inserted (constraint checked); `BookingHeld` |
| `HELD` | `paymentAuthorised` | `CONFIRMED` | `BookingConfirmed`; ticket issued; notification |
| `HELD` | `paymentFailed` | `EXPIRED` | Segments released; `SegmentReleased` |
| `HELD` | `expire` (TTL) | `EXPIRED` | Segments released; `HoldExpired` + `SegmentReleased` |
| `HELD` | `cancel` | `CANCELLED` | Segments released; `SegmentReleased` |
| `CONFIRMED` | `cancel` | `CANCELLED` | Segments released; refund initiated; `SegmentReleased` |
| `CONFIRMED` | `depart` | `CONFIRMED` | Frozen; no further transitions |
| `EXPIRED` / `CANCELLED` | any | — | Terminal. Illegal transitions raise and alert |

Enforced by an explicit `BookingStateMachine` with a transition table, not by scattered `if` statements —
so the legal transitions are readable in one place and unit-testable as a table.

### Trip

`DRAFT → SCHEDULED → PUBLISHED → DEPARTED → CLOSED`

Bookings are only accepted in `PUBLISHED`, and only until `BOOKING_CUTOFF_MINUTES` before departure.
`DEPARTED` freezes inventory; `CLOSED` archives it.

---

## 5. Key domain services

| Service | Responsibility | Why it is a domain service and not a method |
|---|---|---|
| `SegmentAvailabilityService` | Which seats are free for a leg | Spans many aggregates (all bookings on a trip) |
| `BookingAssembler` | Turn a request + quote into a valid `Booking` | Coordinates validation, pricing verification, seat selection |
| `SeatSelectionStrategy` | Auto-assign a seat (window preference, group adjacency, minimise fragmentation) | Pluggable policy — see below |
| `HoldExpiryService` | Sweep expired holds, emit releases | Cross-aggregate, scheduled |
| `WaitlistMatcher` | Match released legs to waiting entries | Cross-aggregate, event-driven |
| `FareCalculator` | Apply the banded fare rules | Pure function over rules + leg |

### Seat selection is a strategy, not a rule

When a passenger does not pick a specific seat, the auto-assigner is pluggable:

- `FirstAvailable` — default, simplest.
- `PreferenceMatching` — window/aisle/forward-facing.
- `GroupAdjacency` — keep a party together.
- `FragmentationMinimising` — ⭐ the interesting one. Prefer the seat whose *remaining* free stretches
  stay largest, i.e. avoid punching a small hole in the middle of an otherwise empty seat. This is a
  best-fit bin-packing heuristic and it directly raises seat-km utilisation. It is a heuristic, not an
  optimum: true optimal packing is NP-hard, and offline optimisation is worthless anyway because
  bookings arrive online.

Documented here because "which seat do we give them?" looks trivial and is actually the lever that
determines how much of the resale revenue the department actually captures.

---

## 6. Domain events

| Event | Payload highlights | Why it exists |
|---|---|---|
| `TripPublished` | trip, stops, coaches, seats | booking snapshots topology |
| `BookingHeld` | bookingId, tripId, segments, expiresAt, totalFare | payment starts; reporting |
| `BookingConfirmed` | bookingId, reference, segments, fare, passengers | ticket, notification, revenue |
| `BookingCancelled` | bookingId, segments, refundable | waitlist, refund, reporting |
| `HoldExpired` | bookingId, segments | waitlist, funnel analytics |
| `SegmentReleased` | tripId, seatId, fromSeq, toSeq | **the waitlist trigger** |
| `SegmentOccupancyChanged` | tripId, segment occupancy ratios | pricing demand tier |
| `WaitlistPromoted` | entryId, bookingId, offerExpiresAt | notification |

All events carry `eventId`, `occurredAt`, `correlationId`, `version`, and are keyed by `tripId` for
per-trip ordering.

---

## 7. What the model deliberately does not do

| Not modelled | Why |
|---|---|
| Seat *reclining* / orientation changes at terminus | Cosmetic; `facing` is stored but not booked against |
| Physical coach identity across trips | A trip snapshots its own seats; through-coaches compose as two trips |
| Passenger identity/accounts | Contact details only; a full identity context is out of scope |
| Fare *products* (return, season, concession) | Deliberately deferred; the `Quote` shape has room for a `productCode` |
| Connections between trains | Single-trip bookings only |

---

**Next:** [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) ·
[`07-data-model.md`](07-data-model.md)
