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

| # | Item | Verification |
|---|---|---|
| M1 | Segment-based inventory model | `SegmentExclusionConstraintIT` |
| M2 | Adjacent legs on one seat both succeed | `ConcurrentAdjacentBookingIT` |
| M3 | Overlapping legs on one seat rejected under concurrency | `ConcurrentBookingIT` |
| M4 | Distance-proportional fares | `FareBandTest`, additivity property test |
| M5 | REST API: stations, trips, availability, bookings | OpenAPI + integration tests |
| M6 | Frontend: origin/destination → leg-specific availability → book | Playwright happy path |
| M7 | Configurable coaches, seats, stations | Seed + a "add a coach with SQL only" check |
| M8 | `docker compose up` from a clean machine | CI job on a fresh runner |
| M9 | No committed secrets | `gitleaks` over full history, blocking |
| M10 | README: decisions, alternatives, challenges, extras | Self-review against the brief, line by line |
| M11 | Legible commit history | `git log --oneline --graph` review |

### Should have

S1 seat map · S2 admin occupancy/revenue · S3 409 conflict UX with one-click recovery ·
S4 hold-then-confirm with TTL · S5 idempotency · S6 ADRs · S7 load test

### Could have

C1 waitlist · C2 SSE live availability · C3 telescopic + scenic + demand fares · C4 trilingual UI ·
C5 observability profile

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
| R7 | Undiscovered concurrency bug | Medium | High | Load test the write path, not just correctness tests | (Materialised — the multi-seat deadlock, see `docs/14` §4) |
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

- [ ] `docker compose up` works on a machine with a pruned Docker cache
- [ ] `./scripts/demo-segment-resale.sh` demonstrates the core behaviour end to end
- [ ] `./scripts/concurrency-proof.sh` passes: 1 winner of 50, and 2 winners on adjacent legs
- [ ] Every Must-have verified against §2
- [ ] README contains all four items the brief asks for
- [ ] `gitleaks detect` clean over full history
- [ ] Commit history reads as a progression, not a dump
- [ ] Repository made public
- [ ] Form submitted before Tue 4 Aug, 23:59

---

## 7. Quality gates in CI

| Gate | Blocking |
|---|---|
| Compile + lint | Yes |
| Unit tests | Yes |
| Integration tests (Testcontainers) | Yes |
| **Concurrency suite** | **Yes — the project's reason for existing** |
| **gitleaks (full history)** | **Yes — explicit brief requirement** |
| OpenAPI breaking-change diff | Yes |
| Coverage thresholds | Yes (95% on booking domain) |
| SAST + dependency + image scan | Yes on High/Critical |
| Clean-machine compose smoke test | Yes |

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
