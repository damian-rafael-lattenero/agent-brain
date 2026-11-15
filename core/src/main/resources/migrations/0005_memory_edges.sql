-- 0005_memory_edges: B4's memory graph, Postgres-native (no Neo4j, no new
-- runtime). One directed row per relationship; traversal treats edges as
-- symmetric, so a single row serves both endpoints. UNIQUE(src, dst, kind)
-- makes edge building idempotent (ON CONFLICT DO NOTHING); the leading
-- src_id column plus the dst_id index cover both traversal directions. A
-- memory's deletion takes its edges with it (CASCADE). The kind CHECK keeps
-- the taxonomy closed at the database — the same boundary role MemoryKind's
-- taxonomy plays for memories. src <> dst is a law (no self-loops), pinned
-- here as a CHECK so no writer can violate it.
CREATE TABLE IF NOT EXISTS memory_edges (
  src_id    bigint NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  dst_id    bigint NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  kind      text   NOT NULL CHECK (kind IN ('related', 'cooccur', 'temporal')),
  weight    double precision NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (src_id <> dst_id),
  UNIQUE (src_id, dst_id, kind)
);

CREATE INDEX IF NOT EXISTS memory_edges_dst ON memory_edges (dst_id);
