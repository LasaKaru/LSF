# ADR-005 — Snapshot trip topology at publication time

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

A booking stores `from_seq` and `to_seq` — positions in the trip's stop list (ADR-001). Those integers
only mean something relative to a stop list. So: *which* stop list, and what happens when it changes?

The brief says explicitly that the department may extend the route. So this is not hypothetical.

## The failure being designed against

If `booking_segment` referenced the live `route_stop` table, inserting a new halt between Gampaha and
Veyangoda would shift every `stop_order` after it by one. Every existing booking would then silently
mean something different from what was sold.

Nothing throws. No constraint fires. No log line appears. The first symptom is a passenger and a
conductor disagreeing on a platform, months later.

## Options considered

| Option | Verdict |
|---|---|
| Reference live route data | The failure above. Also couples booking-service to catalog-service on the hot path. |
| Version the route, reference a version | Works, and is the "proper" temporal-database answer. Rejected as more machinery than the problem needs: a trip is *already* a natural version boundary, so versioning routes separately adds a second concept that must be kept consistent with the first. |
| Never change routes | Not a decision available to us. |
| **Snapshot into the trip at publication** | **Chosen** |

## Decision

When a trip is published, the stop list, cumulative distances, coach composition and seat inventory are
**copied** into `trip_stop`, `trip_coach` and `trip_seat`. A trip does not point at the live catalog.

Publication is idempotent at **two independent levels**:

- `catalog-service` records what it has published in `trip_publication`
- `booking-service` returns `ALREADY_PUBLISHED` for a `(train, date, direction)` it already holds

Either alone would prevent duplicates. Both together prevent something worse: a re-publish must never
*rebuild* a trip, because that would delete `trip_seat` rows that sold bookings reference. Sold
inventory must never be disturbed by a later edit — which is the entire point of the snapshot.

**TRIP-3:** topology is immutable once `PUBLISHED`. Changes require a new trip.

## Consequences

**Good**

- A booking's `from_seq`/`to_seq` mean the same thing forever.
- Extending the route is genuinely safe: `INSERT` a station and a route stop, then re-publish *future*
  trips. Existing bookings are untouched by construction, not by care.
- `booking-service` is self-contained on the hot path — it never calls catalog to serve a booking, so
  catalog can be down without bookings failing.
- Special workings fall out for free: a service that skips Ohiya on Sundays is a trip with a different
  snapshot, needing no special-casing anywhere.

**Costs**

- **Data duplication.** ~180 seats × 25 stops per trip, ~180 active trips. The scale analysis
  (`docs/01 §5`) puts this at tens of thousands of rows — trivially small, and the correctness it buys
  is not purchasable any other way.
- A route change requires re-publishing future trips. That is a real operational step, and it is
  documented as the procedure rather than left implicit. `POST /api/v1/internal/publish` makes it a
  single idempotent call.
- Two representations of "a trip" exist. That is the deliberate anti-corruption boundary of ADR-003:
  booking-service translates catalog's model into its own rather than importing it, so a catalog
  restructure changes one translation function.

**Note on derived data.** Scheduled stop times are currently *derived* at publication from distance, an
average speed and a per-stop dwell, rather than imported from a real timetable. That is an explicit
simplification confined to four lines in `TripPublisher`. Crucially it affects nothing about inventory:
occupancy is indexed by stop sequence, never by clock time (ADR-001), so swapping in real SLR timetable
data is a local change with no correctness implications.

---

**Related:** [ADR-001](ADR-001-half-open-interval-model.md) · [ADR-003](ADR-003-service-boundaries.md) ·
[`docs/07 §3.1`](../07-data-model.md)
