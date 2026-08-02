# 09 — Frontend Design

React 18 + TypeScript + Vite · TanStack Query · Tailwind · Zustand (small UI state only).

---

## 1. The frontend's actual job

The interesting frontend problem here is not layout — it is that **availability is a lie by the time you
render it.** Between the server computing "seat 3A is free for Fort→Kandy" and the passenger clicking
"Book", someone else can take it. Every design decision below follows from one principle:

> **Confirm at write time, not at read time.** The UI never treats availability as authoritative, and
> it is built to recover gracefully when it turns out to be wrong.

A naive implementation shows a spinner, then an alert box, then loses the passenger's selection. That is
the failure mode this design exists to prevent.

---

## 2. Screen flow

```
  ┌────────────┐   ┌─────────────┐   ┌────────────┐   ┌────────────┐
  │ 1. Search  │──►│ 2. Trip list│──►│ 3. Seat map│──►│ 4. Details │
  │ from/to/   │   │ times, avail│   │ leg-scoped │   │ passengers │
  │ date/class │   │ fare from   │   │ + fare     │   │ + contact  │
  └────────────┘   └─────────────┘   └─────┬──────┘   └─────┬──────┘
                                            │                │
                          ┌─────────────────┘                ▼
                          │ (full? ) ⭐               ┌──────────────┐
                          ▼                           │ 5. Hold +    │
                   ┌─────────────┐                    │    payment   │
                   │ Join waitlist│                   │ ⏱ countdown  │
                   └─────────────┘                    └──────┬───────┘
                                                             ▼
                                                     ┌──────────────┐
                                                     │ 6. Ticket    │
                                                     │ ref + QR     │
                                                     └──────────────┘
```

### Screen 1 — Search
Station autocomplete (searches all three scripts), date picker bounded by the booking horizon, class
selector. Recent searches persisted locally. Swap-direction button.

### Screen 2 — Trip list
Per trip: departure/arrival, duration, **availability for the requested leg** (not for the whole train —
that distinction is the entire product), lowest fare, and a compact route bar showing where the passenger's
leg sits within the full journey. Sold-out trips show a *Join waitlist* action rather than being hidden.

### Screen 3 — Seat map ⭐
Covered in §3.

### Screen 4 — Passenger details
Minimal fields. Inline validation. Fare breakdown always visible — the passenger should be able to see
that they are paying for 121 km, not for a whole train.

### Screen 5 — Hold and payment
A **live countdown** on the hold. At 2 minutes remaining the banner escalates; at expiry the UI does not
silently fail — it explains what happened and offers one-click re-selection of the same seat if it is
still free.

### Screen 6 — Ticket
Reference, QR, add-to-calendar, and a plain-language statement of what was bought: *"Seat 3A, coach R1,
Colombo Fort → Kandy."*

---

## 3. The seat map component ⭐

### 3.1 Tri-state colouring — the state that only exists here

| State | Colour | Meaning |
|---|---|---|
| Available | Green | Free for the whole requested leg |
| Taken | Grey | Occupied for some part of the requested leg |
| **Partially available** | **Amber** | Free for *part* of your requested leg — a state that is meaningless in whole-journey booking and is the visible signature of segment inventory |
| Selected | Blue | Your choice |
| Not bookable | Hatched | Structural (no seat at that grid position) |

Amber seats are informative rather than selectable: hovering shows *"Free Colombo Fort → Gampaha only"*,
which is genuinely useful — a passenger going to Gampaha learns they should shorten their search.

### 3.2 Occupancy detail on hover

Each seat renders a miniature route bar showing occupied stretches against the full journey:

```
 3A   ▓▓▓▓▓▓▓▓░░░░░░░░▓▓▓▓▓▓▓▓
      CMB     KDY   NAN     BDL
      └ taken ┘     └ taken ┘
```

Ranges only. **No passenger names, no booking references, nothing identifying** — the endpoint does not
return them, so the UI could not leak them even if a future developer tried.

### 3.3 Layout is data, not code

The component is a pure function of `(layout, occupancy)`:

```tsx
type CoachLayout = {
  rows: number; columns: number; aisleAfterColumn: number | null;
  seatPattern: string[]; facing: 'FORWARD' | 'BACKWARD';
  blanks?: Array<{ row: number; col: number }>;
};
```

A 2+2 second-class coach, a 2+1 observation saloon and a 3+2 commuter coach are three rows in
`coach_layout` — **adding a new coach type requires zero frontend changes.** This is the front-end half
of requirement R10.

### 3.4 Accessibility

An SVG seat grid is a classic accessibility failure. Mitigations:

- Rendered as a `role="grid"` with roving tabindex; arrow keys move between seats.
- Each seat has an `aria-label` carrying every fact the colour conveys: *"Seat 3A, window, coach R1,
  available, LKR 470."* Colour is never the sole channel.
- Status changes announced via `aria-live="polite"`.
- A **list view toggle** offering the same functionality without spatial navigation.
- WCAG 2.1 AA contrast, verified in CI with axe-core.

---

## 4. Conflict handling ⭐

### 4.1 Live availability (SSE)

The seat map subscribes to `/trips/{id}/availability/stream`. Seats grey out as others book them, with a
brief highlight so the change is perceptible rather than spooky. Falls back to ETag polling (5 s) if SSE
is unavailable. The stream is a *hint*; it never gates submission.

### 4.2 The 409 recovery path

When `POST /bookings` returns `409 SEAT_SEGMENT_UNAVAILABLE`:

1. The lost seat flashes red and settles to grey — the passenger *sees* what happened rather than reading
   about it.
2. A non-modal banner: *"Seat 3A was just taken. Seat 3C is also a window seat at the same fare."*
3. The suggestion comes from `suggestedAlternatives` in the problem detail, matched on the passenger's
   original preferences (window/aisle, proximity to their first choice).
4. **One click** re-submits with the alternative. Passenger details are preserved — nothing is retyped.
5. If nothing comparable is free, the waitlist CTA appears inline.

The design goal is that losing a race costs the passenger **one click and about three seconds**, not a
restart.

### 4.3 Optimistic UI with honest boundaries

| Action | Treatment |
|---|---|
| Seat selection | Optimistic — instant, local only |
| Fare display | Optimistic from the quote, reconciled on hold |
| **Booking submit** | **Never optimistic.** Pending state, button disabled, no "success" until 201 |
| Cancellation | Optimistic with rollback |

Rule: optimistic UI is for things the client can predict. A booking outcome depends on other people, so
pretending to know it is dishonest.

### 4.4 Double-submit protection

The `Idempotency-Key` is generated **once when the booking form is first rendered** and reused for every
retry of that attempt. A double-tap, a flaky reconnect, or an impatient user hitting refresh mid-request
all resolve to the same booking rather than two.

---

## 5. State management

| State | Owner | Why |
|---|---|---|
| Server data (trips, availability, bookings) | **TanStack Query** | Caching, revalidation, retries, request dedup — this is 90% of the state |
| SSE deltas | Query cache updates | Push writes into the same cache reads use, so there is one source of truth |
| Search form, selected seats, UI toggles | **Zustand** | Small, ephemeral, non-server |
| Booking draft (survives refresh) | `sessionStorage` | A dropped connection mid-flow should not lose 4 minutes of typing |
| Auth token | httpOnly cookie | Not `localStorage` — XSS-exfiltratable |

Deliberately no Redux. The server-state library covers the hard part, and the remaining client state is a
handful of values.

---

## 6. Performance

| Technique | Effect |
|---|---|
| Route-level code splitting | Search screen ships without the seat map bundle |
| Virtualised seat grid | 180+ seats render without jank on low-end Android |
| `Accept-Encoding: br`, HTTP/2 | Small payloads on 3G |
| Debounced station autocomplete (250 ms) | Fewer requests on slow links |
| ETag revalidation | Cheap polling — usually a 304 |
| Skeletons, not spinners | Perceived performance and no layout shift |
| Budgets enforced in CI | LCP < 2.5 s, CLS < 0.1, initial JS < 180 KB gzipped on a throttled 3G profile |

The 3G profile is not decoration. This is a public transport service in Sri Lanka; the median user is on
a mid-range Android phone on a congested mobile network, and a 2 MB bundle is a broken product regardless
of how good the seat map looks on a laptop.

---

## 7. Internationalisation

Sinhala, Tamil, English, switchable at runtime. Station names come from the API in all three scripts
(`Accept-Language`), so translations live with the data rather than in the bundle. Numerals and dates
localised via `Intl`. Fonts subset per script and lazily loaded so English users do not download Sinhala
glyphs.

---

## 8. Admin console ⭐

Separate bundle, separate route, `admin:read` scope required.

- **Segment occupancy heatmap** — stops on the x-axis, coaches on the y-axis, colour = occupancy.
  The visual answer to *"where does the train empty out?"*, which is the question leadership actually has.
- **Seat-km utilisation** — sold seat-km ÷ available seat-km. The honest load metric: a train with every
  seat sold for 10% of the route is not a full train.
- **Revenue by segment** — where money is actually earned along the line.
- **Resale uplift** — actual revenue vs the whole-journey-only counterfactual. The number that justifies
  the project.
- **Booking search and audit trail.**

---

## 9. Testing

| Level | Tool | Focus |
|---|---|---|
| Unit | Vitest + Testing Library | Seat map states, fare formatting, form validation |
| Component | Storybook | Every seat state including amber and hatched; every layout |
| Integration | MSW | 409 recovery, hold expiry, SSE updates — the paths that are hard to reach manually |
| E2E | Playwright | Full booking journey; **two browser contexts racing for the same seat** |
| A11y | axe-core in CI | WCAG 2.1 AA |
| Visual | Playwright snapshots | Seat map rendering across layouts |

The two-context Playwright race test is the frontend counterpart of the backend concurrency proof: one
context wins, the other must show the recovery banner and successfully rebook in one click.

---

**Related:** [`06-api-contract.md`](06-api-contract.md) · [`13-extra-credit.md`](13-extra-credit.md)
