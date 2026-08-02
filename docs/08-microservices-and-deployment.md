# 08 — Microservices & Deployment

---

## 1. The constraint that shaped this

The brief asks for two things that pull in opposite directions:

- *"Build it as though it could genuinely be launched as a real application"* → a real microservice
  topology with independent services, event streaming, and platform portability.
- *"The project should run in one shot from a clean machine"* → a reviewer types one command and it
  works, on a laptop, in under two minutes.

Twelve containers, Kafka and a schema registry satisfy the first and destroy the second. **Compose
profiles** resolve it: the honest topology is present and documented; the default startup is the subset
that demonstrates every core requirement.

---

## 2. Compose profiles

| Profile | Containers | Purpose |
|---|---|---|
| `core` *(default)* | postgres, redis, catalog, booking, pricing, gateway, web, bootstrap | **All core requirements.** ~90 s cold start |
| `full` | + kafka, kafka-ui, payment-mock, waitlist, admin-reporting, notification, mailpit, admin-web | Complete topology and all extra credit |
| `observability` | + otel-collector, tempo, prometheus, grafana | Traces, metrics, dashboards |

```bash
docker compose up --build                       # core
docker compose --profile full up --build        # everything
docker compose --profile full --profile observability up --build
```

In `core`, the outbox relay runs as an in-process **log-only publisher** — events are still written
transactionally to the outbox table (so the pattern is real and inspectable), they simply are not
delivered to Kafka because no consumer is running. No code path differs between profiles; only the
`OUTBOX_TRANSPORT` env var.

---

## 3. `docker-compose.yml` shape

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./db/init:/docker-entrypoint-initdb.d:ro    # creates one database per service
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 5s
      retries: 20

  booking-service:
    build: ./services/booking-service
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/booking_db
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      QUOTE_SIGNING_KEY: ${QUOTE_SIGNING_KEY}
      BOOKING_HOLD_TTL_SECONDS: ${BOOKING_HOLD_TTL_SECONDS:-600}
      OUTBOX_TRANSPORT: ${OUTBOX_TRANSPORT:-log}
    depends_on:
      postgres: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8081/readyz"]
      interval: 10s
      retries: 12

  bootstrap:
    build: ./scripts/bootstrap
    depends_on:
      booking-service: { condition: service_healthy }
      catalog-service: { condition: service_healthy }
    restart: "no"          # one-shot; idempotent seeding
```

Details that make "one shot" actually true:

- **Health-gated `depends_on`.** `service_started` is not enough — a service that has begun booting is
  not a service that can serve traffic. Every dependency waits for `service_healthy`.
- **`/readyz` checks migrations.** Readiness is false until Flyway reports the schema is at the expected
  version, so nothing talks to a half-migrated database.
- **Idempotent bootstrap.** `ON CONFLICT DO NOTHING` throughout; re-running `up` never duplicates seed
  data or fails.
- **Pinned image digests** in CI. Floating `:latest` tags are how a "clean machine" run breaks six months
  from now.
- **Multi-stage builds** with a dependency-cache layer; images are distroless/JRE-slim, non-root,
  read-only root filesystem.

---

## 4. Twelve-factor compliance

| Factor | How |
|---|---|
| Codebase | One repo, one deployable per service |
| Dependencies | Explicit (Gradle version catalog / `package-lock.json`), vendored in the image |
| **Config** | **Env vars only.** No profile-conditional code, no bundled secrets |
| Backing services | Attached via URL config; swapping Postgres or Redis is a config change |
| Build/release/run | Separate stages; immutable images tagged by git SHA |
| Processes | Stateless; all state in Postgres/Redis |
| Port binding | Self-contained HTTP server, no external app server |
| Concurrency | Horizontal replicas; the DB is the only serialisation point |
| Disposability | Fast start; SIGTERM → drain in-flight, release ShedLock, exit |
| Dev/prod parity | Same images everywhere; Testcontainers uses the same Postgres version |
| Logs | JSON to stdout; the platform ships them |
| Admin processes | Migrations run as an init container/job, never on app start-up in production |

The last row deserves a note: in Compose, Flyway runs at start-up for convenience. In Kubernetes it runs
as a **Job** that must succeed before the Deployment rolls, because three replicas racing to migrate the
same schema is a bad way to learn about advisory locks.

---

## 5. Kubernetes / platform deployment

Manifests in `deploy/k8s/` (Kustomize base + overlays; Helm chart in `deploy/helm/`).

Per service:

```yaml
resources:
  requests: { cpu: 200m, memory: 512Mi }
  limits:   { memory: 1Gi }              # no CPU limit: throttling hurts p99 more than it helps
livenessProbe:  { httpGet: { path: /healthz, port: 8081 }, periodSeconds: 10 }
readinessProbe: { httpGet: { path: /readyz,  port: 8081 }, periodSeconds: 5 }
startupProbe:   { httpGet: { path: /healthz, port: 8081 }, failureThreshold: 30 }
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities: { drop: ["ALL"] }
```

Plus: `PodDisruptionBudget` (minAvailable 1), `HorizontalPodAutoscaler` on CPU + request rate,
`NetworkPolicy` restricting each service to its own database and the gateway,
`preStop` sleep for graceful load-balancer drain, and rolling updates with `maxUnavailable: 0`.

**Secrets** come from the platform (Kubernetes Secrets backed by External Secrets Operator, or the
platform's own secret store) and are injected as the **same environment variables** the Compose file
uses. The application code cannot tell the difference between environments — which is the whole point.

---

## 6. Fit with the WSO2 platform

The brief notes the team will handle deployment with their own technologies, so the design optimises for
**portability rather than platform coupling**. Every service ships:

- a `Dockerfile` producing a non-root, minimal image
- an OpenAPI 3.1 contract in `contracts/`
- env-var-only configuration
- `/healthz`, `/readyz`, `/metrics` endpoints
- OpenTelemetry instrumentation via standard `OTEL_*` env vars

That set is exactly what a container platform needs, so the natural mappings are:

| Concern | Local (compose) | WSO2-flavoured production |
|---|---|---|
| Edge / API management | Spring Cloud Gateway | **WSO2 API Manager** — the OpenAPI specs import directly, giving throttling policies, subscription tiers, and a developer portal without code changes |
| Identity | Keycloak | **WSO2 Identity Server** (OIDC; services validate JWTs by issuer/JWKS, so the provider is a config value) |
| Runtime | Docker Compose | **WSO2 Choreo**, plain Kubernetes, or the department's own runtime — each service is an independently deployable container component |
| Integration / eventing | Kafka | Kafka or the platform's chosen broker; the outbox relay is a thin adapter behind an interface |

**No WSO2-specific code exists in any service.** The gateway is a routing concern, the identity provider
is a JWKS URL, and the broker is behind an interface with two implementations. That is a deliberate
choice: the team should be able to drop this into their own pipeline without unpicking my assumptions.

---

## 7. CI/CD

```
push / PR
   ├─ lint + format (spotless, eslint, prettier)
   ├─ unit tests
   ├─ integration tests (Testcontainers: real Postgres, real DDL)
   ├─ ★ concurrency suite (race + property tests)          ← blocking gate
   ├─ contract tests (OpenAPI diff vs main; fails on breaking change)
   ├─ ★ gitleaks secret scan                                ← blocking gate
   ├─ SAST (CodeQL) + dependency audit (OWASP, Trivy on images)
   ├─ build multi-arch images, tag with git SHA
   └─ (main) publish images · deploy to staging · smoke tests · manual gate → production
```

Two gates are non-negotiable and marked ★: **the concurrency suite** (if the invariant can break, nothing
else matters) and **the secret scan** (the brief asks for it explicitly, and a leaked credential is not
something you fix in the next release).

Deployment strategy: rolling with health gates, `maxUnavailable: 0`. Database migrations are backward
compatible by policy (§`docs/07` §5), so old and new replicas coexist safely during a roll.

---

## 8. Environments

| Env | Purpose | Data | Notes |
|---|---|---|---|
| Local | Development | Seeded | Compose; Testcontainers for tests |
| CI | Verification | Ephemeral | Fresh Postgres per run |
| Staging | Pre-production | Anonymised production shape | Same manifests as production |
| Production | Live | Real | HA Postgres, autoscaling, full observability |

---

## 9. Troubleshooting the one-shot run

| Symptom | Cause | Fix |
|---|---|---|
| `bootstrap` exits non-zero | Services not ready | Health-gated `depends_on` should prevent it; `docker compose logs bootstrap` |
| Port 5432/8080/3000 already in use | Local Postgres/other app | Override `POSTGRES_PORT`, `GATEWAY_PORT`, `WEB_PORT` in `.env` |
| `extension "btree_gist" is not available` | Non-standard Postgres image | Use the pinned `postgres:16-alpine`; the extension ships with it |
| Slow first start | Image build | `docker compose pull` first, or use the published images |
| ARM/Apple Silicon issues | Image arch | All images are built multi-arch (`linux/amd64`, `linux/arm64`) |
| Web app shows no trips | Seed did not run or date drift | `docker compose run --rm bootstrap` re-seeds idempotently |

---

**Related:** [`17-configuration.md`](17-configuration.md) ·
[`11-security-and-secrets.md`](11-security-and-secrets.md) ·
[`12-observability-and-ops.md`](12-observability-and-ops.md)
