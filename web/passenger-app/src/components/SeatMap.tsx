import { useMemo } from 'react';
import type { Coach, Seat } from '../api';

/**
 * The seat map.
 *
 * Two things make it worth building rather than listing seat codes:
 *
 * 1. **Tri-state.** In segment inventory a seat is not simply free or taken --
 *    it can be free for *part* of your journey. That amber state does not exist
 *    in whole-journey booking and no list representation can express it. It is
 *    the visible signature of the whole project.
 *
 * 2. **Layout is data.** This component is a pure function of (layout,
 *    occupancy) where layout is the coach_layout grid JSON. A 2+2 second-class
 *    coach, a 2+1 observation saloon and a 3+2 commuter coach are three database
 *    rows -- adding a coach type needs no change here. That is the frontend half
 *    of the brief's configurability requirement.
 *
 * Colour is never the only channel: every seat carries an aria-label stating
 * everything the colour conveys, and the legend is text.
 */

type Props = {
  coach: Coach;
  stops: { stopSequence: number; stationCode: string }[];
  fromSeq: number;
  toSeq: number;
  selectedSeatId: string | null;
  onSelect: (seat: Seat) => void;
  fareMinor?: number;
};

type SeatState = 'available' | 'partial' | 'taken' | 'selected';

function stateOf(seat: Seat, selectedSeatId: string | null): SeatState {
  if (seat.seatId === selectedSeatId) return 'selected';
  if (seat.availableForRequestedLeg) return 'available';
  return seat.partiallyAvailable ? 'partial' : 'taken';
}

const STATE_LABEL: Record<SeatState, string> = {
  available: 'available',
  partial: 'partly available',
  taken: 'not available',
  selected: 'selected',
};

export function SeatMap({
  coach,
  stops,
  fromSeq,
  toSeq,
  selectedSeatId,
  onSelect,
  fareMinor,
}: Props) {
  const layout = coach.layout;

  // Index seats by grid position so blanks (a door, a luggage rack, a wheelchair
  // bay) render as gaps rather than shifting every seat after them.
  const byPosition = useMemo(() => {
    const map = new Map<string, Seat>();
    coach.seats.forEach((s) => map.set(`${s.row}:${s.col}`, s));
    return map;
  }, [coach.seats]);

  if (!layout) {
    return (
      <p className="muted">
        Coach {coach.coachNumber} is unreserved — no seat assignment, first come first served.
      </p>
    );
  }

  const firstSeq = stops.length ? stops[0].stopSequence : 1;
  const lastSeq = stops.length ? stops[stops.length - 1].stopSequence : 25;
  const span = Math.max(1, lastSeq - firstSeq);

  return (
    <div className="seatmap">
      <div className="seatmap-legend" role="note">
        <span><i className="swatch s-available" /> Available</span>
        <span><i className="swatch s-partial" /> Free for part of your leg</span>
        <span><i className="swatch s-taken" /> Taken</span>
        <span><i className="swatch s-selected" /> Your seat</span>
      </div>

      <div
        className="seatmap-grid"
        role="grid"
        aria-label={`Seat map for coach ${coach.coachNumber}`}
        style={{ ['--cols' as string]: layout.columns }}
      >
        {Array.from({ length: layout.rows }, (_, r) => {
          const row = r + 1;
          return (
            <div className="seatmap-row" role="row" key={row}>
              <span className="seatmap-rownum" aria-hidden="true">{row}</span>
              {Array.from({ length: layout.columns }, (_, c) => {
                const col = c + 1;
                const seat = byPosition.get(`${row}:${col}`);
                const aisleAfter = layout.aisleAfterColumn === col;

                if (!seat) {
                  return (
                    <span key={col} role="gridcell" className="seat seat-blank" aria-hidden="true" />
                  );
                }

                const state = stateOf(seat, selectedSeatId);
                const selectable = state === 'available' || state === 'selected';

                return (
                  <span key={col} role="gridcell" className={aisleAfter ? 'has-aisle' : undefined}>
                    <button
                      type="button"
                      className={`seat s-${state}`}
                      disabled={!selectable}
                      onClick={() => selectable && onSelect(seat)}
                      title={describe(seat, state, stops)}
                      aria-label={
                        `Seat ${seat.label}, ${seat.window ? 'window' : seat.aisle ? 'aisle' : 'middle'}, ` +
                        `coach ${coach.coachNumber}, ${STATE_LABEL[state]}` +
                        (selectable && fareMinor ? `, LKR ${(fareMinor / 100).toFixed(0)}` : '') +
                        (state === 'partial' ? `. ${describe(seat, state, stops)}` : '')
                      }
                    >
                      {seat.label}
                    </button>
                  </span>
                );
              })}
            </div>
          );
        })}
      </div>

      {/* Occupancy bars: where each sold seat is actually busy, as ranges only.
          No names, no references - the endpoint never returned any. */}
      {coach.seats.some((s) => s.occupied.length > 0) && (
        <details className="occupancy">
          <summary>Where this coach is already sold</summary>
          <ul>
            {coach.seats
              .filter((s) => s.occupied.length > 0)
              .map((s) => (
                <li key={s.seatId}>
                  <span className="occ-label">{s.label}</span>
                  <span className="occ-bar" aria-hidden="true">
                    {s.occupied.map(([a, b], i) => (
                      <i
                        key={i}
                        style={{
                          left: `${((a - firstSeq) / span) * 100}%`,
                          width: `${((b - a) / span) * 100}%`,
                        }}
                      />
                    ))}
                    <b
                      className="occ-you"
                      style={{
                        left: `${((fromSeq - firstSeq) / span) * 100}%`,
                        width: `${((toSeq - fromSeq) / span) * 100}%`,
                      }}
                    />
                  </span>
                  <span className="occ-text">
                    {s.occupied.map(([a, b]) => `${codeAt(stops, a)}→${codeAt(stops, b)}`).join(', ')}
                  </span>
                </li>
              ))}
          </ul>
          <p className="muted small">
            The outlined bar is your leg. Shaded stretches are already sold on that seat.
          </p>
        </details>
      )}
    </div>
  );
}

function codeAt(stops: { stopSequence: number; stationCode: string }[], seq: number) {
  return stops.find((s) => s.stopSequence === seq)?.stationCode ?? String(seq);
}

function describe(
  seat: Seat,
  state: SeatState,
  stops: { stopSequence: number; stationCode: string }[],
) {
  if (state === 'available' || state === 'selected') {
    return `Seat ${seat.label} — free for your whole journey`;
  }
  if (!seat.occupied.length) return `Seat ${seat.label}`;
  const sold = seat.occupied.map(([a, b]) => `${codeAt(stops, a)}→${codeAt(stops, b)}`).join(', ');
  return state === 'partial'
    ? `Free for part of your leg. Already sold: ${sold}`
    : `Already sold: ${sold}`;
}
