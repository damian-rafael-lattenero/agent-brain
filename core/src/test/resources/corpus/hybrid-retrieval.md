# Hybrid retrieval

A query runs two ranked legs over the same chunks: cosine distance on the
pgvector column and full-text search on the generated `tsvector`. The two rank
lists are fused with Reciprocal Rank Fusion, scoring each chunk as the sum of
`1 / (60 + rank)` across the legs it appears in, so an exact token match can
rescue a chunk the embedding underweights and vice versa. Every hit carries its
citation: uri, title, heading chain and ordinal.
