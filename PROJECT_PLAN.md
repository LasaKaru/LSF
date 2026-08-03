# PROJECT PLAN — Yathra Segment-Based Rail Booking

**Deadline:** Tuesday 4 August 2026, 23:59 · **Start:** Saturday 1 August 2026 · **Window: ~72 hours**
**Delivery mode:** solo, time-boxed, AI-assisted (see §9)

---

## 1. Objective

Deliver a working, defensible segment-based booking system for the Colombo Fort ⇄ Badulla line, with a
README and documentation set that let a reviewer understand the design without reading the code, and let
me extend it live under questioning.

**The single measure of success:** a reviewer can watch one seat be sold twice on non-overlapping legs,
watch 50 concurrent overlapping attempts produce exactly one winner, and be shown *in the schema* why
that is guaranteed rather than hoped for.

---

## 2. Scope — MoSCoW

### Must have (submission fails without these)

| # | Item | Verification | Status |
|---|---|---|---|
| M1 | Segment-based inventory model | `SegmentExclusionConstraintIT` | ✅ 9 tests |
| M2 | Adjacent legs on one seat both succeed | `ConcurrentAdjacentBookingIT` | ✅ — merged into `ConcurrentBookingIT` rather than a separate class |
| M3 | Overlapping legs on one seat rejected under concurrency | `ConcurrentBookingIT` | ✅ 50 threads → exactly 1 × 201 |
| M4 | Distance-proportional fares | `FareBandTest`, additivity property test | ✅ as **`FareCalculatorTest`**, 16 tests. The additivity test is what disproved `FARE-1` |
| M5 | REST API: stations, trips, availability, bookings | OpenAPI + integration tests | ✅ |
| M6 | Frontend: origin/destination → leg-specific availability → book | Playwright happy path | ⚠️ **built and verified, but not by a committed Playwright suite.** The whole flow was driven through Chromium manually (search → seat map → hold → confirm → ticket, and the waitlist journey end to end). An automated E2E suite remains future work |
| M7 | Configurable coaches, seats, stations | Seed + a "add a coach with SQL only" check | ✅ layouts are `coach_layout` JSON; the seat map is a pure function of it |
| M8 | `docker compose up` from a clean machine | CI job on a fresh runner | ✅ **green in CI — and only ever there.** No Docker daemon in the dev environment |
| M9 | No committed secrets | `gitleaks` over full history, blocking | ✅ green, blocking |
| M10 | README: decisions, alternatives, challenges, extras | Self-review against the brief, line by line | ✅ §3, §4, §9, §10 |
| M11 | Legible commit history | `git log --oneline --graph` review | ✅ Conventional Commits, no squashing |

**All 11 Must-haves met.**

### Should have

| # | Item | Status |
|---|---|---|
| S1 | Seat map | ✅ tri-state, layout-driven, keyboard-navigable |
| S2 | Admin occupancy/revenue | ✅ full dashboard: seat-km utilisation, heatmap, revenue, resale uplift |
| S3 | 409 conflict UX with one-click recovery | ✅ |
| S4 | Hold-then-confirm with TTL | ✅ sweeper + in-transaction expiry |
| S5 | Idempotency | ✅ key + body hash, same transaction |
| S6 | ADRs | ⚠️ all ten written — but batched at the end, not as decisions were made. See `SPRINT_PLAN` Day 1 |
| S7 | Load test | ❌ **not done.** k6 unavailable. Worse, it was reported as done in `docs/14 §4`; corrected, and the deadlock it claimed to have found is now covered by `MultiSeatDeadlockIT` |

### Could have

| # | Item | Status |
|---|---|---|
| C1 | Waitlist | ✅ **built** — event-driven promotion, FIFO, real held offers, its own UI, 7 tests. This was #1 on the cut list and survived |
| C2 | SSE live availability | ❌ cut (cut-list #2). The seat map polls |
| C3 | Telescopic + scenic + demand fares | ✅ all three |
| C4 | Trilingual UI | ⚠️ **data yes, UI no** (cut-list #4). Station names are stored and served in English, Sinhala and Tamil; interface strings are English |
| C5 | Observability profile | ❌ config surface exists, containers do not |

### Won't have (this iteration)

W1 real payment gateway · W2 SMS/email transports · W3 group adjacency optimiser · W4 overbooking ·
W5 native apps · W6 multi-train itineraries

Won't-haves are documented in [`docs/15-future-work.md`](docs/15-future-work.md) with an approach sketch,
so the absence reads as a decision rather than an omission.

---

## 3. The gate

> **No Could-have work begins until every Must-have is complete, the concurrency suite is green, and the
> README is written.**

This is the single most important line in the plan. The brief says explicitly that *"a focused,
well-reasoned core beats an impressive list of extras."* Five extra-credit options, a Tuesday deadline,
and every extra more enjoyable than writing tests — intention alone would not have survived that. The
gate is time-stamped in [`SPRINT_PLAN.md`](SPRINT_PLAN.md) and treated as non-negotiable.

---

## 4. Work breakdown

| Phase | Deliverable | Depends on |
|---|---|---|
| **P0 Foundation** | Repo, `.gitignore` (secrets first), Compose skeleton, Postgres, Flyway, CI | — |
| **P1 Domain & schema** | Stations, routes, trips, coaches, seats, snapshot model, seed | P0 |
| **P2 The invariant** ★ | `booking_segment`, generated `int4range`, GiST exclusion constraint | P1 |
| **P3 Proof** ★ | Unit + Testcontainers + race + property tests | P2 |
| **P4 Booking service** | Availability, hold/confirm/cancel, idempotency, error mapping | P2 |
| **P5 Fares** | Telescopic bands, multipliers, signed quotes | P1 |
| **P6 API surface** | OpenAPI, gateway, problem details, pagination | P4, P5 |
| **P7 Frontend core** | Search → availability → book → ticket | P6 |
| **P8 Docs** | README + `docs/` + ADRs | P2–P7 |
| **P9 Extras** | Seat map, admin, waitlist, SSE, i18n | **Gate passed** |
| **P10 Hardening** | Load test, clean-machine verification, final review | P9 |

P2 and P3 are marked ★ because they are the project. Everything else is competent scaffolding.

---

## 5. Risk register

| # | Risk | L | I | Mitigation | Trigger → response |
|---|---|---|---|---|---|
| R1 | Concurrency approach proves unworkable | Low | **Critical** | Prototype the exclusion constraint in the first hours, before building anything on top of it | Not proven by end of Day 0 → fall back to `SERIALIZABLE` + retry, documented as a compromise |
| R2 | Scope creep into extras | **High** | High | The §3 gate | Any Could-have started before the gate → stop, revert, resume core |
| R3 | Clean-machine startup fails for the reviewer | Medium | **High** | Test on a pruned Docker environment daily, not once at the end | Failure → cut services from the `core` profile until it starts reliably |
| R4 | Time overrun on the frontend | Medium | Medium | Build the functional path first; visual polish is the last thing added | Behind at the Day 2 checkpoint → ship the list view, drop the SVG seat map |
| R5 | Secret accidentally committed | Low | **Critical** | `.gitignore` in the first commit; pre-commit gitleaks; CI over full history | Leak → **rotate first**, scrub second |
| R6 | Documentation squeezed at the end | **High** | High | Write ADRs *as decisions are made*, not retrospectively | Docs not started by Day 2 → cut an extra, not the docs |
| R7 | Undiscovered concurrency bug | Medium | High | Load test the write path, not just correctness tests | **The mitigation was never executed.** This row used to read *"(Materialised — the multi-seat deadlock, see `docs/14` §4)"*, which was false: no load test ran, and the deadlock was reasoned about rather than discovered. The risk is **open**. What exists instead is `MultiSeatDeadlockIT` (0 deadlocks sorted vs 47 unsorted) — a targeted test for the one bug I predicted, which is exactly the class of assurance a load test is supposed to *supplement*, not replace |
| R8 | Fare figures unrealistic | Medium | Low | Flag as illustrative everywhere; keep rules as data | — |
| R9 | Over-engineering the microservice split | Medium | Medium | Compose profiles; default topology is six containers | Cold start > 2 min → move a service behind `full` |

R2 and R6 are rated High-likelihood deliberately. They are the two failure modes I have most often seen
sink otherwise-good take-home submissions, and both are failures of discipline rather than of skill.

---

## 6. Definition of Done

**Per feature:** implemented · unit + integration tested · errors mapped to problem details · configurable
where the brief requires it · documented · no secrets · builds green.

**Per document:** decisions stated with reasoning · alternatives named with reasons for rejection ·
trade-offs acknowledged including the unflattering ones · cross-linked.

**For submission:**

- [x] `docker compose up` works on a machine with a pruned Docker cache — *green in CI's clean-machine job; never run locally, because there is no Docker daemon here*
- [x] `./scripts/demo-segment-resale.sh` demonstrates the core behaviour end to end — *runs in that same CI job*
- [x] `./scripts/concurrency-proof.sh` passes: 1 winner of 50, and 2 winners on adjacent legs
- [x] Every Must-have verified against §2 — *11 of 11; M6 verified manually rather than by a committed Playwright suite*
- [x] README contains all four items the brief asks for — *§3 decisions, §4 alternatives, §9 challenges, §10 extra credit*
- [x] `gitleaks detect` clean over full history — *blocking CI job*
- [x] Commit history reads as a progression, not a dump
- [x] Repository made public
- [ ] **Form submitted** — *the author's action. The PR is open as a draft; marking it ready for review is not mine to do*

---

## 7. Quality gates in CI

The workflow has **four jobs**, all blocking. The rest of this table was aspiration; it is marked as such
rather than left to imply a pipeline that does not exist.

| Gate | Blocking | In `ci.yml`? |
|---|---|---|
| Compile + lint | Yes | ✅ *Backend* and *Frontend* jobs |
| Unit tests | Yes | ✅ *Backend* |
| Integration tests (real PostgreSQL) | Yes | ✅ *Backend* — a Postgres 16 service container, **never H2** |
| **Concurrency suite** | **Yes — the project's reason for existing** | ✅ *Backend* |
| **gitleaks (full history)** | **Yes — explicit brief requirement** | ✅ *Secret scan* |
| Clean-machine compose smoke test | Yes | ✅ *Clean-machine smoke test* — brings the stack up, runs the resale demo and the concurrency proof |
| OpenAPI breaking-change diff | Yes | ❌ **not implemented** |
| Coverage thresholds (95% on booking domain) | Yes | ❌ **not implemented** — no coverage gate, and no measurement |
| SAST + dependency + image scan | Yes on High/Critical | ⚠️ **partial and not ours.** GitHub's Dependabot raises alerts on this repository (there are open ones for `vite`), but nothing blocks the build |

---

## 8. Version control

- **Conventional Commits**, small and sequential. No squash-on-merge for feature branches — the brief asks
  to see the progression, and squashing erases it.
- Branch per phase (`feat/p2-exclusion-constraint`), merged with a merge commit.
- The commit history is intended to be readable as a narrative: schema → invariant → proof → API → UI →
  extras → docs, **including the model rewrite in `docs/14` §1**. That rewrite is visible in the history
  on purpose; a design that appears to have arrived fully formed is less believable than one you can watch
  being corrected.
- Repository private during work, public at submission.

---

## 9. AI assistance — how it was used

The brief permits AI assistants and requires that I understand and can defend everything submitted.
Recording the division of labour honestly:

| Used for | Not used for |
|---|---|
| Boilerplate (DTOs, mappers, Compose scaffolding, test fixtures) | The concurrency design — chosen and reasoned by me |
| Rubber-ducking the exclusion constraint against alternatives | The data model — including finding my own first model wrong |
| Drafting and tightening documentation prose | The trade-off judgements, which are mine to defend |
| Generating test case permutations for the interval truth table | Understanding why any of it works |

Every generated line was reviewed. Anything I could not explain was rewritten or removed — a walkthrough
where the author cannot defend a decision is worse than a simpler system the author fully owns.

---

## 10. Post-submission

Prepared, not improvised: [`docs/16-demo-and-walkthrough.md`](docs/16-demo-and-walkthrough.md) contains a
15-minute demo script, the questions I expect with my answers, and six rehearsed live-extension
scenarios with the changes they require.

---

**See also:** [`SPRINT_PLAN.md`](SPRINT_PLAN.md) — hour-level execution and the longer product roadmap.
