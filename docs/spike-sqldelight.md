# Spike: SQLDelight 2.4.1 over the brain's raw SQL (P0 verdict)

Date: 2026-11-15 · Branch: `agent/sqldelight-spike` (evidence, NOT merged) ·
Plan: the SQLDelight strangler migration (P0–P4), spike = decision gate.

## What was tested

The official wiring (docs `latest/jvm_postgresql`): Gradle plugin
`app.cash.sqldelight` 2.4.1, `dialect("app.cash.sqldelight:postgresql-dialect:2.4.1")`,
driver `app.cash.sqldelight:jdbc-driver:2.4.1` with `dataSource.asJdbcDriver()`
over the EXISTING Hikari pool (`Database.pool`, the only main-source addition).
`.sq` files under `core/src/main/sqldelight/` reproduce the hard statement
families: the hybrid SEARCH, TRAVERSAL, lifecycleCounts, temporalPair,
insertMemory (text-vector bind + `RETURNING id`), findReplay (md5),
quarantineBucketed (split_part/CASE), insertEvalRun (uuid/jsonb/numeric casts).
A 5-law IT (`SqlDelightSpikeIT`) runs the surviving set against real Postgres
(throwaway DB, Migrator-owned schema, dim 4): 5/5 PASSED.

## Verdict table

| Statement family | Parses under the PostgreSQL dialect? | Evidence |
|---|---|---|
| INSERT + `ON CONFLICT DO NOTHING` + `RETURNING id` | YES | `insertMemory` — runtime-proven |
| `md5(...)` | YES | `findReplay` — runtime-proven |
| `split_part` + `CASE` + `GROUP BY` alias | YES | `quarantineBucketed` — runtime-proven |
| `?::uuid` / `?::jsonb` / `?::timestamptz` / `numeric(10,6)` | YES — typed params: `UUID`, `BigDecimal`, `OffsetDateTime` | `insertEvalRun` — runtime-proven |
| `LEFT JOIN LATERAL` + row constructor `(a,b) < (c,d)` + `ON TRUE` | YES | `temporalPair` shape — runtime-proven |
| pgvector `<=>` | **NO** — lexed as `<` then `>` (`<expr> expected, got '>'`) | measured, gen1 |
| Postgres's qualified `OPERATOR(public.<=>)` spelling | **NO** — no generic operator token in the grammar (`got 'OPERATOR'`; the expected-token list enumerates `@@`, JSON/range/regex ops, no catch-all) | measured, gen2 |
| `power(2.0, ...)` | **NO** — unknown functions parse as WINDOW functions: `OVER expected, got 'AS'` | measured, gen3 |
| `exp(...)` / `ln(...)` (the mathematically identical decay rendering SEARCH's weight column already uses) | **NO** — same `OVER expected` wall | measured, gen5 |
| `websearch_to_tsquery` / `ts_rank_cd` | NO by the same rule (function list is hard-coded in `PostgreSql.bnf`) | bnf inspection |
| FILTER on aggregates, `extract(... epoch ...)` | YES (grammar rules exist) | bnf inspection — but their carriers carry walls anyway |

## The two walls

1. **The operator wall**: every statement touching pgvector's cosine operator —
   MemoryStore.SEARCH, RagStore.SEARCH, nearestNeighbor, findDuplicate,
   NEIGHBORS_IN_BAND, TRAVERSAL, BACKFILL_RELATED (7 statements). The FTS legs
   only exist inside SEARCH statements, so they share the fate.
2. **The function wall**: the dialect accepts only a hard-coded function list;
   anything unknown is parsed as a window function. `power`/`exp`/`ln` are all
   unknown → the B3 forgetting-law math (lifecycleCounts, dreamRows) cannot be
   expressed. `md5`/`split_part`/`extract` happen to be in the list.

**Stay-raw set: 9 statements — precisely the measured laws** (search, traversal,
dream, lifecycle). Migratable set: ~55 CRUD statements.

## Runtime evidence (the seam works)

`BrainDb(pool.asJdbcDriver())` over the same Hikari pool; text-vector bind
(`[1,2,3,4]` string through `?::vector`) needs NO `PGvector` JDBC type
registration; the generated `transact` bracket rolls back on failure and
commits on success (law-tested); `::uuid`→`UUID`, `numeric`→`BigDecimal`,
`timestamptz`→`OffsetDateTime` auto-mapping is genuinely typed. The tool is
NOT the problem — the wall DISTRIBUTION is.

## Decision: ABORT the migration (law confirmed with receipts)

The earlier verdict — "raw SQL is the law here; SQLDelight is fit-if-it-grows" —
is now measured. The brain's SQL is interesting exactly where SQLDelight is
expressiveness-blind: retrieval laws, decay math, FTS, dream. Compile-time
checking for the boring half does not pay for a permanent dual stack (9 raw
statements beside generated ones), a build plugin, and an adapter seam — while
the E2 eval matrix already runtime-verifies the hot statements on every CI push
(recall-core byte-identical 20/20 is the standing gate).

Options, if the verdict is overruled:
- **B — CRUD-only strangler** (~55 statements): the seam is proven on this
  branch; P1 can start from `agent/sqldelight-spike`. Accepted cost: the 9 law
  statements stay raw forever.
- **C — fix it at the source**: sqldelight accepts dialect/grammar work
  upstream (griffio's postgres contribution stream is the precedent). The
  unblock conditions are: a generic operator token (or pgvector's `<=>`
  specifically) and a "trust unknown scalar functions" escape hatch. When
  either lands, the FULL migration becomes viable with zero local fork tax.
  A vendored patched dialect today would impose the fork tax now — not worth
  it to chase type-safety the eval harness already provides.

## Artifacts on the branch

- `gradle/libs.versions.toml` + root/core `build.gradle.kts` — plugin wiring
  (catalog-driven version)
- `core/src/main/sqldelight/dev/lattenero/brain/db/sq/{Memories,MemoryEdges,EvalRuns}.sq`
  — the surviving statements, typing-only tables, verdict comments
- `core/src/test/kotlin/dev/lattenero/brain/db/SqlDelightSpikeIT.kt` — 5 laws
- `db/Database.kt` — `pool` accessor (the one main-source seam)
