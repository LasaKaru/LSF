# ADR-006 — Use a transactional outbox for event publication

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

Several things need to react to a booking: waitlist promotion, reporting projections, notifications,
payment reconciliation. None of them belongs in the booking transaction — they tolerate eventual
consistency, and coupling them to the write path would make a notification outage a booking outage.

So booking-service must publish events. The question is how, without losing them.

## Options considered

### 1. Dual write — commit the database, then publish to the broker

```java
bookingRepo.save(booking);   // committed
kafka.send(bookingHeld);     // process dies here
```

The failure is **silent and unrecoverable**: the booking exists, the event never happened, and there is
nothing to reconcile against because no record of the intent survives. Worse in the other order — publish
then commit — because now consumers act on a booking that was rolled back.

### 2. Publish inside the transaction, synchronously

Makes broker availability a precondition for booking. Exactly backwards: the broker exists to decouple.

### 3. Change data capture (Debezium on the WAL)

Genuinely good, and the production answer. Rejected *for this deliverable* because it adds Kafka Connect
plus a connector to a topology that must start in one shot on a reviewer's laptop. The outbox table is
CDC-ready, so adopting Debezium later reads the same rows.

### 4. **Transactional outbox** — **chosen**

## Decision

The event row is written to `outbox_event` **in the same transaction as the state change**. A relay
drains it afterwards.

```sql
CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,   -- trip_id: per-trip ordering
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(64),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;
```

"The booking committed" and "the event will be delivered" become **one atomic fact**.

Events published: `BookingHeld`, `BookingConfirmed`, `BookingCancelled`, `SegmentReleased`,
`HoldExpired`.

Three details that matter more than they look:

- **The partial index.** `WHERE published_at IS NULL` keeps the relay's poll `O(unpublished)` rather
  than `O(all events ever written)`. Without it the outbox pattern scales for a month and then slowly
  dies.
- **`partition_key = trip_id`**, so every event for one trip is ordered relative to the others. The
  waitlist matcher needs that to reason correctly about releases.
- **Delivery is at-least-once**, so every consumer must be idempotent. `processed_event` is the dedupe
  table; a duplicate `SegmentReleased` must not promote two people onto one seat.

## Consequences

**Good**

- No lost events, no dual write, no ordering hazard.
- **The broker is never on the booking critical path.** Kafka can be down and bookings still succeed;
  the backlog drains on recovery.
- The pattern is real and inspectable even in the shipped topology: `SELECT * FROM outbox_event` shows
  the events, transactionally written, regardless of whether anything is consuming them.

**Costs**

- The outbox needs pruning (7 days after publish; retention is in `docs/07 §6`).
- A polling relay adds latency versus CDC — irrelevant at this scale, and the migration path exists.
- **In the shipped `core` profile there is no Kafka and no consumer**, so the relay's transport is a log
  appender. This is stated plainly rather than implied: the *pattern* is implemented and the events are
  written transactionally, but nothing is currently delivering them anywhere. The one env var
  `OUTBOX_TRANSPORT` selects the adapter; no code path branches on it beyond that.

---

**Related:** [ADR-003](ADR-003-service-boundaries.md) · [`docs/02 §6.2`](../02-architecture.md)
