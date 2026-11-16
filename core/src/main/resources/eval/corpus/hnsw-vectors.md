# HNSW indexes and the exact-scan law

Memory and chunk embeddings live in pgvector columns of a fixed dimension and
carry HNSW indexes with the cosine operator class for nearest-neighbor search.
One measured surprise: the hybrid query's alive filter arrives as a semi-join
against a CTE, and a semi-join under the ORDER BY distance clause cannot be
served by the HNSW access method — the planner falls back to a primary-key
scan plus a hash join and sorts exactly. So the brain's vector leg has always
been exact by scan, which explains its clean evaluation numbers and puts the
scale ceiling around tens of thousands of rows. The index engages for shapes
without the semi-join, and the iterative-scan settings are no-ops for the
query as currently written.
