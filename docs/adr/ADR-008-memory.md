# ADR-008: Memory is extracted from the bus, quarantined when in doubt, recalled with decay

**Status:** accepted (2026-10-28)

## Context

The bus (ADR-003) already carries every turn's prompt, reply and tool calls.
Turning that into durable memory needs an LLM, and LLM output is
untrustworthy by construction: wrong shape, hallucinated facts, transient
noise. The bot must be able to USE memories without a round trip to a human.

## Decision

- **Source of truth is the bus table.** The worker polls `bus_events` for
  `turn.completed` rows with `memory_at IS NULL`, folds the turn's events into
  a transcript (prompt, reply, tool names, title) and marks the row when done.
  Idempotent by event id: replaying the bus adds no work; a crash between
  insert and mark is absorbed by the unique index on
  `(source_event_id, kind, md5(content))`.
- **Structured extraction with a strict parse.** One chat call per turn under
  the embedding resilience policy (KAP: `timeoutAsFailure`, jittered retry via
  a schedule factory, circuit breaker, traced). The model is asked for
  `{facts[], decisions[], preferences[], summary}` and constrained to that
  schema (Ollama structured outputs; Z.ai `json_object`). The parser enforces
  types, drops items under 8 chars, clips at 300 chars, dedups, caps at 10 per
  kind. Anything else is an `ExtractionInvalid`.
- **Three failure bins, none silent.** Invalid output → `memory_quarantine`
  with the raw text and the reason, turn marked. Provider failure → attempt
  counted, turn retried next cycle. Too many attempts → quarantined with the
  reason, turn marked. The breaker opening ends the cycle early.
- **Recall is hybrid with decay.** Same RRF fusion as the RAG store (cosine +
  full-text), then `score × salience × 2^(-age_days / half_life_days)`
  (`memory_half_life_days`, 30). Returned memories bump `last_accessed`.
- **Default model is small and local.** `qwen2.5:1.5b` through Ollama: free,
  CI-pullable, and good enough under a schema; the provider is a knob
  (`chat_provider`, `chat_model`, `glm_chat_model`).
- **Surfaces.** `POST /memory/search`, `POST /memory/save`, `GET
  /memory/recent`, `GET /memory/quarantine`, `GET /memory/status`; CLI `memory
  search|recent|save|quarantine|status|run [--once]`; in the bot `/memory` and
  the `memory_inject` knob (bot ADR-020), which prepends bounded, cited
  memories to each phone turn while the bus keeps the original prompt.

## Consequences

- Memory quality is observable: `/memory/quarantine` shows what the model got
  wrong, with the raw text, instead of hiding it.
- Switching to a stronger model is a knob; switching the embedding model
  is still a fresh database (ADR-004).
- The worker runs inside `serve` (knob `memory_worker`) or standalone
  (`memory run`), so a laptop can run the bus tailer without paying for
  extraction.
