import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError, api, money, quoteRef, time } from './api';
import type { Availability, Booking, Problem, Quote, Seat, SeatMap as SeatMapData, Station, Trip } from './api';
import { SeatMap } from './components/SeatMap';
import { Waitlist } from './components/Waitlist';

type Step = 'search' | 'trips' | 'seats' | 'details' | 'ticket' | 'waitlist';

const today = () => new Date().toISOString().slice(0, 10);
const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 10);

export default function App() {
  const [step, setStep] = useState<Step>('search');
  const [stations, setStations] = useState<Station[]>([]);
  const [from, setFrom] = useState('CMB');
  const [to, setTo] = useState('KDY');
  const [date, setDate] = useState(inDays(2));

  const [trips, setTrips] = useState<Trip[]>([]);
  const [availability, setAvailability] = useState<Record<string, Availability>>({});
  const [trip, setTrip] = useState<Trip | null>(null);

  const [seatMap, setSeatMap] = useState<SeatMapData | null>(null);
  const [seat, setSeat] = useState<Seat | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [booking, setBooking] = useState<Booking | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<Problem | null>(null);

  /**
   * The idempotency key is minted once per booking attempt and reused for every
   * retry of that attempt, so a double-tap, an impatient refresh or a flaky
   * reconnect all resolve to the same booking rather than two.
   */
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  useEffect(() => {
    api.stations().then(setStations).catch(() => setError('Could not load stations.'));
  }, []);

  const stationName = useCallback(
    (code: string) => stations.find((s) => s.code === code)?.nameEn ?? code,
    [stations],
  );

  // ----------------------------------------------------------------- search

  async function search() {
    setBusy(true);
    setError(null);
    try {
      const found = await api.trips(date, from, to);
      setTrips(found);
      setStep('trips');

      // Availability is fetched PER TRIP and PER LEG. "Seats free on this train"
      // is meaningless once inventory is segment-granular: the same seat can be
      // free for your leg and sold for someone else's.
      const entries = await Promise.all(
        found.map(async (t) => [t.id, await api.availability(t.id, from, to, 'SECOND')] as const),
      );
      setAvailability(Object.fromEntries(entries));
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Search failed.');
    } finally {
      setBusy(false);
    }
  }

  async function chooseTrip(t: Trip) {
    setBusy(true);
    setError(null);
    setTrip(t);
    try {
      const [map, q] = await Promise.all([
        api.seatMap(t.id, from, to, 'SECOND'),
        api.quote(t.id, from, to, 1),
      ]);
      setSeatMap(map);
      setQuote(q);
      setSeat(null);
      setStep('seats');
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Could not load the seat map.');
    } finally {
      setBusy(false);
    }
  }

  /**
   * Sold out for this leg — offer the queue instead of a dead end.
   *
   * <p>Still fetches a quote, because joining the waitlist locks the fare at
   * today's price. The seat map is skipped: there is no seat to show, and asking
   * the passenger to look at a wall of grey before telling them it is full would
   * be a worse way to deliver the same news.
   */
  async function joinQueueFor(t: Trip) {
    setBusy(true);
    setError(null);
    setTrip(t);
    try {
      setQuote(await api.quote(t.id, from, to, 1));
      setStep('waitlist');
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Could not price this journey.');
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------------ book

  async function submit(seatOverride?: Seat) {
    const chosen = seatOverride ?? seat;
    if (!trip || !chosen || !quote) return;

    setBusy(true);
    setError(null);
    setConflict(null);

    try {
      const created = await api.book(
        {
          tripId: trip.id,
          from,
          to,
          seatSelection: { mode: 'SPECIFIC', seatIds: [chosen.seatId] },
          quote: quoteRef(quote),
          passengers: [{ name: name || 'Passenger', type: 'ADULT' }],
          contact: { email, name: name || 'Passenger' },
        },
        idempotencyKey,
      );
      setBooking(created);
      setStep('ticket');
    } catch (e) {
      if (e instanceof ApiError && e.problem.code === 'SEAT_SEGMENT_UNAVAILABLE') {
        // Losing a race should cost one click, not a restart. The problem detail
        // carries ranked alternatives, so the banner can offer a real seat and
        // resubmit with the passenger's details intact.
        setConflict(e.problem);
        // Back to the seat map, which is where the recovery banner and the refreshed
        // occupancy live. Without this the hold is submitted from the details screen,
        // the 409 sets `conflict`, and *nothing on screen changes* -- the button simply
        // stops spinning. The one-click recovery was unreachable from the only screen
        // that can produce the conflict. Found by driving the real UI; no test caught
        // it, because the API returns a perfectly good 409 either way.
        setStep('seats');
        setSeat(null);
        if (trip) api.seatMap(trip.id, from, to, 'SECOND').then(setSeatMap).catch(() => {});
      } else if (e instanceof ApiError) {
        setError(e.problem.detail);
        // A fresh attempt needs a fresh key: reusing it would replay the failure.
        if (e.problem.code === 'QUOTE_INVALID') setIdempotencyKey(crypto.randomUUID());
      } else {
        setError('Booking failed. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function takeAlternative(seatId: string, label: string) {
    if (!seatMap) return;
    const replacement =
      seatMap.coaches.flatMap((c) => c.seats).find((s) => s.seatId === seatId) ??
      ({ seatId, label } as Seat);
    setSeat(replacement);
    setConflict(null);
    // New attempt, new key.
    setIdempotencyKey(crypto.randomUUID());
    await submit(replacement);
  }

  const coach = seatMap?.coaches[0];

  return (
    <div className="app">
      <header>
        <h1>
          Yathra <span className="muted">· Colombo Fort ⇄ Badulla</span>
        </h1>
        <p className="tagline">Pay for the distance you travel, not for the seat behind you.</p>
        {/* The admin view was previously reachable only by typing the URL. */}
        <a className="navlink" href="/admin">Departmental view →</a>
      </header>

      <StepBar step={step} />

      {error && (
        <div className="banner error" role="alert">
          {error}
          <button className="link" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}

      {/* ------------------------------------------------------- 1. search */}
      {step === 'search' && (
        <section className="card">
          <h2>Where are you going?</h2>
          <div className="row">
            <label>
              From
              <select value={from} onChange={(e) => setFrom(e.target.value)}>
                {stations.map((s) => (
                  <option key={s.code} value={s.code}>{s.nameEn} ({s.code})</option>
                ))}
              </select>
            </label>
            <button
              className="swap"
              title="Swap origin and destination"
              onClick={() => { setFrom(to); setTo(from); }}
            >
              ⇄
            </button>
            <label>
              To
              <select value={to} onChange={(e) => setTo(e.target.value)}>
                {stations.map((s) => (
                  <option key={s.code} value={s.code}>{s.nameEn} ({s.code})</option>
                ))}
              </select>
            </label>
            <label>
              Date
              <input type="date" value={date} min={today()} onChange={(e) => setDate(e.target.value)} />
            </label>
          </div>
          <button className="primary" onClick={search} disabled={busy || from === to}>
            {busy ? 'Searching…' : 'Search trains'}
          </button>
          {from === to && <p className="muted small">Choose two different stations.</p>}
        </section>
      )}

      {/* -------------------------------------------------------- 2. trips */}
      {step === 'trips' && (
        <section className="card">
          <div className="crumbs">
            <button className="link" onClick={() => setStep('search')}>← change search</button>
            <strong>{stationName(from)} → {stationName(to)}</strong> on {date}
          </div>

          {trips.length === 0 && <p className="muted">No trains found for that journey and date.</p>}

          <ul className="triplist">
            {trips.map((t) => {
              const a = availability[t.id];
              return (
                <li key={t.id}>
                  <div>
                    <strong>{time(t.departsAt)} → {time(t.arrivesAt)}</strong>
                    <span className="muted"> · {t.trainCode} {t.trainName}</span>
                    <div className="muted small">
                      {a ? (
                        <>
                          {a.totalAvailable} seat{a.totalAvailable === 1 ? '' : 's'} free for this leg
                          {' · '}{a.distanceKm} km
                        </>
                      ) : '…'}
                    </div>
                  </div>
                  {/* Sold out is not a dead end -- it is the entry point to the
                      queue. A seat on this line frees up every time someone
                      leaves partway, so "full" is very often temporary. */}
                  {a && a.totalAvailable === 0 ? (
                    <button className="primary" disabled={busy} onClick={() => joinQueueFor(t)}>
                      Sold out · join waitlist
                    </button>
                  ) : (
                    <button className="primary" disabled={busy} onClick={() => chooseTrip(t)}>
                      Choose seat
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {/* -------------------------------------------------------- 3. seats */}
      {step === 'seats' && seatMap && coach && (
        <section className="card">
          <div className="crumbs">
            <button className="link" onClick={() => setStep('trips')}>← other trains</button>
            <strong>{stationName(from)} → {stationName(to)}</strong>
            {quote && <> · {money(quote.totalMinor, quote.currency)} · {quote.distanceKm} km</>}
          </div>

          {conflict && (
            <div className="banner warn" role="alert">
              <strong>{conflict.detail}</strong>
              {conflict.suggestedAlternatives?.length ? (
                <div className="alts">
                  {conflict.suggestedAlternatives.slice(0, 3).map((alt) => (
                    <button
                      key={alt.seatId}
                      className="primary small"
                      disabled={busy}
                      onClick={() => takeAlternative(alt.seatId, alt.seatLabel)}
                    >
                      Take {alt.seatLabel}{alt.window ? ' (window)' : ''}
                    </button>
                  ))}
                  <span className="muted small">Your details are kept — one click to rebook.</span>
                </div>
              ) : null}
            </div>
          )}

          <div className="coachtabs">
            {seatMap.coaches.map((c) => (
              <span key={c.coachNumber} className={c.coachNumber === coach.coachNumber ? 'on' : ''}>
                Coach {c.coachNumber} · {c.classCode}
              </span>
            ))}
          </div>

          <SeatMap
            coach={coach}
            stops={seatMap.stops}
            fromSeq={seatMap.fromSeq}
            toSeq={seatMap.toSeq}
            selectedSeatId={seat?.seatId ?? null}
            onSelect={setSeat}
            fareMinor={quote?.totalMinor}
          />

          <button className="primary" disabled={!seat} onClick={() => setStep('details')}>
            {seat ? `Continue with seat ${seat.label}` : 'Select a seat'}
          </button>
        </section>
      )}

      {/* ------------------------------------------------------ 4. details */}
      {step === 'details' && quote && seat && (
        <section className="card">
          <div className="crumbs">
            <button className="link" onClick={() => setStep('seats')}>← change seat</button>
            <strong>Seat {seat.label}</strong>
          </div>

          <h2>Your details</h2>
          <div className="row">
            <label>
              Name
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder="A. Perera" />
            </label>
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.lk"
              />
            </label>
          </div>

          {/* The passenger should be able to see that they are paying for their
              own 121 km, not for a whole train. */}
          <div className="fare">
            <h3>Fare breakdown</h3>
            <ul>
              {quote.breakdown.map((l, i) => (
                <li key={i}>
                  <span>{l.label}</span>
                  <span>{l.amountMinor === 0 ? '—' : money(l.amountMinor, quote.currency)}</span>
                </li>
              ))}
              <li className="total">
                <span>Total for {quote.distanceKm} km</span>
                <span>{money(quote.totalMinor, quote.currency)}</span>
              </li>
            </ul>
          </div>

          <button className="primary" onClick={() => submit()} disabled={busy || !email}>
            {busy ? 'Holding your seat…' : 'Hold this seat'}
          </button>
          {!email && <p className="muted small">An email address is needed to retrieve your booking.</p>}
        </section>
      )}

      {/* ----------------------------------------------------- 4b. waitlist */}
      {step === 'waitlist' && trip && (
        <Waitlist
          trip={trip}
          from={from}
          to={to}
          fromName={stationName(from)}
          toName={stationName(to)}
          quote={quote}
          onBack={() => setStep('trips')}
          // A confirmed offer is an ordinary confirmed booking, so it lands on
          // the ordinary ticket screen. Nothing about arriving via the queue
          // makes the resulting ticket a different kind of thing.
          onTicket={(confirmed) => {
            setBooking(confirmed);
            setStep('ticket');
          }}
        />
      )}

      {/* ------------------------------------------------------- 5. ticket */}
      {step === 'ticket' && booking && (
        <Ticket
          booking={booking}
          fromName={stationName(from)}
          toName={stationName(to)}
          onConfirmed={setBooking}
          onRestart={() => {
            setStep('search');
            setBooking(null);
            setSeat(null);
            setConflict(null);
            setIdempotencyKey(crypto.randomUUID());
          }}
        />
      )}

      <footer className="muted small">
        Fares and distances are illustrative and must be validated against official Sri Lanka Railways
        tariff data before any real deployment.
      </footer>
    </div>
  );
}

/**
 * Where you are in the booking, and how much is left.
 *
 * <p>Worth the space because this flow has a *timed* middle: once a seat is held
 * there is a countdown running, and someone who cannot see how many steps remain
 * cannot judge whether they have time to finish. The waitlist branch replaces the
 * seat and details steps rather than appending to them, so the bar reflects the
 * path actually taken instead of implying the passenger skipped something.
 *
 * <p>Purely presentational, and `aria-hidden`: every step it names is already
 * announced by the heading and breadcrumb of the step itself, so exposing it to a
 * screen reader would mean reading the whole flow twice on every transition.
 */
function StepBar({ step }: { step: Step }) {
  const steps: { key: Step; label: string }[] =
    step === 'waitlist'
      ? [
          { key: 'search', label: 'Search' },
          { key: 'trips', label: 'Trains' },
          { key: 'waitlist', label: 'Waitlist' },
          { key: 'ticket', label: 'Ticket' },
        ]
      : [
          { key: 'search', label: 'Search' },
          { key: 'trips', label: 'Trains' },
          { key: 'seats', label: 'Seat' },
          { key: 'details', label: 'Details' },
          { key: 'ticket', label: 'Ticket' },
        ];

  const current = steps.findIndex((s) => s.key === step);

  return (
    <ol className="steps" aria-hidden="true">
      {steps.map((s, i) => (
        <li
          key={s.key}
          className={i === current ? 'on' : i < current ? 'done' : undefined}
        >
          <span className="steps-dot">{i < current ? '✓' : i + 1}</span>
          {s.label}
        </li>
      ))}
    </ol>
  );
}

/**
 * The hold countdown.
 *
 * A hold that expires silently and then fails at payment is the failure mode
 * this screen exists to prevent: the passenger can see the clock, and when it
 * runs out they are told what happened rather than shown a stack trace.
 */
function Ticket({
  booking,
  fromName,
  toName,
  onConfirmed,
  onRestart,
}: {
  booking: Booking;
  fromName: string;
  toName: string;
  onConfirmed: (b: Booking) => void;
  onRestart: () => void;
}) {
  const expiresAt = useMemo(
    () => (booking.expiresAt ? new Date(booking.expiresAt).getTime() : null),
    [booking.expiresAt],
  );
  const [remaining, setRemaining] = useState(() =>
    expiresAt ? Math.max(0, Math.floor((expiresAt - Date.now()) / 1000)) : 0,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (booking.status !== 'HELD' || !expiresAt) return;
    const id = setInterval(
      () => setRemaining(Math.max(0, Math.floor((expiresAt - Date.now()) / 1000))),
      1000,
    );
    return () => clearInterval(id);
  }, [booking.status, expiresAt]);

  const segment = booking.segments[0];
  const mm = String(Math.floor(remaining / 60)).padStart(2, '0');
  const ss = String(remaining % 60).padStart(2, '0');
  const held = booking.status === 'HELD';
  const expired = held && remaining === 0;

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      onConfirmed(await api.confirm(booking.bookingId));
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Could not confirm.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card ticket">
      <h2>{booking.status === 'CONFIRMED' ? 'Your ticket' : 'Seat held'}</h2>

      {held && !expired && (
        <div className={`banner ${remaining < 120 ? 'warn' : 'info'}`}>
          Held for <strong>{mm}:{ss}</strong> — confirm before the timer runs out.
        </div>
      )}
      {expired && (
        <div className="banner error">
          This hold expired and the seat has been released back to other passengers. Nothing was
          charged.
          <button className="link" onClick={onRestart}>start again</button>
        </div>
      )}
      {error && <div className="banner error">{error}</div>}

      <dl className="ticketgrid">
        <dt>Reference</dt><dd className="ref">{booking.reference}</dd>
        <dt>Journey</dt><dd>{fromName} → {toName}</dd>
        <dt>Seat</dt><dd>{segment?.seatLabel} · coach {segment?.coachNumber}</dd>
        <dt>Distance</dt><dd>{segment?.distanceKm} km</dd>
        <dt>Total</dt><dd>{money(booking.totalMinor, booking.currency)}</dd>
        <dt>Status</dt><dd>{booking.status}</dd>
      </dl>

      {held && !expired && (
        <button className="primary" onClick={confirm} disabled={busy}>
          {busy ? 'Confirming…' : 'Pay and confirm'}
        </button>
      )}
      {booking.status === 'CONFIRMED' && (
        <>
          <p className="muted">
            Keep your reference. You will need it and your email address to retrieve this booking.
          </p>
          <button className="link" onClick={onRestart}>Book another journey</button>
        </>
      )}
    </section>
  );
}
