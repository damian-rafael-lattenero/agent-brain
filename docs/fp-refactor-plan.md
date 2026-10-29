# FP-first refactor plan

Written 2026-10-29. Applies the ecosystem's binding policy — first-class
functional programming, "todo lo bueno de Haskell", KAP as the standard
library — to agent-brain as a refactor of existing code. The policy text
lives in `zcode-bot-kotlin/docs/parity-and-engineering.md` §7; this file
is the concrete plan for THIS repo.

Standing rule: **KAP first.** Any orchestration must be a KAP operator
before it is code here. When KAP lacks the capability, it is implemented
in kap with laws, kap's version is bumped, and only then consumed. This
is the SPEC-5.0 dogfooding contract turned into a development rule.

Baseline (what is already FP-shaped): `EmbedPipeline` is a Kap chain
(`timeoutAsFailure` → `retry` → `withCircuitBreaker` → `traced`), the
`Run @KapTypeSafe` builders, `FileTailer`'s `tailrec` scan logic, pure
decision cores with pinned tests (recall gate, dedup, decay). The
SPEC-5.0 contract already names the four hand-rolled loops to replace.

## R1 — loops become policies

Replace every hand-rolled production loop with `repeat(Schedule)` /
`Process`:

| Loop today | Becomes |
|---|---|
| bus FileTailer's poll loop | `repeat(spaced(...))` under a named `Process` (heartbeat = the offset file) |
| MemoryWorker's loop | `repeat(schedule)` — extraction attempt per `turn.completed` |
| Kafka consumer poll loop | `repeat` with the manual-commit INSIDE the iteration, after the handler |
| bridge loop | `repeat` with the same handler as the tailer (contract test unchanged) |

Exit criteria: `grep -r "while (true)" src/` over production sources is
empty (CI gate); the replay golden (bus replayed twice = identical state)
still passes; every former loop has a restart policy.

## R2 — lifecycles become Resource/bracket

HikariCP pool, Kafka consumer/producer, Ollama/GLM HTTP clients, the MCP
stdio transport: acquire/use/release under `Resource` (release in
NonCancellable), composed with `Resource.zip`. The MCP server's
`Kap.awaiting` park stays (it is already the right shape).

Exit: a test proves release on cancel/failure for each; no `use {}`/
`close()` scattered in business code.

## R3 — pure cores extracted, with laws

Extract and pin as pure modules + kotest-property laws:

- RRF fusion (k=60) — associativity/identity properties of the merge;
- bus dedup by id (`ON CONFLICT DO NOTHING` semantics) — idempotence law;
- memory salience decay `exp(-ln2·age/halfLife)` — monotonicity, zero at
  t=0, half at halfLife;
- extraction schema validation (MIN/MAX caps, per-kind caps) — the three
  failure bins as a pure function `(raw) -> Parsed | Invalid(reason)`;
- Config parsing (env > file > defaults, unknown keys fail) — as a pure
  fold with property tests.

Exit: each module's effects (clock, random, IO) are injected parameters;
property tests green in CI.

## R4 — error channels become Arrow

The extraction pipeline's three bins (invalid → quarantine+reason,
provider down → bump attempts, gave up → quarantine 'gave up') become an
`Either`/`Validated` chain (kap-arrow) instead of exception-driven
branching; the ingest quarantine writes stay at the edge. Same for
provider fallback selection (Ollama → GLM → Fake).

Exit: no exception as control flow outside the IO edge; signatures show
`Either` where accumulation matters; suite green, behavior identical
(pinned tests unchanged).

## R5 — the KAP gaps this uncovers ship as kap versions

Whatever R1-R4 needs that KAP 4.2 lacks lands in kap with laws and a
version bump (candidates, if they appear: a `Schedule` for
"only-once-per-key" debounce, a `Resource`-based server lifecycle, an
`awaiting` variant for Flow). The version table in this repo's version
catalog is bumped in the same change that uses it.

Exit: no local hand-rolled policy; kap's CHANGELOG carries each addition.

## Order and gates

R1 → R2 → R3 → R4, with R5 interleaved as gaps appear. Every phase: full
suite green (tiers 1-3 + black-box where applicable), replay goldens
pass, deployed/stopped cleanly (compose up/down), no phase starts before
the previous is green.
