# Memory ranking fix: relevance dominant, reinforcement a tie-breaker

**Date:** 2026-11-08 · **Branch:** `agent/ranking-relevance`
**Evidence:** live production `memories` (127.0.0.1:5433/agent_brain), read-only
(`MemoryRankingEvidenceIT`, session forced `default_transaction_read_only = on`; SELECTs and three
ollama embeddings, nothing written).

## The defect

`MemoryStore.SEARCH` ranked by the single product
`score = rrf × salience × 2^(−age/half_life)` (`ORDER BY score DESC`). Multiplying reinforcement
(salience, inflated by every dedup merge) into the ranking let old salience-3.0+ veterans beat
genuinely relevant fresh matches. Measured in production on 2026-11-08: the query
"functional programming pure functions denotational design" returned an irrelevant 0.052-score
veteran ("Feature A background is removed") FIRST and the relevant match LAST at 0.0289. The owner
had flagged the same stale-top-3 problem on 3/11 ("the dedup SURVIVORS keep winning the ranking
even when irrelevant").

## The new algebra

Lexicographic (`RankKey` in `core/src/main/kotlin/dev/lattenero/brain/memory/Salience.kt`,
executable twin in `MemoryStore.SEARCH`):

```
ORDER BY rrf DESC, salience × 2^(−age/half_life) DESC, id ASC
```

Relevance (the fused RRF) ORDERS; reinforcement tempered by decay only BREAKS TIES; id is the
deterministic last word. Why lexicographic and not a nudged product (`rrf × (1 + k·log(1+salience))
× decay`): RRF scores are quantized sums of `1/(60+rank)` from two legs, so distinct realizable
values can sit ~0.1% apart — any nudge strong enough to matter inverts LAW 1 (a strictly better
rrf losing), and any k weak enough to honor LAW 1 is dead code. Lexicographic satisfies all three
pinned laws exactly, with no constant to justify. Ties are real (rows recalled at the same rank of
one leg score identically) and are exactly where reinforcement belongs.

Laws pinned by property tests in `SalienceLawsTest`:

- **LAW 1 relevance dominance** — `law 1 relevance dominance - strictly better rrf ranks first for any salience and any age`: better rrf wins for ANY salience (0.05..20), ANY age (0..200 half-lives), any ids.
- **LAW 2 reinforcement still matters** — `law 2 reinforcement breaks ties - among equal rrf the higher weight ranks first` (+ a composed 3.0-vs-1.0 variant).
- **LAW 3 monotonicity** — `law 3 monotonicity - raising rrf or raising salience never lowers a hit's rank`.

The SQL twin stays pinned equivalent on real Postgres fixtures in `MemorySearchEquivalenceIT`
(`score` = pure fusion, `weight` = pure salience × decay, returned order = pure `RankKey` order).
`Memory.score` now carries the fused relevance; the new `Memory.weight` carries the tie-break.

## Evidence: the three queries, old ranking vs new (k = 5, live memories, 2026-11-08)

The live store is young (ages in days), so decay moves little and the defect's driver is pure
salience inflation. "old rank −" = the row was not in the old top-5 at all.

### 1. "functional programming pure functions denotational design" — the measured defect query

OLD order (score = rrf × salience × decay):

| old # | id | kind | old score | rrf | salience | age (d) | weight | content |
|---|----|------|-----------|-----|----------|---------|--------|---------|
| 1 | 798 | fact | 0.0614 | 0.0154 | 4.0 | 0.08 | 3.9928 | "looking for a way to program with good functional programm..." |
| 2 | 796 | fact | 0.0468 | 0.0156 | 3.0 | 0.08 | 2.9946 | "asking about functional programming practices and Nu comma..." |
| 3 | 797 | fact | 0.0447 | 0.0149 | 3.0 | 0.08 | 2.9946 | "seeking information about the memories and the corpus RAG..." |
| 4 | 400 | fact | 0.0393 | 0.0137 | 3.0 | 1.93 | 2.8694 | "plans to provide design questions that change the implementat..." |
| 5 | 793 | fact | 0.0384 | 0.0128 | 3.0 | 0.08 | 2.9946 | "chose to use Kotlin for the FP code" |

NEW order (rrf DESC, weight DESC, id):

| new # | id | kind | rrf | weight | salience | age (d) | old score | old rank | content |
|---|----|------|-----|--------|----------|---------|-----------|----------|---------|
| 1 | 10 | summary | **0.0164** | 0.8936 | 1.0 | 4.87 | 0.0146 | − | "seeking information on functional programming concepts, sp..." |
| 2 | 5 | decision | **0.0161** | 1.7873 | 2.0 | 4.87 | 0.0288 | − | "looking for information on functional programming concepts..." |
| 3 | 1 | fact | **0.0159** | 0.8936 | 1.0 | 4.87 | 0.0142 | − | "looking for concepts of functional programming to understa..." |
| 4 | 796 | fact | 0.0156 | 2.9946 | 3.0 | 0.08 | 0.0468 | 2 | "asking about functional programming practices and Nu comma..." |
| 5 | 798 | fact | 0.0154 | 3.9928 | 4.0 | 0.08 | 0.0614 | 1 | "looking for a way to program with good functional programm..." |

**Relevant match wins: YES.** The three highest-relevance rows (id 10, 5, 1 — all explicitly about
functional programming concepts) take the podium; the old winner (id 798, salience 4.0) drops to 5
because its relevance is genuinely the 5th best. Reinforcement still orders within the tier: 796
(rank 4) before 798 (rank 5) by weight.

### 2. "busy waiting while true reactive loop schedule"

OLD order:

| old # | id | kind | old score | rrf | salience | age (d) | weight | content |
|---|----|------|-----------|-----|----------|---------|--------|---------|
| 1 | 798 | fact | 0.0624 | 0.0156 | 4.0 | 0.08 | 3.9928 | FP fact — no busy-waiting content |
| 2 | 810 | fact | 0.0499 | 0.0125 | 4.0 | 0.05 | 3.9958 | "two read-only agents are reading code with context: concurrency (ha..." |
| 3 | 796 | fact | 0.0461 | 0.0154 | 3.0 | 0.08 | 2.9946 | FP practices fact |
| 4 | 880 | fact | 0.0395 | 0.0132 | 3.0 | 0.01 | 2.9991 | "2 `while(true)` without schedule (`BackfillEmbeddingsM..." |
| 5 | 678 | fact | 0.0268 | 0.0091 | 3.0 | 0.81 | 2.9445 | "bot is set up to capture messages in real-time when the sidecar is..." |

NEW order:

| new # | id | kind | rrf | weight | salience | age (d) | old score | old rank | content |
|---|----|------|-----|--------|----------|---------|-----------|----------|---------|
| 1 | 799 | preference | **0.0164** | 0.9982 | 1.0 | 0.08 | 0.0164 | − | "prefers a straightforward approach to programming without bus[y waiting]..." |
| 2 | 2 | fact | **0.0161** | 0.8936 | 1.0 | 4.87 | 0.0144 | − | "wants to find information on monads, applicative functors..." |
| 3 | 469 | preference | **0.0159** | 0.9611 | 1.0 | 1.72 | 0.0153 | − | "REACTIVE LAW enforced by hooks: blind sleep >= 10s is DENIED..." |
| 4 | 798 | fact | 0.0156 | 3.9928 | 4.0 | 0.08 | 0.0624 | 1 | FP fact |
| 5 | 796 | fact | 0.0154 | 2.9946 | 3.0 | 0.08 | 0.0461 | 3 | FP practices fact |

**Relevant match wins: YES.** id 799 ("without busy waiting") and id 469 (the reactive-loop
scheduling law) — invisible in the old top-5 — rank 1st and 3rd. The old winner (id 798, an FP fact
with salience 4.0 and no busy-waiting content) falls to 4th. Row 880 (`while(true)` without
schedule) stays below 798 because its fused relevance (0.0132) is genuinely lower than 798's
(0.0156) — the residual noise lives in the embedding leg, not in the ranking algebra, and the fix
no longer amplifies it with a ×4 salience.

### 3. "the user prefers concise summaries"

OLD order:

| old # | id | kind | old score | rrf | salience | age (d) | weight | content |
|---|----|------|-----------|-----|----------|---------|--------|---------|
| 1 | 64 | preference | 0.1504 | 0.0152 | 11.0 | 4.44 | 9.9272 | "prefers clear and concise communication" |
| 2 | 182 | preference | 0.1461 | 0.0145 | 11.0 | 3.78 | 10.0795 | "prefers a direct and efficient process" |
| 3 | 18 | preference | 0.0710 | 0.0159 | 5.0 | 4.85 | 4.4700 | "prefers a concise and clear response" |
| 4 | 364 | preference | 0.0614 | 0.0161 | 4.0 | 2.11 | 3.8094 | "prefers clear and concise information" |
| 5 | 186 | preference | 0.0495 | 0.0135 | 4.0 | 3.78 | 3.6654 | "prefers a straightforward and efficient workflow" |

NEW order:

| new # | id | kind | rrf | weight | salience | age (d) | old score | old rank | content |
|---|----|------|-----|--------|----------|---------|-----------|----------|---------|
| 1 | 774 | preference | **0.0328** | 0.9974 | 1.0 | 0.11 | 0.0327 | − | "the user prefers concise summaries" (near-exact) |
| 2 | 364 | preference | 0.0161 | 3.8094 | 4.0 | 2.11 | 0.0614 | 4 | "prefers clear and concise information" |
| 3 | 18 | preference | 0.0159 | 4.4700 | 5.0 | 4.85 | 0.0710 | 3 | "prefers a concise and clear response" |
| 4 | 441 | preference | 0.0156 | 0.9589 | 1.0 | 1.82 | 0.0150 | − | "prefers clear and concise commands" |
| 5 | 323 | preference | 0.0154 | 0.9403 | 1.0 | 2.66 | 0.0145 | − | "prefers a clear and concise explanation of the app's behavior..." |

**Relevant match wins: YES, decisively.** The near-exact match (id 774, rrf 0.0328 — top of BOTH
legs, double the relevance of everything else) ranks first by a 2× gap no salience can close under
the new algebra, while the old ranking buried it (0.0327) under the salience-11 veterans (0.1504).
The veterans stay close behind, ordered by weight within their tier — LAW 2 doing its safe work.

## Verdict

For all three measured queries the genuinely relevant match now ranks first, and the old winner was
in every case a high-salience row the new algebra correctly demotes to its true relevance tier.
Reinforcement never disappears: it decides ties inside a relevance tier (796 before 798; the
salience-11 veterans still right behind the winner in query 3).

## Gates

- `./gradlew build detektAll` — green (with the law tests and `MemoryRankingEvidenceIT`).
- `make eval` — recall-core 20/20 (the retrieval gate runs over the RAG store, whose algebra is
  unchanged; re-run to prove no regression).
- `make bb` — 7/7 black-box local.

No deploy: the running brain keeps the old SQL until the owner restarts it.
