-- 0003_memory_pipeline: the memory worker's bookkeeping on the bus, full-text
-- over memories, provenance per memory, and idempotent inserts per event.
ALTER TABLE bus_events ADD COLUMN IF NOT EXISTS memory_at timestamptz;
ALTER TABLE bus_events ADD COLUMN IF NOT EXISTS memory_attempts int NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS bus_events_memory_pending ON bus_events (ts)
  WHERE kind = 'turn.completed' AND memory_at IS NULL;

ALTER TABLE memories ADD COLUMN IF NOT EXISTS source_event_id text;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS content_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;
CREATE INDEX IF NOT EXISTS memories_tsv ON memories USING gin (content_tsv);
CREATE INDEX IF NOT EXISTS memories_source_event ON memories (source_event_id);
-- A worker that dies between insert and mark re-runs the event: the same
-- extraction must not land twice.
CREATE UNIQUE INDEX IF NOT EXISTS memories_event_kind_content
  ON memories (coalesce(source_event_id, ''), kind, md5(content));

ALTER TABLE memory_quarantine ADD COLUMN IF NOT EXISTS event_id text;
ALTER TABLE memory_quarantine ADD COLUMN IF NOT EXISTS session_id text;
