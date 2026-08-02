# 12 — Observability & Operations

---

## 1. Principle

> Instrument the **invariant**, not just the infrastructure.

CPU and latency dashboards tell you the service is alive. They do not tell you whether the one thing the
system exists to guarantee is still true. The signature metric of this system is not p99 latency — it is
`yathra_invariant_violations_total`, which must be **exactly zero, forever**.

---

## 2. The three signals

### 2.1 Structured logs

JSON to stdout. Every line carries `timestamp`, `level`, `service`, `version`, `correlationId`,
`traceId`, `spanId`, and event-specific fields.

```json
{ "ts":"2026-08-01T09:14:22.481Z", "level":"INFO", "service":"booking-service",
  "correlationId":"c-8f2a…", "traceId":"4bf9…",
  "event":"booking.held", "bookingId":"…", "tripId":"…",
  "seatLabel":"3A", "fromSeq":1, "toSeq":9, "fareMinor":47000, "holdSeconds":600 }
```

Rules:
- **PII is redacted by an explicit filter**, verified by a test that fails if a known PII field name
  appears in log output.
- `correlationId` is accepted from the client, generated if absent, propagated through HTTP headers *and*
  Kafka event envelopes — so an async waitlist promotion can be traced back to the cancellation that
  caused it.
- Levels are disciplined: `ERROR` means a human should look. A 409 on a contested seat is normal
  business flow and logs at `INFO`.

### 2.2 Metrics (Prometheus / OpenTelemetry)

**RED per endpoint** (rate, errors, duration) and **USE per resource** (utilisation, saturation, errors),
plus the domain metrics that matter:

| Metric | Type | Why it exists |
|---|---|---|
| `yathra_bookings_total{status}` | counter | Business volume |
| **`yathra_booking_conflicts_total`** | counter | 409 rate — **the health signal for contention** |
| `yathra_booking_conflict_ratio` | gauge | Conflicts ÷ attempts. Rising = demand outstripping supply *or* a UI that is showing stale availability |
| `yathra_holds_active` | gauge | Inventory currently frozen but unpaid |
| `yathra_hold_expiry_total{reason}` | counter | Abandonment vs payment failure |
| `yathra_hold_conversion_ratio` | gauge | Held → confirmed. The funnel's most important number |
| `yathra_sweeper_lag_seconds` | gauge | How stale expired holds are getting |
| `yathra_segments_per_seat` | histogram | **The product metric — how often seats actually resell** |
| `yathra_seat_km_utilisation{tripId}` | gauge | The real load factor |
| `yathra_revenue_minor_total{segment}` | counter | Revenue by stretch of line |
| **`yathra_invariant_violations_total`** | counter | **Must be 0. Page immediately if not** |
| `yathra_outbox_unpublished` | gauge | Event pipeline health |
| `yathra_waitlist_promotions_total` | counter | Extra-credit feature usage |

`yathra_segments_per_seat` deserves a note: it is the metric that tells the department whether this
project worked. If the mean is 1.0, seats are not reselling and the change delivered nothing.

### 2.3 Traces (OpenTelemetry)

W3C trace context propagated across HTTP and Kafka. A booking trace shows:

```
POST /api/v1/bookings                                       182 ms
├── gateway.auth                                              4 ms
├── booking.idempotency.lookup                                2 ms
├── booking.quote.verify (local HMAC — no network)            1 ms
├── booking.tx                                              168 ms
│   ├── db.expire_conflicting_holds                           6 ms
│   ├── db.insert_booking                                     8 ms
│   ├── db.insert_segment  ★ exclusion constraint evaluated  141 ms  ← blocked on a concurrent tx
│   ├── db.insert_outbox                                      5 ms
│   └── db.commit                                             8 ms
└── response.serialise                                        3 ms
```

That 141 ms span is the whole concurrency design made visible: the transaction *waited* on another
transaction's uncommitted index entry and then succeeded. Being able to see contention rather than infer
it is why the DB spans are instrumented at this granularity.

---

## 3. SLOs

| SLO | Target | Window | Error budget |
|---|---|---|---|
| Availability (booking API) | 99.9% | 30 d | 43 min |
| Availability query latency | p95 < 150 ms | 30 d | |
| Booking latency | p95 < 250 ms, p99 < 800 ms | 30 d | |
| Booking success (excl. legitimate 409) | > 99.5% | 30 d | |
| Seat map load | p95 < 400 ms | 30 d | |
| **Invariant violations** | **0** | always | **none** |

The last row has no error budget by design. Everything else can degrade; this cannot.

---

## 4. Alerts

| Alert | Condition | Severity | Response |
|---|---|---|---|
| **InvariantViolation** | `yathra_invariant_violations_total > 0` | **P1 page** | Runbook R1 — stop writes, investigate immediately |
| BookingErrorRateHigh | 5xx > 1% for 5 min | P1 page | R2 |
| BookingLatencyHigh | p95 > 500 ms for 10 min | P2 | R3 |
| ConflictRatioSpike | conflict ratio > 30% for 10 min | P2 | R4 — usually demand, sometimes a stale cache |
| SweeperLagging | `sweeper_lag_seconds > 120` | P2 | R5 |
| OutboxBacklog | `outbox_unpublished > 1000` for 5 min | P2 | R6 |
| HoldsAbnormal | `holds_active` > 3σ above baseline | P2 | R7 — possible squatting attack |
| DBConnectionsExhausted | pool > 90% for 5 min | P1 page | R8 |
| ReplicationLag | > 30 s | P2 | R9 |

Every alert links to a runbook. An alert without a runbook is a notification.

---

## 5. Runbook extracts

### R1 — Invariant violation detected

**This should be impossible.** The exclusion constraint makes overlapping active segments unwritable, so
a violation means something structural has changed.

1. **Verify** the detector, then verify the data:
   ```sql
   SELECT a.trip_id, a.seat_id, a.leg, b.leg
     FROM booking_segment a JOIN booking_segment b
       ON a.trip_id = b.trip_id AND a.seat_id = b.seat_id AND a.id < b.id
    WHERE a.leg && b.leg
      AND a.status IN ('HELD','CONFIRMED')
      AND b.status IN ('HELD','CONFIRMED');
   ```
2. **Confirm the constraint still exists** — the overwhelmingly likely cause:
   ```sql
   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
    WHERE conname = 'no_overlapping_active_segments';
   ```
   If absent or altered, a migration dropped it. Restore it immediately; the CI test that asserts the
   constraint's existence should have caught this, so also fix that gap.
3. If present and violations exist, suspect a `NOT VALID` constraint or a restore that skipped
   validation. Run `ALTER TABLE … VALIDATE CONSTRAINT`.
4. **Never resolve by cancelling a confirmed booking.** Honour both, move one passenger to another seat
   manually, and treat it as a Sev-1 incident with a written postmortem.

### R4 — Conflict ratio spike

1. Is it one trip or many? One trip on a peak date is normal demand — consider capacity, not code.
2. Many trips ⇒ suspect stale availability. Check Redis invalidation lag and SSE stream health.
3. Check for bot patterns (single IP/ASN, inhuman timing) → tighten rate limits.
4. Check `hold_conversion_ratio`: a collapse alongside a conflict spike suggests hold-squatting.

### R5 — Sweeper lagging

1. Is ShedLock held by a dead pod? Check `shedlock.lock_until` vs `now()`.
2. Check sweeper batch duration and the partial index on `booking(status, expires_at)`.
3. Lazy in-transaction expiry means bookings still work while the sweeper is behind — this is a P2, not a
   P1. Verify that before escalating.

---

## 6. Dashboards

| Dashboard | Audience | Key panels |
|---|---|---|
| **Service health** | On-call | RED per endpoint, error budget burn, saturation, dependency health |
| **Booking funnel** | Product | Search → seat map → hold → confirm; drop-off per step; hold conversion |
| **Contention** | Engineering | Conflict ratio, exclusion-constraint wait time, hot trips, retry counts |
| **Business** ⭐ | Department | Seat-km utilisation, revenue by segment, **segments-per-seat**, **resale uplift** |
| **Data pipeline** | Engineering | Outbox backlog, consumer lag, dedupe hit rate |

The Business dashboard is deliberately built for the department rather than for us. It answers the
question in the brief — *"leadership believes there's revenue being left on the table"* — with numbers
instead of belief.

---

## 7. Operational procedures

| Procedure | Approach |
|---|---|
| Deploy | Rolling, `maxUnavailable: 0`, health-gated, automatic rollback on error-rate regression |
| Migrations | Kubernetes Job before rollout; backward compatible by policy |
| Scaling | HPA on CPU + request rate; DB connection pool sized so `replicas × pool ≤ max_connections × 0.8` |
| Peak events | Pre-scale before known ticket-release windows; consider a queue-based fair-admission gate |
| Incident response | Sev levels, comms template, blameless postmortem within 5 working days |
| DR | PITR restore rehearsed monthly; RPO 5 min, RTO 30 min |
| Capacity review | Monthly, against the growth model in `docs/01` §5 |

The connection-pool constraint is worth stating explicitly because it is a classic microservices
self-inflicted outage: autoscale six services to ten replicas each with a pool of 20 and you have asked
Postgres for 1,200 connections it does not have.

---

**Related:** [`10-testing-strategy.md`](10-testing-strategy.md) ·
[`08-microservices-and-deployment.md`](08-microservices-and-deployment.md)
