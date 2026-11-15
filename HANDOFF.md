# HANDOFF — agent/memory-reconcile (reconcile + dream memory unit)

Worktree: `/Users/lattenero/Projects/.worktrees/agent-brain-reconcile` (branch
`agent/memory-reconcile`, pushed to origin). Base: master `2d90e2f`. Never
touch the main checkout or master.

## 1. STATE

### Commits pushed (this order; each = one logical unit + push)

| hash | unit |
|---|---|
| `7447242` | test: pin the dedup contradiction defect with measured cosines (`MemoryContradictionDefectIT`) |
| `1ff1818` | feat: Verdict ADT, total parser, chat judge, invalid_at column + store exclusion + migration 0005 (`VerdictLawsTest`, `MemoryInvalidationIT`) |
| `c6ccb82` | feat: verdict-driven reconcile pass in the memory worker (`MemoryReconciler`, `MemoryReconcilePassIT`, WorkerReport.reconciled) |
| `d54c954` | feat: dream pass v1 CLI `agent-brain dream` (`Dream.kt`, `DreamClusteringLawsTest`, `DreamPassIT`) |

### Uncommitted work (in tree, unit 5 nearly finished)

- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryStore.kt`:
  `MemoryStatus.superseded` field added (NOT `countSuperseded()` — that IS in
  the tree), everything else already pushed.
- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt`:
  `status()` builder gained `.with { superseded from store.countSuperseded() }`.
- `core/src/main/kotlin/dev/lattenero/brain/http/Metrics.kt`:
  `MemoryMetrics.superseded` + `of()` mapping.
- `core/src/main/kotlin/dev/lattenero/brain/http/MetricsInventory.kt`:
  `brain_memory_superseded` gauge family.
- `core/src/test/kotlin/dev/lattenero/brain/http/MetricsProjectionTest.kt`:
  updated for the new gauge (superseded = 4 in fixtures).
- `core/src/test/kotlin/dev/lattenero/brain/memory/MemoryReconcileIT.kt` (NEW,
  compiles, both tests PASS): end-to-end IT — real nomic embeddings + real
  qwen chat verdict; per-run unique DB via `TestDatabase.fresh(tag)` (random
  suffix — the parallel-IT collision concern is handled by construction; do
  NOT invent a new mechanism).

### Precise next steps (in order)

1. Commit + push unit 5 (status bucket + gauge + `MemoryReconcileIT`).
   Commit message file pattern used so far: write message to /tmp file,
   `git commit -F <file>` (backticks in messages were eaten by zsh once —
   amend with `-F` fixed it).
2. Final gate: `./gradlew detektAll` (clean at last check) and
   `./gradlew test` (344 tests: 342 pass, 6 skipped).
3. KNOWN ENVIRONMENTAL FAILURE, not ours: `EvalChatSuitesIT` (2 tests) fail
   with `ProviderUnavailable: ollama chat timed out at .../api/chat` — the
   shared local ollama is contended (another agent runs ITs on the same box).
   No eval file is touched by this branch (verify with `git diff 2d90e2f --
   core/src/main/kotlin/dev/lattenero/brain/eval`). Re-run once; if still
   timing out, report as environmental.
4. Final report: commits, gate output, measured cosines, additive API
   surface, known limits.

## 2. DESIGN DECISIONS (and why; deviations marked)

- **`Verdict` is a sealed INTERFACE, not sealed class** (deviation): detekt's
  `AbstractClassCanBeInterface` fails a sealed class without members; the
  repo law forbids touching baselines/limits. Same closed ADT with
  `data object Same/Supersede/Distinct`.
- **Parser is `VerdictParser` object**: `parse(raw: String): Verdict` TOTAL
  (never throws; `parseElement` degrades to `Distinct` + log.warn). Wire
  words `same|supersede|distinct` lowercase-insensitive; JsonNull/non-object
  degrade to Distinct (kotlinx `JsonNull.isString == false` — relied upon).
- **`VerdictJudge`** (chat adapter): ONE call per pair, `withTimeout(timeout)`
  + `runCatching` (NOT catch-Exception: detekt `TooGenericExceptionCaught`;
  the baseline is untouchable). Ordering in the fold matters:
  `TimeoutCancellationException` -> `Skipped` BEFORE `CancellationException`
  -> rethrow (structured concurrency must still propagate). A hanging test
  fake must SUSPEND (delay), not `Thread.sleep` — withTimeout cannot
  interrupt thread-blocking (a non-suspending body completes and returns
  normally).
- **Schema in prompt**: `VerdictJudge.SCHEMA` JsonObject (`verdict` enum +
  `rationale`), user message `OLD: <old>\nNEW: <new>\nWhich verdict?` (order
  = meaning; pinned by a law test).
- **Band**: `MemoryReconcile.BAND_LOW = 0.80`, `BAND_HIGH` READS
  `MemoryDedup.SIMILARITY` (cannot drift). `MemoryStore.NeighborBand(low,
  high, halfLifeDays)` groups query knobs (detekt LongParameterList max 5).
- **Reconcile decision** = `MemoryReconcile.decide(Verdict) ->
  ReconcileAction` (InvalidateOld | MergeIntoOld | Nothing); content is
  NEVER rewritten. `order(a, b)` puts earlier-`createdAt` first (id breaks
  ties) — Supersede always stamps the earlier row.
- **Invalidation** (Zep law): `UPDATE ... SET invalid_at = now() WHERE id =
  ? AND invalid_at IS NULL` (idempotent, first stamp only). Content and
  salience untouched; NOT deletion.
- **Exclusions of invalidated rows** (all additive one-liners):
  `MemoryStore.SEARCH`'s `alive` CTE (`AND m.invalid_at IS NULL`), dedup
  `findDuplicate` (a dead claim must NOT absorb a new landing — otherwise
  the restatement of the truth vanishes into the false row), `dedupRows()`
  backfill, `dreamRows()`.
- **Reconcile pass placement**: inside the worker's `attempt` Kap AFTER
  `insertAllDeduped` + `markProcessed` — the turn is marked first, so a
  skipped verdict loses nothing and the write path never blocks on chat
  (one call, one timeout, NO resilience policy — deliberately no breaker/
  retry). `MemoryStore.insertAllDeduped` now returns `insertedIds` (via
  `insertRow` RETURNING id; `-1L` sentinel when ON CONFLICT swallows —
  filtered from count and ids).
- **`MemoryWorker` signature change**: `(store, extractor, embed,
  reconciler: MemoryReconciler? = null, params: WorkerParams =
  WorkerParams())`; `WorkerParams(batch, maxAttempts, halfLifeDays)` exists
  to satisfy detekt LongParameterList (max 6) — its DEFAULT_* consts live in
  ITS OWN companion (a top-level data class cannot reference
  `MemoryWorker.companion` consts declared later — Kotlin resolution).
  `reconciler == null` means "no reconcile" (used by pre-existing tests).
- **Same verdict in reconcile** = `store.mergeInto(old, new)` = reinforce
  old by `REINFORCE_STEP` (1.0) + delete new — exactly dedup's merge.
- **Dream**: pure clustering `Dream.clusters(rows: List<DedupRow>)` — greedy
  oldest-first (dedup backfill's own rule), same embed model only, cosine >=
  `Dream.NEIGHBOR_SIMILARITY` (= BAND_LOW), singletons dropped.
  `DreamCluster(seedId, members)` where members INCLUDES the seed first
  (`others` excludes it — an earlier version double-counted the seed in
  `ids`, caught by the laws test). Per-run `dead` set: rows a verdict killed
  take no further pair (no double-invalidation, no chat call on a dead
  claim) — this plus the input-set exclusion makes dream idempotent per
  pair. `--dry-run` = plan only (clusters + `plannedPairs`), NO chat calls,
  no writes. NO automatic worker cadence ships (mission: OFF by default).
- **`DreamPassIT` cooling fixtures**: plant rows then backdate
  `created_at` AND `last_accessed` to 43-45 days (half-life 30 -> life-weight
  2^-1.5 ≈ 0.354 = COOLING). Forgotten fixture: 120 days (2^-4 < 0.125).
- **IT vectors are HAND-CRAFTED** (unit e0; `tilted(base, eps, c)` = c*e0 +
  sqrt(1-c²)*e_eps — cosine exactly c), so scripted-chat ITs are
  deterministic and offline. The ollama end-to-end IT is the only one using
  the real model.
- **Chat model behaves** (probed via /api/chat, temp 0, schema-enforced):
  qwen2.5:1.5b answers `supersede` with a sane rationale for pending vs
  completed pairs — deterministic across all three probed pairs.

## 3. CODEBASE DISCOVERIES

- **Migrations**: plain SQL files in
  `core/src/main/resources/migrations/` enumerated by `index.txt` (a jar
  cannot list dirs); `MigrationsTest` pins index == directory. Name regex
  `^\d{4}_[a-z0-9_]+\.sql$`, strictly increasing. Only templating:
  `__EMBED_DIM__`. A migration unit test fails if you forget index.txt.
- **Dedup constant**: `MemoryDedup.SIMILARITY = 0.90`, evidence documented
  in its KDoc (true duplicates 0.9123..0.9845, merely-related max 0.8789,
  cross-lingual ~0.65). `MemoryDedup.cosine(a, b)` matches pgvector
  `1 - (a <=> b)`.
- **Search SQL** (`MemoryStore.SEARCH`): params CTE (`qv, model, n, tsq,
  half_life, forget_floor`) -> `alive` CTE (`LIFE_WEIGHT_SQL >=
  forget_floor`) -> `vec`/`fts` legs (both filter `id IN alive`, n =
  max(k*4, 50)) -> RRF fusion (`Rrf.K`) -> final SELECT with `weight` =
  salience*exp decay over created_at. `LIFE_WEIGHT_SQL` =
  `m.salience * power(2.0, -extract(epoch FROM (now() -
  COALESCE(m.last_accessed, m.created_at))) / (86400.0 * p.half_life))` —
  the ONE expression shared by search exclusion, status buckets,
  lifecycleCounts, dreamRows.
- **B3**: `Lifecycle.classify(lifeWeight)`: >= 0.5 ACTIVE, >= 0.125 COOLING,
  else ARCHIVED; `Lifecycle.Params(halfLifeDays)`; config knob
  `memory_half_life_days` default 30. Forgetting = exclusion, not deletion.
- **Test infra**: `TestEnv.gate()` skips when a service is down unless
  `AB_REQUIRE_SERVICES=1`; `TestDatabase.fresh(tag)`/`.config(tag)` create a
  per-call UNIQUE database `agent_brain_test_<tag>_<uuid8>` (dropAll on JVM
  exit). Tags must be SQL-safe identifiers — NO HYPHENS (`inval-law1` blew
  up with `syntax error at or near "-"`; underscores only).
- **Chat client**: `ChatProvider.completeJson(system, user, schema)` —
  `OllamaChatProvider` POSTs `{base}/api/chat` with `format: schema`,
  temperature 0; transport errors already translate to `ProviderException`
  hierarchy in `postForProvider`/`postOrUnavailable` (ProviderHttp.kt).
- **detekt 2.0**: baselines `core/detekt-baseline*.xml` are OFF-LIMITS;
  limits: 120 cols, max 2 throws, 60 lines/function, ReturnCount 2,
  CyclomaticComplexity 14, LongParameterList 5(main)/6, no `catch (e:
  Exception)` in NEW code (use runCatching or specific catches), MagicNumber
  on literals in logic (constants), LoopWithTooManyJumpStatements (>1
  break/continue fails), UseOrEmpty. The gradle detekt output prints
  "28 compiler errors found during analysis" warnings even on green master —
  benign type-resolution noise, gate is exit code.
- **kap builder** for typed parallel construction: `kap(::MemoryStatus).with
  { field from provider }.evalGraph()`; fields MUST exist on the data class
  or the generated intermediate types fail compile (MemoryStatus lives in
  MemoryStore.kt — NOT MemoryWorker.kt where status() is; my first edit
  targeted the wrong file).
- **CLI wiring**: `run(args)` when-dispatch in Main.kt; `withBrain(config) {
  brain -> ... }`; brain exposes `memoryStore`, `verdictJudge`,
  `memoryReconciler`, `memoryWorker`, `memory` (MemoryService), `provider`,
  `chat`, `config`. CLI arg parsing: `optionValue("--flag")`,
  `withoutOption`. Print via `printJson(responseJson.encodeToJsonElement(
  X.serializer(), value))`. Exit codes EXIT_OK/EXIT_FAIL/EXIT_USAGE;
  `MAX_K` lives in `MemoryService` (top-level dream validation used
  `MemoryService.MAX_K`).
- **Surprise**: `insertAllDeduped` counts attempted inserts, not landed ones
  — my RETURNING-id change made it count only real insertions (honest; no
  test relied on the old behavior).
- **ollama**: models present: `qwen2.5:1.5b`, `nomic-embed-text` (768d);
  embed endpoint `/api/embed` `{model, input: [...]} -> embeddings` — used
  via nushell scripts in /tmp/nu-inline for the measurements below.

## 4. MEASURED NUMBERS (nomic-embed-text, 2026-11-15)

Naive pair "The deploy is pending." vs "The deploy completed today." =
**0.8095** — BELOW the 0.90 band: naive antonyms do NOT fuse; the defect is
band-dependent. Realistic status-flip pairs DO land in/above the merge band:

| cosine | pair |
|---|---|
| 0.9524 | "The orders database failover is in progress." ↔ "... is complete." |
| 0.9409 | "The rollout of the new search index is in progress." ↔ "... is finished." |
| 0.9400 | "The migration will run on Saturday." ↔ "The migration ran on Saturday." |
| 0.9398 | "The orders database failover is still in progress." ↔ "... is complete." |
| 0.9337 | "The upgrade of postgres 15 to 16 is pending." ↔ "... is done." |
| 0.8925 | **"The orders database failover is still pending." ↔ "The orders database failover completed successfully." — THE IT PAIR: in the reconcile band [0.80, 0.90), dedup does NOT merge it** |
| 0.8584 | "The deployment to production is pending." ↔ "... completed today." |
| 0.8737 | "The database migration is pending." ↔ "... completed today." |
| 0.8095 | "The deploy is pending." ↔ "The deploy completed today." |

WHAT IT PROVES: today's dedup (merge at cosine >= 0.90) FUSES
contradicting status-flip statements (0.90-0.96 band) and the survivor
keeps the STALE claim — `MemoryContradictionDefectIT` executes it through
the real `insertAllDeduped` path: second insert absorbed (merged, never
inserted), `SELECT content FROM memories` = [stale], and search returns
only the false claim. The reconcile band [0.80, 0.90) catches the pairs
just under that line (0.8925 pair) with ONE verdict call.

## 5. TRAPS HIT AND FIXES (all resolved)

1. Wrong directory names: I repeatedly wrote Kotlin sources to
   `dev/lattenero.brain/...` (dots!) instead of `dev/lattenero/brain/...` —
   detekt `InvalidPackageDeclaration`. Move + rmdir the bogus dir.
2. DB-name tags with hyphens -> `ERROR: syntax error at or near "-"` in
   TestDatabase.fresh. Underscores only.
3. `checkAll` is suspend — law tests must wrap in `runBlocking<Unit>`.
4. Hanging-provider law test: `Thread.sleep` fake does NOT trigger
   withTimeout (non-suspending body completes; TimeoutCancellationException
   never delivered) -> fake must `delay()` (suspend), like real HTTP reads.
5. `saveManual` + unique index `(coalesce(source_event_id,''), kind,
   md5(content))`: planting the SAME content twice in one schema violates
   the index -> per-law fixture databases; the identical-restatement test
   goes through `insertAllDeduped` with a DIFFERENT event id instead.
6. detekt: TooGenericExceptionCaught/SwallowedException (catch-Exception) ->
   `runCatching` + explicit TimeoutCancellation/Cancellation handling;
   ReturnCount (2) -> fold expressions; LongParameterList -> NeighborBand /
   WorkerParams grouping; CyclomaticComplexity/LoopWithTooManyJumpStatements
   in Dream.run -> extracted `judgedCluster`; MaxLineLength 120 -> split
   strings/params; MagicNumber -> PREVIEW_CHARS const; UseOrEmpty ->
   `.orEmpty()`.
7. Worker companion corruption: a careless python replace closed the
   companion early -> "Const 'val' only allowed..." + stray brace; repaired
   by re-merging the companion block.
8. `git commit -m` with backticks in the message: zsh executed
   `reconciled` ("command not found") and swallowed the word -> messages now
   written to /tmp file + `git commit -F`; amended the pushed commit with
   `--force-with-lease` (own agent branch only).
9. EvalChatSuitesIT timeouts: environmental (shared ollama queue; the
   parallel agent's ITs + my own runs). See STATE section.

## 6. ADDITIVE API SURFACE (final, after unit 5 lands)

- `memory/Verdict.kt`: `Verdict` (sealed interface), `VerdictAttempt`
  (Decided/Skipped), `VerdictParser` (total parse), `VerdictJudge` (one
  call/pair, schema in prompt).
- `memory/Reconcile.kt`: `MemoryReconcile` (BAND_LOW 0.80, BAND_HIGH =
  MemoryDedup.SIMILARITY, band(halfLifeDays), decide(), order()),
  `ReconcileAction`, `ReconciledPair`.
- `memory/MemoryReconciler.kt`: `MemoryReconciler(store, judge)` +
  `ReconcileReport` (@Serializable, `changes`).
- `memory/Dream.kt`: `Dream.clusters` (pure), `DreamPass(store, judge).run(
  halfLifeDays, limit, dryRun)`, `DreamReport`/`DreamClusterReport`
  (@Serializable), `NEIGHBOR_SIMILARITY`, `DEFAULT_LIMIT = 100`.
- `MemoryStore`: `InsertReport.insertedIds`, `NeighborRow` (+cosine),
  `NeighborBand`, `invalidate(id): Boolean`, `mergeInto(old, new)`,
  `nearestNeighbor(embedding, selfId, embedModel, band)`,
  `rowAsNeighbor(id)`, `embeddingOf(id)`, `isInvalidated(id)`,
  `dreamRows(halfLifeDays, limit)`, `countSuperseded()`,
  `MemoryStatus.superseded`; SEARCH alive-CTE + findDuplicate + dedupRows
  all exclude invalid_at NOT NULL.
- `MemoryWorker`: optional `reconciler` + `WorkerParams(batch, maxAttempts,
  halfLifeDays)`; `WorkerReport.reconciled`; `TurnOutcome.Stored.reconciled`;
  `status()` fills `superseded`.
- `MemoryMetrics.superseded` + `brain_memory_superseded` gauge.
- Migration `0005_memory_invalidation.sql` (+ index.txt).
- CLI: `agent-brain dream [--limit N] [--dry-run]` (USAGE updated); Brain
  wires `verdictJudge` + `memoryReconciler` into `memoryWorker`.

## 7. KNOWN LIMITS

- Cross-lingual contradictions are invisible to embedding dedup AND to the
  reconcile band (cos ~0.65) — documented dedup limitation, unchanged.
- Reconcile judges ONE pair per new row (its nearest band neighbor); if the
  seed gets invalidated, sibling band members of the same cluster are
  skipped (no re-seeding) — dream covers stored memory later.
- Dream v1 judges member-vs-SEED pairs only (not all C(m,2) pairs);
  Distinct pairs re-judge on every run (harmless: no state change).
- No automatic dream cadence (by design, OFF by default).
- `EvalChatSuitesIT` flakiness under ollama contention is pre-existing.


---

# HISTORICAL — B4 Memory Graph Handoff (agent/b4-memory-graph; merged to master at b90a9c2)

# B4 Memory Graph — Handoff (agent/b4-memory-graph)

State: UNIT COMPLETE, all 4 commits pushed, full gate green.
Worktree: `/Users/lattenero/Projects/.worktrees/agent-brain-b4-graph` (branch `agent/b4-memory-graph`, from master = 2d90e2f).
Everything below is what a fresh agent needs to resume with zero re-derivation.

## 1. Files

### Read (the ones that matter)
- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryStore.kt` — the memories store. Contains `LIFE_WEIGHT_SQL` (public const in companion: the B3 forgetting expression `m.salience * power(2.0, -extract(epoch FROM (now() - COALESCE(m.last_accessed, m.created_at))) / (86400.0 * p.half_life))`, needs aliases `m` and a `p` CTE with `half_life`), the `SEARCH` SQL twin of RankKey, `MemoryStatus` (@KapTypeSafe, lives HERE not in MemoryWorker.kt), dedup-aware `insertAllDeduped`.
- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryDedup.kt` — `MemoryDedup.SIMILARITY = 0.90` (the dedup threshold constant; the band ceiling), evidence docstring (related pairs measure 0.60..0.88, duplicates 0.91+), pure `plan` + store `applyDedup` pattern (the model for pure-core + IO-edge).
- `core/src/main/kotlin/dev/lattenero/brain/memory/Salience.kt` — `Salience.decay/weight` and `RankKey(rrf, weight, id) : Comparable` ordered `rrf DESC, weight DESC, id ASC`.
- `core/src/main/kotlin/dev/lattenero/brain/memory/Lifecycle.kt` — B3: `LifecycleState`, `COOLING_FLOOR=0.5`, `FORGET_FLOOR=0.125`, `Params(halfLifeDays, forgetFloor)`, `classify`, `lifeWeight`.
- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` — worker bins (plan C1), `MemoryKind` ADT pattern, `MemoryService` (search/save/status via kap builder), `MemorySearchRequest/Response`.
- `core/src/main/kotlin/dev/lattenero/brain/Main.kt` — CLI dispatch (top-level commands, `memory` subcommands, USAGE string const, `withBrain(config)`).
- `core/src/main/kotlin/dev/lattenero/brain/Brain.kt` — composition root, all wiring by hand.
- `core/src/main/kotlin/dev/lattenero/brain/http/Metrics.kt` + `MetricsInventory.kt` — metrics = PURE projection of the JSON snapshots (`MemoryMetrics.of(status)`); families via `family(...)` for one sample or `MetricFamily(name, help, type, List<String>)` for many; baselines are PER-FILE so new files must be detekt-clean.
- `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` — migrations = SQL files in `core/src/main/resources/migrations/` named `NNNN_snake_case.sql`, REGISTERED in `migrations/index.txt` (a unit test keeps index and dir in sync — forgetting to register is a red build), applied under `pg_advisory_lock`, one transaction each; `__EMBED_DIM__` is the only placeholder.
- `core/src/main/kotlin/dev/lattenero/brain/db/Database.kt` — `withConnection` is a MEMBER of Database (suspend, Loom dispatcher); `inTransaction` in `Transaction.kt`.
- `core/src/test/kotlin/dev/lattenero/brain/TestEnv.kt` — `TestDatabase.fresh(tag)` creates `agent_brain_test_<tag>_<8-random-hex>` PER CALL and drops at JVM exit: **per-run unique DB names already exist; parallel IT runs cannot collide** (tags must be underscore-only — see traps). `needPostgres()`, `needOllama()`, `needOllamaModel(name)`.
- `core/src/test/kotlin/dev/lattenero/brain/memory/MemorySearchEquivalenceIT.kt` — the IT model: `@Tag("integration")`, `@TestInstance(PER_CLASS)`, fixture-per-test, backdate forgotten rows by setting BOTH `created_at` AND `last_accessed` 400 days back.
- `core/src/test/kotlin/dev/lattenero/brain/memory/LifecycleLawsTest.kt` / `SalienceLawsTest.kt` — law test shape: plain JUnit `@Test` + `checkAll` inside `runBlocking`, assertj assertions.
- `core/src/test/kotlin/dev/lattenero/brain/memory/TestVectors.kt` — `at(c, axis)` = unit vector with EXACT cosine c against axis (lives in the axis/axis+1 plane), `randomUnit(dim, seed)`.
- `core/src/test/kotlin/dev/lattenero/brain/memory/MemoryWorkerIT.kt` — worker IT pattern: recorded bus resource `bus/sample-bot-publisher.jsonl`, `BusPipeline.of(config, db).evalGraph().drain()`, `FakeChatProvider { _, _ -> FakeChatProvider.json(...) }`, FakeProvider BOW.
- `core/build.gradle.kts` — default `test` task runs unit + integration (only tags `bb-local`, `bb-real` excluded); toolchain Corretto 25, bytecode 21; `detektAll` aggregates `:core:detekt|detektMain` and `:brain-mcp:...`.

### Created
- `core/src/main/resources/migrations/0005_memory_edges.sql` + index.txt entry — `memory_edges(src_id bigint REFERENCES memories(id) ON DELETE CASCADE, dst_id likewise, kind text CHECK (kind IN ('related','cooccur','temporal')), weight double precision, created_at timestamptz default now(), CHECK (src_id <> dst_id), UNIQUE (src_id, dst_id, kind))` + index `memory_edges_dst ON (dst_id)`. The unique index's leading src_id column serves src lookups.
- `core/src/main/kotlin/dev/lattenero/brain/memory/Edges.kt` — PURE core: `EdgeKind(wire)` enum (RELATED/COOCUR/TEMPORAL), `MemoryEdge`, `MemoryEdges` object (constants `RELATED_FLOOR=0.70`, `MAX_RELATED=5`, `TEMPORAL_WINDOW_SECONDS=600.0`, `PLAIN_WEIGHT=1.0`, `TRAVERSAL_PER_HIT=3`, `TRAVERSAL_MAX=8`; builders `related(sourceId, neighbors, ceiling=MemoryDedup.SIMILARITY)`, `cooccur(ids)`, `temporalChain(rows, windowSeconds)`), `Neighbor`, `DatedMemory`, `NeighborCandidate`, `TraversalKey` (edgeWeight DESC, relevance DESC w/ null=-inf, salienceWeight DESC, id ASC), `RelatedMemory` (@Serializable wire type: id, kind, content, sessionId, createdAt, viaId, edgeKind, weight), top-level `traverse(hits, candidates, perHit, globalMax)` (pure fold: filter hits/self, sort by TraversalKey, dedup by memory id keeping best rank, per-via cap, global cap).
- `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryGraphStore.kt` — IO edge: `insertEdges` (batched INSERT ON CONFLICT DO NOTHING), `neighborsInBand` (same model, band, alive, top-5), `rows(ids)` (GraphRow), `turnMemoryIds` (alive, sid+turn), `temporalPair` (LATERAL newest earlier alive row in session; row comparison `(created_at, id) < (cur.created_at, cur.id)`), `relatedCandidates` (traversal fetch: edges touching hits from EITHER side, alive both ends, relevance = cosine to query CASE WHEN same embed_model else NULL, salienceWeight = SQL twin of Salience.weight over created_at), `countByKind()`. Also `internal const ALIVE_CTE` (B3 forgetting exclusion shared with EdgeBackfill) and `internal fun PreparedStatement.bind(conn, vararg values)` (positional binder — the MagicNumber kill; supports Int/Long/Double/String/PGvector/List<*> as bigint array). `EdgeBackfillReport` (@Serializable, scanned/related/cooccur/temporal + total).
- `core/src/main/kotlin/dev/lattenero/brain/memory/EdgeBackfill.kt` — one-shot backfill class (separate from the store to keep TooManyFunctions down): `backfill(halfLifeDays, batchSize=200)` — related per id-paginated batch (INSERT..WITH..SELECT with row_number cap per src), cooccur + temporal as whole-table set statements; all `ON CONFLICT DO NOTHING` = idempotent.
- `core/src/main/kotlin/dev/lattenero/brain/memory/EdgeBuilder.kt` — `EdgeOutcome` sealed (Built(related, cooccur, temporal)+total / Failed(reason)); `EdgeBuilder(graph, halfLifeDays)` with `forTurn(sessionId, turnId, insertedIds)` and `forMemory(id)`; `guard` = `runCatching{}.fold(...)` — NEVER throws (honest-degrade law; runCatching instead of catch(Exception) because detekt TooGenericExceptionCaught is baselined per-file).
- `core/src/test/kotlin/dev/lattenero/brain/memory/MemoryEdgesLawsTest.kt` — 9 laws (band incl/excl boundaries, capped best-first view, no self-loops in any builder, once-per-pair clique, chain-not-clique + exact-window boundary, symmetric reachability, deterministic traversal ranking, caps, hits never neighbors).
- `core/src/test/kotlin/dev/lattenero/brain/memory/MemoryEdgesIT.kt` — 4 ITs (see §4).

### Modified (all additive)
- `MemoryStore.kt` (+22/-16 net small): `InsertReport` gains `insertedIds: List<Long> = emptyList()`; `insertAllDeduped` collects ids; `insertRow` now `INSERT ... ON CONFLICT DO NOTHING RETURNING id` returning Long (expression-bodied); `MemoryStatus` gains `edgesByKind: Map<String, Long> = emptyMap()`.
- `MemoryWorker.kt`: `TurnOutcome.Stored` gains `insertedIds`; `MemoryWorker` constructor gains trailing `edges: EdgeBuilder? = null`; `processTurn` calls `buildEdges` after the attempt's Right (log-only degrade); `MemorySearchResponse` gains `related: List<RelatedMemory> = emptyList()`; `MemoryService` gains trailing `graph: MemoryGraphStore? = null, edges: EdgeBuilder? = null`; `search` builds related via graph fetch + pure traverse; `save` calls `buildEdges(id)` (same degrade); `status()` adds `.with { edgesByKind from graph?.countByKind().orEmpty() }`.
- `Brain.kt`: `memoryGraph`, `edgeBuilder = EdgeBuilder(memoryGraph, config.memoryHalfLifeDays)`, `edgeBackfill = EdgeBackfill(db)`; MemoryWorker gets `edges = edgeBuilder`; MemoryService wired with named args (line-length).
- `Main.kt`: USAGE line + top-level command `backfill-edges` -> `backfillEdges()` (withBrain, migrator.apply, EdgeBackfillReport JSON).
- `http/Metrics.kt`: `MemoryMetrics` gains `edgesByKind: Map<String, Long> = emptyMap()` + mapping.
- `http/MetricsInventory.kt`: `brain_memory_edges_total` gauge, `LABEL_KIND="kind"`, `edgeSamples` = one sample per `EdgeKind.entries` (zero when absent — measured fact, breaker one-hot reasoning); import EdgeKind.
- `core/src/test/.../http/MetricsProjectionTest.kt`: expected exposition gains the edges family (fixture with non-zero values) + a new zero-default test. This test pins the EXACT metrics text — any new family must update it.

## 2. Design decisions and why
1. **No Neo4j / no new runtime** (brief): everything Postgres-native; edges symmetric at traversal time (JOIN on `src = hit OR dst = hit`), one stored row per unordered pair.
2. **DB CHECK constraints as the mechanical law** (`kind IN (...)`, `src_id <> dst_id`): the ADT pins the Kotlin side, the CHECK pins every writer. Slight addition over the brief's schema (brief listed only UNIQUE + dst index) — justified by the no-self-loop LAW.
3. **Band ceiling reuses `MemoryDedup.SIMILARITY`** (brief mandate): dedup owns >= 0.90 (merge), the graph walks [0.70, 0.90). Related weight = the pgvector cosine itself.
4. **Pure core + IO edge** (house law): builders and traversal are total functions over fetched facts; SQL only fetches/ranks candidates; ranking decided in Kotlin by `TraversalKey`.
5. **Traversal ranking**: edge weight DOMINANT, then the hit law (relevance first, salience-weight tie-break), id last — matches the brief "(edge weight DESC, then the same RankKey law)". Neighbor relevance = `1 - (embedding <=> qv)` from the query vector, NULL (reads as -inf) when the neighbor's embed_model differs from the query's (ADR-004: cross-model cosine is meaningless).
6. **Forgotten exclusion lives in ONE shared CTE** (`ALIVE_CTE` = `MemoryStore.LIFE_WEIGHT_SQL >= forget_floor`) used by EVERY graph query (band neighbors, turn cliques, temporal pair, traversal fetch, all three backfill statements) — no restatement, exactly B3's "the report and the retriever can never disagree".
7. **Error-as-value degrade**: `EdgeBuilder` catches everything via `runCatching` into `EdgeOutcome.Failed`; the worker/service log and continue. Edge building runs AFTER the memory write (never in the attempt path, so a graph failure can never be classified ProviderDown or defer a turn).
8. **Edge ids via `RETURNING id`**: `InsertReport.insertedIds` (additive default) instead of re-deriving rows by (event, kind, content) — exact, no superset edges on replay.
9. **Backfill** separate class (`EdgeBackfill`), related batched 200 ids/step (vector scan is the expensive leg), cooccur/temporal as set statements (store is small, joins bounded by turn size/session chains). Idempotent via ON CONFLICT.
10. **`related` field semantics**: `MemorySearchResponse.related` default `emptyList()`; responseJson has `encodeDefaults = true` so the field ALWAYS serializes (empty list when unwired) — still additive; MCP facade untouched, passes JSON through.
11. **Gauge** `brain_memory_edges_total{kind=...}` one sample per known kind even at zero (closed ADT = measured fact, breaker one-hot precedent). TYPE_GAUGE (row counts can go down via CASCADE) despite the `_total` suffix — name per the brief.
12. **Deviations from brief, all minor**: (a) CHECK(src<>dst) added; (b) traversal candidates also carry query relevance (the brief's "relevance first" needed a concrete meaning — cosine to the query, same as the vector leg); (c) manual saves (`/memory/save`) also earn edges (the brief said "for each NEW memory on insert" — manual save is an insert); (d) temporal backfill chains ALIVE consecutive rows per session (a forgotten row in the middle splits the chain — law-consistent).

## 3. Codebase discoveries
- Migrations: files + `index.txt`, validated by `MigrationsTest`; `__EMBED_DIM__` only placeholder; advisory-locked; `brain_meta.embed_dim` guards provider mismatches.
- `MemoryStatus` is defined in **MemoryStore.kt** (not MemoryWorker.kt) and built with the kap builder `kap(::MemoryStatus).with { field from suspendCall }.evalGraph()` — a field with a Kotlin default is fine (decode-robustness) as long as it's wired with `.with`.
- detekt: `buildUponDefaultConfig` + per-module baselines (`detekt-baseline.xml`), findings keyed Rule:File:signature — NEW files get NO baseline protection: must be clean. Active defaults that bit: MagicNumber (JDBC param indices, ResultSet indices, 1000.0), TooManyFunctions (11 per class), LoopWithTooManyJumpStatements (max 1), ReturnCount (max 2), MaxLineLength 120, TooGenericExceptionCaught. `responseJson = Json { prettyPrint = true; encodeDefaults = true }`.
- Tests: plain JUnit, kotest-property only via `checkAll` (no kotest engine). `TestMethodsReturnUnitGate` scans compiled test classes for non-void @Test methods — every test must `= runBlocking<Unit> { ... }` or return void.
- ITs run in the DEFAULT `test` task (only bb tags excluded). `TestDatabase.fresh(tag)` names DBs `agent_brain_test_<tag>_<8hex>` per call → per-run uniqueness (the collision warning was already satisfied; keep tags underscore-only: a hyphen breaks unquoted `CREATE DATABASE`).
- Postgres lives in container `agent-brain-pg` on port **5433** (image pgvector/pgvector:pg18, user/pass brain/brain); there is an unrelated `pg-base` on 5432. psql is NOT on the host — use `docker exec agent-brain-pg psql -U brain ...`.
- ollama at `localhost:11434`, model `nomic-embed-text` (768 dims) pulled and UP.
- kap builder `.with { x from expr }` requires non-null expressions — `graph?.countByKind().orEmpty()`.

## 4. Exact state
- **Commits pushed (branch agent/b4-memory-graph, all pre-push hooks green, plain push)**:
  1. `b95008f` feat(memory): B4 the memory graph core — EdgeKind ADT, edge laws, traversal ranking (migration 0005 + index.txt + Edges.kt)
  2. `58e4ce0` feat(memory): B4 edge builder + backfill-edges — the graph grows at insert time (MemoryGraphStore, EdgeBackfill, EdgeBuilder, MemoryStore ids, worker hook, Brain wiring, Main CLI)
  3. `43919e5` feat(memory): B4 traversal — /memory/search gains the additive related surface (MemoryService graph wiring, response field, status edgesByKind, gauges, MetricsProjectionTest update)
  4. `d38ce8f` test(memory): B4 the graph's laws and the real-database integration battery (MemoryEdgesLawsTest 9 laws, MemoryEdgesIT 4 ITs)
- **Gate results (real numbers)**: `:core:test` = **326 tests, 0 failed, 6 skipped** (skips are service-gated tiers, expected); `detektAll` (core + brain-mcp, plain + type-resolved) = **BUILD SUCCESSFUL**; `:core:compileKotlin` clean; brain-mcp tests ran as part of the gate (no failures).
- **Uncommitted work**: this HANDOFF.md (about to be committed+pushed). Everything else committed.
- **Next steps if resuming**: nothing left in-scope. Remaining polish options: (a) smoke-run `agent-brain backfill-edges` — DO NOT: it would apply migration 0005 to the production agent_brain DB (deploy territory, owner's call); (b) merge to master is the PARENT's job (review diff, merge, `git worktree remove`, delete branch); (c) optional follow-ups: related-field docs in `docs/`, a dedup-vs-band evidence note.

## 5. Traps hit and fixes (do not rediscover)
1. **Block-bodied Kotlin fun has no implicit return** — `insertRow(...): Long { conn...use { ... } }` → "Missing return statement". Fix: expression body `= conn...use { ... }`.
2. **`withConnection` is a member of `Database`**, NOT an importable `dev.lattenero.brain.db.withConnection` (that import fails; only `inTransaction` is a top-level extension).
3. **detekt MagicNumber fires on JDBC/ResultSet positional indices in NEW files** (baselines are per-file). Fix: a positional `bind(conn, ...)` helper (index arithmetic in ONE place) + read ResultSet columns BY ALIAS; SQL aliases must be explicit when labels would collide (`cur_id`, `prev_id`...).
4. **`family(...)` helper takes ONE sample; `MetricFamily(...)` takes the List** (breaker precedent) — edges family uses MetricFamily directly.
5. **ReturnCount (max 2)** on `MemoryService.related` — merged the two guards into one `if (graph == null || hits.isEmpty())`.
6. **TooManyFunctions (max 11)** on MemoryGraphStore — backfill split into its own class.
7. **detekt TooGenericExceptionCaught** — never `catch (e: Exception)` in new code; use `runCatching` (EvalRunner precedent).
8. **TestDatabase tags with hyphens break `CREATE DATABASE`** ("syntax error at or near '-'") — tags must be `[a-z0-9_]`.
9. **kotest shrinks into duplicate generated ids** — laws generating lists of ids MUST `.distinct()` (duplicate neighbors/hits broke laws 2/6/7) and law 6's symmetric-reachability must be stated as two one-sided traversals (if BOTH endpoints are hits, traverse correctly returns nothing — hits are excluded as neighbors).
10. **assertJ type strictness**: `assertThat(longValue).isEqualTo(intValue)` fails as `expected: 6 but was: 6L` in some overloads — use `.toLong()` on the expected side.
11. **IT measurement**: the ollama pool must be measured against the LIVE model — initial pool measured all pairs out of band; replaced with a measured pool (see §6). Use `assumeTrue` with the measured cosines in the message for honest skips.
12. **now() is transaction-scoped in Postgres** — rows inserted in one transaction share created_at; the temporal pair's row comparison `(created_at, id) < (cur.created_at, cur.id)` relies on the id tie-break (validated live).

## 6. Measured numbers
- Live nomic-embed-text cosines (measured 2026-11-15 via `/api/embed`, the final IT pool): (0,1) "worker extracts facts/summaries" vs "extraction reads turns writes facts/summaries" = **0.8682** (in band); (0,2) vs "worker polls the bus, extracts memories and stores them" = **0.7724** (in band); rejected first pool measured 0.60-0.68 max. Dedup evidence constants: duplicates 0.9123-0.9845, strongest merely-related 0.8789 (MemoryDedup docstring).
- IT results: worker insert path (recorded bus turn, 4 memories) → cooccur **6** = C(4,2), temporal **3** (session chain); backfill fixture (10 rows: 4 band + 1 forgotten + 2 clique + 3 chain) → related **4** (R1<->R2 at exact cosine 0.80 + R3<->R4 at ~0.82), cooccur **1**, temporal **1** (only the 5-min gap inside the 600 s window), forgotten row (400 days old, in-band cosine 0.85 with R1) → **0** edges; second backfill run → **0/0/0** and identical DB edge count.
- Full core suite: **326 tests, 0 failed, 6 skipped** in 2m13s. detektAll: clean in ~8s.
- SQL validation was done on a throwaway DB `b4_sql_scratch` (created via `docker exec agent-brain-pg psql -U brain -d postgres`) — **DROP IT if still around**: `DROP DATABASE b4_sql_scratch;` (not dropped yet at handoff time).
