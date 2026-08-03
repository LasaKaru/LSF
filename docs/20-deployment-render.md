# 20 — Deploying free on Render

## Why Render and not Netlify or Vercel

Netlify and Vercel host static sites and short-lived serverless functions. This system is three
long-running JVMs, a PostgreSQL database with a GiST exclusion constraint, and background schedulers that
must keep running between requests. None of that fits either platform:

| | Netlify | Vercel | Render |
|---|---|---|---|
| React SPA | ✅ | ✅ | ✅ |
| Long-running JVM services | ❌ | ❌ | ✅ |
| Managed PostgreSQL | ❌ | ⚠️ partner add-on, paid | ✅ free tier |
| Background schedulers (hold sweeper, outbox relay) | ❌ | ❌ | ✅ |

The sweeper and the outbox relay are the deciding factor. They are not request-scoped — a hold has to
expire whether or not anyone is browsing, and a released segment has to reach the waitlist matcher.
Serverless functions do not stay alive to do that.

You *could* host only the SPA on Netlify or Vercel and point it at a backend elsewhere, but then the
backend still needs Render (or similar), and you have added CORS for no benefit.

---

## What you get, and what the free tier costs you

Be aware of these before you start — all three are free-plan limits, not bugs:

1. **Services sleep after ~15 minutes idle.** The first request afterwards takes **50 seconds or more**
   while the instance wakes and the JVM starts. For a reviewer clicking a link cold, that reads as
   "broken". Warn them, or hit the URL yourself a minute beforehand.
2. **Free PostgreSQL is deleted after 30 days.** Fine for a demo; note the expiry date.
3. **512 MB RAM and a shared CPU per service.** The image sets `-XX:MaxRAMPercentage=70` and SerialGC to
   suit this. It fits, but it is not fast.

Three JVMs on free instances also means three independent cold starts. If the demo needs to be reliably
quick, one paid instance for `yathra-booking` (currently $7/month) removes most of the pain, because it is
the service every request touches.

---

## What the Blueprint already handles

[`render.yaml`](../render.yaml) in the repository root declares the whole stack, so this is close to a
one-click deploy. Two things in it are worth understanding, because both were real obstacles:

**One database, three schemas.** Every other environment gives each service its own database. The free
tier allows one PostgreSQL instance, so [`deploy/render/entrypoint.sh`](../deploy/render/entrypoint.sh)
points each service at its own **schema** (`booking`, `catalog`, `pricing`) via `currentSchema`, and tells
Flyway to create and migrate into it. The services never see each other's tables.

`public` stays on the search path because `btree_gist` lives there — a PostgreSQL extension is per
*database*, not per schema, and the exclusion constraint's GiST operator classes have to resolve.

*Verified locally before writing this*: all three services booted against a single database over TLS, each
migrating into its own schema (12 / 9 / 9 tables), and the invariant still held — a duplicate booking
returned `409 SEAT_SEGMENT_UNAVAILABLE` in 0.07 s, and the full waitlist journey passed end to end.

**One image, three services.** Render's Blueprint spec has no field for Docker build arguments, so the
`SERVICE` build arg used by `services/Dockerfile` cannot be expressed there.
[`deploy/render/Dockerfile`](../deploy/render/Dockerfile) builds all three jars into one image and selects
between them at runtime with a `SERVICE` environment variable.

---

## Step by step

### 1. Push the branch and open Render

The Blueprint must be on the branch you deploy. Sign in at <https://dashboard.render.com> with GitHub and
authorise access to `LasaKaru/LSF`.

### 2. Create the Blueprint

**New → Blueprint** → pick `LasaKaru/LSF` → choose the branch → **Apply**.

Render reads `render.yaml` and creates the database, the three services and the static site. It generates
`QUOTE_SIGNING_KEY` once on `yathra-booking` and injects the same value into `yathra-pricing` — they must
match or every quote fails signature verification.

The first build takes roughly **5–10 minutes per service** (Maven downloads the world once).

### 3. ⚠️ Create the `btree_gist` extension — the one manual step

**Do this as soon as the database is live, before `yathra-booking` finishes its first deploy.** A
Blueprint cannot run SQL, and booking's `V2` migration needs this extension. Without it the migration
fails and the service crash-loops.

Render dashboard → **yathra-db** → **Connect** → copy the **PSQL command** → run it in any terminal with
`psql` installed, then:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

`btree_gist` is a *trusted* extension in PostgreSQL 13+, so the database owner can create it without
superuser rights.

If booking already failed, just **Manual Deploy → Deploy latest commit** after creating the extension.

### 4. Point the SPA's rewrites at your real service URLs

`render.yaml` assumes the default hostnames `yathra-booking.onrender.com` and so on. If Render appended a
suffix because a name was taken, edit the `routes` block in `render.yaml` to match the actual URLs and
push, or edit **yathra-web → Redirects/Rewrites** in the dashboard.

Check the order is preserved: the two catalog rules and the two pricing rules must come **before** the
`/api/*` catch-all, and the SPA fallback must come last.

### 5. Verify, in this order

```bash
# 1. Each service is alive (expect {"status":"UP"} — allow ~60s for a cold start)
curl https://yathra-booking.onrender.com/actuator/health
curl https://yathra-catalog.onrender.com/actuator/health
curl https://yathra-pricing.onrender.com/actuator/health

# 2. Catalog published trips into booking on startup
curl "https://yathra-booking.onrender.com/api/v1/trips?date=$(date -d '+2 days' +%F)&from=CMB&to=BDL"

# 3. The SPA and its proxy — same origin, so no CORS
curl https://yathra-web.onrender.com/api/v1/stations
```

If step 2 returns `[]`, catalog could not reach booking on startup — almost always because booking was
still waking. **yathra-catalog → Manual Deploy** to retry publication.

### 6. Prove the headline behaviour against the deployed stack

```bash
./scripts/demo-segment-resale.sh https://yathra-web.onrender.com
```

That books one seat Fort→Kandy, resells the same seat Kandy→Badulla, and shows the two overlapping
attempts rejected with `409`.

---

## If something breaks

| Symptom | Cause | Fix |
|---|---|---|
| booking crash-loops, logs show `type "gist" does not exist` or a `V2` migration failure | `btree_gist` not created | Step 3, then redeploy |
| Every booking returns `422 QUOTE_INVALID` | `QUOTE_SIGNING_KEY` differs between booking and pricing | Compare both services' env vars; set pricing's to booking's value |
| `/api/v1/quotes` returns 404 | Rewrite order wrong — the `/api/*` catch-all is matching first | Move the pricing rules above it |
| Trips list is empty | Catalog published while booking was asleep | Redeploy catalog |
| First request takes ~60s | Free-tier cold start | Expected — see the limits above |
| Requests hang forever and never return | `THREADS_VIRTUAL_ENABLED` was set to `true` | Leave it unset. See README §9.10 — Render's PostgreSQL requires TLS, and on Java 21 that pins virtual threads to their carriers |

That last row is not hypothetical. It is exactly the bug documented in README §9.10, and Render's managed
PostgreSQL is precisely the environment that triggers it: TLS-required connections plus a small carrier
pool. The default is safe; do not override it on Java 21.

---

## Cleaning up

Free PostgreSQL is removed after 30 days, but the services are not. Delete the Blueprint from the Render
dashboard when the demo is finished, or you will keep receiving build notifications for a stack nobody is
using.

---

**See also:** [`08-microservices-and-deployment.md`](08-microservices-and-deployment.md) ·
[`17-configuration.md`](17-configuration.md) · [`19-walkthrough-evidence.md`](19-walkthrough-evidence.md)
