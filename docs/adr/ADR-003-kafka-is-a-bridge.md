# ADR-003: Kafka is a bridge, not the spine

**Status:** accepted (2026-10-28), implemented in P4

## Context

The event producers are a Telegram bot that is deliberately standard-library
only (its ADR-010) and a forked coding harness that must stay unmodified. Both
can append a JSON line to a file; neither should carry a Kafka client.

## Decision

- The source of truth is an append-only JSONL file,
  `~/.zcode/agent-brain/bus-events.jsonl`. Producers append; they never block
  a turn on the bus (best effort, errors swallowed and counted).
- The brain consumes through a transport abstraction, `bus_transport: file |
  kafka`, with **one** handler. The file transport tails by byte offset with
  shrink detection. The Kafka transport is fed by a separate bridge process
  that tails the same file and produces to a topic keyed by session id
  (Redpanda in compose).
- Idempotency lives in the database: `bus_events.id` is the primary key and
  ingest is `INSERT ... ON CONFLICT DO NOTHING`, so at-least-once delivery,
  replays and consumer-group rebalances are all tolerated by construction.

## Consequences

- The contract test feeds identical fixtures through both transports and
  asserts identical database state; that test is the proof the abstraction
  holds.
- Kafka can be removed or replaced without touching producers or the handler.
- Global ordering is not guaranteed and nothing may depend on it; per-session
  ordering is preserved by the key.
