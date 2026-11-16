# eval: contradiction-core (contradiction) · qwen2.5:1.5b (ollama)

- run: 2161ac17-f852-4a76-ad4b-ff527e627e1f
- started: 2026-11-15T22:24:54.518053Z · 29940 ms
- **8/8 passed (gate 0.8) → PASS**
- latency p50 3411 ms · p95 4304 ms · tokens 198 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 8/8 top hits contain a needle

| case | verdict | expected | effect | failure |
|---|---|---|---|---|
| failover-band | supersede | supersede | landed | - |
| rollout | supersede | supersede | landed | - |
| migration-saturday | supersede | supersede | landed | - |
| upgrade-pending | supersede | supersede | landed | - |
| same-restatement | same | same | landed | - |
| same-paraphrase | same | same | landed | - |
| distinct-topics | distinct | distinct | landed | - |
| distinct-tools | distinct | distinct | landed | - |

