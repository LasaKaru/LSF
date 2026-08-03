# ADR-007 — Java 21 + Spring Boot 3 + PostgreSQL 16 + React

**Status:** Accepted · **Date:** 2026-08-01 · **Deciders:** solo

---

## Context

The brief permits any stack. The constraints that actually bind:

1. The correctness argument requires **PostgreSQL range types and GiST exclusion constraints**
   (ADR-002). That is not a preference — it is the one hard dependency in the project.
2. A reviewer must read the code quickly and extend it live.
3. It must start in one shot on a clean machine.

## Decision

| Layer | Choice |
|---|---|
| Database | **PostgreSQL 16** — non-negotiable, see ADR-002 |
| Language | **Java 21** (records, pattern matching, virtual threads) |
| Framework | **Spring Boot 3.3** |
| Data access | **`NamedParameterJdbcTemplate`** — see below |
| Migrations | **Flyway**, forward-only, per service |
| Scheduling | **ShedLock** (one replica runs the sweeper) |
| Testing | **JUnit 5 + AssertJ + Testcontainers** |
| Frontend | **React 18 + TypeScript + Vite** |
| Edge | **nginx** — see below |

### Why plain JDBC rather than JPA

`docs/07` assumed JPA. Building it, JDBC won on merit:

- The domain is **range-typed SQL**. `int4range`, `&&`, `@>`, `FOR UPDATE SKIP LOCKED`, generated
  columns, `FILTER (WHERE …)` — JPA obstructs every one of these and you end up in native queries
  anyway, having paid for an abstraction you then bypass.
- The single most important line in the system is a SQL predicate. Having it *visible as SQL*, next to
  a comment explaining it, is worth more here than entity mapping.
- No lazy-loading surprises, no N+1 in the availability path, no dirty-checking overhead on the hot
  write path.

The cost is hand-written row mappers. Acceptable: they are mechanical, and Java records make them
short.

### Why nginx rather than Spring Cloud Gateway

`docs/02` names Spring Cloud Gateway. nginx replaced it because the gateway's job in this topology is
routing, rate limiting and serving one origin — and a 20 MB container that starts instantly serves the
"one shot from a clean machine" requirement far better than a fourth JVM. It also lets the SPA and the
API share an origin, so **CORS never enters the picture at all**.

Nothing in the services depends on which gateway sits in front of them; in production the role is WSO2
API Manager, and the OpenAPI specs import directly.

### Alternatives considered

**Ballerina** — genuinely considered given the WSO2 context, and its network-primitives-as-language
constructs are a good fit for integration work. Rejected because the hard part here is transactional
data modelling, where Spring Data + Flyway + Testcontainers give the fastest path to a *provably*
correct implementation inside the time budget; and because a reviewer needs to read it quickly.

**Go** — meaningfully better on cold start (~50 ms and ~20 MB images versus ~4 s and ~250 MB), and
`pgx` has excellent range-type support. A real contender, and the deciding factor was not technical:
the author must defend and extend this live, so fluency beats benchmark.

**Node/TypeScript end to end** — one language across the stack is attractive, but the strongest
argument for this system is a *typed, transactional* backend, and Java's ecosystem here (Flyway,
Testcontainers, ShedLock — all named in the design docs) is materially better.

## Consequences

**Good**

- The invariant is enforced by the layer best equipped to enforce it.
- Testcontainers runs the **same PostgreSQL version** as production — never H2. Testing this system
  against H2 would exercise a different system and pass for the wrong reason.
- Virtual threads make the blocking JDBC style scale acceptably without reactive complexity.

**Costs**

- **JVM cold start is the worst of the realistic options** — ~4 s per service, ~250 MB images, three of
  them. Mitigated by a dependency-cache layer in the Dockerfile and health-gated `depends_on`; measured
  at 113 s for a full cold `docker compose up --build` in CI, which is acceptable but not good.
- PostgreSQL lock-in, accepted in ADR-002.
- Hand-written row mappers.

---

**Related:** [ADR-002](ADR-002-exclusion-constraint-concurrency.md) ·
[`docs/08`](../08-microservices-and-deployment.md)
