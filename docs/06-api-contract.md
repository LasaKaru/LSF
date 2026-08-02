# 06 — API Contract

Contract-first. The OpenAPI 3.1 specifications in `contracts/` are the source of truth; server stubs and
TypeScript clients are generated from them, and CI fails on a breaking change.

Base URL: `https://api.yathra.lk/api/v1` (locally `http://localhost:8080/api/v1`).

---

## 1. Conventions

| Concern | Convention |
|---|---|
| **Versioning** | URL path (`/api/v1`). Chosen over header versioning for cache-friendliness and because it is trivially visible in logs, curl, and browser address bars |
| **Media types** | `application/json`; errors are `application/problem+json` (RFC 9457) |
| **Money** | Integer **minor units** (`fareMinor: 47000` = LKR 470.00) plus an explicit `currency`. Never decimals in JSON |
| **Dates/times** | RFC 3339 UTC (`2026-08-04T09:15:00Z`). Service dates are `LocalDate` (`2026-08-12`) |
| **IDs** | UUIDv7 (time-ordered — index-friendly and sortable). Booking *references* are short, human-speakable codes (`YTH-7K2QD9`) with an ambiguity-free alphabet (no `O/0`, `I/1`) |
| **Idempotency** | `Idempotency-Key` header **required** on every POST |
| **Correlation** | `X-Correlation-Id` accepted, generated if absent, echoed and logged on every hop |
| **Pagination** | Cursor-based: `?limit=&cursor=`, response carries `nextCursor`. Offsets are unstable under concurrent inserts |
| **Caching** | `ETag` + `If-None-Match` on availability and catalog reads; `Cache-Control: no-store` on booking |
| **Language** | `Accept-Language: en \| si \| ta` — localises station names and messages |
| **Rate limits** | `X-RateLimit-Limit/Remaining/Reset`; `429` with `Retry-After` |

**Stations are addressed by code, not by internal ID.** `?from=CMB&to=KDY` is readable in a log, stable
across environments, and does not leak database identifiers. The API resolves codes to the trip's stop
sequences server-side — the client never sees or sends `fromSeq`.

---

## 2. Endpoint catalogue

### 2.1 Catalog

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/stations` | All stations (`code`, names in en/si/ta) |
| `GET` | `/routes` | Routes with ordered stops and cumulative distances |
| `GET` | `/routes/{code}/stops` | Stop list for one route |
| `GET` | `/trips` | Search: `?routeCode=&date=&from=&to=&class=` |
| `GET` | `/trips/{tripId}` | Trip detail: stops, timings, coach composition |

### 2.2 Availability — the leg-scoped read

```http
GET /api/v1/trips/{tripId}/availability?from=CMB&to=KDY&class=SECOND
```

```json
{
  "tripId": "…", "from": "CMB", "to": "KDY",
  "fromSeq": 1, "toSeq": 9, "distanceKm": 121.0,
  "coaches": [
    { "coachNumber": "R1", "classCode": "SECOND", "reservable": true,
      "totalSeats": 60, "availableSeats": 41 }
  ],
  "totalAvailable": 118,
  "generatedAt": "2026-08-01T09:14:22Z"
}
```

> `availableSeats` is a **snapshot, not a reservation**. The API contract explicitly states that
> availability is advisory and only `POST /bookings` is authoritative. This is written into the OpenAPI
> description so no client author assumes otherwise.

### 2.3 Seat map — occupancy ranges, not booleans

```http
GET /api/v1/trips/{tripId}/seat-map?from=CMB&to=KDY&class=SECOND
```

```json
{
  "coaches": [{
    "coachNumber": "R1", "classCode": "SECOND",
    "layout": { "rows": 15, "columns": 4, "aisleAfterColumn": 2,
                "seatPattern": ["A","B","C","D"], "facing": "FORWARD" },
    "seats": [
      { "seatId": "…", "label": "1A", "row": 1, "col": 1,
        "window": true, "aisle": false,
        "availableForRequestedLeg": true, "occupied": [] },
      { "seatId": "…", "label": "3A", "row": 3, "col": 1,
        "window": true, "aisle": false,
        "availableForRequestedLeg": false, "occupied": [[1,9],[15,25]] }
    ]
  }],
  "stops": [{ "seq": 1, "code": "CMB", "name": "Colombo Fort" }, "…"]
}
```

Returning `occupied` ranges rather than a boolean is what makes the **tri-state seat map** (available /
taken / *partially available*) possible in a single round trip, and what powers the hover detail showing
where a seat frees up. It exposes ranges only — **no passenger data of any kind**.

### 2.4 Pricing

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/quotes` | Signed, expiring priced offer for a leg (see `docs/05` §6) |
| `GET` | `/fare-rules` | Current published rule set — transparency for a public operator |

### 2.5 Booking

```http
POST /api/v1/bookings
Idempotency-Key: 5f3c9b2e-…
Content-Type: application/json

{
  "tripId": "…",
  "from": "CMB",
  "to": "KDY",
  "quoteId": "qte_01J…",
  "seatSelection": { "mode": "SPECIFIC", "seatIds": ["…"] },
  "passengers": [{ "name": "A. Perera", "type": "ADULT" }],
  "contact": { "email": "a@example.lk", "phone": "+947…" }
}
```

`seatSelection.mode`: `SPECIFIC` (client picked) | `AUTO` (server assigns via
`SeatSelectionStrategy`) | `AUTO_PREFER` (`{ window: true, adjacent: true }`).

**201 Created**

```json
{
  "bookingId": "…", "reference": "YTH-7K2QD9", "status": "HELD",
  "expiresAt": "2026-08-01T09:24:22Z", "holdSeconds": 600,
  "totalMinor": 47000, "currency": "LKR",
  "segments": [{ "seatLabel": "3A", "coachNumber": "R1",
                 "from": "CMB", "to": "KDY", "distanceKm": 121.0, "fareMinor": 47000 }],
  "paymentUrl": "/api/v1/payments/intents/…"
}
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/bookings` | Create a **hold** (201) |
| `POST` | `/bookings/{id}/confirm` | Confirm after payment authorisation |
| `GET` | `/bookings/{reference}` | Retrieve (requires token ownership or the contact email) |
| `DELETE` | `/bookings/{id}` | Cancel; releases segments, initiates refund if confirmed |
| `GET` | `/bookings/{reference}/ticket` | Ticket payload (QR content) |

### 2.6 Waitlist ⭐

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/waitlist` | Join for a `(trip, leg, class)`; returns queue position |
| `GET` | `/waitlist/{id}` | Status, position, live offer if any |
| `DELETE` | `/waitlist/{id}` | Leave |

### 2.7 Admin ⭐ (scope `admin:read`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/admin/trips/{id}/occupancy` | Per-segment occupancy + **seat-km utilisation** |
| `GET` | `/admin/revenue` | Revenue by trip / segment / date range |
| `GET` | `/admin/uplift` | **Resale uplift** vs whole-journey-only counterfactual |
| `GET` | `/admin/bookings` | Search and audit |
| `POST` | `/admin/trips/{id}/blocks` | Departmental seat blocks (an `INTERNAL_HOLD` booking) |

### 2.8 Streaming

```http
GET /api/v1/trips/{tripId}/availability/stream?from=CMB&to=KDY   (text/event-stream)

event: availability
data: {"seatId":"…","label":"3A","availableForRequestedLeg":false,"at":"2026-…"}
```

### 2.9 Operational

`GET /healthz` (liveness) · `GET /readyz` (readiness, checks DB + migrations) ·
`GET /metrics` (Prometheus) · `GET /info` (build/version).

---

## 3. Error catalogue

All errors are RFC 9457 Problem Details with a stable machine-readable `code` and, where useful,
extension members carrying data the client needs to recover.

| HTTP | `code` | When | Client should |
|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | Unparseable body | Fix and resubmit |
| 401 | `UNAUTHENTICATED` | Missing/invalid token | Re-authenticate |
| 403 | `FORBIDDEN` | Scope or ownership failure | Stop |
| 404 | `TRIP_NOT_FOUND` / `BOOKING_NOT_FOUND` | — | — |
| 409 | **`SEAT_SEGMENT_UNAVAILABLE`** | **Overlapping segment already sold** | **Re-render availability; offer `suggestedAlternatives`** |
| 409 | `BOOKING_CONTENTION` | Deadlock/serialisation after retries | Retry with backoff |
| 409 | `BOOKING_NOT_HELD` | Confirm/cancel on a terminal booking | Refresh state |
| 410 | `HOLD_EXPIRED` | TTL elapsed before confirm | Restart with a fresh quote |
| 422 | `INVALID_JOURNEY_LEG` | `from == to`, reversed, or off-trip | Fix inputs |
| 422 | `STATION_NOT_ON_TRIP` | Station not in this trip's stop list | Fix inputs |
| 422 | `QUOTE_INVALID` | Missing / expired / tampered / mismatched quote | Re-quote (a fresh quote is included) |
| 422 | `BOOKING_WINDOW_CLOSED` | Past the departure cutoff | Choose another trip |
| 429 | `RATE_LIMITED` | Throttled | Honour `Retry-After` |
| 502/503 | `UPSTREAM_UNAVAILABLE` | Payment/pricing down | Retry with backoff |

The 409 body is the one that matters; its full shape is in [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) §5.3.

---

## 4. Idempotency semantics

1. `Idempotency-Key` (client-generated UUID) is **required** on all POSTs; missing ⇒ `400`.
2. The key, a SHA-256 of the canonicalised body, and the response are stored **in the same transaction**
   as the booking. There is no window in which the booking exists but the idempotency record does not.
3. Replay with the **same** key and **same** body ⇒ the original response, with `Idempotency-Replayed: true`.
4. Same key, **different** body ⇒ `422 IDEMPOTENCY_KEY_REUSED`.
5. Concurrent duplicates: the loser hits the unique constraint, waits, and returns the winner's response.
6. Records are retained 24 h.

This is what makes a double-tapped "Confirm" button on a flaky mobile connection harmless.

---

## 5. Security summary

- OAuth2 / OIDC bearer tokens validated at the gateway; scopes `booking:read`, `booking:write`,
  `admin:read`, `admin:write`.
- Anonymous booking is permitted (station-counter and guest flows) but rate-limited harder and requires
  the booking reference **plus** the contact email to retrieve — a reference alone is guessable enough to
  matter.
- All input validated against the OpenAPI schema at the edge; unknown fields rejected.
- Full treatment in [`11-security-and-secrets.md`](11-security-and-secrets.md).

---

## 6. Compatibility policy

**Additive-only within a major version.** New optional fields and new endpoints may appear at any time;
removing a field, tightening validation, or changing a `code` requires `/api/v2`. A CI job diffs the
OpenAPI spec against `main` and fails on a breaking change, so the policy is mechanically enforced rather
than remembered.

---

**Related:** [`04-segment-concurrency-design.md`](04-segment-concurrency-design.md) ·
[`09-frontend-design.md`](09-frontend-design.md) · [ADR-009](adr/ADR-009-idempotency.md)
