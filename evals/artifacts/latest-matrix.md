# eval matrix: recall-core (embed axis)

- matrix: e630b408-5a89-4af7-8ca4-28a9651e2c75
- started: 2026-11-16T15:39:16.627250Z · 8380 ms
- models: nomic-embed-text@768, all-minilm@384
- suites: recall-core

| suite | model | pass/total | gate | MRR | recall@5 | nDCG@5 | p50 ms | p95 ms | tokens in | tokens out | cost USD | support |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| recall-core | nomic-embed-text | 33/33 | 0.9 | 0.985 | 1.000 | 0.989 | 33 | 59 | 449 | 0 | 0.000000 | 32/33 |
| recall-core | all-minilm | 33/33 | 0.9 | 0.909 | 1.000 | 0.932 | 23 | 36 | 449 | 0 | 0.000000 | 29/33 |

## Recommendation by turn

- **retrieval (embedding a query)** → `all-minilm` — all-minilm: 33/33 at gate 0.9 (p50 23 ms, 449 in / 0 out tokens, cost 0.000000 USD); beats nomic-embed-text: 33/33, p50 33 ms, cost 0.000000 USD. Retrieval runs on every query: quality first, then latency and declared cost.

## Per case

### recall-core

| case | nomic-embed-text | all-minilm |
|---|---|---|
| migrator-lock-literal | ok | ok |
| embedding-dimensions-literal | ok | ok |
| bus-contract-literal | ok | ok |
| hybrid-retrieval-literal | ok | ok |
| message-storm-literal | ok | ok |
| jdk-policy-literal | ok | ok |
| tailer-truncation-literal | ok | ok |
| kap-timeout-retry-literal | ok | ok |
| circuit-breaker-literal | ok | ok |
| telegram-black-box-literal | ok | ok |
| memory-lifecycle-literal | ok | ok |
| memory-dedup-literal | ok | ok |
| dream-reconcile-literal | ok | ok |
| graph-edges-literal | ok | ok |
| bus-transport-literal | ok | ok |
| metrics-inventory-literal | ok | ok |
| extraction-model-literal | ok | ok |
| tailer-dedupe-paraphrase | ok | ok |
| hybrid-citations-paraphrase | ok | ok |
| kap-slow-embedding-paraphrase | ok | ok |
| jdk-casks-paraphrase | ok | ok |
| bus-rotated-file-paraphrase | ok | ok |
| message-storm-postmortem-paraphrase | ok | ok |
| extraction-schema-paraphrase | ok | ok |
| telegram-readback-paraphrase | ok | ok |
| migrator-race-paraphrase | ok | ok |
| circuit-breaker-open-paraphrase | ok | ok |
| memory-lifecycle-paraphrase | ok | ok |
| memory-dedup-paraphrase | ok | ok |
| dream-reconcile-paraphrase | ok | ok |
| graph-traversal-law-paraphrase | ok | ok |
| hnsw-scan-paraphrase | ok | ok |
| quarantine-reasons-paraphrase | ok | ok |

