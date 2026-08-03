# 02 — Architecture

---

## 1. Architectural principles

These are the rules I applied consistently; every decision below traces back to one of them.

| # | Principle | Consequence |
|---|---|---|
| P1 | **A transactional invariant defines a service boundary.** Never draw a boundary through the middle of one. | Booking + seat inventory are one service, one database |
| P2 | **Correctness belongs in the strongest available layer.** | The overlap rule is DDL, not Java |
| P3 | **Configuration is data.** | Coaches, layouts, stations, fare bands are rows, not constants |
| P4 | **Synchronous where the user waits; asynchronous everywhere else.** | REST for booking/availability; Kafka for waitlist, reporting, notifications |
| P5 | **No service reads another service's database.** | Integration is API + events only |
| P6 | **Fail toward "unavailable", never toward "double-sold".** | Stale caches and expired holds bias to the safe error |
| P7 | **Every artefact is 12-factor.** | Env-only config, stateless replicas, health probes, structured logs |
| P8 | **Simple enough to run in one command.** | Compose profiles; default topology is 6 containers |

---

## 2. C4 Level 1 — System context

```
        ┌──────────────┐        ┌──────────────────┐        ┌────────────────┐
        │  Passenger   │        │  Railway staff / │        │  Station        │
        │  (web/mobile)│        │  Dept. analyst   │        │  counter clerk  │
        └──────┬───────┘        └────────┬─────────┘        └───────┬────────┘
               │                          │                          │
               ▼                          ▼                          ▼
        ╔══════════════════════════════════════════════════════════════════╗
        ║              YATHRA — Segment Booking Platform                    ║
        ║  Sells (seat × leg) inventory on the Colombo Fort ⇄ Badulla line  ║
        ╚═══════╤═══════════════════╤══════════════════════╤═══════════════╝
                │                   │                      │
                ▼                   ▼                      ▼
        ┌───────────────┐   ┌────────────────┐   ┌───────────────────┐
        │ Payment       │   │ SMS / Email     │   │ SLR timetable &   │
        │ gateway       │   │ provider        │   │ tariff master data│
        │ (mocked)      │   │ (mocked)        │   │ (seeded)          │
        └───────────────┘   └────────────────┘   └───────────────────┘
```

---

## 3. C4 Level 2 — Containers

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                                  EDGE                                           │
│  ┌────────────────────────────────────────────────────────────────────────┐    │
│  │ API Gateway — WSO2 API Manager (prod) / Spring Cloud Gateway (compose) │    │
│  │ OAuth2 introspection · rate limit · CORS · request-id · routing        │    │
│  └───┬──────────┬───────────┬────────────┬────────────┬────────────┬─────┘    │
└──────┼──────────┼───────────┼────────────┼────────────┼────────────┼───────────┘
       │          │           │            │            │            │
┌──────▼───┐ ┌────▼─────┐ ┌───▼──────┐ ┌───▼──────┐ ┌───▼──────┐ ┌──▼───────────┐
│ booking  │ │ catalog  │ │ pricing  │ │ payment  │ │ waitlist │ │ admin-       │
│ -service │ │ -service │ │ -service │ │ -mock    │ │ -service │ │ reporting    │
│  ★★★     │ │          │ │          │ │          │ │          │ │              │
│ avail.   │ │ stations │ │ quotes   │ │ authorise│ │ FIFO     │ │ CQRS read    │
│ hold     │ │ routes   │ │ fare     │ │ capture  │ │ promote  │ │ occupancy    │
│ confirm  │ │ trains   │ │ rules    │ │ refund   │ │ notify   │ │ revenue      │
│ cancel   │ │ trips    │ │          │ │          │ │          │ │ uplift       │
└────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └──────┬───────┘
     │            │            │            │            │              │
┌────▼─────┐ ┌────▼─────┐ ┌────▼─────┐ ┌────▼─────┐ ┌────▼─────┐ ┌──────▼───────┐
│booking_db│ │catalog_db│ │pricing_db│ │payment_db│ │waitlist_ │ │ reporting_db │
│★ GiST    │ │          │ │          │ │          │ │   db     │ │ (read model) │
│ EXCLUDE  │ │          │ │          │ │          │ │          │ │              │
└──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────────┘
     │            │            │            │            │              ▲
     └────────────┴────────────┴────────────┴────────────┴──────────────┘
                        Kafka  ·  transactional outbox
        BookingHeld · BookingConfirmed · BookingCancelled · HoldExpired
        SegmentReleased · TripPublished · PaymentAuthorised · PaymentFailed

┌──────────────────┐  ┌───────────────────┐   ┌─────────┐  ┌────────────────────┐
│ passenger web    │  │ admin web (React) │   │  Redis  │  │ OTel → Tempo /      │
│ (React + Vite)   │  │                   │   │ cache · │  │ Prometheus /Grafana │
└──────────────────┘  └───────────────────┘   │ idem ·  │  └────────────────────┘
                                              │ ratelimit│
                                              └─────────┘
```

**Database-per-service** is honoured strictly. `booking_db` is the only one with the exclusion
constraint, and it is the only one that matters for correctness.

---

## 4. Service catalogue

This is the **target** topology. Four of the eight are deployed as their own containers; the rest are
either folded into `booking-service` or not built. The **As built** column is the honest column.

| Service | Owns | Sync API | Publishes | Consumes | As built |
|---|---|---|---|---|---|
| **booking-service** ★ | Trips (snapshot), seat inventory, bookings, segments, holds, idempotency | `GET /availability`, `GET /seat-map`, `POST /bookings`, `POST /{id}/confirm`, `DELETE /{id}` | `BookingHeld`, `BookingConfirmed`, `BookingCancelled`, `HoldExpired`, `SegmentReleased` | `TripPublished`, `PaymentAuthorised`, `PaymentFailed` | ✅ **own container.** Consumes `TripPublished` via a direct call from catalog (`POST /internal/trips`), not a broker. Payment events are not consumed — there is no payment service |
| **catalog-service** | Stations, routes, route stops, trains, coach types, coach layouts, schedules, trip publication | `GET /stations`, `GET /routes`, `GET /trips` | `TripPublished`, `RouteChanged` | — | ✅ **own container.** Publishes trips by calling booking-service directly and keeps its own publication ledger so republication is idempotent |
| **pricing-service** | Fare bands, class/coach multipliers, scenic and demand rules, signed quotes | `POST /quotes`, `GET /fare-rules` | `QuoteIssued` | `SegmentOccupancyChanged` (for demand tier) | ✅ **own container.** Demand tier is computed from published rules, not from live occupancy — it consumes no events |
| **gateway** | Routing, authn, rate limiting | — | — | — | ✅ **own container** (nginx). Routing and rate limiting yes; **authn no** — see the known gap in the README |
| **waitlist-service** ⭐ | Waitlist entries, FIFO matching | `POST /waitlist`, `GET /waitlist/{id}` | `WaitlistPromoted` | `SegmentReleased`, `BookingCancelled`, `HoldExpired` | ⚠️ **built, inside booking-service.** Full behaviour and its own UI; consumes `SegmentReleased` through the in-process outbox relay. Not split because the matcher's promotion is a real `HELD` insert that must go through the exclusion constraint — see README §4 |
| **admin-reporting-service** ⭐ | Denormalised read model: occupancy, revenue, resale uplift | `GET /admin/occupancy`, `GET /admin/revenue`, `GET /admin/uplift` | — | All booking events | ⚠️ **built, inside booking-service.** Queries the booking tables directly rather than maintaining a projection; a read replica is a deployment decision, not an architectural one |
| **payment-mock-service** | Payment intents, mock authorisation | `POST /payments`, `POST /{id}/capture` | `PaymentAuthorised`, `PaymentFailed` | `BookingHeld` | ❌ **not built.** Confirm is a state transition; no money moves. A half-integrated gateway would look like more work and be worth less than an honest seam |
| **notification-service** | Delivery log | — | — | `BookingConfirmed`, `WaitlistPromoted`, `HoldExpired` | ❌ **not built.** `WaitlistPromoted` is published with the contact address on it; nothing sends the mail. A promoted passenger has to poll |

★ = owns the invariant  ⭐ = extra credit

**Deployed topology: 5 containers** — `postgres`, `booking-service`, `catalog-service`, `pricing-service`,
`gateway` (plus a one-shot `web-build` that compiles the SPA into a volume the gateway serves). That is
deliberate and is argued in `docs/14 §5`: the brief asks for one command from a clean machine, and every
container added is cold-start time and another thing that can time out on the reviewer's first run.

---

## 5. Why booking and inventory are not split

This is the decision most likely to be challenged in a microservices shop, so it is argued explicitly.

The tempting decomposition is `inventory-service` (seats and their occupancy) + `booking-service`
(orders, passengers, payment state). It fails on principle P1.

If the two are separate, INV-1 becomes a distributed invariant, and there are only three ways to enforce
one:

1. **Two-phase commit.** Blocking protocol, coordinator is a SPOF, poor fit for HTTP services, and
   effectively nobody runs it in this style of stack.
2. **Saga with compensation.** The system becomes eventually consistent. There is a window in which the
   same `(seat, leg)` can be sold twice, detected later, and compensated by **cancelling a confirmed
   booking**. An airline calls that "involuntary denied boarding" and prices it in. For a state railway
   selling a LKR 1,200 seat to a passenger who is already standing on the platform, "sorry, our
   architecture double-sold your seat" is not a compensating action — it is a service failure.
3. **A shared database between the two services**, which is a distributed monolith with extra latency
   and none of the benefits.

So the invariant defines the boundary. What *is* split out is everything that genuinely tolerates
eventual consistency: pricing rules, payments, waitlist, reporting, notifications. The result is
microservice-shaped where it helps and single-transaction where physics demands.

**Honest counter-argument.** The booking service is now the largest and hottest service, and if the
department later adds cargo booking, parcel space and dining reservations, it will need splitting along
*those* lines (different invariants, different aggregates). The design anticipates this: the internal
package structure is already modular by aggregate, and the event contracts are public, so extraction is
mechanical.

---

## 6. Communication patterns

### 6.1 Synchronous (REST, OpenAPI 3.1)

Used only where a human is waiting: search, availability, quote, book, confirm, cancel, seat map.

- JSON, `application/problem+json` for errors (RFC 9457).
- Timeouts everywhere; **Resilience4j** circuit breakers with fallbacks.
- `X-Correlation-Id` propagated end to end and logged on every hop.
- Contract-first: OpenAPI specs live in `contracts/`, clients and server stubs are generated, and a CI
  job fails the build on a breaking change.

**Critical rule: no synchronous call inside a database transaction.** The quote check inside the booking
transaction is a *local HMAC verification*, not an HTTP call to pricing-service, specifically so that
pricing-service latency can never hold a booking transaction open.

### 6.2 Asynchronous (Kafka + transactional outbox)

Dual-write ("commit the DB, then publish to Kafka") is a lost-message bug waiting to happen. Instead the
event is written to an `outbox` table **in the same transaction** as the state change, and a relay
(Debezium CDC in production, a polling publisher in compose to keep the default topology small) drains it.

- Delivery is **at-least-once**; every consumer is **idempotent** via a `processed_event` dedupe table.
- Ordering is per-key: `trip_id` is the partition key, so all events for a trip are ordered.
- Event schemas are versioned and additive-only; a schema registry gates incompatible changes.

Event catalogue:

| Event | Producer | Key consumers |
|---|---|---|
| `TripPublished` | catalog | booking (snapshots topology) |
| `BookingHeld` | booking | payment, reporting |
| `BookingConfirmed` | booking | notification, reporting |
| `BookingCancelled` | booking | waitlist, reporting, payment (refund) |
| `HoldExpired` | booking | waitlist, reporting |
| `SegmentReleased` | booking | waitlist (promotion trigger), reporting |
| `PaymentAuthorised` / `PaymentFailed` | payment | booking (confirm / release) |
| `WaitlistPromoted` | waitlist | notification |

### 6.3 Server-Sent Events

`GET /api/v1/trips/{id}/availability/stream` pushes availability deltas to the seat map. SSE over
WebSocket because the traffic is unidirectional, it survives proxies and HTTP/2 cleanly, and it
reconnects natively. Bounded per-trip fan-out; falls back to polling with ETags.

---

## 7. The booking saga

Orchestrated by booking-service (it owns the timeout, so it owns the orchestration).

```
      ┌─────────┐  POST /bookings           ┌──────────┐
      │  (none) │ ─────────────────────────►│   HELD   │  expires_at = now() + TTL
      └─────────┘  segments inserted        └────┬─────┘
                                                 │
              ┌──────────────────────────────────┼──────────────────────────┐
              │ PaymentAuthorised                │ PaymentFailed / timeout  │ user cancels
              ▼                                  ▼                          ▼
        ┌───────────┐                      ┌──────────┐              ┌────────────┐
        │ CONFIRMED │                      │ EXPIRED  │              │ CANCELLED  │
        └─────┬─────┘                      └────┬─────┘              └─────┬──────┘
              │ user cancels                    │ SegmentReleased          │ SegmentReleased
              ▼                                 ▼                          ▼
        ┌────────────┐                    ┌──────────────────────────────────────┐
        │ CANCELLED  │───────────────────►│ waitlist matcher · reporting · refund │
        └────────────┘  SegmentReleased   └──────────────────────────────────────┘
```

Compensations are all **inventory releases**, which are cheap and safe. The only irreversible step is
payment capture, and it happens *after* `CONFIRMED`, so a failure there leaves money to reconcile rather
than a passenger without a seat. That ordering is deliberate: **never take money for inventory you have
not secured.**

---

## 8. Cross-cutting concerns

| Concern | Approach |
|---|---|
| **AuthN** | OAuth2 / OIDC. WSO2 Identity Server in production; Keycloak in compose. JWT validated at the gateway; services trust the gateway-injected principal and re-validate scopes |
| **AuthZ** | Scope-based: `booking:write`, `booking:read`, `admin:read`, `admin:write`. Ownership checks on booking retrieval |
| **Rate limiting** | Gateway, per-IP and per-token; a tighter bucket on `POST /bookings` to blunt seat-sniping bots |
| **Idempotency** | `Idempotency-Key` required on all POSTs; key + body hash + stored response, 24 h retention |
| **Caching** | Redis for availability (short TTL + event invalidation), catalog (long TTL), ETags at the edge |
| **Resilience** | Timeouts, bounded retries with jitter, circuit breakers, bulkheads per downstream |
| **Observability** | OpenTelemetry traces, RED metrics, JSON logs with correlation id — see `docs/12` |
| **Config** | Env vars only; no profile-specific code paths — see `docs/17` |
| **Migrations** | Flyway per service, forward-only, expand/contract for zero-downtime — see `docs/07` |
| **i18n** | Station names in `en`/`si`/`ta`; `Accept-Language` honoured |

---

## 9. Deployment topology

Local (`docker compose --profile core`): gateway, booking, catalog, pricing, web, postgres, redis.
Everything else is behind `--profile full`.

Production target is any container platform. The services are deliberately platform-agnostic — a
Dockerfile, an OpenAPI spec, env-var config, and health endpoints — which is what makes them portable to
WSO2 Choreo, plain Kubernetes, or the department's own runtime without code changes. See
[`08-microservices-and-deployment.md`](08-microservices-and-deployment.md).

---

## 10. Known architectural risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| booking-service becomes a fat service | Medium | Medium | Modular internal packaging by aggregate; public event contracts make extraction mechanical |
| Hot partition on a popular trip | High (peak dates) | Medium | Contention is per (seat, range), not per trip; queue-based fair admission is a documented next step |
| Kafka operational burden for a small deployment | Medium | Medium | Outbox pattern means Kafka is never on the booking critical path; the `core` profile omits it entirely |
| Postgres single point of failure | Low | High | Managed HA with sync replica; read replicas for reporting only |
| Fare rule changes mid-flight | Medium | Low | Rules are versioned and effective-dated; a quote records the rule version applied |

---

**Next:** [`03-domain-model.md`](03-domain-model.md)
