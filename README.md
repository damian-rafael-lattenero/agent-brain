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
| P6 | Evals as a product: three versioned suites (`evals/suites`: recall, memory extraction, grounded answer with a citation), pass/fail per case with latency p50/p95, real token counts and a declared price table, `eval --matrix a,b` comparing >=2 models into one artifact with a recommendation per turn, `eval latest` + `GET /eval/latest` reading the ledger, `make eval` as the cheap CI gate, promptfoo config for the external tool | done |
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
./bootstrap.nu        # checks Corretto 25, docker (colima), ollama + nomic-embed-text; builds the CLI
make up               # pgvector/pgvector:pg18 + Redpanda (19092), then migrations
make test             # tiers 1-3 (skips a tier with a reason when its service is down)
make bb               # tier 4: black-box local, services required
make serve            # http://127.0.0.1:8765/ready
make index            # index ~/.zcode/agent-brain/corpus.txt (copy corpus.example.txt first)
make index-repos-dry  # what the repos under ~/Projects would add, without Postgres
make index-repos      # index those repos incrementally (docs + code)
make query Q="how does the tailer handle truncation"
make eval             # the cheap gate: recall-core (20 questions, gate 0.9) → eval_runs + latest.{md,json}
make eval-matrix      # compare >=2 models on the same suites: one table + a recommendation per turn
make eval-latest      # what the ledger says (also GET /eval/latest?matrix=true)
```

The bus file is `~/.zcode/agent-brain/bus-events.jsonl` (bot side: set `"event_bus": true`
in `~/.zcode/telegram-remote.json`, bot ADR-019). The CLI lives at `core/build/install/agent-brain/bin/agent-brain`:

```
agent-brain serve | migrate [--status] | ingest [--once] | bridge [--once] | bus status | index [GLOB...] | index-repos [--dry-run] [--repo NAME]...
            | query TEXT [--k N]
            | memory search TEXT [--k N] | memory recent [--k N] [--kind K] | memory save KIND TEXT
            | memory quarantine | memory status | memory run [--once]
            | eval [--suite NAME|PATH[,...]] [--gate G] [--matrix MODEL[@DIM],...] [--out DIR] [--strict]
            | eval latest [--suite S] [--matrix] [--limit N] [--md]
            | health [--url U] | embed TEXT... | config
```

`serve` runs the HTTP service and, with `bus_transport: file`, the bus tailer.
`ingest --once` drains the bus into Postgres and exits with the counters (scripts, CI).
`index` reads `~/.zcode/agent-brain/corpus.txt` (one absolute glob per line, see
`corpus.example.txt`) and prints a report with per-phase counters; `query` prints
ranked hits with citations (retrieval only, the caller composes the answer).
`index-repos` indexes whole repositories instead of a hand-maintained corpus: by
default `kap`, `bot`, `zcode-bot-kotlin`, `ZCode` and `agent-brain` under
`~/Projects`, one absolute `**/*.{md,kt,...}` glob per repo built from the same
text-extension set and prune rules as the corpus (`build/`, `.git/`,
`node_modules/` and friends stay out). `--repo NAME` (repeatable) narrows the
scan. `--dry-run` scans and prints the report — documents, total and per-repo
bytes, skipped files with their reasons — and never opens Postgres, so it works
before `make up`. A second run over an unchanged tree reports every document
`unchanged` (idempotent by content sha); a file deleted inside a present
repository is pruned, while a repository whose root vanished is reported and
left intact instead of losing its indexed documents. The scan holds the text
in memory while it walks (the ZCode checkout alone is ~150 MB of text), which
is why the CLI runs with a 768m heap; files over 1 MB and non-UTF-8 files are
skipped and listed in the report.

From the Telegram chat: `/brain <question>`, `/brain status`, `/memory <query>`, `/memory recent`,
`/memory status` (bot ADR-019/020). `serve` also runs the memory worker (knob `memory_worker`).

## The plugin (brain-mcp)

`make plugin` builds `brain-mcp/build/plugin/` (manifest + installed distribution). In ZCode add that
directory as a local marketplace and install `brain-mcp`; the harness gains four tools that talk to the
running `agent-brain serve` over HTTP. The exact JSON, the UI and CLI steps, verification and
troubleshooting live in [docs/install-brain-mcp.md](docs/install-brain-mcp.md). Every tool answers the
same envelope, so a model can branch on
`status` and quote `meta.trace_id`:

```json
{"status":"ok","result":{...},"warnings":[],"nextStep":"cite the uri and heading of what you use; ...",
 "meta":{"trace_id":"trace-1793228960047-3","tool_version":1}}
```

The same four tools are also served over the MCP **streamable HTTP** transport, on loopback and behind a
mandatory bearer token, for clients that are not this machine's ZCode:

```bash
AB_MCP_TOKEN_FILE=~/.zcode/agent-brain/mcp.token brain-mcp serve --transport http --port 8767
```

No arguments still means stdio (the plugin's contract is unchanged). The transport decision, the token
rules, the client configuration, the tool contracts with real request/response pairs, the security
properties and the U8 evidence run live in [docs/mcp.md](docs/mcp.md) — including why the endpoint is a
separate process on its own port (a tunnel to the brain's :8765 would expose its unauthenticated
mutating routes) and why it is a facade over the brain's HTTP API instead of a second in-process brain
(`bus_status` must report the one real tailer).

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
| Tiers 1-3 pass (125 tests, GLM tier skipped without a key) | `make test` |
| The eval gate: 20/20 recall@5 with the real local model on the shipped fixture corpus, the run persisted in `eval_runs`, the eval database dropped afterwards | `make eval` (`EvalRunnerIT` in the suite) |
| The installed `brain-mcp` binary speaks real MCP over stdio: initialize, tools/list (4 tools, flat schemas), every tool answers the envelope with a trace id; invalid input and a down brain are error envelopes; unknown argv refuses to start | `./gradlew :brain-mcp:test` |
| No `while(true)` survives in production sources: every long-lived loop is a KAP `repeat(schedule)` policy, the quiet-window drain a `KapStream.pollUntilQuiet` + `Schedule.debounce`, the bridge drain a `repeatUntil`, the plugin parks on `Kap.awaiting` | `grep -rn 'while (true)' core/src/main brain-mcp/src/main` (empty) |
| detekt 2.0 on the single-JDK-25 build (workers isolated, no daemon dance): both flavours per module — plain + type-resolved — existing findings baselined, any NEW violation fails | `./gradlew detektAll` |
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
| Repo scan: fixed default list, one absolute alternation glob per repo rooted at the repo, `--repo` filter, noise dirs pruned, same tree = same documents in the same order | `./gradlew test --tests '*ReposTest*'` |
| `index-repos --dry-run` reports documents/bytes per repo and never opens Postgres | `make index-repos-dry` |
| Repo indexing on real Postgres: second run all `unchanged` with the same document ids, a deleted file prunes only itself, a vanished repo root prunes nothing | `./gradlew test --tests '*ReposIT*'` |
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

## Evals

`agent-brain eval` is a product, not only the recall gate. A **suite is a versioned file**
(`evals/suites/*.json`, copied into the jar at build time) with cases, an expected result and a gate:

| suite | kind | axis | what a case asserts |
|---|---|---|---|
| `recall-core` | recall | embed | the expected document is in the top 5 hybrid hits (the shipped CI gate, gate 0.9, 20 questions) |
| `extraction-core` | extraction | chat | the chat model's schema-validated extraction carries the expected durable items and does not invent them on a trivial turn (gate 0.6, 6 turns) |
| `grounded-citation` | grounded | chat | the composed answer parses as `{answer, citations}`, cites the expected document and states the expected fact (gate 0.8, 5 questions) |

Every case is graded by a **pure assertion** over what the model returned
(`EvalAssertions`) and carries its **latency**, **tokens** and **declared cost**.
Cost is `tokens × evals/prices.json`: local inference is a declared `0.000000`, and a paid
provider with no declared price reports `unknown` instead of a made-up number. Chat tokens are the
provider's real `prompt_eval_count`/`eval_count`; embedding tokens are estimated (4 chars/token)
and the flag travels with the number.

```bash
make eval                        # the cheap gate: recall-core, one model, ~1.5 s
make eval-matrix                 # extraction + grounded answer on qwen2.5:1.5b vs qwen2.5:3b
make eval-matrix-retrieval       # recall-core on nomic-embed-text@768 vs all-minilm@384
make eval-latest SUITE=recall-core   # what eval_runs already knows (or GET /eval/latest?matrix=true)
```

`make eval-matrix` is **one command** and produces one artifact, `evals/artifacts/latest-matrix.{md,json}`:
the comparison table plus a recommendation per kind of turn, computed from the numbers. The measured
run of 2026-11-04 (local models, 0 cost by declaration):

| suite | model | pass/total | gate | p50 ms | p95 ms | tokens in | tokens out | cost USD | support |
|---|---|---|---|---|---|---|---|---|---|
| extraction-core | qwen2.5:1.5b | 3/6 | 0.6 | 2167 | 5124 | 1229 | 607 | 0.000000 | - |
| extraction-core | qwen2.5:3b | 4/6 | 0.6 | 3901 | 6644 | 1229 | 563 | 0.000000 | - |
| grounded-citation | qwen2.5:1.5b | 5/5 | 0.8 | 1996 | 3257 | 4698 | 145 | 0.000000 | 5/5 |
| grounded-citation | qwen2.5:3b | 5/5 | 0.8 | 3885 | 6287 | 4698 | 147 | 0.000000 | 5/5 |
| recall-core | nomic-embed-text | 20/20 | 0.9 | 18 | 28 | 263 | 0 | 0.000000 | 18/19 |
| recall-core | all-minilm | 20/20 | 0.9 | 12 | 14 | 263 | 0 | 0.000000 | 18/19 |

(`support` is the extra clause of the case: for recall the top hit contains a needle, for a grounded
answer the answer states the fact; extraction has no support column because its groups are the assertion.)

**Recommendation per turn** (the artifact's own conclusion, from those numbers):

- **Memory extraction** → `qwen2.5:3b`: 4/6 vs 3/6, at ~1.8x the p50 latency. An extraction miss is a
  memory the bot never gets back, so quality wins over a slower call on the worker (off the reply path).
- **Answer composition** → `qwen2.5:1.5b`: both models answer 5/5 with a correct citation, and the
  smaller one does it at half the p50 latency, on the turn where latency is user-visible.
- **Retrieval** → `nomic-embed-text`: both embedding models saturate this 13-document fixture (20/20) and
  the full-text leg of RRF hides the difference; `all-minilm` is faster (p50 12 vs 18 ms) but is 384-dimensional, so
  switching would force a re-index and a schema change for no measured gain. Re-run
  `make eval-matrix-retrieval` when the fixture grows past the point where it discriminates.

The **matrix is explicit, not on every push**: it pulls a second model and runs 22 model calls
(~70 s locally, more on a CI runner), while the gate is one model and ~1.5 s. `make eval` runs on every
push and nightly (`.github/workflows/evals.yml`); the matrix is run on demand by a human deciding a model
(`.github/workflows/eval-matrix.yml` is a manual workflow that uploads the artifact). The matrix also
**reports instead of gating** — a model below the gate is a finding in the table, not a red build;
`eval --matrix ... --strict` makes it fail when that is what you want.

`eval_runs` (main database, migration `0004_eval_product.sql`) holds one row per (suite, model):
`kind`, `axis`, `gate`, `ok`, `passed`/`total`, `p50_ms`/`p95_ms`, `tokens_prompt`/`tokens_completion`,
`cost_usd` plus `cost_known`, `duration_ms`, `started_at`, and the whole report as `detail`; the rows of
one matrix share `matrix_id`. So the trend and the decision are a query away, and the bot does not need an
artifact file:

```sql
SELECT suite, model, passed, total, p50_ms, tokens_prompt, tokens_completion, cost_usd, ts
FROM eval_runs ORDER BY ts DESC;
```

`evals/promptfoo/` carries the recall questions as a promptfoo config for anyone who prefers that tool
against a running `serve`.

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
brain-mcp/               the MCP surface: the same four tools over stdio (the ZCode plugin) and over
                         token-gated streamable HTTP; ToolSpec registry, envelope, plugin manifest
core/                    Kotlin module: config, db (pool + migrator), embeddings, chat, bus (tailer + ingest),
                         rag (corpus, repos, chunker, store, KAP embed pipeline, indexer, service),
                         memory (extractor, store, worker, service), http, Main
corpus.example.txt       the author's corpus; copy to ~/.zcode/agent-brain/corpus.txt
core/src/main/resources/migrations/   plain SQL, enumerated by index.txt, shipped in the jar
core/src/test/kotlin/    tiers 1-4 (tier 5 arrives with P1)
docker-compose.yml       pgvector/pgvector:pg18 (Redpanda joins in P4)
Makefile, bootstrap.nu   operator entry points
.github/workflows/ci.yml tiers 1-4 + detekt on every push, Ollama installed for real
docs/adr/                decisions
docs/install-brain-mcp.md  plugin install: exact marketplace/plugin JSON, UI and CLI steps, verification
docs/mcp.md              the MCP surface: both transports, the token, external client config, the four
                         tool contracts with examples, the security properties, and the second-MCP verdict
```
