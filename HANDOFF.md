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
