# eval: recall-core (recall) · nomic-embed-text (ollama)

- run: 5e46950b-f565-4c36-a43c-15367ee91d4e
- started: 2026-11-08T13:36:39.138202Z · 1761 ms
- **20/20 passed (gate 0.9) → PASS**
- latency p50 22 ms · p95 32 ms · tokens 263 in / 0 out (embedding tokens estimated) · cost 0.000000 USD
- citation support@1: 18/19 top hits contain a needle

| case | question | expected | rank@5 | top hit | support |
|---|---|---|---|---|---|
| migrator-lock-literal | which advisory lock id does the migration runner take? | migrator-lock.md | 1 | migrator-lock.md | yes |
| embedding-dimensions-literal | how many dimensions does nomic-embed-text produce? | embedding-dimensions.md | 1 | embedding-dimensions.md | yes |
| bus-contract-literal | what are the seven fields of a bus event? | bus-contract.md | 1 | bus-contract.md | yes |
| hybrid-retrieval-literal | how are the vector and full-text rank lists combined? | hybrid-retrieval.md | 1 | hybrid-retrieval.md | yes |
| message-storm-literal | what caused the bot to send 118 messages at 12:33? | message-storm.md | 1 | message-storm.md | yes |
| jdk-policy-literal | which JDK distribution is installed through Homebrew casks? | jdk-policy.md | 1 | jdk-policy.md | yes |
| tailer-truncation-literal | what does the tailer do when the bus file is shorter than the saved offset? | tailer-truncation.md | 1 | tailer-truncation.md | yes |
| kap-timeout-retry-literal | why is a KAP timeout placed before retry never retried? | kap-timeout-retry.md | 1 | kap-timeout-retry.md | yes |
| circuit-breaker-literal | after how many consecutive failures does the embedding circuit breaker open? | circuit-breaker.md | 1 | circuit-breaker.md | yes |
| telegram-black-box-literal | how does tier five talk to the real Telegram account? | telegram-black-box.md | 1 | telegram-black-box.md | yes |
| tailer-dedupe-paraphrase | what does the database do with an event id it already has? | tailer-truncation.md | 2 | migrator-lock.md | no |
| hybrid-citations-paraphrase | where do the citations of a retrieval hit point? | hybrid-retrieval.md | 1 | hybrid-retrieval.md | yes |
| kap-slow-embedding-paraphrase | what broke the retry of a slow embedding call? | kap-timeout-retry.md | 2 | circuit-breaker.md | yes |
| jdk-casks-paraphrase | which brew casks install the JDKs? | jdk-policy.md | 1 | jdk-policy.md | yes |
| bus-rotated-file-paraphrase | what happens to a poisoned or rotated bus file? | tailer-truncation.md | 1 | tailer-truncation.md | yes |
| message-storm-postmortem-paraphrase | what did the postmortem of the message storm fix? | message-storm.md | 1 | message-storm.md | yes |
| embedding-model-schema-paraphrase | which model answers the extraction under a schema? | embedding-dimensions.md | 4 | bus-contract.md | - |
| telegram-readback-paraphrase | how does the black-box driver read the chat back? | telegram-black-box.md | 1 | telegram-black-box.md | yes |
| migrator-race-paraphrase | what serializes two migrators racing? | migrator-lock.md | 1 | migrator-lock.md | yes |
| circuit-breaker-open-paraphrase | when does the breaker fail fast? | circuit-breaker.md | 1 | circuit-breaker.md | yes |

