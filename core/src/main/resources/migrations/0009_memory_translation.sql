-- The English-corpus migration (the owner's call 19/11: one language = one
-- embedding space). `translated_at` marks rows whose content was rewritten
-- to English by the legacy-translation pass AND re-embedded — the marker is
-- the pass's cursor law (untranslated = NULL), not a flag to display. The
-- original Spanish content is kept in the backup table the pass creates
-- before its first batch (the house prune/backup pattern).
ALTER TABLE memories ADD COLUMN IF NOT EXISTS translated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS memories_untranslated_idx ON memories (id) WHERE translated_at IS NULL;
