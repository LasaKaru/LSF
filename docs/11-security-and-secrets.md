# 11 — Security & Secrets

---

## 1. Secrets — the brief's explicit requirement

> *"Please don't commit database credentials or other secrets to version control — use environment
> variables or another secure method."*

### 1.1 What is in the repository

| File | Committed | Contents |
|---|---|---|
| `.env.example` | ✅ Yes | **Placeholders only.** Documents every variable, its purpose and default |
| `.env` | ❌ Never | Gitignored on the first commit, before any env file existed |
| `docker-compose.yml` | ✅ Yes | `${VAR}` references only — no literal credential anywhere |
| `application.yml` | ✅ Yes | `${DB_PASSWORD}` placeholders; no defaults for anything sensitive |
| Migrations | ✅ Yes | Schema only; no data with credentials |

`.env.example` looks like this — self-documenting, and useless to an attacker:

```bash
# ── Database ────────────────────────────────────────────────
POSTGRES_USER=yathra
POSTGRES_PASSWORD=CHANGE_ME_LOCAL_ONLY          # dev only; production uses the platform secret store
POSTGRES_PORT=5432

# ── Signing keys ────────────────────────────────────────────
# Generate:  openssl rand -base64 48
QUOTE_SIGNING_KEY=CHANGE_ME
JWT_ISSUER_URI=http://keycloak:8180/realms/yathra

# ── Booking behaviour ───────────────────────────────────────
BOOKING_HOLD_TTL_SECONDS=600
HOLD_SWEEP_INTERVAL_SECONDS=15
BOOKING_CUTOFF_MINUTES=30
```

### 1.2 Defence in depth against accidental commits

One mechanism is a policy; four is a control.

1. **`.gitignore`** covers `.env`, `*.pem`, `*.key`, `*.p12`, `secrets/`, and was committed **first**.
2. **Pre-commit hook** runs `gitleaks protect --staged` — blocks locally, before the mistake exists.
3. **CI gate** runs `gitleaks detect` across the **full history**, not just the diff. Blocking.
4. **Push protection** enabled on the remote as a final backstop.

If a secret ever does land in history, the documented response is: **rotate first, scrub second.**
Rewriting history does not un-leak a credential that has already been cloned; rotation does.

### 1.3 Production

Secrets come from the platform's store (Kubernetes Secrets via External Secrets Operator, or the
platform's own secure configuration) and are injected as **the same environment variables** the Compose
file uses. The application code has no environment-specific branch — it reads `DB_PASSWORD` and does not
know or care where it came from.

Rotation: signing keys support **overlapping validity** (verify against current *and* previous key) so
rotation never invalidates in-flight quotes. Database credentials rotate through the platform with a
rolling restart.

---

## 2. Authentication and authorisation

| Layer | Mechanism |
|---|---|
| Identity provider | OIDC — WSO2 Identity Server in production, Keycloak in compose. Services trust an **issuer + JWKS URL**, both config values |
| Token validation | At the gateway (signature, issuer, audience, expiry); re-validated in-service — the gateway is a filter, not a trust boundary to be relied on alone |
| Scopes | `booking:read`, `booking:write`, `admin:read`, `admin:write` |
| Ownership | Retrieving a booking requires the owning token **or** reference + matching contact email |
| Anonymous booking | Permitted (guest and counter flows) with stricter rate limits and email verification before ticket issue |
| Admin | Separate scope, separate audience, MFA required, all actions audit-logged |

**Why reference + email rather than reference alone:** a 8-character reference is enumerable. Requiring a
second factor the attacker does not have turns a scraping attack into a targeted one.

---

## 3. OWASP Top 10 (2021)

| Risk | Treatment |
|---|---|
| A01 Broken access control | Deny by default; ownership checks on every booking read; scope checks on every admin route; integration tests assert 403 paths |
| A02 Cryptographic failures | TLS 1.3 everywhere; HSTS; secrets never logged; PII encrypted at rest; **HMAC-SHA256 quote signatures** |
| A03 Injection | Parameterised queries only (JPA/JDBC); no string-concatenated SQL — enforced by static analysis; JSONB validated |
| A04 Insecure design | Threat model below; the core invariant is enforced by the database, not by a code path an attacker can skip |
| A05 Security misconfiguration | Non-root, read-only rootfs, dropped capabilities, no default credentials, stack traces never returned to clients |
| A06 Vulnerable components | Dependabot + OWASP dependency-check + Trivy image scans; builds fail on High/Critical |
| A07 Auth failures | Short-lived tokens, refresh rotation, rate-limited auth endpoints, generic failure messages |
| A08 Integrity failures | Signed images, pinned digests, SBOM per build, signed quotes |
| A09 Logging failures | Structured audit log for all state changes; **PII redacted**; append-only retention |
| A10 SSRF | No user-supplied URLs are fetched anywhere |

---

## 4. Threat model — domain-specific

The generic OWASP list misses the attacks that actually apply to a ticketing system.

| Threat | Impact | Mitigation |
|---|---|---|
| **Seat sniping / scalper bots** on peak dates | Legitimate passengers locked out; a resale market forms | Tight per-IP and per-token rate limits on `POST /bookings`; CAPTCHA above a threshold; hold TTL caps how long a bot can squat; **hold-count limits per contact and per payment instrument** |
| **Hold-squatting DoS** — create holds, never pay | Inventory frozen without revenue | Max concurrent holds per identity; shortened TTL under high demand; hold abuse metrics alert |
| **Price manipulation** | Underpayment | Fares computed server-side; HMAC-signed, TTL-bound quotes bound to the exact leg and class |
| **Booking reference enumeration** | PII disclosure | High-entropy references; reference + email required; rate-limited lookups |
| **Availability scraping** for a resale market | Competitive/abuse | Rate limits, ETag caching, no bulk export endpoint |
| **Insider manipulation** of inventory | Fraud | All admin actions audit-logged with actor identity; departmental seat blocks are ordinary bookings subject to the same constraint; segregation of duties |
| **Replay of a booking request** | Duplicate charges | Mandatory idempotency keys |
| **Fare-rule tampering** | Revenue loss | Rule sets are versioned, effective-dated, immutable once published; changes require `admin:write` and are audit-logged |

The hold-squatting row is worth highlighting: the hold mechanism that makes the user experience humane
is also an attack surface, and any design that introduces holds without also introducing hold limits has
built a free inventory-freezing weapon.

---

## 5. Data protection and PII

| Field | Classification | Handling |
|---|---|---|
| Passenger name | PII | Encrypted at rest; redacted in logs |
| Email / phone | PII | Encrypted at rest; used for booking retrieval; redacted in logs |
| Contact ↔ booking link | PII | Access-controlled |
| Seat occupancy ranges | **Not PII** | Freely returned by the seat-map API — ranges only, never identities |
| Payment details | Sensitive | **Never stored.** Tokenised at the gateway |

- Anonymised 12 months after travel; the financial record is retained without identity.
- Export and deletion requests supported (retaining the anonymised financial record, as law requires).
- Logs are structured with an explicit redaction filter; a test asserts that known PII field names never
  appear in log output.

---

## 6. Network and infrastructure

- TLS terminated at the gateway; **mTLS between services** in production.
- `NetworkPolicy` restricts each service to its own database, Redis, the broker, and the gateway. No
  service can reach another service's database even if it tried.
- No database is exposed outside the cluster; in Compose, Postgres binds to `127.0.0.1` only.
- Security headers at the edge: HSTS, CSP, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors`.
- CORS: explicit allowlist from config. Never `*`.

---

## 7. Auditability

Every state change writes an audit record: actor, action, subject, before/after, correlation id,
timestamp, source IP. Append-only, 7-year retention.

This is not optional for a public operator. When a passenger disputes a fare or a seat, the department
must be able to reconstruct exactly what happened, which rule set applied, and who changed what — and
that reconstruction has to be trustworthy in front of a regulator.

---

**Related:** [`17-configuration.md`](17-configuration.md) ·
[`08-microservices-and-deployment.md`](08-microservices-and-deployment.md)
