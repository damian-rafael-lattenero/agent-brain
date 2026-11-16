# eval: memory-core (memory) · nomic-embed-text (ollama)

- run: 83ea0439-a76f-4e18-bfb0-b251a98b7625
- started: 2026-11-16T04:13:35.818367Z · 1424 ms
- **9/10 passed (gate 0.9) → PASS**
- latency p50 27 ms · p95 54 ms · tokens 103 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 0/1 top hits contain a needle
- retrieval: MRR 0.950 · recall@1 0.900 · recall@5 1.000 · nDCG@5 0.963

| case | question | rank@k | related | support | failure |
|---|---|---|---|---|---|
| worker-extracts | how does the brain learn from completed turns? | 2 | 3 | - | - |
| extraction-reads | what reads completed turns and writes memories? | 1 | 3 | no | the related field carried no memory matching 'extracts facts and summaries' |
| worker-polls | what polls the bus for new turns? | 1 | 4 | - | - |
| dedup-merges | what happens to near-identical memories? | 1 | 4 | - | - |
| kafka-rebalance | when are kafka partitions reassigned? | 1 | 3 | - | - |
| lemon-pie | what does the lemon pie recipe need? | 1 | 3 | - | - |
| lifecycle-forgets | how does the brain forget old memories? | 1 | 4 | - | - |
| graph-traversal | how do related memories travel with a search? | 1 | 4 | - | - |
| verdict-invalidate | what happens to a superseded claim? | 1 | 3 | - | - |
| quarantine-echo | what happens to template echo answers? | 1 | 3 | - | - |

