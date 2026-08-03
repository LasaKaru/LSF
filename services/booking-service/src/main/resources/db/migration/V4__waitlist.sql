-- ===========================================================================
-- Waitlisting for fully booked segments.
--
-- On peak dates every seat is sold for the popular Nanu Oya -> Ella stretch.
-- Cancellations and expired holds DO free inventory, often several times an
-- hour, but that is invisible: passengers either refresh obsessively or give
-- up. The department loses the sale and the seat may go empty anyway.
--
-- Deployment note: docs/02 describes waitlist-service as a separate deployable
-- consuming SegmentReleased from Kafka. This implementation lives inside
-- booking-service and consumes the same events from the transactional outbox
-- instead, because the shipped topology has no broker. The consumer contract is
-- identical -- it reads SegmentReleased and dedupes on event id -- so extracting
-- it later is a deployment change, not a redesign. It also has the pleasant
-- side effect of making the outbox a table something actually reads.
-- ===========================================================================

CREATE TYPE waitlist_status AS ENUM ('WAITING', 'OFFERED', 'CONVERTED', 'EXPIRED', 'CANCELLED');

CREATE TABLE waitlist_entry (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id           UUID NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    from_seq          INT NOT NULL,
    to_seq            INT NOT NULL,
    from_station_code VARCHAR(8) NOT NULL,
    to_station_code   VARCHAR(8) NOT NULL,
    class_code        class_enum NOT NULL,
    contact_name      VARCHAR(160),
    contact_email     CITEXT NOT NULL,
    contact_phone     VARCHAR(32),
    passengers        INT NOT NULL DEFAULT 1,

    -- The fare is locked when the passenger joins the queue, not when they are
    -- promoted. Promotion is server-initiated and may happen at 03:00; making
    -- the passenger accept whatever the fare engine says at that moment would be
    -- indefensible, and re-quoting mid-promotion would need a pricing call
    -- inside the matcher's transaction (see ADR-003).
    fare_minor        BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'LKR',

    status            waitlist_status NOT NULL DEFAULT 'WAITING',
    offered_booking_id UUID REFERENCES booking(id),
    offer_expires_at  TIMESTAMPTZ,

    -- FIFO position. NEVER modified after insert: a passenger whose offer lapses
    -- returns to WAITING at their ORIGINAL place in the queue. Punishing someone
    -- for being asleep when a notification fired is not fair queueing.
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    leg int4range GENERATED ALWAYS AS (int4range(from_seq, to_seq, '[)')) STORED,

    CONSTRAINT waitlist_seq_ordered  CHECK (from_seq < to_seq),
    CONSTRAINT waitlist_seq_positive CHECK (from_seq >= 1),
    CONSTRAINT waitlist_fare_non_neg CHECK (fare_minor >= 0),
    CONSTRAINT offered_has_expiry
        CHECK (status <> 'OFFERED' OR (offered_booking_id IS NOT NULL AND offer_expires_at IS NOT NULL))
);

-- The matcher's query: oldest WAITING entry on a trip whose leg fits inside a
-- freed stretch. Partial so the index stays proportional to the live queue.
CREATE INDEX idx_waitlist_matching ON waitlist_entry (trip_id, created_at)
    WHERE status = 'WAITING';

-- "Where am I in the queue?" and "what am I waiting for?"
CREATE INDEX idx_waitlist_contact ON waitlist_entry (contact_email, created_at DESC);
CREATE INDEX idx_waitlist_offers  ON waitlist_entry (offer_expires_at) WHERE status = 'OFFERED';

CREATE TRIGGER waitlist_touch_updated_at
    BEFORE UPDATE ON waitlist_entry
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
