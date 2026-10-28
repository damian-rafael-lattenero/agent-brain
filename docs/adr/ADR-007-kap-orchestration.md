# ADR-007: KAP for orchestration, where there are phases or policies

**Status:** accepted (2026-10-28), updated the same day for KAP 4.1.0

## Context

The author maintains KAP (`io.github.damian-rafael-lattenero:kap-*`, 4.0.0 on
Maven Central): a lazy, type-safe orchestration DSL over coroutines with
parallel phases, retry schedules, timeouts, circuit breakers and tracing. The
indexing pipeline (upsert, chunk, embed in batches against a flaky remote
model, store) and the memory extraction (LLM calls with timeouts and
breakers) are exactly the shapes it was built for. Using it here is also the
first real-world exercise of its API by a system that is not its own test
suite.

## Decision

- KAP is used where a unit of work has **two or more phases or a resilience
  policy**: `EmbedPipeline` (bounded-parallel batches, timeout, retry with
  jittered exponential backoff, circuit breaker, tracing) and `Indexer` (per
  document graph under `traverseSettled`, so one failing document is a
  report line, not a crash). KSP builders are not used yet.
- Plain coroutines stay where there is a long-lived loop or a single call:
  the bus tailer, the migrator, the HTTP layer, the future Kafka consume loop
  and MCP stdio loop (KAP graphs are request-scoped and one-shot).
- Findings about the library are pinned by tests in `kapx/KapFindingsTest`
  and worked around in `kapx/`, never patched silently. When a KAP upgrade
  makes a pinned test fail, the workaround goes away.

## Typed builders (4.1.0)

`@KapTypeSafe` + `kap(::T)` is used wherever a result has independent parts:
`Checks` (db probe ∥ embed probe), `RagStats` (three counts), `MemoryStatus`
(five counts ∥ three values), `BusPipeline.Probe` (file size ∥ db count) and
`Indexer.Run` (indexing ∥ pruning). Value-dependent steps (upsert → embed →
store) stay on `andThen`, which is the right tool for them.

## Findings in 4.0.0 (fed back to the library, fixed in 4.1.0)

All four are specified with reproductions in KAP's
`SPEC-4.1.0-agent-brain-findings.md` and fixed in 4.1.0 (consumed from
`mavenLocal` until the Central release is tagged). `kapx/KapFindingsTest` now
pins them as FIXED. Two more surfaced while adopting the builders: the plain
`kap(::T)` entry was never generated for classes inside a package (F-09), and
nested annotated classes generated code that did not compile (F-11).


1. **A `timeout` placed before `retry` is never retried.** `retry` rethrows
   `CancellationException` by law and `TimeoutCancellationException` is one.
   The fallback overload `timeout(d, Kap.failed(...))` converts the timeout
   into an ordinary failure first; `EmbedPipeline` does that and a
   virtual-time test proves the retry happens. 4.1.0 ships `timeoutAsFailure`.
2. **`traverse` / `with` launch their children on the caller's scope**, so a
   failing grandchild fails the enclosing coroutine through structured
   concurrency. `traverseSettled`, `settled` and `recover` therefore cannot
   contain failures of nested parallel graphs: `awaitAll` rethrows even
   though `runCatching` produced a `Result`. This contradicts the CONVERT
   discipline the library documents. 4.1.0 gives every CONVERT and RETRY
   operator its own scope boundary (the Isolation law); the local
   `isolated()` workaround is gone.
3. Smaller: `Schedule` instances may hold state, so the retry takes a
   factory (documented footgun, honoured here); the `CircuitBreaker` exposes
   less than its CHANGELOG claims (only `currentState`), which is enough.

## Consequences

- The platform doubles as KAP's integration test; the two findings above are
  concrete, reproducible issues to open upstream with the pinned tests as
  repro.
- Dependencies: `kap-core`, `kap-resilience` at runtime, `kap-kotest` in tests
  (the `shouldProveParallel` matcher turns "it runs in parallel" into an
  assertion under virtual time).
