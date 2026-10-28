# ADR-001: One database for everything

**Status:** accepted (2026-10-28)

## Context

The platform needs a durable log of bus events, a document and chunk store with
vector search, agent memories with vector recall, a quarantine for failed
extractions, and a ledger of eval runs. Each could live in a specialized store
(a vector database, a queue's own log, a document store).

## Decision

One Postgres 18 database with the pgvector extension holds all of it:
`bus_events`, `documents`, `chunks`, `memories`, `memory_quarantine`,
`eval_runs`, plus `schema_migrations` and `brain_meta`. Vector search uses HNSW
indexes with cosine distance; full-text search uses generated `tsvector`
columns with the `simple` configuration (the corpus mixes Spanish and English).

## Consequences

- One backup, one connection pool, one transaction boundary when ingest and
  memory need to agree.
- Hybrid retrieval (vector + full-text) is a single SQL statement, not a join
  across systems.
- The embedding dimension becomes a schema property (see ADR-004).
- Scale ceilings of pgvector apply; they are orders of magnitude above the
  workload of one person's agents.
