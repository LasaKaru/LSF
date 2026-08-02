# Architecture Decision Records

Each record captures one significant decision: the context, the options, the choice, and what it costs.
Format is a lightly adapted Michael Nygard template. Records are immutable once accepted — a reversal
is a new record that supersedes the old one, so the reasoning trail survives.

| # | Decision | Status |
|---|---|---|
| [001](ADR-001-half-open-interval-model.md) | Model bookings as half-open integer ranges over trip stop sequence | Accepted |
| [002](ADR-002-exclusion-constraint-concurrency.md) | Enforce the overlap invariant with a PostgreSQL GiST exclusion constraint | Accepted |
| [003](ADR-003-service-boundaries.md) | Keep booking and seat inventory in one service and one database | Accepted |
| [004](ADR-004-hold-then-confirm.md) | Two-phase booking: hold with TTL, then confirm | Accepted |
| [005](ADR-005-trip-topology-snapshot.md) | Snapshot trip topology at publication time | Accepted |
| [006](ADR-006-transactional-outbox.md) | Use a transactional outbox for event publication | Accepted |
| [007](ADR-007-technology-stack.md) | Java 21 + Spring Boot 3 + PostgreSQL 16 + React | Accepted |
| [008](ADR-008-signed-fare-quotes.md) | Server-computed, HMAC-signed, expiring fare quotes | Accepted |
| [009](ADR-009-idempotency.md) | Require idempotency keys on all mutating endpoints | Accepted |
| [010](ADR-010-telescopic-fare-model.md) | Telescopic banded fares with an additivity guard | Accepted |
