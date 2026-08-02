# 10 — Testing Strategy

---

## 1. What actually needs proving

Most of this system is ordinary CRUD that ordinary tests cover adequately. Exactly one property is both
**critical** and **hard to verify by inspection**:

> **INV-1** — no two active booking segments overlap on the same `(trip, seat)`, under arbitrary
> concurrency, across replicas.

Test effort is allocated accordingly. The pyramid is conventional; the *interesting* tests are the
concurrency ones, and they are treated as first-class blocking CI gates rather than as a nice extra.

```
                    ╱╲          E2E (Playwright)              ~15 tests
                   ╱  ╲         incl. 2-context seat race
                  ╱────╲        Contract (OpenAPI, Pact)      ~25
                 ╱      ╲       Integration (Testcontainers)  ~80
                ╱  ★★★   ╲      ★ CONCURRENCY & PROPERTY      ~15  ← the point
               ╱──────────╲     Unit                          ~350
```

---

## 2. Unit tests

Fast, no I/O. The ones worth naming:

**`IntervalTest`** — the complete overlap truth table from `docs/04` §3.1, including adjacency
(`[1,9)` vs `[9,25)` ⇒ **false**), containment, identity, degenerate `[9,9)` and inverted `[9,1)`. Written
as a parameterised table so a new case is one line.

**`BookingStateMachineTest`** — every legal transition, and an exhaustive check that every *illegal*
transition throws. Generated from the transition table so no combination is silently untested.

**`FareBandTest`** — band boundaries and off-by-ones (49/50/51 km, 149/150/151 km), minimum fare,
rounding applied exactly once.

**`SeatSelectionStrategyTest`** — fragmentation-minimising assignment prefers seats that keep the largest
contiguous free stretches.

---

## 3. Integration tests (Testcontainers)

Real PostgreSQL 16, real Flyway migrations, real DDL. **Never H2 or an in-memory substitute** — the entire
correctness argument rests on a GiST exclusion constraint that only PostgreSQL has. Testing against H2
would test a different system and pass for the wrong reason.

**`SegmentExclusionConstraintIT`** — the constraint itself, directly:

```java
@Test void adjacentSegmentsOnSameSeatAreAllowed() {
    insertSegment(trip, seat3A, 1, 9);       // Fort → Kandy
    assertThatCode(() -> insertSegment(trip, seat3A, 9, 25))   // Kandy → Badulla
        .doesNotThrowAnyException();          // ★ the brief's core requirement
}

@Test void overlappingSegmentsOnSameSeatAreRejected() {
    insertSegment(trip, seat3A, 1, 9);
    assertThatThrownBy(() -> insertSegment(trip, seat3A, 5, 15))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
}

@Test void cancelledSegmentsDoNotBlock() {
    var s = insertSegment(trip, seat3A, 1, 9);
    cancel(s);
    assertThatCode(() -> insertSegment(trip, seat3A, 1, 9))
        .doesNotThrowAnyException();          // the WHERE predicate works
}
```

Also covered: availability query correctness against a fixture of known bookings; migration
idempotency; and a test asserting the constraint **exists** with the expected definition — so that a
future migration that drops it fails the build loudly.

---

## 4. The concurrency proof ★

This is the test suite the project stands on.

### 4.1 Negative: overlapping legs, one winner

```java
@Test
void fiftyThreadsBookingOverlappingLegsOnOneSeatProduceExactlyOneBooking() throws Exception {
    int threads = 50;
    var barrier = new CyclicBarrier(threads);       // all fire in the same millisecond
    var pool    = Executors.newFixedThreadPool(threads);
    var results = Collections.synchronizedList(new ArrayList<Integer>());

    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            barrier.await();
            results.add(bookingClient.book(trip, seat3A, CMB, KDY).statusCode());
            return null;
        });
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(30, SECONDS)).isTrue();

    // HTTP-level assertion
    assertThat(results).filteredOn(s -> s == 201).hasSize(1);
    assertThat(results).filteredOn(s -> s == 409).hasSize(49);

    // ★ DATABASE-level assertion — the one that actually matters
    assertThat(activeSegments(trip, seat3A)).hasSize(1);
    assertNoOverlappingSegmentsAnywhere();
}
```

Asserting on database state as well as on status codes is deliberate: a system can return a plausible
set of responses and still have written garbage. The HTTP assertion checks the API; the SQL assertion
checks the truth.

### 4.2 Positive: adjacent legs, both win

The mirror image, and the one that proves we solved the actual *product* problem rather than just
preventing double-booking:

```java
@Test
void concurrentAdjacentLegsOnOneSeatBothSucceed() throws Exception {
    var barrier = new CyclicBarrier(2);
    var a = async(() -> { barrier.await(); return book(trip, seat3A, CMB, KDY); }); // [1,9)
    var b = async(() -> { barrier.await(); return book(trip, seat3A, KDY, BDL); }); // [9,25)

    assertThat(a.get().statusCode()).isEqualTo(201);
    assertThat(b.get().statusCode()).isEqualTo(201);
    assertThat(activeSegments(trip, seat3A)).hasSize(2);   // ★ one seat, two paying passengers
}
```

A system that passes 4.1 and fails 4.2 has merely reimplemented whole-seat locking. Both tests must be
green for the project to mean anything.

### 4.3 Property-based (jqwik)

```java
@Property(tries = 10_000)
void invariantHoldsForArbitraryIntervalSets(
        @ForAll @Size(min = 1, max = 20) List<@From("legs") Leg> legs) {
    legs.forEach(this::attemptBooking);        // some succeed, some 409 — both fine
    assertNoOverlappingActiveSegments(trip, seat3A);   // ★ the invariant, always
}
```

The value here is that it explores boundary combinations a human would not enumerate — nested ranges,
chains of adjacent legs, single-stop hops at the extremes of the route.

### 4.4 Chaos / interleaving

| Test | Scenario |
|---|---|
| `HoldExpiryRaceIT` | The sweeper expires a hold at the same instant a booking tries to take that seat |
| `ConfirmCancelRaceIT` | Confirm and cancel arrive simultaneously for one booking |
| `IdempotencyRaceIT` | Two identical POSTs with the same key at the same instant ⇒ one booking, two identical responses |
| `MultiSeatDeadlockIT` | Two transactions booking the same 4 seats in opposite orders ⇒ no deadlock (sorted acquisition), or clean retry |
| `OutboxDuplicateIT` | The same event delivered twice ⇒ consumer is idempotent, no double waitlist promotion |

`MultiSeatDeadlockIT` exists because the load test found that bug. It is now regression-protected.

---

## 5. Contract tests

- **OpenAPI conformance** — every response validated against the schema in CI; drift fails the build.
- **Breaking-change diff** — the spec is diffed against `main`; removing a field or tightening validation
  fails.
- **Consumer-driven (Pact)** — the frontend publishes expectations; booking-service verifies them.
- **Event schemas** — producers and consumers validate against registered schemas; only additive changes
  pass.

---

## 6. End-to-end (Playwright)

15 scenarios, including:

- Search → seat map → book → pay → ticket, happy path.
- **Two browser contexts race for the same seat**: one succeeds; the other must show the recovery banner
  and rebook the suggested alternative in **one click**. This is the frontend counterpart of §4.1.
- Adjacent-leg resale visible in the UI: book Fort→Kandy, then confirm the same seat is offered for
  Kandy→Badulla.
- Hold expiry: the countdown reaches zero and the UI explains rather than breaks.
- Waitlist: join a full leg, cancel a booking elsewhere, receive the offer.
- Keyboard-only booking, start to finish.
- Sinhala and Tamil UI render correctly.

---

## 7. Load and performance (k6)

`load/booking-contention.js` — deliberately adversarial:

```js
export const options = {
  scenarios: {
    browsing: { executor: 'constant-vus', vus: 180, duration: '5m' },  // availability reads
    booking:  { executor: 'constant-arrival-rate', rate: 40, timeUnit: '1s',
                duration: '5m', preAllocatedVUs: 60 },                  // writes
    thundering_herd: { executor: 'shared-iterations', vus: 200, iterations: 200,
                       startTime: '2m', maxDuration: '30s' },           // all on ONE trip
  },
  thresholds: {
    'http_req_duration{endpoint:availability}': ['p(95)<150'],
    'http_req_duration{endpoint:booking}':      ['p(95)<250'],
    'checks{check:no_invariant_violation}':     ['rate==1.00'],   // ★ non-negotiable
  },
};
```

The `thundering_herd` scenario models the real risk: the morning Poya-weekend Ella tickets open and
hundreds of users converge on one trip in seconds. A post-run SQL check asserts zero overlapping
segments in the whole database — a load test that measures latency but not correctness would miss the
only failure that matters.

---

## 8. Coverage targets

| Area | Line | Branch | Rationale |
|---|---|---|---|
| Booking domain + concurrency | **95%** | **90%** | Correctness-critical |
| Fare engine | 95% | 90% | Money |
| API layer | 85% | 75% | |
| Catalog / reporting | 75% | 65% | Lower risk |
| Frontend components | 80% | 70% | |

Coverage is a **floor, not a goal**. A 100%-covered service can still double-sell a seat; the
concurrency suite is what actually protects the invariant, and it is weighted accordingly in review.

---

## 9. What is deliberately not tested

Recorded so absence reads as a decision:

- The mock payment gateway's internals (it is a stub with a defined contract).
- PostgreSQL's own GiST implementation (we test that we *use* it correctly, not that it works).
- Third-party library behaviour beyond our integration points.
- Exhaustive visual regression across browsers — smoke-level only, given the time budget.

---

**Related:** [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) ·
[`14-challenges-and-tradeoffs.md`](14-challenges-and-tradeoffs.md)
