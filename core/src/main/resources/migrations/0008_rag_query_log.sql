-- The application-specific eval (the one number the curated suites cannot
-- give): every REAL rag_query the HTTP boundary serves is logged here — the
-- query, the chunk ids it answered with, the latency the caller felt. The
-- in-process eval CLI never rides this table (its queries grade against
-- ground truth); these rows are graded by the judge over the logged evidence.
--
-- chunk_ids / top_uris are JSON arrays (text), not native arrays: the
-- SQLDelight dialect types binds and unnest fine, but a logging table needs
-- no SQL-side array algebra — Kotlin re-materializes the chunks by id.
--
-- Grading columns land when the usage eval judges the row (judge verdicts
-- kept verbatim as evidence); `graded` gates the sampler's partial index.
CREATE TABLE IF NOT EXISTS rag_query_log (
  id                  bigserial PRIMARY KEY,
  query               text NOT NULL,
  chunk_ids           text NOT NULL,
  top_uris            text NOT NULL,
  latency_ms          integer NOT NULL,
  graded              boolean NOT NULL DEFAULT false,
  relevant_count      integer,
  first_relevant_rank integer,
  verdicts            text,
  judged_at           timestamptz,
  at                  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS rag_query_log_ungraded_idx ON rag_query_log (at) WHERE NOT graded;
CREATE INDEX IF NOT EXISTS rag_query_log_at_idx ON rag_query_log (at DESC);
