-- 0006_memory_invalidation: Zep-style invalidation for superseded claims.
-- A verdict of Supersede stamps the OLD row's invalid_at; the row stays with
-- its content and salience untouched (invalidation is NOT deletion), and rows
-- with invalid_at set leave BOTH search legs (MemoryStore.SEARCH) and every
-- dedup path - a dead claim can neither fuse, nor absorb a new landing, nor
-- be a verdict input again.
ALTER TABLE memories ADD COLUMN IF NOT EXISTS invalid_at timestamptz;
