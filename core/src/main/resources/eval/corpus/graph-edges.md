# Memory edges and graph traversal

Two memories may be connected by one of three edges: related (a semantic
neighbor proven by the traversal), cooccur (they appeared in the same turn)
and temporal (they were stored next to each other in time), each with a
weight. A search follows them to surface memories the raw query never named,
which is why the related field of a search answer can carry rows the vector
leg did not return. History matters here: the first traversal joined the graph
with an OR across kinds and took 86,346 milliseconds on 27,000 edges, because
symmetric-edge joins cannot use indexes; rewritten as one UNION ALL branch per
kind it answers in 38 milliseconds.
