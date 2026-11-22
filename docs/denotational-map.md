# The denotational map — agent-brain's foundations

ADR, written 22/11 after the owner asked for a foundations-first refactor. The
method: one sentence per domain saying what it DENOTES, stripped of
implementation — if a change breaks its sentence, that is a design signal:
redesign the denotation, never patch the code (the homomorphism check).

The verdict that motivated this map, with receipts (every hotspot file read
line-by-line the same night): the foundations ARE denotational already — the
13/11 sweep left `when`-tables, one-time error translation at the edges, the
`Brain` resource graph and structured concurrency. A total rewrite would
violate the minimal-code law. What remained was finite and named; it is all
closed or listed here.

## The domains — one sentence each

- **Bus** (`bus/`): the ordered stream of what happened — turn events from the
  fork, File or Kafka as interchangeable transports, Postgres rows as truth.
- **Memory** (`memory/`): the distilled owner — facts/decisions/preferences
  extracted from turns, quarantine for degraded extractions, a decay clock
  (half-life) and dream passes that reconcile contradictions.
- **Retrieval** (`rag/`): the corpus's answer to a question — ranked legs
  (original, English restatement, synonyms) fused by RRF, with the confidence
  floor saying whether the corpus is about the question AT ALL (top-1 cosine,
  measured 0.55; RRF saturates at ~0.03 for hits and noise alike).
- **Indexer** (`rag/Indexer`, `Repos`): the corpus's shape on disk as chunks +
  vectors — what leaves the disk leaves the index (prune), same sha never
  re-embedded.
- **Eval** (`eval/`): the system graded by itself — suites, the ledger, the
  artifacts; the judge is a different model than the maker, and "nothing to
  grade" is an honest zero, never a failure.
- **Serving** (`http/`): the one speaking boundary — every domain answers JSON
  here; domain exceptions translate to HTTP codes exactly once, at this edge.
- **Composition** (`Main.kt`, `Brain`): the wiring — one command `when`-table,
  one Resource graph per process, every long-lived loop a supervised child.

## The laws and where they live (receipts)

| Law | Where it holds |
|---|---|
| Dispatch as data (mode tables) | `Main.run` command `when`; `EvalRunner.databaseReport` kind `when`; `EvalRunner.casesMarkdown`; `Server` routes |
| Error-as-value, translated once | `Main.run`'s catch ladder → exit codes; `EvalRunner` chat failures → `CaseResult.failure`; `RagService.bestEffort` → the missing leg |
| Effects at the edges | `EvalOptions` (injected clocks), `emit-to` at CLI edges, `Server` as the only HTTP speaker |
| Resources own their cleanup | `Brain.resource`, `withBrain`, `freshEvalDatabase`, `evalCorpus` (CE-arm before catch-all in drops) |
| Structured concurrency | `serve()`'s supervisorScope; kap `Process` children; `startProcess` logs exhaustion, never fatals |
| Best-effort never breaks retrieval | `bestEffort` (rerank law, stated once) — degradation is an honest flag (`reranked: false`) or a missing leg, never an error |
| No opaque types | `Hit`, `QueryResponse`, `CaseResult`, `BusStatus` — field names are the documentation; wire shapes are the ADR |

## The named debts (finite — closed 22/11 or explicitly kept)

1. CLOSED 22/11: the degradation shape was copy-pasted ×3 in `RagService`
   (`catch ChatException → System.err → null`); now one `bestEffort`
   combinator through the logger.
2. KEPT (why): `System.err` in `EvalRunner` cleanup paths — a failed drop of a
   throwaway database must never mask an eval result; stderr is the right
   loud-but-harmless channel there.
3. KEPT (why): `QueryResponse.relevant: Boolean` + `topSimilarity: Double?`
   instead of a sealed relevance ADT — the wire is JSON/MCP where a boolean +
   number document themselves; a sealed type would add ceremony the callers
   cannot consume.
4. OPEN (small): `MultiHop`'s per-leg catches (`ProviderException`,
   `SQLException`) duplicate the degrade-to-empty shape with different failure
   families than `bestEffort`'s — foldable only if a second multi-family
   consumer appears (not before: no abstraction by dogma).
