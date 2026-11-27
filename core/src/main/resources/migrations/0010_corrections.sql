-- 0010_corrections (plan U8): the owner's corrections ride the bus as
-- kind='correction' events; this table is the durable mirror the extractor
-- works on and the owner decides over. The row id IS the bus event id, so an
-- at-least-once replay lands once (the consumer deduplicates by id).
CREATE TABLE IF NOT EXISTS corrections (
  id           text PRIMARY KEY,
  sid          text NOT NULL DEFAULT '',
  turn         text NOT NULL DEFAULT '',
  kind         text NOT NULL DEFAULT 'explicit',
  text         text NOT NULL DEFAULT '',
  candidate    text,
  status       text NOT NULL DEFAULT 'pending',
  created_at   timestamptz NOT NULL DEFAULT now(),
  extracted_at timestamptz,
  decided_at   timestamptz
);

-- The worker's queue: pending rows with no candidate yet.
CREATE INDEX IF NOT EXISTS corrections_pending ON corrections (created_at)
  WHERE status = 'pending';
