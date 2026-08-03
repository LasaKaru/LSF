import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api, money, quoteRef } from '../api';
import type { Booking, Quote, Trip, WaitlistEntry } from '../api';

/**
 * The queue for a leg that is sold out.
 *
 * <p>Three states, and the screen is really three screens:
 *
 * <ul>
 *   <li><b>Join</b> — name and email, and the fare locked at today's price.
 *   <li><b>Waiting</b> — your position, polled. Nothing to do but wait.
 *   <li><b>Offered</b> — a seat came free and is <i>held for you</i>, with a countdown.
 * </ul>
 *
 * <p>The offer is a real hold on a real seat, not a notification to go and race
 * for one. That is the whole point of the feature, so the UI says so plainly and
 * shows the clock: an offer that quietly expires while the passenger assumes
 * their seat is safe would be worse than no waitlist at all.
 *
 * <p>The entry id is kept in localStorage. There is no login in this system, so
 * without it a passenger who closes the tab has no way back to their place in the
 * queue — and losing your position because you closed a tab is precisely the
 * unfairness the FIFO rules elsewhere are there to prevent.
 */

const STORAGE_KEY = 'yathra.waitlist';

type Stored = { id: string; email: string };

const remember = (entry: Stored) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entry));
  } catch {
    // Private browsing, quota, a hardened profile — none of it is worth failing a
    // booking flow over. The passenger just loses the convenience of coming back.
  }
};

const recall = (): Stored | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Stored) : null;
  } catch {
    return null;
  }
};

const forget = () => {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* see remember() */
  }
};

export function Waitlist({
  trip,
  from,
  to,
  fromName,
  toName,
  quote,
  onBack,
  onTicket,
}: {
  trip: Trip;
  from: string;
  to: string;
  fromName: string;
  toName: string;
  quote: Quote | null;
  onBack: () => void;
  onTicket: (booking: Booking) => void;
}) {
  const [entry, setEntry] = useState<WaitlistEntry | null>(null);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Resume an entry left in this browser, but only if it is for the journey on
  // screen. Showing someone yesterday's Kandy queue while they are looking at
  // Badulla would be worse than showing nothing.
  const resumed = useRef(false);
  useEffect(() => {
    if (resumed.current) return;
    resumed.current = true;

    const stored = recall();
    if (!stored) return;

    api
      .waitlistStatus(stored.id)
      .then((found) => {
        if (found.tripId === trip.id && found.from === from && found.to === to) {
          setEntry(found);
          setEmail(stored.email);
        }
      })
      .catch(() => forget());
  }, [trip.id, from, to]);

  // ------------------------------------------------------------------ poll

  const refresh = useCallback(async () => {
    if (!entry) return;
    try {
      setEntry(await api.waitlistStatus(entry.id));
    } catch {
      // A failed poll is not worth an error banner: the next one is 5s away and
      // the state on screen is still the last thing the server actually said.
    }
  }, [entry]);

  useEffect(() => {
    if (!entry || (entry.status !== 'WAITING' && entry.status !== 'OFFERED')) return;
    const timer = setInterval(refresh, 5000);
    return () => clearInterval(timer);
  }, [entry, refresh]);

  // ----------------------------------------------------------------- actions

  async function join() {
    if (!quote) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.joinWaitlist({
        tripId: trip.id,
        from,
        to,
        classCode: 'SECOND',
        name: name || 'Passenger',
        email,
        quote: quoteRef(quote),
      });
      remember({ id: created.id, email });
      setEntry(created);
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Could not join the waitlist.');
    } finally {
      setBusy(false);
    }
  }

  async function claim() {
    if (!entry?.offeredBookingId) return;
    setBusy(true);
    setError(null);
    try {
      const confirmed = await api.confirm(entry.offeredBookingId);
      forget();
      onTicket(confirmed);
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.problem.detail
          : 'Could not confirm that seat. It may have already expired.',
      );
      await refresh();
    } finally {
      setBusy(false);
    }
  }

  async function leave() {
    if (!entry) return;
    setBusy(true);
    setError(null);
    try {
      await api.leaveWaitlist(entry.id, email);
      forget();
      setEntry(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.problem.detail : 'Could not leave the waitlist.');
    } finally {
      setBusy(false);
    }
  }

  // -------------------------------------------------------------------- view

  return (
    <section className="card">
      <div className="crumbs">
        <button className="link" onClick={onBack}>← other trains</button>
        <strong>{fromName} → {toName}</strong>
        <span className="muted"> · {trip.trainCode} {trip.trainName}</span>
      </div>

      {error && <div className="banner warn" role="alert">{error}</div>}

      {!entry && (
        <>
          <h2>This leg is sold out — join the queue</h2>
          <p className="muted">
            Seats free up constantly on this line, because a passenger leaving at Kandy releases the rest
            of their seat to Badulla. When one comes free for <strong>{fromName} → {toName}</strong>, it
            is held for you automatically and we email you.
          </p>
          <p className="muted small">
            Strictly first come, first served. Your fare is locked now
            {quote ? <> at <strong>{money(quote.totalMinor, quote.currency)}</strong></> : null}, so a
            price change while you wait cannot cost you.
          </p>

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

          <button className="primary" onClick={join} disabled={busy || !email || !quote}>
            {busy ? 'Joining…' : 'Join the waitlist'}
          </button>
        </>
      )}

      {entry?.status === 'WAITING' && (
        <>
          <h2>You are number {entry.position} in the queue</h2>
          <p className="muted">
            {entry.queueLength} passenger{entry.queueLength === 1 ? '' : 's'} waiting for this leg. We are
            watching for a release and will hold the seat the moment one appears — you do not need to keep
            this page open.
          </p>
          <p className="muted small">
            Fare locked at {money(entry.fareMinor, entry.currency)}.
            {' '}Checking again every few seconds.
          </p>
          <button className="link" onClick={leave} disabled={busy}>
            Leave the waitlist
          </button>
        </>
      )}

      {entry?.status === 'OFFERED' && (
        <>
          <div className="banner info" role="status">
            <strong>A seat came free, and it is being held for you.</strong>
          </div>
          <p className="muted">
            This is a real hold, not an alert to go and race for it — nobody else can take this seat while
            the clock runs.
          </p>
          {entry.offerExpiresAt && <Countdown until={entry.offerExpiresAt} />}
          <button className="primary" onClick={claim} disabled={busy}>
            {busy ? 'Confirming…' : `Confirm my seat · ${money(entry.fareMinor, entry.currency)}`}
          </button>
          <button className="link" onClick={leave} disabled={busy}>
            No longer needed — release it
          </button>
        </>
      )}

      {entry?.status === 'CONVERTED' && (
        <p className="muted">That offer has already been confirmed. Check your email for the ticket.</p>
      )}

      {(entry?.status === 'CANCELLED' || entry?.status === 'EXPIRED') && (
        <p className="muted">This waitlist entry is no longer active.</p>
      )}
    </section>
  );
}

/**
 * Counts down to the moment the offer lapses.
 *
 * <p>Rendered from the server's `offerExpiresAt` rather than from a duration
 * counted locally, so a sleeping laptop or a slow network cannot make the browser
 * believe there is more time left than there is.
 */
function Countdown({ until }: { until: string }) {
  const remaining = () => Math.max(0, Math.floor((new Date(until).getTime() - Date.now()) / 1000));
  const [left, setLeft] = useState(remaining);

  useEffect(() => {
    const timer = setInterval(() => setLeft(remaining()), 1000);
    return () => clearInterval(timer);
  }, [until]);

  const mm = String(Math.floor(left / 60)).padStart(2, '0');
  const ss = String(left % 60).padStart(2, '0');

  return (
    <p className={left < 120 ? 'countdown urgent' : 'countdown'}>
      {left > 0 ? <>Held for you for {mm}:{ss}</> : <>This offer has expired.</>}
    </p>
  );
}
