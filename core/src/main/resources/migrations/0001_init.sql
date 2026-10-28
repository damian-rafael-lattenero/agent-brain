-- 0001_init: the whole brain in ONE database (ADR-001).
-- __EMBED_DIM__ is rendered by migrate.py from the configured embedding
-- dimension; brain_meta remembers it so a mismatched provider is refused.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS brain_meta (
  key   text PRIMARY KEY,
  value text NOT NULL
);
INSERT INTO brain_meta (key, value) VALUES ('embed_dim', '__EMBED_DIM__')
  ON CONFLICT (key) DO NOTHING;

-- Every event the bus ever delivered, deduplicated by producer id.
-- Replaying the bus twice must leave this table identical (P1 golden).
CREATE TABLE IF NOT EXISTS bus_events (
  id          text PRIMARY KEY,
  ts          timestamptz NOT NULL,
  kind        text NOT NULL,
  sid         text,
  turn        text,
  source      text NOT NULL,
  payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
  consumed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS bus_events_sid_ts  ON bus_events (sid, ts);
CREATE INDEX IF NOT EXISTS bus_events_kind_ts ON bus_events (kind, ts);

CREATE TABLE IF NOT EXISTS documents (
  id          bigserial PRIMARY KEY,
  source      text NOT NULL,
  uri         text NOT NULL UNIQUE,
  title       text NOT NULL,
  content     text NOT NULL,
  content_sha text NOT NULL,
  meta        jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 'simple' config: the corpus mixes Spanish and English, stemming for one
-- language would bias recall against the other.
CREATE TABLE IF NOT EXISTS chunks (
  id          bigserial PRIMARY KEY,
  doc_id      bigint NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  ordinal     int NOT NULL,
  content     text NOT NULL,
  content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  embed_model text NOT NULL,
  embedding   vector(__EMBED_DIM__) NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (doc_id, ordinal)
);
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS chunks_tsv ON chunks USING gin (content_tsv);

CREATE TABLE IF NOT EXISTS memories (
  id            bigserial PRIMARY KEY,
  session_id    text,
  turn_id       text,
  kind          text NOT NULL CHECK (kind IN ('fact', 'decision', 'preference', 'summary')),
  content       text NOT NULL,
  embed_model   text NOT NULL,
  embedding     vector(__EMBED_DIM__) NOT NULL,
  salience      real NOT NULL DEFAULT 1.0,
  last_accessed timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS memories_embedding_hnsw ON memories USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS memories_session ON memories (session_id, created_at);

-- A failed extraction is never silent: the raw model output and the reason
-- land here so the failure can be read, counted and replayed.
CREATE TABLE IF NOT EXISTS memory_quarantine (
  id      bigserial PRIMARY KEY,
  turn_id text,
  reason  text NOT NULL,
  raw     text NOT NULL,
  ts      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS eval_runs (
  id       bigserial PRIMARY KEY,
  suite    text NOT NULL,
  model    text NOT NULL,
  provider text NOT NULL,
  passed   int NOT NULL,
  total    int NOT NULL,
  cost_usd numeric(10, 6) NOT NULL DEFAULT 0,
  detail   jsonb NOT NULL DEFAULT '{}'::jsonb,
  ts       timestamptz NOT NULL DEFAULT now()
);
