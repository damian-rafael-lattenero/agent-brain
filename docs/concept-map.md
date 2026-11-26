# The complete map of your AI engineering — v2 (2026-11-18)

> **SUPERSEDED (2026-11-20):** the canonical and complete version is now
> `docs/ai-engineering-map-and-dictionary.md` (the v4 BOOK: architecture with
> diagrams, a dictionary from scratch with examples, the arc, the method, the
> exam). This file stays as the SHORT version for quick reference — the
> numbers here reach up to 2026-11-20 inclusive.

Everything built up to today + the term dictionary. Every number here is
measured and verifiable (suite, ledger, endpoint or drill).

## Part 1 — The story in one page

**2026-10-30 — The compass.** The 2026 consensus confirmed by searches: you need
FIVE things on top of the models that already exist — your own MCP, RAG with your data and
citations, memory for the agent, evals, your own harness. They are not five separate
projects: it is a single app that grows with each one. The map: 5 repos — ZCode (the
desktop harness fork), bot (Telegram harness, retired), zcode-bot-kotlin
(the Kotlin replacement), agent-brain (the brain), kap (the concurrency stdlib).

**2026-10-30 to 2026-11-02 — The migration.** The Python bot was retired in one afternoon: cutover
of sending (first real Kotlin send, zero duplicates), daemon-local channel in the
fork (unix socket with auth), complete Kotlin bot, retirement of every
Python LaunchAgent. New rule: every script we own is typed Nushell.

**2026-11-03 — The brain turns on.** kap 4.3/5.0 (Supervisor, KapStream, Schedule
semiring). The RAG indexes the 5 repos; brain-mcp installed in ZCode; the bus
publishes real turns and memory injects context on every turn (≤1500
chars, 1.5 s deadline, silence if it is down).

**2026-11-08 — FP audit of the bot.** 19 real fixes. The house dialect: denotational
FP, kap as stdlib, zero `while(true)`.

**2026-11-11 to 13 — Fleet.** Two upstream syncs of the fork (v3.18.0, v3.18.2), stable
signature (Keychain), full-fleet Nushell sweeps (46 + 57 scripts), KB 0.117
with 21 verified drills.

**2026-11-14 — Compass plan v2.** Evals and observability as a PRODUCT, rerank
measured, memory lifecycle as a discipline, MCP Apps. B1 graded metrics
(MRR/recall@1/nDCG). B2 rerank BUILT and VERDICTED: the listwise with a small LLM
loses against fusion (recall@1 0.350 vs 0.850) → default OFF. The
modern discipline is NOT shipping what the evals killed.

**2026-11-15 — Memory as a system.** B3 lifecycle (access-reinforces +0.1,
decay by last_accessed, ACTIVE/COOLING/ARCHIVED; forgetting = excluding, never
deleting). B4 graph (related/cooccur/temporal edges + traversal; the symmetric OR
cost 33–35 s → 223 ms with UNION ALL of indexed joins). Reconcile with a judge
(same/supersede/distinct, invalid_at). Dream (consolidation outside the session).
Separate judge: deepseek 8/8 on contradiction-core vs 5/8 for the local 1.5b.

**2026-11-15 to 16 — Typing and extractor.** 5-probe arc: the iterative pgvector was a
NO-OP (discovered law: the "alive" semi-join beats the HNSW) and the
SQLDelight fork compiled the whole retrieval law → full migration to typed SQL,
byte-identical recall (20/20), fork published to Maven Central. Eval corpus
13 → 29 single-concern docs: MRR 0.9125 → 0.9848. THE EXTRACTOR CASCADE in
7 measured layers: cap 512 → visible truncation → splitting → free defers with an
open breaker → sequential extraction → REAL timeout (the dead knob: a shared 60 s
HTTP client cut before the config) → deepseek as the root
extractor. The "giants" that the 1.5b degraded recovered +130
memories, zero new quarantine.

**2026-11-17 — Bilingual RAG + audit.** QueryExpansion (Spanish → English
keywords) + a second leg fused with RRF. The honest lesson: the misses
were CORPUS (docs that did not exist), not language; real-questions 6/8 → 8/8.
rag-nightly.nu: index + eval at 04:00 — the RAG certifies itself every
night. Fleet quality audit (waves 1–6). Learning map + challenge
protocol (the challenger uses ANOTHER model; every objection names its check).

**2026-11-18 — Operation and CV.** The 5 operation gaps closed: backups with a
PASS drill (03:40, 14-day retention), cost ledger per turn (turn_costs +
`/costs/today`), 👍/👎 feedback to the bus, secrets gate (gitleaks tree/staged;
trufflehog verified for the history: 7 repos, ZERO live credentials),
quota watcher with hysteresis. The CV redone on top of this map: two artifacts
from one `content.tex` (designed cv.pdf; parser-first one-column cv-ats.pdf),
gates that fail if the 17 keywords stop being in the text
layer, extractable contact, zero private-use glyphs.

## Part 2 — The system today (the numbers)

**RAG**: 20,722 docs / 72,934 chunks. recall-core 33/33, MRR 0.9848,
recall@1 0.9697, nDCG@5 0.9888. real-questions 8/8 (gate 0.8). Hybrid
pgvector + FTS + RRF, p50 ~20–33 ms, bilingual expansion. Rerank implemented,
measured twice, default OFF.

**Memory**: ~4.4k memories, ~27k edges. Write dedup ≥0.90 reinforces the
oldest. Reconcile with the deepseek judge (supersede with a verdict; evidence in
quarantine). Lifecycle with two clocks (created_at = freshness, last_accessed =
usefulness).

**Evals**: recall-core 33/33 · real-questions 8/8 · contradiction-core 8/8
(deepseek judge) · memory-core 9/10 · extraction-core 6/6. eval_runs ledger;
the 04:00 nightly indexes and evaluates.

**Bot/channel**: Kotlin daemon (poller + router + socket + live cards +
multi-lane). turn.completed carries model + tokens + stop_reason → turn_costs →
`/costs/today`. 👍/👎 reach the bus. Quota watcher (once per
episode).

**Infra**: pg backups with a PASS drill; secrets: 7 repos, zero live
credentials; LaunchAgents for everything that survives ZCode.

**kap**: 5.4.0 on Maven Central (8 modules, consumed by the 4 repos);
1,234 tests / 92 suites with laws; 144 JMH benchmarks; its own detekt rules.

**CV**: content.tex single source; cv.pdf + cv-ats.pdf; ATS gates (17/17
keywords, extractable contact, zero PUA).

## Part 3 — The dictionary

### 3.1 Models and LLMs

- **LLM (large language model)** — the model that predicts the next token.
  In your stack: deepseek-v4.1-flash (remote, OpenCode plan) to extract and
  judge; before that, local qwen2.5:1.5b.
- **Token** — the unit of text (~4 chars). Caps and costs are measured in
  tokens.
- **Context window** — SHORT-term memory: everything it sees in one
  call (num_ctx 4096 → 8192). Your long-term thing is MEMORY, not the
  window.
- **System prompt** — the fixed role instruction. The extraction schema lives there.
- **Parameters (1.5b/3b/7b)** — capacity. The 1.5b fit on your Mac and
  DEGENERATED (9,227 chars of a single sentence, measured).
- **Degeneration** — the small model's failure mode: a repetition loop. It is not
  fixed with more cap (more cap = more garbage); it is fixed with a better model.
- **Finish reason / done_reason** — WHY it ended ("stop" good, "length"
  cut off). Reading it made visible the truncation that disguised itself as "invalid
  JSON".
- **Embedding model vs chat model** — the embedding turns text into a vector
  (local nomic-embed-text, free); the chat converses/extracts/judges. Different
  by design. Matrix: nomic stays (MRR 0.985 vs 0.909 for all-minilm).
- **Local inference vs API** — local = free/private/slow for giants; API
  = paid/fast/full capacity. Provider-split verdict: embeddings and
  volume local; critical quality remote.
- **Fine-tuning / LoRA / QLoRA** — training small adapters on top of an
  existing model; QLoRA does it with the quantized model. Your
  closing project: the LoRA extractor (pairs already exported: positives = transcripts →
  canonical extraction; negatives = quarantine).
- **Quantization** — compressing the model to fewer bits (GGUF/ollama) so it
  runs on your Mac.
- **SLM** — the small ones (1.5b–3b): local volume; insufficient capacity to
  judge (5/8) and for long transcripts (degeneration).

### 3.2 RAG and retrieval

- **RAG** — retrieving the relevant pieces of YOUR knowledge and handing them
  to the model as context, WITH A CITATION. An LLM does not know your stuff.
- **Corpus / chunk / chunking** — the indexed set (20,722 docs / 72,934
  chunks) and its pieces. Every chunk of yours starts with "path > heading": the
  cheap version of Contextual Retrieval.
- **Vector / embedding** — 768 numbers that represent MEANING.
- **Cosine similarity** — the angle between vectors (1.0 = same direction).
  Dedup 0.90; related band 0.60–0.88.
- **Vector DB / pgvector** — Postgres with vector superpowers.
- **HNSW / ANN vs exact scan** — the neighbor index approximates; the scan
  compares everything. Discovery: the "alive" semi-join kept the HNSW
  from being used — the search was exact-by-scan without anyone knowing.
- **FTS / BM25 (sparse)** — search by WORDS; the vector searches meaning.
  That is why the hybrid is the standard.
- **Hybrid retrieval** — dense + sparse together. Your de facto standard.
- **RRF (K=60)** — the formula that fuses rankings: every doc adds
  1/(60+position) per list where it appears.
- **Rerank** — a second pass that re-orders. Cross-encoder (scores query-doc
  pairs; the 2026 standard, untested here) vs listwise-LLM (tested twice,
  lost: 0.350 vs 0.850 and +0.004 MRR for 100–1000× latency → OFF).
- **recall@k / MRR / nDCG@5** — is it in the top-k? how high? the whole
  top-5 weighted? Your numbers: 1.0 / 0.9848 / 0.9888 on recall-core.
- **p50/p95** — median and tail latency (20–33 ms p50 on search).
- **Grounding / citation** — the answer leans on the cited chunks; the
  eval demands the correct citation.
- **Distractor / needle** — the doc that steals the query through shared
  vocabulary (hence single-concern docs); the specific fact being looked for.
- **Query expansion / RAG Fusion** — rephrase and fuse. Your bilingual
  expansion: Spanish → English keywords, verbatim identifiers, a single leg
  if the restatement is identical (it can only ADD recall).
- **Contextual Retrieval (Anthropic)** — LLM context per chunk: −67% failures
  on their benchmark; your heading path already covers the essentials at this scale.
- **HyDE** — embed a hypothetical answer; not implemented (no signal).
- **Agentic RAG / Adaptive / Self-RAG / CRAG** — retrieval as a LOOP
  (rephrase, decide steps, evaluate, retry). The next natural jump.
- **GraphRAG / RAPTOR / sentence-window** — entity graphs over docs and
  hierarchical chunking; not implemented (your MEMORY graph already exists).
- **Holdout / overfit** — questions never used for curation; the check for the
  risk "8/8 may be test leakage".
- **Memory benchmarks (LoCoMo/LongMemEval/BEAM)** — the standards the
  industry compares against (Mem0 reports 92.5 on LoCoMo).

### 3.3 Agent memory

- **Long-term memory** — what survives the close: facts/decisions/
  preferences extracted by an LLM from every turn.
- **Salience / decay / reinforcement** — the weight, its cooling from
  last_accessed (30-day half-life) and the rise (+0.1 per hit, +1.0 per
  merge). A search hit does not weigh like a re-asserted fact.
- **Lifecycle (ACTIVE/COOLING/ARCHIVED)** — total classification at runtime by
  thresholds (0.5/0.125); no stored state. Forgetting = excluding from
  search, never deleting; reinforcing resurrects.
- **Dedup (0.90)** — a MEASURED boundary: true duplicates 0.9123–0.9845 vs
  the strongest related pair 0.8789 → 0.90 in the empty gap.
- **Reconcile / supersede / invalid_at** — the judge decides same/supersede/
  distinct in the related band; the old row is invalidated, the evidence
  stays. Retrieval filters `invalid_at IS NULL`: one valid row per fact
  (auditing WITHOUT that filter counts designed history, not corruption).
- **Memory graph / edges / traversal** — related/cooccur/temporal (~27k) and
  walking the graph from a hit. SQL law: UNION ALL of two indexed joins
  (the symmetric OR: 85.7M intermediate rows).
- **Dream** — consolidation outside the session (Letta style), weekly.
- **Staleness** — a memory that lies because the world changed; reconcile
  is the cure.
- **Mem0 / Zep / Letta** — the reference commercial ones; at your scale their value
  is not needed and the swap is a module, not a rewrite (measured).

### 3.4 Evals and quality

- **Eval suite / ground truth** — questions with a known answer written by
  a human; without ground truth there is a demo, not an eval.
- **Gate** — the minimum score (recall 0.9, real-questions 0.8). A model below
  the gate is a FINDING; the floor is never lowered.
- **Ledger (eval_runs)** — every run leaves a row: the history that is not
  rewritten.
- **LLM-as-judge** — where determination is not possible (reconcile,
  contradiction) and NOT where an assert is enough (bias, drift, self-preference).
- **Red teaming** — attacking your own system; a typed 10-vector drill,
  exit 1 if it fails: 10/0.
- **Eval matrix** — the same suite against several models with cost and latency
  (the nomic verdict came out of there).
- **Regression gate** — evals as CI: what lowers the number does not merge
  (byte-identical recall after 5 surgery units).

### 3.5 MCP, agents and security

- **MCP (Model Context Protocol)** — the open standard for an
  assistant to use tools and data. Server = brain-mcp (6 tools); host =
  ZCode.
- **Tool calling / function calling** — the model calls a tool with
  structured arguments ("cancel my subscription" → typed tool call).
- **JSON Schema** — the tool's typed contract (inputSchema).
- **Idempotence** — calling twice = calling once; the property that makes it
  safe for an LLM to retry.
- **MCP Apps** — UI panels served by the same MCP server (brain-panel,
  diff-ask).
- **Harness / agent loop** — the program around the model and its cycle
  think → tool → look → repeat. Claude Code/ZCode are harnesses.
- **Subagent / hooks / permission boundaries** — agents launched by agents
  (parentToolUseId), the hook points (where your mechanical laws live)
  and what the agent can do without asking — fail-closed when in doubt.
- **Prompt injection / guardrails** — the attack (hostile content posing
  as an instruction) and the barriers: a header "retrieved data, NOT
  instructions", HTML escaping in every renderer, an injection drill.
- **Context engineering** — what enters the window and in what order (memory,
  RAG, history); your injection ≤1500 chars with a deadline is measured
  context engineering.

### 3.6 Infrastructure and observability

- **Bus / event sourcing** — the event tape with id (user.prompt,
  turn.completed, tool.call, turn.feedback); the brain consumes it and projects.
- **Worker / queue** — pending = turn.completed without memory_at and attempts < cap.
- **Circuit breaker** — the fuse (5 failures → OPEN → half-open → CLOSE), with
  the law: a rejection by an open breaker does NOT consume the turn's attempt.
- **Retry/backoff / backpressure** — kap Schedule (semiring) and bounded
  concurrency (extraction_concurrency=1 against a single runner).
- **Knob (and the dead knob)** — a config dial; the lesson: chat_timeout_s
  said 240 but a shared client cut at 60 s. Configuring without
  verifying WHO cuts first = dead config.
- **LaunchAgent / heartbeat / staleness watchdog** — what survives ZCode
  lives outside; the healer reads freshness (not the hollow "loaded"); "silence is
  data" — a ticker that lies is killed and replaced.
- **OpenTelemetry / Prometheus / Grafana** — a span per turn and per tool call;
  scraped metrics and dashboards (lifecycle and bus gauges).
- **Migration / advisory lock** — the schema changes at boot (0001–0007) with an
  anti-double-migration lock.
- **Quota gates vs lanes** — GATES: a closed window blocks EVERY call;
  LANES: independent buckets per model. The watcher warns ONCE per
  episode with hysteresis.
- **Backup drill** — restore the dump into a scratch DB and compare counts:
  a backup without a drill is an assumption.

### 3.7 Your FP dialect

- **ADT** — sealed + data: the domain in types; invalid states are not
  representable (TurnState, Outcome, Tick).
- **Law test / property-based testing** — verify algebraic laws and
  generate hundreds of cases (kap: 92 suites with laws).
- **Resource / Process / Schedule / Ref** — acquisition with a guarantee
  (bracketCase), loops with a restart policy, the retry algebra, the
  lock-free atomic cell.
- **traverseSettled** — concurrent mapping that NEVER throws, with bounded
  concurrency.
- **Parse, don't validate** — the datum enters the pipeline parsed ONCE into a
  type that makes the invalid invalid.
- **No opaque types** — your law: scalars with a role (typealias/value class),
  structure (data class), choices (sealed); the test: does a reader of the
  domain know what each value is from the signature?

## Part 4 — How to read the numbers

1. **recall@5** — is the answer among the first 5? The gate.
2. **MRR** — how high? 0.5 ≈ the correct doc averages position 2.
3. **nDCG@5** — MRR that rewards the whole top-5 in order.
4. **p50/p95** — median and tail latency.
5. **gate** — the floor you approved; if it does not reach it, it is a finding.

Live example: recall-core 33/33, MRR 0.9848, nDCG@5 0.9888, p50 22 ms — "in 33
real questions, the correct doc was in the top-5 ALWAYS, almost always
first, in 22 ms".

## Part 5 — What comes next + reading plan

1. **Real-usage eval** — log every rag_query, sample weekly, grade
   with the judge, to the ledger. The missing number: hit-rate over production.
2. **Cross-encoder rerank as a probe** — the standard mechanism you have not tested;
   it is integrated ONLY if it wins.
3. **Agentic / multi-hop RAG** — retrieval as a loop, gated by a multi-hop
   suite.
4. **The missing rung: the models** — LoRA of the extractor, gate =
   extraction-core ≥ deepseek and cost.

Reading plan: read it twice (today and tomorrow); the chain that sums up everything is
`rag_query "extraction cascade breaker timeout"`; in 3 days, explain from
memory RRF, recall@5, dedup 0.90, breaker and context engineering, and why
rerank stayed OFF.

## Part 6 — the arc of 2026-11-18 (four verdicts, units 1-4)

**Unit 1 — usage eval (application-specific evaluation), SHIPPED.** Every real
`rag_query` answered by HTTP lands in `rag_query_log` (question,
chunks served, latency of the asker; the evals CLI never writes
there) and `usage-eval` grades it with the judge: the rank of the first relevant
chunk feeds the same graded ledger columns (MRR/recall@1/@5/
nDCG@5) with PRODUCTION latency. Suite `usage`, gate 0.7; baseline 1/1
rank 1 MRR 1.0; the 04:00 nightly certifies it. Words: application-
specific evaluation (Mem0 lists it among the open problems of 2026), usage
log.

**Unit 2 — cross-encoder probe, VERDICT.** Same 41 questions, same
candidates (production top-10), only the order changes. bge-reranker-base is
the FIRST mechanism that beats fusion (recall@1 0.439 → 0.537; 10/3
improved/worsened); MiniLM loses and both listwise-LLMs remain lost
(default OFF forever). Integration ON HOLD: the real queries
worsened (0/4), latency ~25-50×, and on the JVM it is ONNX/DJL + artifact.
THE BIG FINDING: the fixture lies — the same 33 questions with 33/33
(MRR 0.985) on the curated corpus give recall@5 0.697 / MRR 0.608 against the
20,722 production docs. The usage eval is the honest instrument; the
corpus/retrieval is the headroom. Words: cross-encoder (direct score of the
query-text pair; the 2026 standard), listwise (re-order with a chat), leg
dilution (in RRF, a doc in SEVERAL legs beats a better one that is in ONE).

**Unit 3 — agentic multi-hop, SHIPPED.** `QueryRequest.iterate` decomposes
(MultiHop) into 2-4 sub-queries and fuses all the legs ON TOP OF the bilingual
order — the first version REPLACED it and the production A/B caught it
(a Spanish query sank from rank 2 to outside the top-10: cross-lingual
0.55-0.65 with nomic is exactly the weakness). Suite `multi-hop.json`: 5
questions over twin pairs (one doc's vocabulary drowns the other; the distractor
law turned into cases), gate 0.8, 5/5 in both modalities. Production:
mixed — one real rescue 0→1 and one dilution 2→0 (leg dilution). Opt-in per
CLI (`--iterate`), MCP (rag_query v2) and suite. Words: multi-hop/agentic
loop, composite legs (a new stage can ONLY add recall), twin pairs.

**Unit 4 — the models rung (LoRA), COMPLETE.** The first model trained in the
house, end to end: dataset = the pipeline pairs (961
positives — transcript → canonical extraction, the label IS the output that the
pipeline kept: self-distillation — + 31 negatives from quarantine →
empty extraction, ~748k tokens, hold-out of the FINAL 10%), Qwen2.5-3B-Instruct
4-bit on MLX (Apple Silicon), 16-layer LoRA, seq 1536, mask-prompt, lr 1e-4,
400 iters ≈ 1.7 h. Gate unchanged: extraction-core against deepseek 6/6.
NUMBERS: LoRA @ 200 iters 4/6 PASS (p50 9.8 s with the GPU contested); LoRA @ 400
iters FINAL 4/6 PASS, p50 4.5 s / p95 9.4 s, $0. deepseek KEEPS THE SEAT
(B2's law applies to the home model); the two failures (the discipline of
emptiness on trivial turns, the content needle) are the job of the full
recipe (2-3 epochs, weighted negatives 0.1-0.2, ~8 h of nightly run —
the owner's word). Adapters in `evals/lora/adapter-400/` (gitignored).
UPDATE: the nightly run IS RUNNING — launchd one-shot
`com.zcode.lora-full` (script `scripts/lora-full-run.nu`), 2400 iters
(~2.7 epochs, ~10 h), the negatives at their natural ratio (3% — mlx has no
per-example weight), and when it finishes the script serves the adapter, runs
extraction-core and sends ONE Telegram line with the verdict (noisy if it
fails). Words: mask-prompt, final hold-out, self-distillation.

**The day's style law:** terminal 100% Nushell → 99% Nushell by
default (builtins over coreutils, zero bash -c, ^ only if a builtin shadows the
name; bash ONLY for what exists only on POSIX).

## Part 8 — arc close (2026-11-20)

**LoRA data-pass FINAL verdict** (`scripts/lora-gate.nu` committed): 5/6
PASS (up from 4/6) — the data pass FIXED the content needle, but
small-talk-nothing-durable keeps failing WITH empties at 24% + oversample ×3.
Structural finding: a 3B instruct with 0.216% trainable parameters
grasps content but does NOT sustain "extract NOTHING" against its prior of being
useful. Remaining levers: empty ratio ≥50% (grows with use), per-example
loss weight (mlx does not expose it → custom loss or NVIDIA trainer), 7B+ base.
deepseek keeps the seat (6/6). The "models" rung PROVEN end to
end: export → train → resume-launchd → serve → gate → verdict.

**The double user.prompt** (one message = two user.prompt events, 5.5 s
apart) was noted as a check candidate in the queue.

**The traps of the month** (for study): run-external rejects literal dash-flags
(they travel as quoted list values via spread — KB
run-external-flag-traps, three strikes of the same error); SQLDelight RETURNING
is lazy (without executeAsOne it does not execute — ghost rows); the migration
index.txt is verified after touching it (the migrator skipped 0009 in
silence once); nohup inside ZCode dies with the session (the training
resume lives under launchd with KeepAlive SuccessfulExit=false:
crash → relaunch from the checkpoint, clean exit 0 → terminal); the seed
vectors in tests match the migrator dim (768).

## Part 7 — the arc continues (2026-11-19: T1 shadow judge, rubric v2, T3 /learn, the DATA pass)

**T1 — shadow judge, SHIPPED** (`3838151`): the bot's completed turns
are graded every night with the judge over a rubric (anchored score 1-5,
pass 4+, gate 0.7), state cursor in `state/shadow-cursor.json`, ledger
suite `shadow`. 2026 laws applied: rubric not vibes, judge ≠ performer,
single-output (no position bias), the trend is the signal, sample not everything,
the deterministic stays in code (240-char floor in SQL). First run 8/8
score-4 and THE SIGNAL: 7/8 reasons said "too long for Telegram".

**Rubric v2** (`d891da1`): that signal was a BUG in the spec — the typical reply
of 1090 chars is exactly the owner's format (done/pending/next
with numbers). v2 penalizes FILLER (repetition, rhetoric, invention), never
length. Live A/B: same class of replies 4/5 → 5/5. Anti-inflation
guard: the wrong/partial anchors did not change.

**T3 — /learn, SHIPPED** (`809d22a`, bot deployed): the 7-day learning
card on Telegram — memories by type (window filter in
the BOT: /memory/recent has no date parameter), eval rows vs gate,
cost of the day. DETERMINISTIC by decision (no LLM): the ingredients are the
facts, the card orders them. Each leg degrades on its own; three falls = one
down.

**LoRA — the DATA pass** (`989bc2d`): the 2400-iter run finished 4/6,
IDENTICAL to 400 — the lever was DATA. New leg `trivialPairs`: short
processed turns (reply ≤ 200 chars) with EMPTY extraction as the label — the
owner's manual pruning (75 tiny status memories deleted by hand) IS the human
label. Dataset v2 986+31+58; the naive split sent ALL the empties to the
holdout (0.5% in train) — fixed: hold-out every 10 lines ONLY of
positives, empties to train with oversample ×3 (24%). v2 training (800
iters) in flight; the gate against deepseek 6/6 decides.

**The owner's question (for the exam, #13): why do ollama/nomic stay
if deepseek is the extractor?** The provider-split verdict (measured 2026-11-15):
two providers by ROLE. Embeddings = the VOLUME leg (every rag_query and
every insert): $0 marginal, p50 ~20-33 ms on the query path, and it works
without the relay (the 429s of the remote plan killed entire probes while the local one
kept going). Privacy too: vectors derive from private content.
Replacement only if a remote embedding BEATS nomic in the matrix (all-
minilm lost: MRR 0.909 vs 0.985) with flat price and a privacy blessing.
deepseek stayed on the CRITICAL quality and low
frequency legs (extraction, judge, dream); qwen2.5:1.5b was retired for
measured degeneration (9,227 chars of repetition).

**Embeddings from scratch (the owner's 4 questions, 2026-11-19):**
- **HNSW** = Hierarchical Navigable Small World: the index of layers in
  pgvector; the search descends through a graph of highways and compares
  hundreds instead of 73k. Measured laws: the alive semi-join defeats it (hence
  the search was always exact), and the inline rewrite left it
  HNSW-capable for scale.
- **768** = the dimension chosen by nomic's trainer (an architectural
  constant: minilm 384, text-embedding-3-small 1536). The lock: the
  pgvector column is vector(768) — changing model = re-embedding 20,722
  docs + migrating the column.
- **The distance** = cosine (1.0 same direction). Meaning was learned by
  the encoder through contrastive training (pairs of the same meaning
  attract; different ones repel) — the geometry IS the representation.
- **Paid embedder** (OpenAI/Cohere/Voyage): price is NOT the blocker (cents
  per month); the blockers are the schema lock, the saturated suite (33/33 — without
  misses there is no demonstrable gain), and the only real gap with a number: the
  cross-lingual ES-EN one (0.55-0.65) that a strong multilingual model could indeed
  win. Reopening: usage eval showing multilingual misses.
**Expanded exam (19 questions):** the original 13 + HNSW, 768,
cosine distance/contrastive training, paid embedder.
