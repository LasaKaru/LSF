import { useEffect, useMemo, useRef, useState } from 'react';
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
 *
 * **Keyboard.** The grid declares `role="grid"`, and that declaration is a
 * promise: a screen-reader user who meets a grid expects arrow keys to move
 * between cells and expects the whole grid to be a single tab stop. It used to
 * declare the role without implementing either, which is worse than using no
 * role at all -- 60 seats meant 60 tab stops, and arrow keys did nothing. It now
 * uses a roving tabindex: one seat is tabbable, arrows move within the coach,
 * Home/End jump along a row, Ctrl+Home/End to the first or last seat.
 *
 * Unavailable seats are `aria-disabled` rather than `disabled`, deliberately.
 * A `disabled` button cannot be focused, so a keyboard user could not reach a
 * taken seat to find out *why* it was unavailable -- and with segment inventory
 * "why" is the interesting part, because an amber seat is free for some of the
 * journey and its occupancy range is the thing worth reading. Activating one
 * does nothing; it just can't be silently hidden from non-mouse users.
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

  // ------------------------------------------------------- keyboard navigation

  const gridRef = useRef<HTMLDivElement>(null);

  /** Every occupied grid position, in reading order. */
  const positions = useMemo(
    () =>
      [...coach.seats]
        .sort((a, b) => a.row - b.row || a.col - b.col)
        .map((s) => `${s.row}:${s.col}`),
    [coach.seats],
  );

  // Which seat currently carries tabindex=0. Starts on the selected seat if there
  // is one, so returning to the map puts you back where you were rather than at 1A.
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const selectedKey = useMemo(() => {
    const seat = coach.seats.find((s) => s.seatId === selectedSeatId);
    return seat ? `${seat.row}:${seat.col}` : null;
  }, [coach.seats, selectedSeatId]);

  const active = activeKey ?? selectedKey ?? positions[0] ?? null;

  // Move focus only when the grid already owns it. Without this guard the effect
  // would steal focus from the page on first render and on every coach change.
  useEffect(() => {
    const grid = gridRef.current;
    if (!grid || !activeKey || !grid.contains(document.activeElement)) return;
    grid.querySelector<HTMLButtonElement>(`[data-key="${activeKey}"]`)?.focus();
  }, [activeKey]);

  function step(from: string, dRow: number, dCol: number): string | null {
    let [row, col] = from.split(':').map(Number);
    // Walk until the next occupied cell, so a door or luggage bay is skipped over
    // rather than swallowing the keypress.
    for (let i = 0; i < (layout ? layout.rows + layout.columns : 0); i++) {
      row += dRow;
      col += dCol;
      if (row < 1 || col < 1 || (layout && (row > layout.rows || col > layout.columns))) return null;
      const key = `${row}:${col}`;
      if (byPosition.has(key)) return key;
    }
    return null;
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!active) return;

    const rowOf = (key: string) => Number(key.split(':')[0]);
    let next: string | null = null;

    switch (event.key) {
      case 'ArrowUp': next = step(active, -1, 0); break;
      case 'ArrowDown': next = step(active, 1, 0); break;
      case 'ArrowLeft': next = step(active, 0, -1); break;
      case 'ArrowRight': next = step(active, 0, 1); break;
      case 'Home':
        next = event.ctrlKey
          ? positions[0]
          : positions.find((k) => rowOf(k) === rowOf(active)) ?? null;
        break;
      case 'End':
        next = event.ctrlKey
          ? positions[positions.length - 1]
          : [...positions].reverse().find((k) => rowOf(k) === rowOf(active)) ?? null;
        break;
      default:
        return;
    }

    // Swallow the key even at an edge, so Down on the back row does not scroll the
    // page out from under someone who is still reading the coach.
    event.preventDefault();
    if (next) setActiveKey(next);
  }

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
        ref={gridRef}
        onKeyDown={onKeyDown}
        aria-label={`Seat map for coach ${coach.coachNumber}. Use the arrow keys to move between seats.`}
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
                      data-key={`${row}:${col}`}
                      // Roving tabindex: the grid is one tab stop, arrows move inside it.
                      tabIndex={`${row}:${col}` === active ? 0 : -1}
                      aria-disabled={!selectable}
                      onFocus={() => setActiveKey(`${row}:${col}`)}
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
