# Concurrency/FP audit — findings and verdicts (2026-11-08)

Companion to [`concurrency-audit.md`](concurrency-audit.md) (the mechanical U0
inventory: 157 suspects over `core/src/main` + `brain-mcp/src/main`). U1 = two
read-only review agents (concurrency + purity/errors); U2 = the fix batches
(merged); U3 = this document. The same machinery the bot and whatsdigest-kt
repos ran the same day.

## Score

| U0 category | hits | REAL | false-positive / lawful |
|---|---|---|---|
| forbidden | 12 | 0 | every hit is an entry-point `runBlocking` bridge (serve/eval/CLI) — the lawful composition-root edge |
| handrolled | 7 | 1 (a 3-line KDoc) | Loom = the lawful blocking-JDBC executor bridge; EmbedPipeline's CHM is `Ref.sync`-shaped with atomic `computeIfAbsent`; Kafka closes under `NonCancellable` (exemplary); the rest are KDoc narration |
| impure | 10 | 0 | the tracer birth-stamp executes once at `.evalGraph()`; FakeProvider's randomness is content-derived (sha256 seed); providers are edge-IO translations |
| errors | 128 | 6 | all six typed-throw families are lawful boundary validation translated by callers; `parseBins` is a total pure core returning an ADT (never throws from the pure side); the breaker NEVER counts cancellation (kap law, verified in CircuitBuilder) |

## The real findings — one class again, fixed

The repo knew the CE-rethrow idiom (Server dbCheck, EmbeddingProvider) but five
suspend catch-alls lacked it, plus one config hole:

| commit | what |
|---|---|
| `364b46b` + `297e9ad` | (ranking, separate audit thread) search ranking: relevance orders, reinforcement breaks ties — lexicographic `RankKey` with 3 law tests + prod evidence table in `docs/memory-ranking-fix.md` |
| `609da7a` | mcp `envelope` + the four ktor routes rethrow CE (one `receiveBodyOrBadRequest` seam, law provable without an HTTP engine) + FileTailer offset-cell KDoc (+2 tests) |
| `a587b80` | a cancelled eval run no longer leaks its throwaway database |
| `6fcf510` | `bus_transport` fails loudly on an unknown value (constants owned once by Config, re-exported by BusPipeline; config.json AND env paths covered) |

## The laws this audit pinned (repo review checklist)

1. Every suspend catch-all carries the CE arm first — including ktor receive
   paths, where a disconnected client must not read a misleading 400.
2. kap's breaker/retry already honor cancellation (`CircuitBuilder` rethrows CE
   before `recordFailure`; `KapTimeoutException` is NOT a CE — timeouts are
   provider failures and count, correctly). Do not add CE handling inside
   kap-resilience usage; add it in YOUR catch-alls.
3. A pure core returns an ADT; its "throwing twin" translation lives at the
   worker edge (the `parseBins`/`parse` pair is the house exemplar).
4. Blocking JDBC rides the Loom virtual-thread dispatcher (`withConnection`);
   lifecycle: process-wide, daemon threads, Hikari closed by `Database.close()`.
5. One `@Volatile` cell = one writer coroutine, documented AT the cell (the
   FileTailer offset law).

## Documented-justified (do not "fix")

Entry-point `runBlocking`s (serve/eval/mcp/CLI) · Loom's never-closed dispatcher
(intentional, daemon threads) · the tracer birth-stamp · `stdoutGuard` for the
stdio MCP protocol · KafkaTopics' blocking `Future.get()` on the startup path
(minor; startup-only).

## Open candidates (not scheduled)

- ~~`BusPipeline` typed transport ADT~~ — DONE 2026-11-13 (`87744ea`): the
  transport is the `BusTransport` sum (File | Kafka), parsed once at the
  composition root; `kafka!!`, `isKafka` and the nullable consumer are gone.
- ~~the stringly-typed event taxonomy~~ — DONE 2026-11-13 (`ef3787a`):
  `BusKind`/`BusSource` enums, unknown values fail the decode itself.
- ~~the stringly-typed memory kind~~ — DONE 2026-11-13 (`eed879d`):
  `MemoryKind` enum, the JSON decode is the parse edge, string edges convert
  through `MemoryKind.parse`.
- ~~the 4x HTTP provider error translation, the 2x kap resilience chain, the
  5x catch->400 route block, the Main.kt inline copy~~ — DONE 2026-11-13
  (`50d420c`, `3059453`, `4b8c5da`, `85808a1`): one `postForProvider` +
  per-provider translations, one `withProviderResilience` +
  `ProviderResilience` defaults, one `runOrBadRequest` seam, `withoutOption`
  reused.
- `KapStream` for the drain predicate — Phase G territory, already covered.
- The detekt rule for "CE arm before catch-all" — awaiting
  `kap-detekt-rules` publication (kap repo, owner's call).

Post-refactor mechanical inventory (same patterns, worktree scan):
forbidden 12 = 12, handrolled 7 = 7, impure 8 < 10, errors 112 < 128 — no
category grew; the error/impure suspects shrank with the deleted copies.
