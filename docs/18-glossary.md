# 18 — Glossary

Domain and technical vocabulary, used identically in code, database, API and documentation. If a term is
not here, it does not appear in a class name.

---

## Domain

| Term | Definition |
|---|---|
| **Station** | A physical stopping place with a unique code (`CMB`, `KDY`, `BDL`) |
| **Route** | An ordered list of stations, e.g. Colombo Fort ⇄ Badulla |
| **Route Stop** | A station's position (`stop_order`) and cumulative distance on a route |
| **Train** | A named service pattern, e.g. *1005 Podi Menike* |
| **Trip** | One train, one calendar date, one direction. **The unit inventory belongs to** |
| **Trip Stop** | A snapshot of a route stop for a specific trip, carrying `stop_sequence` |
| **Stop Sequence** | Dense ascending integer position within a trip. The coordinate system for everything |
| **Consist** | The set of coaches making up a train on a given trip |
| **Coach** | A carriage, with a type, class and layout |
| **Coach Layout** | The seating grid template: rows, columns, aisle, labels, window flags |
| **Reserved coach** | Seat assigned in advance; seat-level inventory |
| **Unreserved coach** | First-come-first-served; headcount capacity only |
| **Seat** | A numbered position in a coach on a trip |
| **Leg** | A half-open range `[from_seq, to_seq)` over a trip's stops. **The unit of inventory** |
| **Segment** | Used interchangeably with leg; `booking_segment` is the persisted `(seat, leg)` sale |
| **Booking** | A passenger's order: one or more segments, one payment, one reference |
| **Booking Reference** | Short human-speakable code (`YTH-7K2QD9`), ambiguity-free alphabet |
| **Hold** | A booking in `HELD` status with a TTL. Occupies inventory; auto-releases |
| **Quote** | A signed, expiring priced offer for a specific leg, class and coach type |
| **Resale** | Selling a seat again on a leg vacated by a previous passenger. **The point of the project** |
| **Waitlist Entry** | A request queued for a leg that is currently full |
| **Promotion** | Converting a waitlist entry into a held booking when inventory frees |
| **Seat-km** | Distance × seats. The correct unit for utilisation |
| **Seat-km utilisation** | Sold seat-km ÷ available seat-km. The honest load factor |
| **Resale uplift** | Actual revenue vs the whole-journey-only counterfactual |
| **Telescopic fare** | Tariff whose marginal rate per km decreases with distance |
| **Scenic surcharge** | Additive premium on the Nanu Oya → Ella stretch, charged only on km travelled |

---

## Technical

| Term | Definition |
|---|---|
| **INV-1** | The core invariant: no two active segments overlap on one `(trip, seat)` |
| **Half-open interval** | `[from, to)` — includes the start, excludes the end. Why adjacency works |
| **Exclusion constraint** | PostgreSQL constraint rejecting rows whose values conflict under given operators. Enforces INV-1 |
| **GiST** | Generalised Search Tree — the index type supporting range overlap (`&&`) |
| **`btree_gist`** | Extension providing GiST operator classes for scalar equality, so `uuid WITH =` can share an index with `int4range WITH &&` |
| **`int4range`** | PostgreSQL integer range type |
| **`&&`** | Range overlap operator |
| **`23P01`** | SQLSTATE for `exclusion_violation` — mapped to HTTP 409 |
| **`40001` / `40P01`** | Serialisation failure / deadlock detected — retried with backoff |
| **Predicate locking** | Locking a *condition* rather than existing rows; prevents phantom writes |
| **Phantom read** | Anomaly where a row matching a predicate appears after you checked. The bug the constraint prevents |
| **Check-then-act** | The broken pattern: query for conflicts, then insert. Racy across transactions |
| **Idempotency key** | Client-supplied token making a retried POST safe |
| **Transactional outbox** | Writing events to a table in the same transaction as the state change, relayed asynchronously. Avoids dual-write loss |
| **Saga** | Distributed transaction as a sequence of local transactions with compensations |
| **CQRS** | Separating the write model from read models |
| **ShedLock** | Library ensuring a scheduled job runs on only one replica |
| **RFC 9457** | Problem Details for HTTP APIs — the error format used throughout |
| **SSE** | Server-Sent Events — unidirectional server push, used for live availability |
| **Testcontainers** | Library running real dependencies (PostgreSQL) in Docker during tests |
| **Property-based testing** | Asserting invariants over generated inputs rather than fixed examples |
| **Expand/contract** | Zero-downtime migration pattern: add, backfill, dual-write, switch, remove |
| **Minor units** | Integer currency representation (LKR 470.00 = `47000`). Money is never a float |

---

## Sinhala / Tamil terms appearing in the project

| Term | Meaning |
|---|---|
| **Yathra** (යාත්‍රා) | Journey — the project name |
| **Podi Menike** (පොඩි මැණිකේ) | "Little Gem" — the Colombo–Badulla express |
| **Udarata Menike** (උඩරට මැණිකේ) | "Up-country Gem" — the Colombo–Badulla express via the hill country |
| **Poya** | Full-moon Buddhist public holiday; a major demand peak for up-country travel |
