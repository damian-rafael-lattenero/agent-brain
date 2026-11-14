# eval: recall-core (recall) · nomic-embed-text (ollama) · rerank

- run: 8ae9ec9e-9d90-4797-b71f-21915e847163
- started: 2026-11-14T15:00:26.511854Z · 21159 ms
- **20/20 passed (gate 0.9) → PASS**
- latency p50 887 ms · p95 1233 ms · tokens 263 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 8/19 top hits contain a needle
- retrieval: MRR 0.658 · recall@1 0.350 · recall@5 1.000 · nDCG@5 0.747

| case | question | expected | rank@5 | top hit | support |
|---|---|---|---|---|---|
| migrator-lock-literal | which advisory lock id does the migration runner take? | migrator-lock.md | 2 | hybrid-retrieval.md | no |
| embedding-dimensions-literal | how many dimensions does nomic-embed-text produce? | embedding-dimensions.md | 1 | embedding-dimensions.md | yes |
| bus-contract-literal | what are the seven fields of a bus event? | bus-contract.md | 2 | hybrid-retrieval.md | no |
| hybrid-retrieval-literal | how are the vector and full-text rank lists combined? | hybrid-retrieval.md | 3 | bus-contract.md | no |
| message-storm-literal | what caused the bot to send 118 messages at 12:33? | message-storm.md | 2 | kap-timeout-retry.md | no |
| jdk-policy-literal | which JDK distribution is installed through Homebrew casks? | jdk-policy.md | 2 | bus-contract.md | no |
| tailer-truncation-literal | what does the tailer do when the bus file is shorter than the saved offset? | tailer-truncation.md | 1 | tailer-truncation.md | yes |
| kap-timeout-retry-literal | why is a KAP timeout placed before retry never retried? | kap-timeout-retry.md | 1 | kap-timeout-retry.md | yes |
| circuit-breaker-literal | after how many consecutive failures does the embedding circuit breaker open? | circuit-breaker.md | 1 | circuit-breaker.md | yes |
| telegram-black-box-literal | how does tier five talk to the real Telegram account? | telegram-black-box.md | 1 | telegram-black-box.md | yes |
| tailer-dedupe-paraphrase | what does the database do with an event id it already has? | tailer-truncation.md | 3 | bus-contract.md | no |
| hybrid-citations-paraphrase | where do the citations of a retrieval hit point? | hybrid-retrieval.md | 2 | migrator-lock.md | no |
| kap-slow-embedding-paraphrase | what broke the retry of a slow embedding call? | kap-timeout-retry.md | 2 | circuit-breaker.md | yes |
| jdk-casks-paraphrase | which brew casks install the JDKs? | jdk-policy.md | 2 | hybrid-retrieval.md | no |
| bus-rotated-file-paraphrase | what happens to a poisoned or rotated bus file? | tailer-truncation.md | 1 | tailer-truncation.md | yes |
| message-storm-postmortem-paraphrase | what did the postmortem of the message storm fix? | message-storm.md | 2 | circuit-breaker.md | no |
| embedding-model-schema-paraphrase | which model answers the extraction under a schema? | embedding-dimensions.md | 2 | migrator-lock.md | - |
| telegram-readback-paraphrase | how does the black-box driver read the chat back? | telegram-black-box.md | 2 | bus-contract.md | no |
| migrator-race-paraphrase | what serializes two migrators racing? | migrator-lock.md | 2 | kap-timeout-retry.md | no |
| circuit-breaker-open-paraphrase | when does the breaker fail fast? | circuit-breaker.md | 1 | circuit-breaker.md | yes |

