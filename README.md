# agent-brain

Event bus, retrieval (RAG), agent memory, evals and an MCP plugin, built around a
Telegram bot and a forked coding harness that already exist and stay untouched.
One Postgres 18 + pgvector database holds everything. The core is Kotlin on
Amazon Corretto 25; Python survives only where it is the native tongue of its
host (the bus publisher inside the bot, the MTProto test driver).

## Status

| Phase | Scope | State |
|---|---|---|
| P0 | Foundation: config, migrations, embedding providers (Ollama, GLM, fake), HTTP `/health` `/ready`, CLI, five-tier test layout, CI | done |
| P1 | Event bus: stdlib publisher in the bot behind the `event_bus` knob (bot ADR-019), byte-offset tailer with truncation detection, idempotent batch ingest, `/bus/status`, replay golden test, black-box with the bot's real publisher | done (file transport; Kafka in P4) |
| P2 | RAG: corpus by globs, heading-aware chunker, KAP-orchestrated indexer (bounded parallel embeds, timeout, retry, breaker), hybrid cosine + full-text with RRF and citations, recall gate with the real model (1.0@5), `POST /rag/query`, `index`/`query` CLI, `/brain` in the bot | done (promptfoo suites arrive with P6) |
| P3 | Memory: worker over `turn.completed`, schema-constrained extraction with a small local model, quarantine with raw text, hybrid recall with half-life decay, `/memory/*` HTTP + CLI, `/memory` and the `memory_inject` knob in the bot (bot ADR-020) | done (tier 5 driver pending the author's one-time Telegram login) |
| P4 | Kafka transport: Redpanda in compose, `bridge --once` (file → topic, key = session id, idempotent producer), consumer feeding the SAME handler with commit-after-ingest, the contract test (same fixtures through file and through Kafka = identical database), redelivery + poison-record tolerance, black-box through a Kafka-consuming `serve` | done |
| P5 | `brain-mcp`: a ZCode plugin on the official MCP Kotlin SDK (stdio) exposing `rag_query`, `memory_search`, `memory_recent`, `bus_status` with the status envelope (`status, result|error{kind}, warnings, nextStep, meta{trace_id, tool_version}`), flat schemas, argv-only; e2e over the real protocol against the installed binary | done (install into your ZCode: `make plugin`, then add `brain-mcp/build/plugin` as a local marketplace) |
| P6 | Evals platform: suites persisted in `eval_runs`, nightly workflow, model/cost/quality report | planned |
| P7 | Panel (post-MVP) | planned |

## Architecture

```
PRODUCERS (untouched)              TRANSPORT                 CORE (this repo)               SURFACES
ZCode fork ── sidecars/logs ──┐                              agent-brain service          brain-mcp (MCP stdio)
Telegram bot: turn cycle ─────┼─► bus-events.jsonl ─┬─(tail)─► ingest (dedup by id)     HTTP API (ktor)
 (hooks + 3 tap points)       │                     │          chunker + embedder        bot commands /brain /memory
                              │                     └─(bridge)  hybrid RAG with citations
                              │                        Kafka    memory extraction + decay
                              └─ live-io sidecars (direct read) evals (promptfoo)
                                                     ──────────────────────────────────
                                                     Postgres 18 + pgvector: bus_events, documents, chunks,
                                                     memories, memory_quarantine, eval_runs
```

Kafka is a bridge, not the spine: the bot never speaks Kafka. A separate bridge
reads the file bus and produces to a topic; the consumer runs the same handler
behind `bus_transport: file|kafka`, and the contract test feeds identical
fixtures through both and asserts identical database state.

## Why Kotlin

Judged purely on ecosystem gravity for an LLM-centric service, Python would be
the shorter path (reference MCP SDK, every provider SDK, Telethon). Judged on
what this project must also be, a system its author can defend line by line in
JVM engineering interviews, Kotlin wins: structured concurrency and `Flow` for
backpressure, the canonical Kafka client, plain JDBC with the official pgvector
type, and the official MCP Kotlin SDK for the plugin. Both lenses were weighed
explicitly; see [ADR-005](docs/adr/ADR-005-kotlin-core-python-glue.md).

## Quickstart

```bash
./bootstrap.sh        # checks Corretto 25, docker (colima), ollama + nomic-embed-text; builds the CLI
make up               # pgvector/pgvector:pg18 + Redpanda (19092), then migrations
make test             # tiers 1-3 (skips a tier with a reason when its service is down)
make bb               # tier 4: black-box local, services required
make serve            # http://127.0.0.1:8765/ready
make index            # index ~/.zcode/agent-brain/corpus.txt (copy corpus.example.txt first)
make query Q="how does the tailer handle truncation"
```

The bus file is `~/.zcode/agent-brain/bus-events.jsonl` (bot side: set `"event_bus": true`
in `~/.zcode/telegram-remote.json`, bot ADR-019). The CLI lives at `core/build/install/agent-brain/bin/agent-brain`:

```
agent-brain serve | migrate [--status] | ingest [--once] | bridge [--once] | bus status | index [GLOB...] | query TEXT [--k N]
            | memory search TEXT [--k N] | memory recent [--k N] [--kind K] | memory save KIND TEXT
            | memory quarantine | memory status | memory run [--once]
            | health [--url U] | embed TEXT... | config
```

`serve` runs the HTTP service and, with `bus_transport: file`, the bus tailer.
`ingest --once` drains the bus into Postgres and exits with the counters (scripts, CI).
`index` reads `~/.zcode/agent-brain/corpus.txt` (one absolute glob per line, see
`corpus.example.txt`) and prints a report with per-phase counters; `query` prints
ranked hits with citations (retrieval only, the caller composes the answer).
From the Telegram chat: `/brain <question>`, `/brain status`, `/memory <query>`, `/memory recent`,
`/memory status` (bot ADR-019/020). `serve` also runs the memory worker (knob `memory_worker`).

## The plugin (brain-mcp)

`make plugin` builds `brain-mcp/build/plugin/` (manifest + installed distribution). In ZCode add that
directory as a local marketplace and install `brain-mcp`; the harness gains four tools that talk to the
running `agent-brain serve` over HTTP. Every tool answers the same envelope, so a model can branch on
`status` and quote `meta.trace_id`:

```json
{"status":"ok","result":{...},"warnings":[],"nextStep":"cite the uri and heading of what you use; ...",
 "meta":{"trace_id":"trace-1793228960047-3","tool_version":1}}
```

Error kinds: `invalid_input`, `brain_unavailable`, `internal_error`. stdout is the protocol channel and
stderr is logs; a library banner on stdout (kotlin-logging's) once corrupted the channel, so stdout is
redirected to stderr until the transport is up.

## Configuration

One immutable `Config` built once at startup and injected everywhere
([ADR-002](docs/adr/ADR-002-configuration.md)). Per knob: `AB_<NAME>`
environment variable, then `~/.zcode/agent-brain/config.json`, then the
default. An unknown key in the file is a startup error. `GLM_API_KEY` (or
`ZAI_API_KEY`) is environment-only.

| Knob | Default | Notes |
|---|---|---|
| `pg_url` / `pg_user` / `pg_password` | `jdbc:postgresql://127.0.0.1:5433/agent_brain` / `brain` / `brain` | compose defaults |
| `http_host` / `http_port` | `127.0.0.1` / `8765` | |
| `embed_provider` | `ollama` | `ollama`, `glm`, `fake` |
| `embed_dim` | `768` | a property of the database, see ADR-004 |
| `embed_batch` | `32` | texts per provider call |
| `ollama_base_url` / `ollama_model` | `http://127.0.0.1:11434` / `nomic-embed-text` | |
| `glm_base_url` / `glm_model` | `https://api.z.ai/api/paas/v4` / `embedding-3` | needs `GLM_API_KEY`; dims 256/512/1024/2048 |
| `http_timeout_s` | `60` | outbound HTTP |
| `bus_transport` / `kafka_bootstrap` / `kafka_topic` / `kafka_group` / `kafka_partitions` | `file` / `127.0.0.1:19092` / `agent-brain.bus` / `agent-brain` / `3` | `kafka` transport; the bridge always tails the file
| `bus_poll_ms` | `500` | tailer cadence |
| `index_doc_concurrency` / `embed_concurrency` | `2` / `2` | documents in flight / embedding batches in flight |
| `chat_provider` / `chat_model` / `glm_chat_model` / `chat_timeout_s` | `ollama` / `qwen2.5:1.5b` / `glm-4.5-flash` / `120` | the extraction model (structured JSON output) |
| `memory_worker` / `memory_poll_ms` / `memory_half_life_days` | `true` / `2000` / `30` | extraction worker inside `serve`; recall decay half-life |
| `embed_breaker_failures` / `embed_breaker_reset_s` | `5` / `30` | circuit breaker in front of the embedding provider |

`AB_BASE` (default `~/.zcode/agent-brain`) moves every derived path: state,
logs, the bus file, the config file.

## Test pyramid

| Tier | Tag | What is real | Task |
|---|---|---|---|
| 1 unit | none | nothing external | `test` |
| 2 integration | `integration` | Postgres/pgvector, Ollama, GLM (key permitting) | `test` |
| 3 e2e | `e2e` | real database and routes, in-process server, fake model | `test` |
| 4 black-box local | `bb-local` | the installed distribution as a subprocess, real services, only the Telegram wire virtualized | `bbLocal` |
| 5 black-box real | `bb-real` | your Telegram account over MTProto; the brain observed through the bot | `e2eReal` (`make e2e-real`) |

Tier 5 is a Python driver (`tools/e2e-real/driver.py`, Telethon) run by `RealTelegramBB`: it
sends real messages from your account, waits for the bot's replies and asserts the flow as you would
read it (`/brain status` before and after a turn; a stated preference recalled by `/memory` on a
later turn). One-time setup: a dedicated Telegram app (api id/hash) and `make e2e-real-login` to
mint `AB_TG_SESSION`; the nightly workflow (`.github/workflows/e2e-real.yml`) runs the same driver
from the cloud with those values as secrets, `continue-on-error` by design. The session grants full
access to the account: rotate it like a password. Status: built and wired, not yet exercised live
(waiting for the author's login).

Locally an unreachable service skips its tier with a printed reason. With
`AB_REQUIRE_SERVICES=1` (CI, `make bb`, `make ci`) the same condition fails:
a green build never hides a skipped tier. Every test class owns throwaway
databases (`agent_brain_test_*`), created on demand and dropped at JVM exit.

## Verification

Claims and the command that proves each one, in a clean clone with colima running.

| Claim | Command |
|---|---|
| Tiers 1-3 pass (123 tests, GLM tier skipped without a key) | `make test` |
| The installed `brain-mcp` binary speaks real MCP over stdio: initialize, tools/list (4 tools, flat schemas), every tool answers the envelope with a trace id; invalid input and a down brain are error envelopes; unknown argv refuses to start | `./gradlew :brain-mcp:test` |
| No `while(true)` survives in production sources: every long-lived loop is a KAP `repeat(schedule)` policy, every bounded drain a `repeatUntil`, the plugin parks on `Kap.awaiting` | `grep -rn 'while (true)' core/src/main brain-mcp/src/main` (empty) |
| Extraction parsing: typed items, every invalid shape quarantined with reason and raw text, caps and hygiene, transport failures retried but invalid output not | `./gradlew test --tests '*MemoryExtractorTest*'` |
| The worker on real Postgres: the recorded bot bus becomes memories with provenance, re-running does nothing, the bus replayed twice leaves memories identical, invalid output and provider outages land in the right bins | `./gradlew test --tests '*MemoryWorkerIT*'` |
| The real local chat model produces a valid extraction that mentions what was said | `./gradlew test --tests '*MemoryExtractionOllamaIT*'` |
| A real `serve` turns a bus turn stating a preference into a memory the brain recalls, observed only over HTTP and the CLI | `make bb` (`P3MemoryBB`) |
| The two KAP 4.0.0 findings are FIXED in 4.1.0 (pinned) | `./gradlew test --tests '*KapFindingsTest*'` |
| The chunker: heading chains, fenced code is not headings, small sections pack, long paragraphs split into overlapping windows, deterministic | `./gradlew test --tests '*ChunkerTest*'` |
| The corpus: absolute globs, `**` includes depth zero, noise directories pruned, oversized and non-UTF-8 files skipped with a reason | `./gradlew test --tests '*CorpusTest*'` |
| Hybrid retrieval on real Postgres: both legs agree on the right document, the full-text leg rescues an exact token, another embedding model sees nothing, deletes cascade | `./gradlew test --tests '*RagStoreIT*'` |
| The KAP embed pipeline under virtual time: batches PROVEN parallel (`shouldProveParallel`), bounded by concurrency, transient failures retried, a timeout retried, the breaker opens and fails fast | `./gradlew test --tests '*EmbedPipelineTest*'` |
| Indexing lifecycle: first run indexes all, second run embeds nothing, an edit re-indexes one, a deletion prunes, one poisoned document is reported and the rest land | `./gradlew test --tests '*IndexerIT*'` |
| Two KAP 4.0.0 findings pinned (timeout before retry; settled not isolating nested parallel graphs) and the `isolated()` workaround proven | `./gradlew test --tests '*KapFindingsTest*'` |
| Ten planted facts among distractors, indexed with the REAL model: recall@5 = 1.0 (floor 1.0), recall@1 = 1.0 (floor 0.8) | `./gradlew test --tests '*RecallGateIT*'` |
| A real `serve` indexes the fixture corpus through the CLI with the real model, answers with the right citation over HTTP and CLI, and a re-index embeds nothing | `make bb` (`P2RagBB`) |
| Every line the bot's publisher produces parses under the bus contract; unknown kinds, sources, empty ids and bad timestamps are refused | `./gradlew test --tests '*BusEventsTest*'` |
| The tailer: partial lines wait, truncation restarts from zero, a restart resumes from the persisted byte offset, a handler failure keeps the offset (at-least-once), a line longer than the read window never stalls the tail, multibyte lines keep byte-exact offsets | `./gradlew test --tests '*FileTailerTest*'` |
| Replaying the whole bus twice (with a duplicate and a malformed line in it) leaves the database identical: 6 rows, 7 duplicates counted, 1 malformed counted | `./gradlew test --tests '*PgBusIngestIT*'` |
| A real `serve` process tails a bus written by the bot's own `bus.py`; a byte-for-byte replay adds nothing; `bus status` and `ingest --once` report the same numbers | `make bb` (`P1BusPipelineBB`) |
| The transport contract: the same bus through the file tailer and through bridge → Redpanda → consumer leaves an IDENTICAL database; a lost bridge offset (full re-produce) and a poison record change nothing but the counters | `./gradlew test --tests '*KafkaTransportIT*'` |
| A real `serve` consuming from Kafka: `bridge --once` produces the bus file, the consumer lands it in Postgres, `/bus/status` reports the Kafka transport with commits | `make bb` (`P4KafkaBB`) |
| The empty stack comes up green end to end: real `serve` subprocess, migrations applied, Ollama embedding a probe, CLI printing clean JSON | `make bb` (`P0StackHealthcheckBB`) |
| Migrations are idempotent and concurrent runners apply exactly once | `./gradlew test --tests '*MigratorPgTest*'` |
| A provider whose dimension disagrees with the database is refused with an actionable message | `AB_EMBED_DIM=1024 core/build/install/agent-brain/bin/agent-brain migrate` (exit 1) |
| Secrets never appear in `/health` | `./gradlew test --tests '*ServerReadyTest*'` |
| A `@Test` method JUnit would silently skip is a red build | `./gradlew test --tests '*TestMethodsReturnUnitGate*'` |
| `migrations/index.txt` and the directory cannot drift | `./gradlew test --tests '*MigrationsTest*'` |
| The tree compiles with warnings as errors | `./gradlew compileKotlin compileTestKotlin` |

## Lessons already paid for

- **The embedding dimension is a database property.** `brain_meta.embed_dim` is
  written by migration 0001 and checked on every startup; changing the model
  means `make nuke && make up`, never a silent re-index ([ADR-004](docs/adr/ADR-004-embedding-dimension.md)).
- **pgjdbc caches "unknown type" per physical connection.** `PGvector` is a
  binary type, so registering it looks the OID up eagerly; a pooled connection
  that saw the database before the extension existed could never write a
  vector. The migrator now retires every pooled connection after applying
  anything. Regression test: `a connection that saw the database before the extension can still write vectors after migrating`.
- **JUnit silently ignores non-void `@Test` methods**, and Kotlin's
  `fun x() = runBlocking { assertThat(..) }` returns the assert. Four tests
  once never ran while the build stayed green; `TestMethodsReturnUnitGate`
  scans every compiled test class and names the offenders.
- **A line longer than the read window stalled the tail forever.** The first
  tailer read a fixed window and waited for a newline inside it; a longer
  line meant no newline, ever. The window now doubles until a newline
  appears (hard cap, oversized lines are counted and skipped). Test: `a line
  longer than one read window never stalls the tail`.
- **Loops are policies.** The bus tailer, the memory worker, the Kafka consumer
  and the bridge were `while (true) { try poll catch log; delay }`. KAP 4.1.0
  gained `repeat(schedule)` / `repeatUntil` / `Kap.awaitUntil` / `Kap.awaiting`
  (SPEC-5.0 in the KAP repo, milestone 4.2 first cut), so cadence, stop
  condition and lifecycle are values proven under virtual time, and the only
  remaining loops are pure byte algorithms written as tail recursion.
- **Typed builders need the real library.** Adopting `@KapTypeSafe` surfaced two
  more KAP defects (plain `kap(::T)` never generated for packaged classes;
  nested classes generating non-compiling code), fixed upstream in 4.1.0 the
  same day. Until the Central release is tagged, 4.1.0 comes from `mavenLocal`
  (`settings.gradle.kts`), and CI builds it from the KAP repo.
- **A KAP `timeout` before `retry` is never retried** (`TimeoutCancellationException`
  is a `CancellationException`, which `retry` rethrows by law). The fallback
  overload converts it first. See [ADR-007](docs/adr/ADR-007-kap-orchestration.md).
- **KAP's `settled` does not isolate nested parallel graphs**: `traverse`
  launches children on the caller's scope, so a failing grandchild fails the
  settled coroutine itself. `Kap.isolated()` (own `coroutineScope`) restores
  the CONVERT discipline; `KapFindingsTest` pins both behaviours.
- **stdout is data, stderr is logs.** The CLI prints JSON; a log line after
  it broke `ingest --once` in the black-box tier. Logback writes to stderr.
- **Tests must not share order-dependent state.** One shared database plus
  `@Order` produced a schema created with the wrong dimension by the test that
  was supposed to reject it. Every class now owns its databases.

## ADRs

- [ADR-001 One database for everything](docs/adr/ADR-001-one-database.md)
- [ADR-002 Configuration: one immutable object, env over file over defaults](docs/adr/ADR-002-configuration.md)
- [ADR-003 Kafka is a bridge, not the spine](docs/adr/ADR-003-kafka-is-a-bridge.md) (implemented in P4)
- [ADR-004 The embedding dimension is a database property](docs/adr/ADR-004-embedding-dimension.md)
- [ADR-005 Kotlin core, Python only at two glue points](docs/adr/ADR-005-kotlin-core-python-glue.md)
- [ADR-006 Five test tiers, services required in CI](docs/adr/ADR-006-test-tiers.md)
- [ADR-007 KAP for orchestration, where there are phases or policies](docs/adr/ADR-007-kap-orchestration.md)
- [ADR-008 Memory is extracted from the bus, quarantined when in doubt, recalled with decay](docs/adr/ADR-008-memory.md)

## Layout

```
brain-mcp/               the ZCode plugin: MCP Kotlin SDK stdio server, ToolSpec registry, envelope, plugin manifest
core/                    Kotlin module: config, db (pool + migrator), embeddings, chat, bus (tailer + ingest),
                         rag (corpus, chunker, store, KAP embed pipeline, indexer, service),
                         memory (extractor, store, worker, service), http, Main
corpus.example.txt       the author's corpus; copy to ~/.zcode/agent-brain/corpus.txt
core/src/main/resources/migrations/   plain SQL, enumerated by index.txt, shipped in the jar
core/src/test/kotlin/    tiers 1-4 (tier 5 arrives with P1)
docker-compose.yml       pgvector/pgvector:pg18 (Redpanda joins in P4)
Makefile, bootstrap.sh   operator entry points
.github/workflows/ci.yml tiers 1-4 on every push, Ollama installed for real
docs/adr/                decisions
```
