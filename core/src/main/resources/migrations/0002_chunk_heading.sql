-- 0002_chunk_heading: every chunk remembers the heading chain it lives under,
-- so a citation can say "cv/README.md > Verification" and not just "chunk 7".
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS heading text NOT NULL DEFAULT '';
CREATE INDEX IF NOT EXISTS documents_source ON documents (source);
