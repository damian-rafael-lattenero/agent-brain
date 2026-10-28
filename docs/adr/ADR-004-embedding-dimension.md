# ADR-004: The embedding dimension is a database property

**Status:** accepted (2026-10-28)

## Context

pgvector columns and HNSW indexes need a fixed dimension. The dimension is
decided by the embedding model (nomic-embed-text: 768; GLM embedding-3: 256,
512, 1024 or 2048), and vectors from different models are not comparable even
when their dimensions match.

## Decision

- Migrations contain exactly one placeholder, `__EMBED_DIM__`, rendered from
  `embed_dim` when the migration runs. Migration 0001 records the value in
  `brain_meta('embed_dim')`.
- Every startup and every `migrate` compares the stored value with the
  configured one and **refuses** to continue on mismatch, with a message that
  says what to do. A silent mismatch would be a corrupt index, not an error.
- Changing the embedding model means a fresh database: `make nuke && make up`
  followed by re-indexing. There is no in-place migration of vectors.
- `chunks.embed_model` and `memories.embed_model` record which model produced
  each row, so a query can filter to the current model and a mixed table is
  detectable.
- Providers validate every vector they return against the configured
  dimension before anything is stored (`DimensionMismatch`), so the error
  names the model, not the column.

## Consequences

- Swapping providers is explicit and auditable; nothing degrades quietly.
- The readiness endpoint reports both the stored and the expected dimension.
