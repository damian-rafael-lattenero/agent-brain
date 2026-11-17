# eval: real-questions (recall) · nomic-embed-text (ollama)

- run: 8b66153c-b010-4243-8c7a-08ae803cb942
- started: 2026-11-17T03:23:00.552115Z · 8321 ms
- **7/8 passed (gate 0.8) → PASS**
- latency p50 304 ms · p95 599 ms · tokens 133 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 3/8 top hits contain a needle
- retrieval: MRR 0.458 · recall@1 0.250 · recall@5 0.875 · nDCG@5 0.562

| case | question | expected | rank@5 | top hit | support |
|---|---|---|---|---|---|
| why-chat-timeout-dead | por que chat_timeout_s no funcionaba y quien cortaba antes | chat-timeout-ownership.md | 1 | chat-timeout-ownership.md | yes |
| breaker-burns-attempts | el breaker abierto quema intentos sin llamar al provider | breaker-defer-economics.md | 2 | circuit-breaker.md | yes |
| extraction-concurrency | cuantas extracciones corren a la vez contra el runner de ollama | extraction-sequential.md | 3 | distractor-viaje.md | no |
| dedup-pairs-not-corruption | los pares near duplicate con una fila invalidada son corrupcion | dedup-lifecycle.md | 1 | dedup-lifecycle.md | yes |
| qwen-degenerates | por que qwen2.5 1.5b degenera en los transcripts gigantes | extraction-model.md | 4 | distractor-viaje.md | no |
| judge-secrets-durable | donde viven los secrets del judge despues de un deploy que reescribe el plist | config-durability.md | 4 | distractor-viaje.md | no |
| giants-repetition-pattern | que hacer cuando el modelo repite la misma frase hasta llenar el cap | quarantine.md | 3 | distractor-viaje.md | no |
| queue-attempts-mechanics | como decide la cola que vueltas estan pendientes y cuantas veces se reintentan | worker-queue.md | - | distractor-viaje.md | no |

