/**
 * API client.
 *
 * Same-origin relative URLs throughout: the gateway serves the SPA and proxies
 * /api on the same host, so there is no CORS involved and no base URL to
 * configure per environment.
 */

export type Station = {
  code: string;
  nameEn: string;
  nameSi?: string;
  nameTa?: string;
};

export type Trip = {
  id: string;
  trainCode: string;
  trainName: string;
  direction: string;
  serviceDate: string;
  departsAt: string;
  arrivesAt: string;
  bookingCutoffAt: string;
};

export type CoachAvailability = {
  coachNumber: string;
  classCode: string;
  totalSeats: number;
  availableSeats: number;
};

export type Availability = {
  tripId: string;
  from: string;
  to: string;
  fromSeq: number;
  toSeq: number;
  distanceKm: number;
  coaches: CoachAvailability[];
  totalAvailable: number;
};

export type Seat = {
  seatId: string;
  label: string;
  row: number;
  col: number;
  window: boolean;
  aisle: boolean;
  availableForRequestedLeg: boolean;
  partiallyAvailable: boolean;
  /** Stretches already sold on this seat, as half-open [from, to) ranges. */
  occupied: [number, number][];
};

export type CoachLayout = {
  rows: number;
  columns: number;
  aisleAfterColumn: number | null;
  seatPattern: string[];
  windowColumns?: number[];
  blanks?: { row: number; col: number }[];
};

export type Coach = {
  coachNumber: string;
  classCode: string;
  layout: CoachLayout | null;
  seats: Seat[];
};

export type Stop = {
  stopSequence: number;
  stationCode: string;
  nameEn: string;
  distanceKm: number;
  scheduledArrival?: string;
  scheduledDeparture?: string;
};

export type SeatMap = {
  tripId: string;
  fromSeq: number;
  toSeq: number;
  coaches: Coach[];
  stops: Stop[];
};

export type LineItem = { code: string; label: string; amountMinor: number };

export type Quote = {
  quoteId: string;
  tripId: string;
  fromSeq: number;
  toSeq: number;
  distanceKm: number;
  classCode: string;
  coachType: string;
  passengers: number;
  currency: string;
  breakdown: LineItem[];
  unitFareMinor: number;
  totalMinor: number;
  ruleSetVersion: string;
  expiresAt: string;
  signature: string;
};

export type BookingSegment = {
  seatId: string;
  seatLabel: string;
  coachNumber: string;
  from: string;
  to: string;
  fromSeq: number;
  toSeq: number;
  distanceKm: number;
  fareMinor: number;
};

export type Booking = {
  bookingId: string;
  reference: string;
  status: string;
  expiresAt?: string;
  holdSeconds?: number;
  totalMinor: number;
  currency: string;
  segments: BookingSegment[];
};

/** RFC 9457 problem detail, with the extension members that make a 409 recoverable. */
export type Problem = {
  status: number;
  code: string;
  title: string;
  detail: string;
  conflicts?: { seatId: string; seatLabel: string; occupiedLeg: [number, number] }[];
  suggestedAlternatives?: { seatId: string; seatLabel: string; coachNumber: string; window: boolean }[];
  availabilityUrl?: string;
};

export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail || problem.title);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });

  if (!response.ok) {
    let problem: Problem;
    try {
      problem = (await response.json()) as Problem;
    } catch {
      problem = {
        status: response.status,
        code: 'UNKNOWN',
        title: 'Request failed',
        detail: `${response.status} ${response.statusText}`,
      };
    }
    throw new ApiError({ ...problem, status: problem.status ?? response.status });
  }

  return (await response.json()) as T;
}

export const api = {
  stations: () => request<Station[]>('/api/v1/stations'),

  trips: (date: string, from: string, to: string) =>
    request<Trip[]>(`/api/v1/trips?date=${date}&from=${from}&to=${to}`),

  availability: (tripId: string, from: string, to: string, classCode?: string) =>
    request<Availability>(
      `/api/v1/trips/${tripId}/availability?from=${from}&to=${to}` +
        (classCode ? `&class=${classCode}` : ''),
    ),

  seatMap: (tripId: string, from: string, to: string, classCode?: string) =>
    request<SeatMap>(
      `/api/v1/trips/${tripId}/seat-map?from=${from}&to=${to}` + (classCode ? `&class=${classCode}` : ''),
    ),

  quote: (tripId: string, fromStation: string, toStation: string, passengers = 1) =>
    request<Quote>('/api/v1/quotes', {
      method: 'POST',
      body: JSON.stringify({
        tripId,
        fromStation,
        toStation,
        classCode: 'SECOND',
        coachType: 'RESERVED',
        passengers,
      }),
    }),

  /**
   * Creates a hold.
   *
   * The idempotency key is generated once per booking attempt by the caller and
   * reused for every retry of that attempt, so a double-tap or a flaky reconnect
   * resolves to one booking rather than two.
   */
  book: (body: unknown, idempotencyKey: string) =>
    request<Booking>('/api/v1/bookings', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }),

  confirm: (bookingId: string) =>
    request<Booking>(`/api/v1/bookings/${bookingId}/confirm`, { method: 'POST' }),
};

export const money = (minor: number, currency = 'LKR') =>
  `${currency} ${(minor / 100).toLocaleString(undefined, { maximumFractionDigits: 0 })}`;

export const time = (iso?: string) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
