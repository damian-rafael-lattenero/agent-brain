# eval matrix: extraction-core + grounded-citation (chat axis)

- matrix: 38f128c5-6bc1-4744-b26d-073e8e0c2bff
- started: 2026-11-04T02:26:28.273445Z · 76409 ms
- models: qwen2.5:1.5b, qwen2.5:3b
- suites: extraction-core, grounded-citation

| suite | model | pass/total | gate | p50 ms | p95 ms | tokens in | tokens out | cost USD | support |
|---|---|---|---|---|---|---|---|---|---|
| extraction-core | qwen2.5:1.5b | 3/6 | 0.6 | 2167 | 5124 | 1229 | 607 | 0.000000 | - |
| extraction-core | qwen2.5:3b | 4/6 | 0.6 | 3901 | 6644 | 1229 | 563 | 0.000000 | - |
| grounded-citation | qwen2.5:1.5b | 5/5 | 0.8 | 1996 | 3257 | 4698 | 145 | 0.000000 | 5/5 |
| grounded-citation | qwen2.5:3b | 5/5 | 0.8 | 3885 | 6287 | 4698 | 147 | 0.000000 | 5/5 |

## Recommendation by turn

- **memory extraction (one chat call per turn)** → `qwen2.5:3b` — qwen2.5:3b: 4/6 at gate 0.6 (p50 3901 ms, 1229 in / 563 out tokens, cost 0.000000 USD); beats qwen2.5:1.5b: 3/6, p50 2167 ms, cost 0.000000 USD. Every miss is a memory the bot never gets back, so quality wins over a slower call.
- **answer composition (one chat call per answered turn)** → `qwen2.5:1.5b` — qwen2.5:1.5b: 5/5 at gate 0.8 (p50 1996 ms, 4698 in / 145 out tokens, cost 0.000000 USD); beats qwen2.5:3b: 5/5, p50 3885 ms, cost 0.000000 USD. An answer the question can be checked against first; among models that clear the gate, the cheapest one.

## Per case

### extraction-core

| case | qwen2.5:1.5b | qwen2.5:3b |
|---|---|---|
| git-policy-preference | FAIL (preferences has none of [push, no-verify, verify] (got [The ) | FAIL (preferences has none of [push, no-verify, verify] (got [])) |
| environment-fact | ok | ok |
| decision-kapstream | ok | ok |
| language-preference | ok | ok |
| small-talk-nothing-durable | FAIL (expected no durable items, got 3 (The user is greeting the c) | FAIL (expected no durable items, got 3 (The user is greeting and a) |
| eval-clock-decision | FAIL (decisions has none of [clock, timeSource, pure] (got [No whi) | ok |

### grounded-citation

| case | qwen2.5:1.5b | qwen2.5:3b |
|---|---|---|
| migrator-lock-id | ok | ok |
| embedding-dimensions | ok | ok |
| circuit-breaker-threshold | ok | ok |
| tailer-truncation | ok | ok |
| hybrid-fusion | ok | ok |

