# 19 — Walkthrough evidence: every screen, every scenario

Screenshots of the running system with the backend log lines each one produced. Captured through
Chromium against the real nginx configuration, with all three services and PostgreSQL 16 running — not
mock-ups, and not a storyboard.

Five scenarios: the happy path, the headline resale behaviour, losing a race, the waitlist, and the
departmental view.

> **Two bugs were found while capturing these**, both invisible to the test suite and to CI, and both
> fixed before the screenshots below were taken. They are written up in §6 rather than quietly omitted,
> because "we produced the screenshots" and "the screenshots came out first time" are different claims.

---

## 1. Happy path — search to ticket

### 1.1 Search

Origin, destination and date. Stations come from `catalog-service`; nothing about the route is compiled
into the app.

![Search](screenshots/01-passenger-search.png)

### 1.2 Trains, with leg-specific availability

Availability is fetched **per trip and per leg**. "Seats free on this train" is meaningless once
inventory is segment-granular — the same seat can be free for your leg and sold for someone else's.

![Trains](screenshots/02-passenger-trains-CMB-KDY.png)

### 1.3 The seat map

Rendered from the coach's layout JSON, so a 2+2 second-class coach and a 2+1 observation saloon are two
database rows rather than two components. One tab stop, arrow keys to move.

![Seat map](screenshots/03-passenger-seatmap-all-free.png)

### 1.4 Seat selected

![Seat selected](screenshots/04-passenger-seat-selected.png)

### 1.5 Details, and the fare broken down

The passenger can see they are paying for their own 121 km, not for a whole train.

![Details](screenshots/05-passenger-details-fare-breakdown.png)

### 1.6 Held, with the clock running

```
booking.held reference=YTH-VD288N leg=[1,9) seats=1 totalMinor=47000
```

A hold is a real row under the exclusion constraint, so the seat is genuinely reserved for this leg while
the passenger finishes. The countdown is rendered from the server's `expiresAt`, never from a duration
counted locally — a sleeping laptop must not be able to show more time than there is.

![Hold](screenshots/06-passenger-hold-countdown.png)

### 1.7 Confirmed

```
booking.confirmed reference=YTH-VD288N
```

![Ticket](screenshots/07-passenger-ticket-confirmed.png)

---

## 2. The headline: one seat, two passengers, no overlap

With seat 1A sold `CMB→KDY` `[1,9)`, searching `CMB→BDL` `[1,25)` shows that seat **amber**: free for
*part* of your leg. This state cannot exist in whole-journey booking, and a boolean availability API
could not express it — which is why the seat-map endpoint returns occupancy **ranges** per seat.

![Amber](screenshots/08-passenger-seatmap-amber-partial.png)

Hovering gives the detail — and the same text is in the seat's `aria-label`, so it is not colour-only:

> *"Seat 1A, window, coach R1, partly available. Free for part of your leg. Already sold: CMB→KDY"*

![Amber detail](screenshots/09-passenger-amber-hover-detail.png)

The backend log for the pair, on one seat, both accepted:

```
booking.held reference=YTH-BKPG7D leg=[1,15)    <- Fort -> Nanu Oya
booking.held reference=YTH-FCRQZ9 leg=[15,25)   <- Nanu Oya -> Badulla, SAME SEAT
```

`[1,15)` and `[15,25)` are adjacent, not overlapping. That is the entire project in two log lines.

---

## 3. Losing a race — 409 with one-click recovery

Another passenger takes seat 1B in the seconds between selecting it and submitting. The exclusion
constraint rejects the second insert, and the `409` carries the conflicting seat, its occupied range, and
ranked alternatives — so recovery is one click with the passenger's details intact.

```
POST /api/v1/bookings -> 409 SEAT_SEGMENT_UNAVAILABLE
   detail = "1B is already booked for part of CMB to KDY."
```

![Conflict](screenshots/10-passenger-409-conflict-recovery.png)

---

## 4. Sold out — the waitlist

### 4.1 "Sold out" is an entry point, not a dead end

Seats free up constantly on this line, because a passenger leaving at Kandy releases the rest of their
seat to Badulla.

![Sold out](screenshots/11-passenger-sold-out-join-waitlist.png)

### 4.2 Join, with the fare locked

![Join](screenshots/12-passenger-waitlist-join-form.png)

### 4.3 Your position, strictly FIFO

![Position](screenshots/13-passenger-waitlist-position.png)

### 4.4 A seat frees, and is *held* for you

A cancellation emits `SegmentReleased`; the outbox relay dispatches it; the matcher creates a real `HELD`
booking under the same exclusion constraint as any other. **932 ms end to end**, from the backend log:

```
05:46:28.449  booking.cancelled  reference=YTH-4K84BS segments=1
05:46:29.381  waitlist.promoted  entryId=4a87f1dc… reference=YTH-GWJ7AZ seat=1C leg=[1,25)
```

The UI says *held*, not *available*, because that is the truth — nobody else can take this seat while the
clock runs, and a passenger who assumed otherwise would lose it.

![Offer](screenshots/14-passenger-waitlist-offer-countdown.png)

### 4.5 Confirmed

An offer confirmed is an ordinary confirmed booking; arriving via the queue does not make it a different
kind of thing, so it lands on the ordinary ticket screen.

![Waitlist ticket](screenshots/15-passenger-waitlist-ticket.png)

---

## 5. The departmental view

Seeded with a realistic day's trade on one train: 34 short-leg commuters into Kandy, 22 of those seats
resold onward to Badulla, 14 whole-journey travellers, and a scattering of hill-country legs.

![Admin](screenshots/16-admin-dashboard-top.png)

The two numbers side by side are the argument:

| Metric | Value |
|---|---|
| Seat-km utilisation | **33%** |
| Conventional load factor | **38.9%** |
| **The gap** | **5.9 points of apparent occupancy carrying no passenger** |
| Segments per seat | **1.5** — above 1.0, so seats *are* reselling |
| Actual revenue | LKR 53,020 |
| If each seat sold once, to whoever booked it first | LKR 35,840 |
| **Resale uplift** | **+47.9%** |

A train with every seat sold for a tenth of the route reports as 100% full under the conventional metric.
That is exactly what hid the problem, so the dashboard shows both and states the difference outright.

Occupancy by section answers *"where does the train empty out?"* — and the sections that empty early are
where resale earns most:

![Heatmap](screenshots/17-admin-occupancy-heatmap.png)

![Revenue](screenshots/18-admin-revenue-and-uplift.png)

Full page: [`19-admin-full-realistic-day.png`](screenshots/19-admin-full-realistic-day.png)

---

## 6. Two bugs these screenshots found

Both were invisible to 61 green tests and to four green CI jobs. Recorded because the interesting thing
about a walkthrough is what it catches.

### 6.1 The conflict banner was unreachable from the screen that produced conflicts

The hold is submitted from the **details** step. The recovery banner rendered only on the **seats** step.
On a `409` the app called `setConflict(...)` and changed nothing else — so the passenger who lost a race
saw *nothing at all*: the button simply stopped spinning.

The API was returning a perfectly good 409 with alternatives attached the whole time, which is why no
backend test could have caught it, and why the extra-credit feature looked finished. Fixed by returning
to the seat map — where the banner and the refreshed occupancy live — on conflict.

### 6.2 Virtual threads + a TLS PostgreSQL = requests that hang forever

The more serious one, and it very nearly shipped.

The second booking for an already-held seat **never returned**. Not slow — indefinitely hung. Diagnosis,
in order:

1. `pg_stat_activity` showed **zero** active backends and no `idle in transaction`. So the database was
   not doing anything, and was not blocked.
2. `jstack` showed no application frames at all — only Tomcat's poller and four `ForkJoinPool` workers.
3. That absence *was* the clue: all three services set `spring.threads.virtual.enabled=true`, and a
   standard thread dump does not show virtual threads. `jcmd Thread.dump_to_file -format=json` showed six
   `tomcat-handler-*` virtual threads parked inside `BookingTransaction.createHold`, all blocked in
   `PGStream.receiveString` — reading from an **SSL-wrapped** socket.

On Java 21 a virtual thread that blocks inside a `synchronized` block **pins its carrier**.
`SSLSocketImpl` reads inside synchronized methods, and pgjdbc's default `sslmode=prefer` negotiates TLS
whenever the server offers it. The carrier pool is sized to the CPU count — four here — so a handful of
concurrent queries starved the scheduler and every later request hung.

Measured, same seat, same request:

| `spring.threads.virtual.enabled` | Result |
|---|---|
| `true` (against a TLS PostgreSQL) | **hangs indefinitely** |
| `false` | **409 `SEAT_SEGMENT_UNAVAILABLE` in 0.07 s** |

**Why CI never saw it, and why that is the dangerous part.** The `postgres:16-alpine` image ships with
`ssl=off`, so in Compose and in CI the JDBC connection is plaintext, nothing pins, and everything is
green. My local PostgreSQL happens to have `ssl=on` — as does every managed Postgres a real deployment
would use (RDS, Cloud SQL, Azure). This is a bug that appears **in production and nowhere else**.

Virtual threads are now **off by default** across all three services, env-overridable via
`THREADS_VIRTUAL_ENABLED`, with the reasoning in `application.yml` next to the setting. These services are
synchronous and DB-bound, so virtual threads bought little here in the first place. Java 24's JEP 491
removes the pinning and would make them safe to re-enable.

---

**See also:** [`10-testing-strategy.md`](10-testing-strategy.md) · [`14-challenges-and-tradeoffs.md`](14-challenges-and-tradeoffs.md) · [`16-demo-and-walkthrough.md`](16-demo-and-walkthrough.md)
