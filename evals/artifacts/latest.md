# eval: real-questions (recall) · nomic-embed-text (ollama)

- run: 5f83698a-db57-4c00-940c-ef449c6c6c74
- started: 2026-11-21T07:00:07.973474Z · 19172 ms
- **8/8 passed (gate 0.8) → PASS**
- latency p50 1924 ms · p95 2977 ms · tokens 133 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 6/8 top hits contain a needle
- retrieval: MRR 0.781 · recall@1 0.625 · recall@5 1.000 · nDCG@5 0.837

| case | question | expected | rank@5 | top hit | support |
|---|---|---|---|---|---|
| why-chat-timeout-dead | por que chat_timeout_s no funcionaba y quien cortaba antes | chat-timeout-ownership.md | 1 | chat-timeout-ownership.md | yes |
| breaker-burns-attempts | el breaker abierto quema intentos sin llamar al provider | breaker-defer-economics.md | 1 | breaker-defer-economics.md | yes |
| extraction-concurrency | cuantas extracciones corren a la vez contra el runner de ollama | extraction-sequential.md | 1 | extraction-sequential.md | yes |
| dedup-pairs-not-corruption | los pares near duplicate con una fila invalidada son corrupcion | dedup-lifecycle.md | 1 | dedup-lifecycle.md | yes |
| qwen-degenerates | por que qwen2.5 1.5b degenera en los transcripts gigantes | extraction-model.md | 1 | extraction-model.md | yes |
| judge-secrets-durable | donde viven los secrets del judge despues de un deploy que reescribe el plist | config-durability.md | 2 | telegram-black-box.md | no |
| giants-repetition-pattern | que hacer cuando el modelo repite la misma frase hasta llenar el cap | quarantine.md | 2 | extraction-model.md | yes |
| queue-attempts-mechanics | como decide la cola que vueltas estan pendientes y cuantas veces se reintentan | worker-queue.md | 4 | distractor-viaje.md | no |

