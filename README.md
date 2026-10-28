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
| P2 | RAG: corpus indexing, heading-aware chunker, hybrid cosine + full-text search with RRF, cited answers, recall gate, promptfoo | planned |
| P3 | Memory: structured extraction per turn with quarantine, salience decay, context injection knob in the bot | planned |
| P4 | Kafka transport (Redpanda) as a bridge behind the same consumer contract, rebalance/duplicate tolerance test | planned |
| P5 | `brain-mcp` plugin (official MCP Kotlin SDK, stdio) installed in the harness: `rag_query`, `memory_search`, … | planned |
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
make up               # pgvector/pgvector:pg18 on 127.0.0.1:5433, then migrations
make test             # tiers 1-3 (skips a tier with a reason when its service is down)
make bb               # tier 4: black-box local, services required
make serve            # http://127.0.0.1:8765/ready
```

The bus file is `~/.zcode/agent-brain/bus-events.jsonl` (bot side: set `"event_bus": true`
in `~/.zcode/telegram-remote.json`, bot ADR-019). The CLI lives at `core/build/install/agent-brain/bin/agent-brain`:

```
agent-brain serve | migrate [--status] | ingest [--once] | bus status | health [--url U] | embed TEXT... | config
```

`serve` runs the HTTP service and, with `bus_transport: file`, the bus tailer.
`ingest --once` drains the bus into Postgres and exits with the counters (scripts, CI).

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
| `bus_transport` / `kafka_bootstrap` / `kafka_topic` | `file` / `127.0.0.1:19092` / `agent-brain.bus` | Kafka in P4 |
| `bus_poll_ms` | `500` | tailer cadence |

`AB_BASE` (default `~/.zcode/agent-brain`) moves every derived path: state,
logs, the bus file, the config file.

## Test pyramid

| Tier | Tag | What is real | Task |
|---|---|---|---|
| 1 unit | none | nothing external | `test` |
| 2 integration | `integration` | Postgres/pgvector, Ollama, GLM (key permitting) | `test` |
| 3 e2e | `e2e` | real database and routes, in-process server, fake model | `test` |
| 4 black-box local | `bb-local` | the installed distribution as a subprocess, real services, only the Telegram wire virtualized | `bbLocal` |
| 5 black-box real | `bb-real` | your Telegram account over MTProto (from P1) | `e2eReal` |

Locally an unreachable service skips its tier with a printed reason. With
`AB_REQUIRE_SERVICES=1` (CI, `make bb`, `make ci`) the same condition fails:
a green build never hides a skipped tier. Every test class owns throwaway
databases (`agent_brain_test_*`), created on demand and dropped at JVM exit.

## Verification

Claims and the command that proves each one, in a clean clone with colima running.

| Claim | Command |
|---|---|
| Tiers 1-3 pass (73 tests, GLM tier skipped without a key) | `make test` |
| Every line the bot's publisher produces parses under the bus contract; unknown kinds, sources, empty ids and bad timestamps are refused | `./gradlew test --tests '*BusEventsTest*'` |
| The tailer: partial lines wait, truncation restarts from zero, a restart resumes from the persisted byte offset, a handler failure keeps the offset (at-least-once), a line longer than the read window never stalls the tail, multibyte lines keep byte-exact offsets | `./gradlew test --tests '*FileTailerTest*'` |
| Replaying the whole bus twice (with a duplicate and a malformed line in it) leaves the database identical: 6 rows, 7 duplicates counted, 1 malformed counted | `./gradlew test --tests '*PgBusIngestIT*'` |
| A real `serve` process tails a bus written by the bot's own `bus.py`; a byte-for-byte replay adds nothing; `bus status` and `ingest --once` report the same numbers | `make bb` (`P1BusPipelineBB`) |
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
- **stdout is data, stderr is logs.** The CLI prints JSON; a log line after
  it broke `ingest --once` in the black-box tier. Logback writes to stderr.
- **Tests must not share order-dependent state.** One shared database plus
  `@Order` produced a schema created with the wrong dimension by the test that
  was supposed to reject it. Every class now owns its databases.

## ADRs

- [ADR-001 One database for everything](docs/adr/ADR-001-one-database.md)
- [ADR-002 Configuration: one immutable object, env over file over defaults](docs/adr/ADR-002-configuration.md)
- [ADR-003 Kafka is a bridge, not the spine](docs/adr/ADR-003-kafka-is-a-bridge.md)
- [ADR-004 The embedding dimension is a database property](docs/adr/ADR-004-embedding-dimension.md)
- [ADR-005 Kotlin core, Python only at two glue points](docs/adr/ADR-005-kotlin-core-python-glue.md)
- [ADR-006 Five test tiers, services required in CI](docs/adr/ADR-006-test-tiers.md)

## Layout

```
core/                    Kotlin module: config, db (pool + migrator), embeddings, bus (tailer + ingest), http, Main
core/src/main/resources/migrations/   plain SQL, enumerated by index.txt, shipped in the jar
core/src/test/kotlin/    tiers 1-4 (tier 5 arrives with P1)
docker-compose.yml       pgvector/pgvector:pg18 (Redpanda joins in P4)
Makefile, bootstrap.sh   operator entry points
.github/workflows/ci.yml tiers 1-4 on every push, Ollama installed for real
docs/adr/                decisions
```
