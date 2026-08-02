import { useCallback, useEffect, useState } from 'react';
import { api, money } from '../api';
import type { Trip } from '../api';

/**
 * The departmental view.
 *
 * The brief says leadership *believes* revenue is being left on the table. Believes. The department
 * has no instrument that shows where the train empties out or how much inventory evaporates at
 * departure — so the change cannot be justified, tuned, or defended.
 *
 * The headline number is **seat-km utilisation**, deliberately shown next to conventional load factor.
 * Load factor reports a train as 100% full when every seat was sold for a tenth of the route; that
 * metric is precisely what hid the problem in the first place. The gap between the two numbers *is*
 * the problem, made numeric.
 */

type HopOccupancy = {
  fromSeq: number;
  toSeq: number;
  fromCode: string;
  toCode: string;
  distanceKm: number;
  seatsOccupied: number;
  seatsTotal: number;
  occupancyPct: number;
};

type SegmentRevenue = {
  fromCode: string;
  toCode: string;
  bookings: number;
  revenueMinor: number;
  seatKm: number;
};

type TripReport = {
  tripId: string;
  trainCode: string;
  serviceDate: string;
  direction: string;
  bookableSeats: number;
  routeKm: number;
  availableSeatKm: number;
  soldSeatKm: number;
  seatKmUtilisationPct: number;
  distinctSeatsUsed: number;
  conventionalLoadFactorPct: number;
  segmentsPerSeat: number;
  actualRevenueMinor: number;
  firstSaleRevenueMinor: number | null;
  resaleUpliftPct: number | null;
  wholeJourneyCounterfactualMinor: number | null;
  occupancy: HopOccupancy[];
  revenueBySegment: SegmentRevenue[];
};

const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 10);

export function AdminDashboard() {
  const [date, setDate] = useState(inDays(2));
  const [trips, setTrips] = useState<Trip[]>([]);
  const [tripId, setTripId] = useState<string | null>(null);
  const [report, setReport] = useState<TripReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setReport(null);
    setTripId(null);
    api
      .trips(date, 'CMB', 'BDL')
      .then((found) => {
        setTrips(found);
        if (found.length) setTripId(found[0].id);
      })
      .catch(() => setError('Could not load trips for that date.'));
  }, [date]);

  const load = useCallback(async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      // The through fare is optional and is NOT what the resale uplift is computed from — that comes
      // from the trip's own recorded fares and needs no assumption about historic pricing. It is
      // supplied only for the separate fare-policy comparison, and it is fetched here rather than
      // server-side because pricing already depends on booking for trip topology; calling back the
      // other way would close a dependency cycle for a reporting figure. A failure is non-fatal.
      let throughFare: number | undefined;
      try {
        throughFare = (await api.quote(id, 'CMB', 'BDL', 1)).totalMinor;
      } catch {
        throughFare = undefined;
      }

      const qs = throughFare ? `?wholeJourneyFareMinor=${throughFare}` : '';
      const response = await fetch(`/api/v1/admin/trips/${id}/report${qs}`);
      if (!response.ok) throw new Error(String(response.status));
      setReport((await response.json()) as TripReport);
    } catch {
      setError('Could not load the report.');
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (tripId) load(tripId);
  }, [tripId, load]);

  const maxOcc = report ? Math.max(1, ...report.occupancy.map((h) => h.seatsOccupied)) : 1;

  return (
    <div className="app">
      <header>
        <h1>
          Yathra Admin <span className="muted">· departmental view</span>
        </h1>
        <p className="tagline">Where the train empties out, and what resale is actually earning.</p>
      </header>

      <div className="banner warn small">
        <strong>Unauthenticated in this deployment profile.</strong> In production these routes sit
        behind the <code>admin:read</code> scope — occupancy and revenue are commercially sensitive.
      </div>

      {error && <div className="banner error">{error}</div>}

      <section className="card">
        <div className="row">
          <label>
            Service date
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label>
            Trip
            <select value={tripId ?? ''} onChange={(e) => setTripId(e.target.value)}>
              {trips.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.trainCode} {t.trainName} ({t.direction})
                </option>
              ))}
            </select>
          </label>
          <button className="primary" disabled={!tripId || busy} onClick={() => tripId && load(tripId)}>
            {busy ? 'Loading…' : 'Refresh'}
          </button>
        </div>
        {trips.length === 0 && <p className="muted small">No trips published for that date.</p>}
      </section>

      {report && (
        <>
          {/* ---- the two numbers that matter, side by side ---- */}
          <section className="card">
            <h2>Utilisation</h2>
            <div className="kpis">
              <div className="kpi accent">
                <span className="kpi-value">{report.seatKmUtilisationPct}%</span>
                <span className="kpi-label">Seat-km utilisation</span>
                <span className="kpi-note">sold seat-km ÷ available seat-km — the honest load metric</span>
              </div>
              <div className="kpi">
                <span className="kpi-value">{report.conventionalLoadFactorPct}%</span>
                <span className="kpi-label">Conventional load factor</span>
                <span className="kpi-note">seats touched ÷ seats available — what hid the problem</span>
              </div>
              <div className="kpi">
                <span className="kpi-value">{report.segmentsPerSeat}</span>
                <span className="kpi-label">Segments per seat</span>
                <span className="kpi-note">
                  {report.segmentsPerSeat > 1
                    ? 'seats are reselling'
                    : 'at 1.0, nothing is reselling'}
                </span>
              </div>
            </div>
            <p className="muted small">
              {report.bookableSeats} reserved seats × {report.routeKm} km ={' '}
              {report.availableSeatKm.toLocaleString()} available seat-km;{' '}
              {report.soldSeatKm.toLocaleString()} sold.{' '}
              {report.conventionalLoadFactorPct > report.seatKmUtilisationPct && (
                <>
                  The gap between those first two numbers is the problem the brief describes, made
                  numeric.
                </>
              )}
            </p>
          </section>

          {/* ---- revenue and the counterfactual ---- */}
          <section className="card">
            <h2>Revenue</h2>
            <div className="kpis">
              <div className="kpi accent">
                <span className="kpi-value">{money(report.actualRevenueMinor)}</span>
                <span className="kpi-label">Actual revenue</span>
              </div>
              <div className="kpi">
                <span className="kpi-value">
                  {report.firstSaleRevenueMinor != null ? money(report.firstSaleRevenueMinor) : '—'}
                </span>
                <span className="kpi-label">If each seat sold once</span>
                <span className="kpi-note">the whole-journey-only constraint</span>
              </div>
              <div className="kpi">
                <span className="kpi-value">
                  {report.resaleUpliftPct != null ? `${report.resaleUpliftPct > 0 ? '+' : ''}${report.resaleUpliftPct}%` : '—'}
                </span>
                <span className="kpi-label">Resale uplift</span>
              </div>
            </div>
            <p className="muted small">
              Resale uplift compares actual revenue against <strong>each seat sold once, to whoever
              booked it first</strong> — the defining constraint of whole-journey-only ticketing. It
              needs no assumption about historic pricing, so it isolates what resale earned rather than
              conflating it with the separate fare-policy change.
              {report.wholeJourneyCounterfactualMinor != null && (
                <>
                  {' '}For contrast, selling every occupied seat at the full through fare would gross{' '}
                  {money(report.wholeJourneyCounterfactualMinor)} — a <em>fare-policy</em> comparison,
                  and a modelled one, since a passenger travelling 29 km would not have bought a 292 km
                  ticket.
                </>
              )}
            </p>
          </section>

          {/* ---- the heatmap: where does the train empty out? ---- */}
          <section className="card">
            <h2>Occupancy by section</h2>
            <p className="muted small">Seats occupied on each hop between consecutive stops.</p>
            <div className="heatmap">
              {report.occupancy.map((h) => (
                <div className="heat-row" key={h.fromSeq}>
                  <span className="heat-label">
                    {h.fromCode}→{h.toCode}
                  </span>
                  <span className="heat-track">
                    <i
                      style={{
                        width: `${(h.seatsOccupied / maxOcc) * 100}%`,
                        background: heatColour(h.occupancyPct),
                      }}
                    />
                  </span>
                  <span className="heat-value">
                    {h.seatsOccupied}
                    <span className="muted"> / {h.seatsTotal}</span>
                  </span>
                </div>
              ))}
            </div>
            {report.occupancy.length > 0 && (
              <p className="muted small">
                Busiest section{' '}
                <strong>{busiest(report.occupancy)}</strong>; emptiest{' '}
                <strong>{emptiest(report.occupancy)}</strong>. Sections that empty out early are where
                resale earns most.
              </p>
            )}
          </section>

          {/* ---- revenue by segment ---- */}
          <section className="card">
            <h2>Revenue by segment sold</h2>
            {report.revenueBySegment.length === 0 ? (
              <p className="muted">No bookings on this trip yet.</p>
            ) : (
              <table className="datatable">
                <thead>
                  <tr>
                    <th>Segment</th>
                    <th>Bookings</th>
                    <th>Seat-km</th>
                    <th>Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {report.revenueBySegment.map((r) => (
                    <tr key={`${r.fromCode}-${r.toCode}`}>
                      <td>
                        {r.fromCode} → {r.toCode}
                      </td>
                      <td>{r.bookings}</td>
                      <td>{r.seatKm}</td>
                      <td>{money(r.revenueMinor)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}

      <footer className="muted small">
        <a href="/">← passenger app</a> · figures are illustrative seed data
      </footer>
    </div>
  );
}

function heatColour(pct: number) {
  if (pct >= 90) return 'var(--danger)';
  if (pct >= 70) return 'var(--amber)';
  if (pct > 0) return 'var(--accent)';
  return 'var(--line)';
}

function busiest(hops: HopOccupancy[]) {
  const h = hops.reduce((a, b) => (b.seatsOccupied > a.seatsOccupied ? b : a));
  return `${h.fromCode}→${h.toCode} (${h.seatsOccupied})`;
}

function emptiest(hops: HopOccupancy[]) {
  const h = hops.reduce((a, b) => (b.seatsOccupied < a.seatsOccupied ? b : a));
  return `${h.fromCode}→${h.toCode} (${h.seatsOccupied})`;
}
