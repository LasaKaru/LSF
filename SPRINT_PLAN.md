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

> ## ✅ Status: complete, with variances recorded below
>
> Every item carries its real outcome — **✅ done**, **⚠️ done differently**, or **❌ not done**. The
> variances are the interesting part of a plan, so they are marked rather than quietly rewritten to match
> what happened. Summary:
>
> - **All five checkpoints passed.** The concurrency proof, README, clean-machine run and secret scan —
>   the four items marked *never cut* — all shipped.
> - **Only two things were cut**, both from the pre-agreed cut list: SSE live availability (#2) and the
>   trilingual UI switcher (#4). The waitlist was #1 on that list and was built anyway.
> - **One item was silently missed rather than cut: the k6 load test.** It was not on the cut list, and
>   worse, two documents described its *results* as though it had run. That is corrected in
>   `docs/14 §4` and `docs/10 §7`, and the deadlock it was supposed to have found is now covered by a
>   real test, `MultiSeatDeadlockIT`.
> - **The daily `docker compose down -v && docker compose up` ritual was impossible** — no Docker daemon
>   in the build environment. CI's clean-machine smoke test carries that load instead. This is the single
>   biggest gap between plan and execution.

## Milestones

| Checkpoint | When | Gate condition | Outcome |
|---|---|---|---|
| **CP1 — Invariant proven** | Sat 22:00 | Exclusion constraint works; adjacency test green | ✅ **Passed.** Spiked in raw SQL before any Java: overlap blocked 2.37 s then `23P01`; adjacency 0.087 s, zero contention |
| **CP2 — Core API complete** | Sun 20:00 | Book, confirm, cancel, availability, idempotency | ✅ **Passed.** Fare sophistication was never cut — telescopic bands shipped |
| **CP3 — End-to-end working** | Mon 14:00 | Search → book → ticket in a browser | ✅ **Passed.** Seat map shipped; the list-view fallback was not needed |
| **CP4 — 🚧 THE GATE 🚧** | **Mon 18:00** | **All Must-haves ✅, concurrency suite green, README written** | ✅ **Passed**, so extras were allowed to start |
| **CP5 — Submission ready** | Tue 18:00 | Clean-machine run verified, repo public | ✅ **Passed.** Clean-machine run is green *in CI*, not locally — see the variance note above. Repo is public; gitleaks scans full history |

CP4 is the only checkpoint that can veto work rather than merely reshape it.

---

## Day 0 — Saturday 1 August (evening, ~5 h)

**Theme: prove the hard thing first.**

| Time | Work | Output | ✓ |
|---|---|---|---|
| 18:00 | Read the brief twice. Write the problem in my own words. Identify it as interval packing | `docs/01` draft | ✅ |
| 18:30 | Repo init. **`.gitignore` in the very first commit**, before any env file exists | `chore: initialise repository` | ✅ commit `8394a67` contains exactly `.gitignore` + `.env.example`, nothing else |
| 19:00 | Compose skeleton: Postgres 16, health checks, Flyway | `chore: compose + postgres + flyway` | ✅ |
| 19:30 | **Spike the exclusion constraint in raw SQL in psql.** No application code at all | Confidence, or an early pivot | ✅ the highest-value hour of the project |
| 20:30 | Domain schema: stations, routes, trips, coaches, seats | `feat: network and trip schema` | ✅ |
| 21:30 | `booking_segment` + generated `int4range` + GiST exclusion constraint | `feat: segment exclusion constraint` ★ | ✅ |
| 22:00 | **CP1** — adjacency and overlap verified by hand in psql | ✅ / rethink | ✅ passed |

Spiking the constraint in psql before writing a line of Java is deliberate: if the primitive did not
behave as expected, I needed to know in hour two, not on Monday. R1 in the risk register is the only
Critical-impact risk, and it is retired first.

## Day 1 — Sunday 2 August (~10 h)

**Theme: make it real and prove it under concurrency.**

| Block | Work | ✓ |
|---|---|---|
| 09:00–10:30 | Booking service skeleton, JPA entities, `Leg` value object with validating factory | ⚠️ **no JPA.** `NamedParameterJdbcTemplate` instead — the write path is one carefully shaped INSERT whose failure mode *is* the design, and an ORM's generated SQL and flush timing sit between me and that. Recorded as **ADR-002**. `Leg` ✅ |
| 10:30–12:00 | Availability query; station-code → sequence resolution; seed data (25 stations, 8 coaches, 30 days) | ✅ |
| 12:30–14:30 | **Booking write path**: transaction, `23P01` → 409 mapping, idempotency records | ✅ |
| 14:30–16:00 | ★ **Concurrency test suite**: 50-thread race, adjacent-leg positive test, jqwik property test | ✅ / ✅ / ⚠️ **no jqwik.** `LegTest` enumerates *every* leg pair on a 25-stop route exhaustively — stronger than sampling, and no new dependency |
| 16:00–17:00 | Hold TTL, ShedLock sweeper, lazy in-transaction expiry | ✅ |
| 17:00–18:30 | Pricing service: telescopic bands, multipliers, signed quotes, additivity property test | ✅ — and the additivity test is what disproved `FARE-1` (§9.1 of the README) |
| 18:30–20:00 | OpenAPI spec, gateway routing, problem details | ✅ |
| 20:00 | **CP2** | ✅ passed |
| 20:00–21:00 | ADRs 001–004 written **now**, while the reasoning is fresh | ⚠️ **not same-day.** All ten ADRs were written in one batch later (`9cc6193`). The intent below was right and I did not follow it |

Writing ADRs the same day as the decisions is not administrative tidiness — reconstructed reasoning is
reliably worse than recorded reasoning, and the reviewer is going to probe exactly these choices.

**And that is precisely what went wrong.** Batching all ten ADRs at the end is the one process failure
worth owning: the reasoning in them is reconstructed, which is exactly the weaker artefact this paragraph
warned about. The related failures in `docs/14 §4` and `docs/10 §6` — describing a load test and a test
class that did not exist — are the same defect in a different place. Writing up an intention in the past
tense before the work is done makes the document indistinguishable from a record of work that was.

## Day 2 — Monday 3 August (~10 h)

**Theme: make it usable, then close the gate.**

| Block | Work | ✓ |
|---|---|---|
| 09:00–11:00 | Frontend: search, trip list, TanStack Query, API client from OpenAPI | ✅ / ⚠️ **TanStack Query dropped** — the app has four screens and no shared server cache to speak of; `useState` + `fetch` was less machinery. It sat in `package.json` unused until this was audited, and has now been removed. ⚠️ API client is hand-written, not generated |
| 11:00–13:00 | Booking flow: selection → details → hold with countdown → mock payment → ticket | ✅ except ❌ **no payment step.** Confirm is a state transition and no money moves; a half-built gateway would look like more work and be worth less than an honest seam |
| 13:00–14:00 | **CP3** — end-to-end in a browser | ✅ passed |
| 14:00–16:00 | Clean-machine verification (pruned Docker). Fix whatever breaks — it always does | ❌ **impossible locally — no Docker daemon.** Substituted: PostgreSQL 16 running natively, all three services as JARs, and a Python stand-in for the gateway mirroring `nginx.conf`'s routing. Real `nginx` was later installed and the routing verified against it directly. The compose path is exercised **only by CI** |
| 16:00–18:00 | **README written in full.** Decisions, alternatives, challenges, extras | ✅ all four required sections (§3, §4, §9, §10) |
| 18:00 | **🚧 CP4 — THE GATE 🚧** | ✅ passed |
| 18:00–21:00 | *Gate passed* → extras begin: seat map component (tri-state, layout-driven) | ✅ |

## Day 3 — Tuesday 4 August (~8 h, submission day)

| Block | Work | ✓ |
|---|---|---|
| 09:00–11:00 | Seat map completion: hover occupancy bars, keyboard navigation, list-view fallback | ✅ occupancy bars. ✅ **keyboard navigation** — roving tabindex, arrows, Home/End, Ctrl+Home/End, verified in Chromium (60 seats, exactly 1 tab stop). Unavailable seats moved from `disabled` to `aria-disabled` so a keyboard user can reach them and hear *why*. ⚠️ list-view fallback exists only for unreserved coaches, which have no seat assignment |
| 11:00–12:30 | 409 conflict UX: one-click recovery, suggested alternatives, SSE live availability | ✅ / ✅ / ❌ **SSE cut** (cut-list #2). The seat map polls |
| 12:30–14:00 | Admin console: occupancy heatmap, seat-km utilisation, revenue, resale uplift | ✅ all four. The uplift metric was **inverted** on first implementation, reporting resale *losing* 24% — see README §9.6 |
| 14:00–15:00 | Waitlist service (if time — first thing to cut) | ✅ **built, not cut.** Event-driven promotion, FIFO, real held offers, its own UI, 7 integration tests |
| 15:00–16:00 | k6 load test | ❌ **not done, and not honestly reported at the time.** k6 is unavailable here. This row previously read *"(This is where the multi-seat deadlock was found — see docs/14 §4)"*, and `docs/14` reported figures from a run that never happened. Corrected; `MultiSeatDeadlockIT` now covers the deadlock for real — 0 deadlocks sorted vs 47 unsorted |
| 16:00–18:00 | Remaining docs, ADRs 005–010, cross-links, glossary | ✅ all ten ADRs, links verified resolving |
| **18:00** | **CP5 — final clean-machine run, gitleaks over full history, repo public** | ✅ clean-machine run green **in CI**; gitleaks over full history green; repo public |
| 18:30 | **Submit.** Deadline is 23:59; submitting five hours early is the buffer | ⏳ the PR is open as a **draft** — marking it ready is the author's call |

**Hard rule: no new features after 16:00 Tuesday.** The last two hours are for verification and
documentation only. Features added in the final hours are the ones that break the reviewer's first run.

---

## Cut list, in order

If time runs short, these are dropped in this sequence — decided **in advance**, so the decision is not
made under pressure at 2 a.m.:

| # | Item | Actually cut? |
|---|---|---|
| 1 | Waitlist service | ❌ **not cut** — built, with a UI and 7 tests |
| 2 | SSE live availability (fall back to ETag polling) | ✅ **cut.** The seat map polls |
| 3 | Admin console beyond a single occupancy view | ❌ not cut — full dashboard shipped |
| 4 | Trilingual UI (keep the schema and data; drop the UI switcher) | ✅ **cut.** Station names are stored and served in all three languages; the interface strings are English |
| 5 | Demand-based fare multiplier (keep telescopic bands) | ❌ not cut |
| 6 | SVG seat map (fall back to an accessible list view) | ❌ not cut |

**Never cut:** the concurrency proof, the README, the clean-machine run, the secret scan. ✅ **All four
shipped.**

Two cuts out of six, both taken in the pre-agreed order. Deciding this list in advance did the job it was
meant to do — neither cut was argued about at 2 a.m. What the list did *not* protect against was the k6
load test, which was never a candidate for cutting and simply did not happen; a cut list only governs
work you remember you are choosing not to do.

---

## Daily discipline

- **First action each day:** run `docker compose down -v && docker compose up` from scratch. A build that
  works only on my machine's warm cache is not a build.
  → ❌ **Not possible.** No Docker daemon in the build environment. CI's clean-machine job is the only
  thing that has ever run the compose path, so it is a gate rather than a convenience.
- **Last action each day:** commit, push, and update the plan with what actually happened.
  → ⚠️ Commits and pushes yes; *this* update is the plan being reconciled at the end rather than daily.
- **Never leave `main` broken overnight.** Deadline slippage plus a broken `main` is how a submission
  becomes a non-submission.
  → ✅ `main` was never pushed to directly; all work is on a feature branch behind CI.

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
