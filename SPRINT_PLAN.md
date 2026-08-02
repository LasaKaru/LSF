# SPRINT PLAN

Two parts, because "sprint plan" means two different things here:

- **Part A — the 72-hour execution plan** for the take-home itself (Sat 1 Aug → Tue 4 Aug 2026).
- **Part B — the product roadmap** this would follow if the department adopted it.

A two-week sprint cadence would be theatre for a submission due in three days. Part A is what actually
governed the work; Part B is what I would propose on day one of a real engagement.

---

# PART A — 72-hour execution plan

**Working assumption:** ~30 focused hours across four days, not 72 continuous ones. Buffer is built in
because it always gets consumed.

## Milestones

| Checkpoint | When | Gate condition | If missed |
|---|---|---|---|
| **CP1 — Invariant proven** | Sat 22:00 | Exclusion constraint works; adjacency test green | **Stop and rethink the model.** Everything depends on this |
| **CP2 — Core API complete** | Sun 20:00 | Book, confirm, cancel, availability, idempotency | Cut fare sophistication to flat rate |
| **CP3 — End-to-end working** | Mon 14:00 | Search → book → ticket in a browser | Ship the list view; drop the SVG seat map |
| **CP4 — 🚧 THE GATE 🚧** | **Mon 18:00** | **All Must-haves ✅, concurrency suite green, README written** | **No extras. None. Spend the remaining time hardening** |
| **CP5 — Submission ready** | Tue 18:00 | Clean-machine run verified, repo public | Submit whatever is green; never push a broken `main` |

CP4 is the only checkpoint that can veto work rather than merely reshape it.

---

## Day 0 — Saturday 1 August (evening, ~5 h)

**Theme: prove the hard thing first.**

| Time | Work | Output |
|---|---|---|
| 18:00 | Read the brief twice. Write the problem in my own words. Identify it as interval packing | `docs/01` draft |
| 18:30 | Repo init. **`.gitignore` in the very first commit**, before any env file exists | `chore: initialise repository` |
| 19:00 | Compose skeleton: Postgres 16, health checks, Flyway | `chore: compose + postgres + flyway` |
| 19:30 | **Spike the exclusion constraint in raw SQL in psql.** No application code at all | Confidence, or an early pivot |
| 20:30 | Domain schema: stations, routes, trips, coaches, seats | `feat: network and trip schema` |
| 21:30 | `booking_segment` + generated `int4range` + GiST exclusion constraint | `feat: segment exclusion constraint` ★ |
| 22:00 | **CP1** — adjacency and overlap verified by hand in psql | ✅ / rethink |

Spiking the constraint in psql before writing a line of Java is deliberate: if the primitive did not
behave as expected, I needed to know in hour two, not on Monday. R1 in the risk register is the only
Critical-impact risk, and it is retired first.

## Day 1 — Sunday 2 August (~10 h)

**Theme: make it real and prove it under concurrency.**

| Block | Work |
|---|---|
| 09:00–10:30 | Booking service skeleton, JPA entities, `Leg` value object with validating factory |
| 10:30–12:00 | Availability query; station-code → sequence resolution; seed data (25 stations, 8 coaches, 30 days) |
| 12:30–14:30 | **Booking write path**: transaction, `23P01` → 409 mapping, idempotency records |
| 14:30–16:00 | ★ **Concurrency test suite**: 50-thread race, adjacent-leg positive test, jqwik property test |
| 16:00–17:00 | Hold TTL, ShedLock sweeper, lazy in-transaction expiry |
| 17:00–18:30 | Pricing service: telescopic bands, multipliers, signed quotes, additivity property test |
| 18:30–20:00 | OpenAPI spec, gateway routing, problem details |
| 20:00 | **CP2** |
| 20:00–21:00 | ADRs 001–004 written **now**, while the reasoning is fresh |

Writing ADRs the same day as the decisions is not administrative tidiness — reconstructed reasoning is
reliably worse than recorded reasoning, and the reviewer is going to probe exactly these choices.

## Day 2 — Monday 3 August (~10 h)

**Theme: make it usable, then close the gate.**

| Block | Work |
|---|---|
| 09:00–11:00 | Frontend: search, trip list, TanStack Query, API client from OpenAPI |
| 11:00–13:00 | Booking flow: selection → details → hold with countdown → mock payment → ticket |
| 13:00–14:00 | **CP3** — end-to-end in a browser |
| 14:00–16:00 | Clean-machine verification (pruned Docker). Fix whatever breaks — it always does |
| 16:00–18:00 | **README written in full.** Decisions, alternatives, challenges, extras |
| 18:00 | **🚧 CP4 — THE GATE 🚧** |
| 18:00–21:00 | *Gate passed* → extras begin: seat map component (tri-state, layout-driven) |

## Day 3 — Tuesday 4 August (~8 h, submission day)

| Block | Work |
|---|---|
| 09:00–11:00 | Seat map completion: hover occupancy bars, keyboard navigation, list-view fallback |
| 11:00–12:30 | 409 conflict UX: one-click recovery, suggested alternatives, SSE live availability |
| 12:30–14:00 | Admin console: occupancy heatmap, seat-km utilisation, revenue, resale uplift |
| 14:00–15:00 | Waitlist service (if time — first thing to cut) |
| 15:00–16:00 | k6 load test. *(This is where the multi-seat deadlock was found — see `docs/14` §4)* |
| 16:00–18:00 | Remaining docs, ADRs 005–010, cross-links, glossary |
| **18:00** | **CP5 — final clean-machine run, gitleaks over full history, repo public** |
| 18:30 | **Submit.** Deadline is 23:59; submitting five hours early is the buffer |

**Hard rule: no new features after 16:00 Tuesday.** The last two hours are for verification and
documentation only. Features added in the final hours are the ones that break the reviewer's first run.

---

## Cut list, in order

If time runs short, these are dropped in this sequence — decided **in advance**, so the decision is not
made under pressure at 2 a.m.:

1. Waitlist service
2. SSE live availability (fall back to ETag polling)
3. Admin console beyond a single occupancy view
4. Trilingual UI (keep the schema and data; drop the UI switcher)
5. Demand-based fare multiplier (keep telescopic bands)
6. SVG seat map (fall back to an accessible list view)

**Never cut:** the concurrency proof, the README, the clean-machine run, the secret scan.

---

## Daily discipline

- **First action each day:** run `docker compose down -v && docker compose up` from scratch. A build that
  works only on my machine's warm cache is not a build.
- **Last action each day:** commit, push, and update the plan with what actually happened.
- **Never leave `main` broken overnight.** Deadline slippage plus a broken `main` is how a submission
  becomes a non-submission.

---

# PART B — Product roadmap (if adopted)

Two-week sprints, cross-functional team of ~5. This is what I would propose after the take-home, and it
front-loads *measurement* rather than features — because the brief's premise ("leadership believes there's
revenue on the table") is currently a belief, and a pilot that cannot prove or disprove it is worthless.

## Sprint 1 — Production readiness

**Goal:** deployable to a real environment.
Real IdP integration · secret management via the platform store · HA Postgres with PITR, rehearsed ·
observability stack and dashboards · load testing at projected peak · security review and pen test scope.
**Exit:** staging environment passing a peak-load simulation.

## Sprint 2 — Payments and tickets

**Goal:** real money, real tickets.
PayHere/LankaPay integration · refund and cancellation policy engine · signed offline-verifiable QR
tickets · conductor inspection app · payment reconciliation and settlement reporting.
**Exit:** an end-to-end paid booking, inspected on a train.

## Sprint 3 — Pilot on one service

**Goal:** learn from reality on the narrowest possible surface.
One train, both directions · counter-clerk interface for station sales · staff training · **daily
seat-km utilisation reporting from day one** · a structured feedback loop with passengers and staff.
**Exit:** two weeks of real bookings and a written learning report.

## Sprint 4 — Measurement and pricing calibration

**Goal:** turn belief into evidence.
Calibrate fare bands against real SLR tariffs and observed demand · resale uplift analysis against the
counterfactual · **the reserved/unreserved rebalancing model** (`docs/15` §1.1) · a departmental
decision-support dashboard.
**Exit:** a defensible recommendation on coach composition and fare structure, backed by pilot data.

## Sprint 5 — Scale-out

**Goal:** the full line, then the network.
All Badulla-line services · northern and southern lines · trilingual rollout · group booking with
adjacency optimisation · waitlist at production scale.
**Exit:** the full up-country route live.

## Sprint 6 — Optimisation

**Goal:** capture the remaining value.
Overbooking with a per-segment no-show model (now that data exists) · fair-admission queue for peak
releases · CQRS availability projection if read volume demands it · fragmentation-minimising seat
assignment tuned against real occupancy data.
**Exit:** measured improvement in seat-km utilisation versus the pilot baseline.

## Sequencing rationale

Measurement comes before optimisation, and a pilot comes before scale. The temptation with a project like
this is to roll out the network-wide version of a change nobody has yet proved works. Sprint 3 exists so
that if the thesis is wrong — if seats do not in fact resell at a useful rate — we find out on one train,
in two weeks, instead of across the network after six months.

---

**See also:** [`PROJECT_PLAN.md`](PROJECT_PLAN.md) · [`docs/15-future-work.md`](docs/15-future-work.md)
