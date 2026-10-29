# agent-brain — full functional plan

Written 2026-10-29, from the ecosystem-wide audit of that date (kap, the Python
bot, zcode-bot-kotlin, this repo). This file **extends and amends**
`docs/fp-refactor-plan.md`: R1 is already done in code, R4 is amended (see C3),
and everything below is the executable backlog, ordered by dependency. Every
item cites the current code so it can be checked off against reality.

## Standing policies (the laws of this plan)

1. **Pure Haskell style by default** — immutable values, pure functions, HOFs,
   ADTs. Mutable or imperative code only when no alternative exists, with a
   comment saying why, scoped to the smallest possible hole.
2. **KAP first** (the ecosystem doctrine, `zcode-bot-kotlin/docs/parity-and-engineering.md` §7):
   any orchestration — retry, cadence, timeouts, supervision, resources, racing,
   validation, partial failure — must be a KAP operator before it is code here.
   If KAP lacks it, it is implemented **in kap, with laws**, kap's version is
   bumped, and only then consumed. No hand-rolled loop or policy in application
   code.
3. **Pinned behavior never changes**: the replay golden, the quarantine
   behavior, the MCP status envelope and the eval gates are invariants. Every
   phase ships with the full suite green before the next starts.
4. The friction history stays pinned (`core/src/test/.../kapx/KapFindingsTest.kt`:
   F-01 isolation, F-02 timeoutAsFailure; `docs/adr/ADR-007` records why loops
   moved onto KAP).

**State today**: `while (true)` in production sources = **0** (R1 done: all four
loops are `repeat`/`repeatUntil`). The embed chain
(`timeoutAsFailure → retry → withCircuitBreaker → traced`), `traverse`/
`traverseSettled` and the `@KapTypeSafe` builders are the FP baseline. **Unused
on the classpath**: `Process`/`RestartPolicy`/`ProcessHandle`, `Resource`/
`Bracket`, and all of kap-arrow — the whole lifecycle and error-channel half of
kap 4.2.0 (the 4.2.0 bump commit says it: "no Process usage yet").

## Map to fp-refactor-plan.md

| Old | New | State |
|---|---|---|
| R1 loops → policies | — | **done in code**; only its CI gate is missing (F1) |
| R2 lifecycles → Resource | **Phase A** | not started |
| R3 pure cores + laws | **Phase E** | partial |
| R4 error channels → Arrow | **Phase C** (amended: C3) | not started |
| R5 gaps ship as kap | **Phase G** | pending kap milestones |

---

## Phase F — CI gates (do this FIRST: minutes, protects everything else)

- **F1** — the R1 exit criterion, currently unenforced: add a CI step that fails
  on `grep -rn "while (true)" core/src/main brain-mcp/src/main`. The loops are
  clean today; the gate keeps them clean.
- **F2** — `runBlocking` policy: allowed only at the world's edge
  (`core/Main.kt`, `EvalRunner`, `brain-mcp/Main.kt`); start as an
  informational grep, tighten later.
- **F3** — property-law suites (Phase E) join the same workflow as the eval gate.
- **G5 (pull forward)** — resolve kap from Maven Central, not `mavenLocal`
  (`settings.gradle.kts:13`): kap 4.2.0 has been on Central since 2026-10-29.
  Removes machine friction; nothing else waits on it.

## Phase B — transactions and effect brackets (smallest phase, unblocks A)

- **B1** — `Database.inTransaction` built on kap `Bracket`: collapse the three
  hand copies of `autoCommit=false / commit / rollback / restore`
  — `bus/PgBusIngest.kt:26-48`, `rag/RagStore.kt:70-96` (`replaceChunks`),
  `db/Migrator.kt:108-125`.
  Law (test): commit on success, rollback on failure, autocommit restored even
  on cancel — inject a failing statement and assert all three.
- **B2** — `EvalRunner`'s create/drop database pair (`eval/EvalRunner.kt:42-52`)
  as a `Resource`; its raw `DriverManager` connections (`:50-52`) go through the
  same bracket.
- **B3** (optional) — the stdout guard (`System.setOut`, `brain-mcp/Main.kt:131-132`
  restored at `:201`) is an honest hatch; bracket it with `Resource` for symmetry.

Exit: one `inTransaction` definition, three call sites; no bare `close()` in
business code paths.

## Phase A — lifecycle: `Process` + `Resource.zip` (the big win; algebra already shipped)

- **A1** — `Brain` as `Resource.zip(pool, httpClient, kafka, ...)` replacing the
  manual reverse-order `close()` (`core/Brain.kt:64-70`).
- **A2** — the three long-lived loops become named `Process`es under one scope:
  - bus tailer: `bus/FileTailer.kt:97-100` (`Kap{poll}.recover{0}.repeat(spaced)`)
    → `Process` with `RestartPolicy` + heartbeat (the offset file is the floor);
  - Kafka consumer: `bus/Kafka.kt:187-207`;
  - memory worker: `memory/MemoryWorker.kt:83-86`.
  `serve`'s hand-rolled scope (`core/Main.kt:113-128`: `CoroutineScope(SupervisorJob())`,
  `launch`, `finally { cancel() }`) becomes the process scope. This fixes a real
  hole: today a crash *between* polls is only survivable if it happens to throw
  inside the `recover{0}`; a Process restarts on any failure, by law.
- **A3** — observability from the handle: `ProcessHandle.restarts`/
  `heartbeatAge()` replace the AtomicLong liveness counters that fake it
  (`bus/FileTailer.kt:48-54,70-72`, `bus/PgBusIngest.kt:7-17`,
  `bus/Kafka.kt:129-137`). True business counters stay plain until kap `Ref`
  (Phase G).
- **A4** — brain-mcp's shutdown-hook thread (`brain-mcp/Main.kt:148`) →
  `Kap.awaiting { resume, cancel -> ... }` (the MCP park at `:207-212` is
  already the right shape and stays).
- **A5** — `Indexer.embedBatchSize` stops being post-construction mutation:
  constructor parameter instead of `.also { }` (`rag/Indexer.kt:140`,
  assigned from `Brain.kt:52`). (Purity fix; belongs with this phase's wiring.)

Exit: a test proves release on cancel/failure for each resource; a kill test
restarts a Process body mid-cycle and the tailer resumes from the persisted
offset floor; no `var job: Job?` start/stop guards remain
(`bus/BusPipeline.kt:25-41` is the pattern to delete).

## Phase C — error channels as ADTs + Arrow (R4, amended)

- **C1** — extraction failure taxonomy as a **sealed ADT**
  (`Invalid(reason) | ProviderDown | GaveUp`), and `MemoryWorker.processOnce`
  (`memory/MemoryWorker.kt:44-78`: `for` + five `var` counters + `try/catch`
  branching) becomes `pending.traverseSettled { turn -> ... }` with the three
  bins as `Either`/`Validated` (kap-arrow). The report becomes a fold over
  `Result`s; exception-as-control-flow leaves the business logic. Quarantine
  writes stay at the edge (unchanged behavior).
- **C2** — the early-abort (`if (e is CircuitBreakerOpenException) break`,
  `MemoryWorker.kt:76`) is a **supervision policy without a home yet**: document
  it as the interim, move it to kap `Supervisor` when it lands (Phase G).
- **C3** — **amendment to fp-refactor-plan R4**: provider fallback selection
  (Ollama → GLM → Fake) does **NOT** become a runtime `Either` chain. ADR-004:
  `embed_dim` is a database property; runtime fallback across embedding models
  would corrupt the index. Selection stays the config-time pure `when`
  (`embeddings/EmbeddingProvider.kt:82-87`). The old R4 sentence is superseded
  by this item.
- **C4** — brain-mcp's blocking HTTP inside suspend tool handlers
  (`brain-mcp/Main.kt:112-124`, `java.net.http.HttpClient.send`) →
  `Kap { ... }.timeoutAsFailure(...)` on `Dispatchers.IO`; deduplicate the
  second HTTP transport next to Brain's ktor client.

Exit: no exception as control flow outside the IO edge; bin shapes visible in
signatures; pinned quarantine/eval tests identical.

## Phase D — state as values (interim until KapStream / Ref)

- **D1** — every drain stops mutating closures: the repeated body **returns**
  its state and the loop terminates on that value —
  `bus/Kafka.kt:104-110` (`var total`), `bus/Kafka.kt:187-196` (`var total` +
  `var quiet`), `bus/BusPipeline.kt:49-53` (`var total`). `repeatWhile`/
  `repeatUntil` over the returned `(count, quiet)`.
- **D2** — the quiet-window termination is a hand-rolled **stateful schedule**;
  the lawful home is a kap `Schedule` addition (R5 candidate: debounce /
  only-once-per-key) — implement in kap when D1 makes the shape explicit.
- **D3** — final homes: drains → `KapStream` folding stages; the tailer → a
  polling stream source owning its offset (both Phase G).

Exit: `var` inside repeated bodies = 0; drain behavior pinned by tests unchanged.

## Phase E — pure cores with property laws (R3)

- **E1** — add `kotest-property` (only `kap-kotest` exists today).
- **E2** — RRF fusion (k=60): pure module + laws (bounded score, rank
  monotonicity, no-recall ⇒ no-score). It currently lives only inside SQL
  (`memory/MemoryStore.kt:238-261`, `rag/RagStore`); extract the fusion, pin
  SQL-vs-pure equivalence on fixtures.
- **E3** — salience decay `exp(-ln2·age/halfLife)`: monotone decreasing, `1` at
  t=0, exactly `0.5` at `halfLife`.
- **E4** — ingest dedup idempotence law: ingesting a batch twice == once
  (against the real SQL with a disposable schema; the replay golden already
  points at this — make the law explicit).
- **E5** — extraction validation bins as a pure function
  `(raw) -> Parsed | Invalid(reason)` with property tests over the caps
  (`MemoryExtractor.parse` is already pure — keep pushing the boundary).
- **E6** — `Config.load` fold laws: env > file > defaults; unknown keys fail.
- **E7** — `EvalRunner` purity: inject the clock (`System.currentTimeMillis`
  at `eval/EvalRunner.kt:59,99` → `TimeSource`), bracket the temp corpus writes.

Exit: property tests green in CI; every effect (clock, random, IO) is an
injected parameter.

## Phase G — kap unlocks (implemented IN kap, with laws, then consumed here)

| Gap | Consumer here (evidence) | Kap milestone |
|---|---|---|
| `Supervisor` (OneForOne/AllForOne) | batch abort on open breaker (C2); process group liveness in `serve` | SPEC-5.0 5.0 |
| `KapStream` (sources own offsets; fold stages) | drains (D1-D3); tailer as stream | SPEC-5.0 5.0 |
| `Ref` thin wrapper | remaining monotone counters (A3 leftovers) | SPEC-5.0 5.0 |
| `Resource`-based server lifecycle (SIGTERM → graceful stop) | `http/Server.kt:216-222` blocks forever; `serve`'s `finally` never runs | R5 candidate — new kap-kap-ktor addition |
| Debounce / only-once-per-key `Schedule` | quiet-window termination (D2) | R5 candidate |
| **File-effect laws** (atomic write tmp+fsync+rename, flock-with-deadline, mtime-as-clock) | new SPEC-5.0 item from the 2026-10-29 audit (not yet written there) | propose to SPEC-5.0 |

Rule: the version catalog bump lands in the same change that consumes the new
operator; kap's CHANGELOG carries each addition.

## Order

**F → B → A → C → D → E**, with G interleaved as kap milestones ship.
Every phase: full suite + replay goldens + `compose up/down` green before the
next begins.

## What does NOT change (invariants)

- The bot, the fork, and the sidecar contracts are untouched (their interfaces
  are files, by design).
- Ingest dedup stays in SQL (its law gets a test, not a rewrite).
- Embedding selection stays config-time (ADR-004).
- The replay golden, the MCP status envelope, the eval gates and their
  thresholds.
