# ADR-003 — Keep booking and seat inventory in one service and one database

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

The target organisation builds microservices, and the reflex decomposition here is obvious:
`inventory-service` owns seats and their occupancy; `booking-service` owns orders, passengers and
payment state. Two aggregates, two services, two databases.

That decomposition breaks INV-1 (ADR-002).

## Options considered

### 1. Split, coordinated by two-phase commit

A blocking protocol with the coordinator as a single point of failure, a poor fit for HTTP services, and
effectively nobody runs it in this style of stack.

### 2. Split, coordinated by a saga with compensations

The system becomes eventually consistent. That means a window in which the same `(seat, leg)` is sold
twice, detected afterwards, and resolved by **cancelling a confirmed booking**.

An airline prices that in and calls it involuntary denied boarding. For a state railway selling a
LKR 1,200 seat to a passenger already standing on the platform, *"our architecture double-sold your
seat"* is not a compensating action — it is a service failure. This is the argument that settled it.

### 3. Split, sharing a database

A distributed monolith: all the coupling, none of the benefits, plus network latency.

### 4. One service, one database for the invariant — **chosen**

## Decision

> **A hard transactional invariant defines a service boundary. You do not draw a boundary through the
> middle of one.**

`booking-service` owns trips (snapshotted), seat inventory, bookings, segments, holds and idempotency —
one database, one ACID transaction.

Everything that genuinely tolerates eventual consistency **is** split out:

| Service | Owns |
|---|---|
| `catalog-service` | Stations, routes, trains, coach layouts, timetable, trip publication |
| `pricing-service` | Fare rules, quotes |
| *(future)* waitlist, payment, notification, reporting | Consume events |

Database-per-service is honoured strictly: no service reads another's tables. catalog publishes trips by
POSTing a snapshot; pricing reads trip topology over the API. They share one Postgres *instance* in
compose because running three for a demo would be theatre — each service only ever sees its own JDBC
URL, so splitting the instances in production is a config change.

## Consequences

**Good**

- INV-1 is enforced by a single transaction against a single constraint. No saga, no compensation, no
  window in which a seat is double-sold.
- The booking write path makes **no synchronous call inside its transaction** — quote verification is a
  local HMAC check (ADR-008) precisely so that pricing latency can never hold a booking transaction
  open, and a pricing outage cannot become a booking outage.
- The seams that *are* split are the ones where an outage is survivable: if pricing is down you cannot
  quote, but existing holds still confirm.

**Costs — the honest counter-argument**

`booking-service` is now the largest and hottest component. If the department later adds cargo booking,
parcel space and dining reservations, it will need splitting along *those* lines — they are different
invariants over different aggregates, and none of them conflicts with seat occupancy.

The design anticipates that: internal packaging is modular by aggregate (`trip`, `availability`,
`booking`, `admin`, `outbox`), and the event contracts are already public, so extraction is mechanical
rather than archaeological. But I would not claim the current shape is permanent, and I would raise this
myself in a design review rather than wait to be asked.

**Also accepted**

- `catalog-service` and `booking-service` both hold a representation of a trip. That duplication is
  deliberate and is the subject of [ADR-005](ADR-005-trip-topology-snapshot.md).
- Reporting currently queries the transactional tables directly. At the documented scale that is
  comfortably fast; the trigger for moving it behind a CQRS read model is recorded in `docs/15 §2.2`
  rather than guessed at now.

---

**Related:** [ADR-002](ADR-002-exclusion-constraint-concurrency.md) ·
[ADR-005](ADR-005-trip-topology-snapshot.md) · [`docs/02 §5`](../02-architecture.md)
