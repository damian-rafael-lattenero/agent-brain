-- memory-dedup-evidence: the READ-ONLY measurement behind MemoryDedup.SIMILARITY (0.90).
--
-- Run against the production store (SELECT only, no writes):
--   docker exec -i agent-brain-pg psql -U brain -d agent_brain -P pager=off \
--     < scripts/memory-dedup-evidence.sql
--
-- Measured 2026-11-03: 37 memories, all nomic-embed-text.
-- - Duplicate band (same memory restated, across kinds): 0.9123..0.9845.
-- - Strongest merely-related pair: 0.8789. The empty gap [0.8789, 0.9123]
--   is where the threshold 0.90 sits.
-- - Cross-lingual equivalents sit at ~0.61..0.65 (Spanish 15 vs English 20
--   = 0.6524): nomic-embed-text does NOT align the languages at a mergeable
--   similarity, so embedding dedup cannot and must not chase them.

-- 1. Candidate duplicates: pairs at or above the threshold, strongest first.
SELECT round((1 - (a.embedding <=> b.embedding))::numeric, 4) AS sim,
       a.id AS id_a, a.kind AS kind_a, left(a.content, 70) AS content_a,
       b.id AS id_b, b.kind AS kind_b, left(b.content, 70) AS content_b
FROM memories a JOIN memories b ON a.id < b.id
WHERE a.embed_model = b.embed_model
  AND (1 - (a.embedding <=> b.embedding)) >= 0.90
ORDER BY sim DESC;

-- 2. The band below the threshold: the strongest pairs that must stay
--    separate (related but distinct memories).
SELECT round((1 - (a.embedding <=> b.embedding))::numeric, 4) AS sim,
       a.id AS id_a, a.kind AS kind_a, left(a.content, 70) AS content_a,
       b.id AS id_b, b.kind AS kind_b, left(b.content, 70) AS content_b
FROM memories a JOIN memories b ON a.id < b.id
WHERE a.embed_model = b.embed_model
  AND (1 - (a.embedding <=> b.embedding)) >= 0.60
  AND (1 - (a.embedding <=> b.embedding)) < 0.90
ORDER BY sim DESC
LIMIT 20;

-- 3. The cited near-duplicate cluster, measured pair by pair:
--    15/16/19 Spanish restatements, 20/24 English restatements, 21 alone.
SELECT a.id AS id_a, b.id AS id_b,
       round((1 - (a.embedding <=> b.embedding))::numeric, 4) AS sim,
       left(a.content, 50) AS content_a, left(b.content, 50) AS content_b
FROM memories a JOIN memories b ON a.id < b.id
WHERE a.id IN (15, 16, 19, 20, 21, 24) AND b.id IN (15, 16, 19, 20, 21, 24)
  AND (1 - (a.embedding <=> b.embedding)) >= 0.55
ORDER BY sim DESC;
