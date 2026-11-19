# eval: real-questions (recall) · nomic-embed-text (ollama)

- run: fe0f55fc-4993-4b07-8fc7-caccd38c661b
- started: 2026-11-19T07:10:26.950856Z · 24863 ms
- **8/8 passed (gate 0.8) → PASS**
- latency p50 2850 ms · p95 3771 ms · tokens 133 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 7/8 top hits contain a needle
- retrieval: MRR 0.875 · recall@1 0.750 · recall@5 1.000 · nDCG@5 0.908

| case | question | expected | rank@5 | top hit | support |
|---|---|---|---|---|---|
| why-chat-timeout-dead | por que chat_timeout_s no funcionaba y quien cortaba antes | chat-timeout-ownership.md | 1 | chat-timeout-ownership.md | yes |
| breaker-burns-attempts | el breaker abierto quema intentos sin llamar al provider | breaker-defer-economics.md | 1 | breaker-defer-economics.md | yes |
| extraction-concurrency | cuantas extracciones corren a la vez contra el runner de ollama | extraction-sequential.md | 1 | extraction-sequential.md | yes |
| dedup-pairs-not-corruption | los pares near duplicate con una fila invalidada son corrupcion | dedup-lifecycle.md | 1 | dedup-lifecycle.md | yes |
| qwen-degenerates | por que qwen2.5 1.5b degenera en los transcripts gigantes | extraction-model.md | 1 | extraction-model.md | yes |
| judge-secrets-durable | donde viven los secrets del judge despues de un deploy que reescribe el plist | config-durability.md | 1 | config-durability.md | yes |
| giants-repetition-pattern | que hacer cuando el modelo repite la misma frase hasta llenar el cap | quarantine.md | 2 | extraction-model.md | yes |
| queue-attempts-mechanics | como decide la cola que vueltas estan pendientes y cuantas veces se reintentan | worker-queue.md | 2 | distractor-viaje.md | no |

