-- ===========================================================================
-- booking_db, part 3 — idempotency, the transactional outbox, and the
-- sweeper's distributed lock.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Idempotency. Written in the SAME transaction as the booking, so there is no
-- window in which a booking exists but its idempotency record does not.
-- Mobile users on congested networks double-tap "Confirm" constantly and the
-- gateway retries on 5xx; without this, a network blip sells two seats.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    key             VARCHAR(128) PRIMARY KEY,
    request_hash    CHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body   JSONB NOT NULL,
    booking_id      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);

-- ---------------------------------------------------------------------------
-- Transactional outbox. The event row is written in the booking transaction,
-- so "booking committed" and "event will be delivered" are the same atomic
-- fact. A relay drains it afterwards. If the broker is down the booking still
-- succeeds and the backlog drains on recovery (ADR-006).
--
-- In the `core` compose profile the relay's transport is a log appender; in
-- `full` it is Kafka. No code path branches on this beyond the adapter.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Partial index keeps the relay's poll O(unpublished) rather than O(all events
-- ever written). This one line decides whether the outbox scales or slowly dies.
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

-- Consumer-side dedupe: brokers deliver at-least-once, so every consumer must
-- be idempotent and this is where it records what it has already handled.
CREATE TABLE processed_event (
    event_id     UUID NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- ---------------------------------------------------------------------------
-- ShedLock. The hold sweeper must run on exactly one replica at a time --
-- otherwise every replica scans the same expiring holds and they contend with
-- each other and with live bookings.
-- ---------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
