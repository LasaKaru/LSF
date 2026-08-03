#!/usr/bin/env bash
#
# Demonstrates the whole point of this system in about twenty seconds:
# one physical seat, sold twice, on non-overlapping legs of the same journey.
#
#   1. Book seat 1A, Colombo Fort -> Kandy          201  LKR 470
#   2. Book seat 1A, Kandy -> Badulla               201  LKR 680   <- same seat
#   3. Book seat 1A, Colombo Fort -> Badulla        409  SEAT_SEGMENT_UNAVAILABLE
#   4. Book seat 1A, Gampaha -> Nanu Oya            409  (straddles Kandy)
#
# Usage:  ./scripts/demo-segment-resale.sh [API_BASE]
#         API_BASE defaults to http://localhost:8080 (the gateway).
#
set -euo pipefail

API="${1:-${API_BASE:-http://localhost:8080}}"

command -v python3 >/dev/null 2>&1 || {
  echo "This script needs python3 to read JSON responses." >&2
  exit 1
}
command -v curl >/dev/null 2>&1 || { echo "This script needs curl." >&2; exit 1; }

export YATHRA_API="$API"
python3 - <<'PYTHON'
import datetime, json, os, sys, urllib.error, urllib.request, uuid

API = os.environ["YATHRA_API"]

BOLD, DIM, GREEN, RED, YELLOW, RESET = "\033[1m", "\033[2m", "\033[32m", "\033[31m", "\033[33m", "\033[0m"


def call(path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method="POST" if data else "GET")
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")
    except urllib.error.URLError as e:
        print(f"{RED}Cannot reach {API} - is the stack running?{RESET}\n  {e}", file=sys.stderr)
        sys.exit(1)


def money(minor):
    return f"LKR {minor / 100:,.0f}"


# --- find a bookable trip -------------------------------------------------
# Two days out, so the booking cutoff (30 min before departure) is never in play.
date = (datetime.date.today() + datetime.timedelta(days=2)).isoformat()
status, trips = call(f"/api/v1/trips?date={date}&from=CMB&to=BDL")
if status != 200 or not trips:
    print(f"{RED}No trips found for {date}. Has catalog-service published the horizon yet?{RESET}")
    sys.exit(1)

trip = trips[0]
trip_id = trip["id"]
print(f"\n{BOLD}Yathra - segment resale demonstration{RESET}")
print(f"{DIM}{'-' * 62}{RESET}")
print(f"Trip     {trip['trainCode']} {trip['trainName']} ({trip['direction']})")
print(f"Date     {date}")
print(f"API      {API}\n")

# --- pick the first bookable seat ----------------------------------------
status, seatmap = call(f"/api/v1/trips/{trip_id}/seat-map?from=CMB&to=KDY&class=SECOND")
coach = seatmap["coaches"][0]
seat = next(s for s in coach["seats"] if s["availableForRequestedLeg"])
print(f"Seat     {seat['label']} in coach {coach['coachNumber']}"
      f"{' (window)' if seat['window'] else ''}\n")


def quote(frm, to):
    status, q = call("/api/v1/quotes", {
        "tripId": trip_id, "fromStation": frm, "toStation": to,
        "classCode": "SECOND", "coachType": "RESERVED", "passengers": 1,
    })
    if status != 200:
        print(f"{RED}Quote failed ({status}): {q}{RESET}")
        sys.exit(1)
    return q


def book(frm, to, q):
    fields = ("quoteId", "tripId", "fromSeq", "toSeq", "classCode", "coachType",
              "passengers", "unitFareMinor", "totalMinor", "currency",
              "ruleSetVersion", "expiresAt", "signature")
    body = {
        "tripId": trip_id, "from": frm, "to": to,
        "seatSelection": {"mode": "SPECIFIC", "seatIds": [seat["seatId"]]},
        "quote": {k: q[k] for k in fields},
        "passengers": [{"name": "A. Perera", "type": "ADULT"}],
        "contact": {"email": f"demo-{uuid.uuid4()}@example.lk",
                    "phone": "+94770000000", "name": "A. Perera"},
    }
    return call("/api/v1/bookings", body, {"Idempotency-Key": str(uuid.uuid4())})


STEPS = [
    ("CMB", "KDY", "Colombo Fort -> Kandy", 201),
    ("KDY", "BDL", "Kandy -> Badulla", 201),
    ("CMB", "BDL", "Colombo Fort -> Badulla", 409),
    ("GMP", "NAN", "Gampaha -> Nanu Oya", 409),
]

earned = 0
failures = 0

for frm, to, label, expected in STEPS:
    q = quote(frm, to)
    status, resp = book(frm, to, q)
    ok = status == expected
    failures += 0 if ok else 1
    mark = f"{GREEN}OK{RESET}" if ok else f"{RED}UNEXPECTED{RESET}"

    if status == 201:
        seg = resp["segments"][0]
        earned += resp["totalMinor"]
        print(f"  {label:<26} {GREEN}{status}{RESET}  {money(resp['totalMinor']):>10}"
              f"   seat {seg['seatLabel']} [{seg['fromSeq']},{seg['toSeq']})   {mark}")
    else:
        code = resp.get("code", "?")
        print(f"  {label:<26} {YELLOW}{status}{RESET}  {code:<28} {mark}")
        for c in resp.get("conflicts", [])[:2]:
            lo, hi = c["occupiedLeg"]
            print(f"       {DIM}already sold: seat {c['seatLabel']} over [{lo},{hi}){RESET}")
        alts = resp.get("suggestedAlternatives", [])
        if alts:
            a = alts[0]
            print(f"       {DIM}one-click alternative: {a['seatLabel']} in coach {a['coachNumber']}"
                  f"{' (window)' if a['window'] else ''}{RESET}")

# --- the point ------------------------------------------------------------
whole = quote("CMB", "BDL")["totalMinor"]
print(f"\n{DIM}{'-' * 62}{RESET}")
print(f"  One seat, two passengers:      {BOLD}{money(earned)}{RESET}")
print(f"  Same seat sold whole-journey:  {money(whole)}")
if whole:
    print(f"  Resale uplift on this seat:    {BOLD}{(earned - whole) / whole * 100:+.0f}%{RESET}")
print(f"\n  {DIM}...and the short-leg passenger paid LKR 470 rather than the ~LKR 760")
print(f"  they pay today, because the seat no longer runs empty for 171 km.{RESET}\n")

sys.exit(1 if failures else 0)
PYTHON
