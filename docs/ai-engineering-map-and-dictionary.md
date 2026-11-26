# THE BOOK OF YOUR AI ENGINEERING — v4 (2026-11-20)

> Translated from Spanish on 2026-11-26; the original stays in git history.
> Everything built up to today, explained from scratch: the story, the architecture part by part with diagrams, the complete dictionary with long definitions and examples, the arc with its verdicts, the method, and the exam.
> **The rule of this file:** every number that appears here came out of a suite, a ledger, an endpoint or a measurable drill. If a number cannot be verified, it is not here.
> v4: moved into the repo (`agent-brain/docs/`) — it lives in git and the nightly RAG index picks it up on its own. The dictionary was rewritten assuming ZERO prior knowledge: every term with a complete definition, an example, and the anchor in your system. New Part 4: the architecture explained box by box, with Mermaid diagrams.

---

## 🧭 PART 0 — HOW TO USE THIS BOOK

- **First reading (today):** PART 1 (the story) → PART 3 (the compass fulfilled) → PART 4 (the architecture with the diagrams). It is the skeleton: who each piece is and how they talk to each other.
- **The backbone is PART 5 (the dictionary).** It is written from scratch: if a term is used anywhere in the book and you do not know it, it is defined here — starting with the base words (5.0: what an API, a port, a queue, an index are) before the AI ones.
- **The diagrams:** they are Mermaid (text inside the Markdown). GitHub renders them by itself when you view the file on the repo's web page. In VS Code: Cmd+Shift+V (preview) with the "Markdown Preview Mermaid Support" extension. To play with one: copy it to [mermaid.live](https://mermaid.live) and modify it.
- **How to read a diagram:** each box is a program or a place where a datum lives; each arrow is a message or a read; dotted arrows mean "takes care of" instead of "talks to". Below each graph there is a numbered walkthrough that explains it.
- **The exam (PART 10) is the measurement.** It is answered from memory, in your own words, and every answer with ONE number from the system that proves it.
- **The question protocol:** when something does not close, ask it in the chat. The list of things you could not explain in one sentence IS your real study list.

### 🎨 The visual legend (this book's color and mark system)

| Mark | What it means |
|---|---|
| **bold** | key term — the word that carries the meaning |
| `code` | MEASURED number or exact identifier (if it looks like this, it was verified) |
| 💡 **Example** / 💡 **Analogy** | the concrete thing: where the definition lands |
| 📍 **In your system** | where that idea lives in YOUR fleet, with its number |
| 📊 **The number** | the measured verdict of that unit |
| ⚠️ | trap — what actually bit |
| 🔑 | house rule / sentence to remember |
| <mark>highlighted sentence</mark> | THE key sentence of the paragraph (yellow background) |
| <mark class="law">**law**</mark> | house rule (violet in VS Code) |
| <mark class="num">`number`</mark> | measured number (green in VS Code) |
| <mark class="warn">**careful**</mark> | trap/weak point (red in VS Code) |
| 🟣 purple box `[!IMPORTANT]` | LAW — house rule, not negotiable |
| 🟢 green box `[!TIP]` | the sentence that sums it up, the tip |
| 🟡 yellow box `[!WARNING]` | careful / trap |
| 🔵 blue box `[!NOTE]` | context that helps understanding |

In the diagrams, colors are by ROLE: 🔵 blue = where the datum lives (Postgres) · 🟢 green = models · 🟡 amber = decision/critical point · 🔴 red = rejection/failure path · ⬜ dotted gray = the guardian (launchd).

> [!NOTE]
> **Where each level of color shows up:** GitHub (web) shows the alert boxes and the yellow `<mark>`s. **VS Code with the integrated preview (Cmd+Shift+V) shows the WHOLE system** — boxes, violet/green/red marks, heading hierarchy — because the repo ships its own theme in `.vscode/styles.css` (it loads by itself when you open the repo folder; if it does not show up, reload the window). Mermaid diagrams render natively in both. In a viewer without support, everything degrades to plain text — nothing is lost, only the color.

---

## 📜 PART 1 — THE STORY IN TWO PAGES (how we got here)

### 2026-10-30 — THE COMPASS

The video ("to be an AI engineer you don't need to build your own ChatGPT") + searches that confirmed the 2026 consensus: you need **FIVE things on top of the models that already exist**. They are not five separate projects: it is a single app that grows with each one.

1. **MCP** of your own system
2. **RAG** that answers with YOUR data and cites the source
3. **Memory** for your agent
4. **Evals** ("unit tests for AI answers")
5. **Your own harness**

The map: 5 repos — **ZCode** (desktop harness fork), **bot** (Telegram harness), **zcode-bot-kotlin** (the Kotlin replacement), **agent-brain** (the brain: bus + RAG + memory + evals + MCP), **kap** (your concurrency stdlib).

### 2026-10-30 to 11-02 — THE MIGRATION

The Python bot was retired in one afternoon: sending cutover (first real Kotlin send 16:00:40, zero duplicates), "local daemon" channel in the fork (unix socket with mandatory auth), complete Kotlin bot, retirement of every Python LaunchAgent. New rule: every script we own is **typed Nushell**.

### 11-03 — THE BRAIN TURNS ON

kap 4.3/5.0 (Supervisor, KapStream, Schedule as a semiring). The RAG indexes the 5 repos (17.5k docs at the start). **brain-mcp** installed in ZCode: `rag_query` and `memory_search` from the chat. The bus publishes real turns and memory injects context on every turn (≤1500 chars, 1.5 s deadline, silence if it is down).

### 11-08 — FP AUDIT OF THE BOT

19 real fixes (concurrency, ADTs, purity). The house dialect is born: denotational FP, kap as stdlib, zero `while(true)`.

### 11-11 to 11-13 — THE FLEET

Two upstream syncs of the fork (v3.18.0, v3.18.2), stable fork signature (Keychain), full-fleet Nushell sweeps (46 + 57 scripts, clean gate), Nushell KB updated to 0.117 with 21 verified drills.

### 11-14 — COMPASS PLAN v2

The 2026 consensus taken deeper: **evals and observability as a PRODUCT**, rerank measured (not assumed), memory lifecycle as a discipline, MCP Apps. That same day:

- **B1 graded metrics:** MRR / recall@1 / nDCG@5 (first live measurement).
- **B2 rerank BUILT and VERDICTED:** the listwise with a small LLM LOSES against fusion (recall@1 0.350 vs 0.850) → default OFF, the mechanism stays opt-in.
  > [!IMPORTANT]
  > 🔑 **Law:** the modern discipline is NOT shipping what the evals killed.

### 11-15 — MEMORY AS A SYSTEM

- **B3 lifecycle:** access-reinforces (+0.1 salience per hit), decay by last_accessed, ACTIVE/COOLING/ARCHIVED states. Forgetting = excluding from search, never deleting; reinforcement resurrects.
- **B4 graph:** edges (related / cooccur / temporal) in native Postgres + related in memory_search. Performance bug caught live: 33-35 s → 223 ms (2,263×) with UNION ALL of indexed joins.
- **Reconcile:** the judge compares similar memories and decides same / supersede / distinct; old claims are invalidated (invalid_at), not deleted.
- **Dream:** consolidation outside the session (Letta style).
- **Separate judge:** deepseek 8/8 on contradiction-core vs 5/8 for the local 1.5b. The judge is independent by CONTEXT and role, not by brand.

### 11-15 to 11-16 — TYPING AND THE EXTRACTOR

- **5-probe arc:** the iterative pgvector was a NO-OP (discovery: the "alive" semi-join beats the HNSW) and the SQLDelight fork DID compile the whole retrieval law → full migration to typed SQL, deployed, byte-identical recall (20/20). Fork published to Maven Central (M5, secrets saga included).
- The eval corpus grew 13 → 29 single-concern docs: MRR 0.9125 → 0.9848.
- **THE EXTRACTOR CASCADE (7 layers, each one measured):** cap 512 → visible truncation → splitting of giant transcripts → free defers with an open breaker → sequential extraction → REAL timeout (the dead knob: a shared 60 s HTTP client cut before the config) → root replacement: **deepseek-v4.1-flash as the extractor**. The "giants" that the 1.5b degraded recovered +130 memories, zero new quarantine.

### 11-17 — BILINGUAL RAG + FLEET AUDIT

- **QueryExpansion** (rephrasing the query into English keywords) + second search leg fused with RRF. The honest lesson: the misses were CORPUS (docs that did not exist), not language. real-questions: 6/8 → 7/8 → 8/8.
- **rag-nightly.nu:** the index refreshes on its own at 04:00 and evaluates itself — the RAG certifies itself every night with the score in the ledger.
- Fleet quality audit (waves 1-6): kap refactor of the channel Turn, typed SQL in the 3 repos with SQL, fork hygiene, evals out of git, script dedupe.
- Your learning map + the first concept map + the **challenge protocol** (anti-sycophancy as a mechanism: the challenger uses ANOTHER model and every objection names its falsifiable check).

### 11-18 — OPERATION AND CV

- The 5 operation gaps closed: Postgres **backups** with a PASS drill (03:40 daily), **cost ledger** per turn (turn.completed carries model + tokens → turn_costs + /costs/today), owner **feedback** 👍/👎 to the bus, **secrets gate** (gitleaks by tree and staged; trufflehog with verification for the HISTORY: 7 repos, ZERO live credentials), **quota watcher** with hysteresis.
- The CV redone on top of this map: two artifacts from one content.tex (cv.pdf designed for humans; one-column cv-ats.pdf for the forms), mechanical gates that fail if the 17 keywords stop being in the text layer.

### 11-19 — MEASURED QUALITY: THE JUDGE IN THE SHADOW

- **T1 shadow judge:** the bot's completed turns are graded every night with a rubric (score 1-5, pass = 4+). First run: 8/8 at score 4 — and the real SIGNAL: 7/8 reasons said "too long for Telegram".
- **Rubric v2:** the signal was a mirror — rubric v1 penalized length, contradicting the owner's render laws. It was a bug in the SPEC. v2 penalizes FILLER, never length with structure.
- **T3 /learn:** the weekly card (memories by type + latest evals + today's cost), deterministic, no LLM.
- **LoRA data pass (design):** the 2400-iter run gave the SAME 4/6 as the 400 — the lever was DATA: trivial turns with empty extraction, oversample ×3, hold-out of positives only.
- Language normalization: the extractor always writes in ENGLISH + 6,233 legacy memories translated (48 pending). New memories are born in English.
- Incident: the daemon hung silently 16:09→18:46; the healer revived it. The "why" of the wedge stayed open.

### 11-20 — THE ARC CLOSE + THE HONEST VERDICT

- **Data-pass adapter: 5/6** (up from 4/6). The content needle was FIXED; the discipline of emptiness (extracting NOTHING on trivial turns) turned out to be STRUCTURAL in a 3B with 0.216% trainable parameters. Three levers remain (ratio ≥ 50%, loss weighting, 7B/8B base). deepseek keeps the seat (6/6).
- Proposal on the table: **deterministic trivial-skip in the worker** (short turns without tool calls do not go to the extractor — code, not model).
- **The honest verdict of the system** (PART 9): three layers — real daily utility, engineering discipline, proof of capability — and the weaknesses named.

---

## 📊 PART 2 — THE SYSTEM TODAY (the machine's numbers)

### RAG

- Corpus: **20,722 documents / 72,934 chunks** (5 repos + KBs + official docs).
- recall-core: **33/33** · MRR **0.9848** · recall@1 0.9697 · nDCG@5 0.9888.
- real-questions (your real questions, verbatim): **8/8** — gate 0.8 PASS.
- **Hybrid pgvector + FTS + RRF** search, p50 ~20-33 ms; **bilingual expansion** (a Spanish query adds an English leg; if the restatement is identical, a single leg — expansion can only ADD recall).
- **Multi-hop** opt-in (`--iterate` / MCP v2): decomposes into sub-queries and fuses ON TOP of the bilingual leg. Twin suite: 5/5.
- Rerank: implemented, measured TWICE (listwise lost), cross-encoder probe verdict delivered (wins on curated data, integration on hold) — default OFF.

### MEMORY

- ~4.4k live memories; ~27k edges (related / cooccur / temporal).
- Write dedup: similarity ≥ **0.90** reinforces the oldest, does not duplicate.
- Reconcile with the deepseek judge: supersede with a verdict (old claims stay invalidated, the evidence stays in quarantine).
- Lifecycle with two clocks: created_at (freshness) and last_accessed (usefulness).
- Content in English since 11-19 (extraction + translated legacy).

### EVALS

- Suites: recall-core 33/33 · real-questions 8/8 · **contradiction-core 8/8** (deepseek judge; the local 1.5b did 5/8) · memory-core 9/10 · **extraction-core 6/6** (deepseek incumbent; LoRA 5/6) · multi-hop 5/5 · **usage** (real traffic, gate 0.7) · **shadow** (bot answers, gate 0.7).
- Every run leaves a row in the **ledger** (eval_runs); the 04:00 nightly indexes AND evaluates AND grades: the system certifies itself.

### BOT / CHANNEL

- Kotlin daemon: poller + router + socket to the fork + live cards + multi-lane.
- turn.completed carries the active model + tokens in/out/total + stop_reason → **turn_costs** in the brain → GET /costs/today and /costs/recent.
- The owner's 👍/👎 reach the bus (turn.feedback) and turn_costs.feedback.
- `/learn`: the week condensed (7d memories + evals + cost). `/memory`, `/costs`, `/tasks`.
- Quota watcher: window tightened ≤ threshold warns ONCE per episode (hysteresis).

### INFRA

- Backups: daily pg_dump -Fc at 03:40 (agent-brain + whatsdigest), 14-day retention, a drill that restores into a scratch DB and compares counts: PASS.
- Secrets: pre-commit with gitleaks (tree and staged); history with trufflehog verification: 7 repos, ZERO live credentials.
- LaunchAgent fleet: colima (watchdog), stack-up, brain serve, nightly index, bot daemon + healer + sentinel + beacon, weekly dream, daily pg-backup.
- Everything that must survive the session lives OUTSIDE (launchd), never as a shell background job.

### kap

- **5.4.0 on Maven Central** (8 modules), consumed by brain/bot/whatsapp/kap-starter. 1,234 tests / 92 suites with law verification; 144 JMH benchmarks; its own detekt rules (ManualWaitLoop, CE-arm).

### CV

- content.tex = single source; cv.pdf (designed) and cv-ats.pdf (one column, parser-first); gates: clean log, 3 pages, 17/17 keywords in the text layer, extractable email/phone, zero private-use glyphs.

---

## ✅ PART 3 — THE COMPASS FULFILLED (the 5 things → where they live today)

| The promise (10-30) | Where it lives | The number that proves it |
|---|---|---|
| 1. MCP of your own system | brain-mcp (rag_query, memory_search, memory_recent, bus_status, eval_latest) + MCP Apps (brain-panel, diff-ask) | 6 live tools in the ZCode host |
| 2. RAG with YOUR data and citations | Bilingual hybrid + opt-in multi-hop, 20,722-doc corpus | 33/33 · 8/8 · MRR 0.9848 · p50 ~20 ms |
| 3. Memory for your agent | Lifecycle + graph + reconcile + dream + nightly | ~4.4k live · ~27k edges · dedup 0.90 |
| 4. Evals | 8 suites + ledger + usage eval + shadow judge | every run leaves a row; the nightly certifies |
| 5. Your own harness | The ZCode fork + the Kotlin daemon (Telegram) | live cards, multi-lane, /tasks, feedback |

> [!TIP]
> All five promises have a deploy behind them and a number in front. This table is the short answer to "what did you build?"

---

## 🏗️ PART 4 — THE ARCHITECTURE, PART BY PART (with diagrams)

*Before the diagrams, the seven protagonists, each in one sentence:*

1. **ZCode (the fork)** — your desktop harness: the program that surrounds the model with tools, permissions and the agent loop. It is a fork (our own copy that you maintain) of the ZCode harness, branch `damian`.
2. **The bot (Kotlin daemon)** — your phone harness: an always-on process that listens to Telegram, shows the live cards and executes your taps.
3. **The event bus** — the accounting tape: EVERYTHING that happens stays as an event with id (user.prompt, turn.completed, tool.call, turn.feedback) in a Postgres table. Nothing talks "sideways": everything goes through the bus.
4. **agent-brain (the brain)** — the Kotlin server that holds the RAG, the memory, the evals and the extraction worker. It speaks through two doors: HTTP (:8765) and MCP (:8767).
5. **Postgres + pgvector** — where ALL the data lives: the bus, the memories, the chunks with their vectors, the evals ledger, the costs. pgvector adds vector search to Postgres.
6. **The models** — ollama serves local nomic-embed (turns text into a vector, free); the deepseek API extracts and judges (remote, through the plan); MLX trains the LoRA on your GPU.
7. **launchd** — the macOS guardian: every process that must survive is a LaunchAgent that launchd revives if it dies. Nothing important lives inside a ZCode session.

### 4.1 THE GENERAL MAP (who talks to whom)

```mermaid
flowchart TB
    subgraph entry["1 · Where everything comes in"]
        direction LR
        ZC["ZCode (fork)<br/>desktop harness"]
        TG["Telegram<br/>the phone"]
    end

    subgraph botbox["2 · The bot (Kotlin daemon)"]
        RT["router — commands, live cards,<br/>queues, /learn, /costs"]
    end

    subgraph brainbox["3 · agent-brain (the brain)"]
        HTTP["HTTP :8765<br/>RAG · /costs · /learn"]
        MCPB["brain-mcp :8767<br/>rag_query · memory_search"]
        WORKER["memory worker<br/>turn → memory"]
        NIGHTLY["nightly cycle 04:00<br/>reindexes · evaluates · grades"]
    end

    subgraph modelbox["4 · The models"]
        NOMIC["ollama · nomic-embed<br/>text → vector (local, free)"]
        DEEP["deepseek API<br/>extracts and judges (remote)"]
        MLXB["MLX · Qwen2.5-3B<br/>trains the LoRA adapter"]
    end

    PG[("5 · Postgres<br/>bus · memories · chunks<br/>eval_runs · turn_costs")]
    LAUNCHD["6 · launchd — the guardian:<br/>revives what dies"]

    TG -->|"messages"| RT
    RT -->|"turns and taps"| ZC
    RT -->|"publishes events"| PG
    ZC -->|"MCP tools"| MCPB
    HTTP -->|"reads and searches"| PG
    MCPB -->|"reads and searches"| PG
    WORKER -->|"reads pending turns"| PG
    WORKER -->|"extracts"| DEEP
    WORKER -->|"writes memories"| PG
    NIGHTLY -->|"reindexes repos"| PG
    NIGHTLY -->|"judges and grades"| DEEP
    NIGHTLY -->|"one summary line"| TG
    HTTP -->|"query embed"| NOMIC
    LAUNCHD -.-> botbox
    LAUNCHD -.-> brainbox

    classDef data fill:#dbeafe,stroke:#1e40af
    classDef model fill:#dcfce7,stroke:#166534
    classDef guardian fill:#e5e7eb,stroke:#374151,stroke-dasharray: 5 5
    class PG data
    class NOMIC,DEEP,MLXB model
    class LAUNCHD guardian
```

**The walkthrough, step by step:**

1. You write from the **phone** (Telegram) or from the **desktop** (ZCode). They are the two entry doors — two harnesses, one and the same brain behind.
2. The Telegram message reaches the **bot's router**, which assembles the turn, shows the live card and sends it to the fork so it runs the agent loop.
3. Everything that happens in the turn stays **published as events on the bus** (Postgres): every prompt, every tool call, every answer, every tap of yours.
4. The fork, while running the turn, can call **MCP tools of the brain** (`rag_query`, `memory_search`) — that is how the agent "remembers" and "consults your docs" without you doing anything.
5. The brain's **memory worker** reads the completed turns that stayed pending and asks deepseek to extract the durable facts → they stay as memories.
6. **Search** (when someone asks) reads Postgres: the vector leg embeds the query with nomic and searches by meaning; the word leg searches literally; RRF fuses.
7. At **04:00** the nightly cycle backs up, reindexes the repos and runs ALL the evals — and sends ONE line to Telegram with the verdict.
8. **launchd** watches: bot, brain, colima (the Docker VM where Postgres runs) and the nightly are LaunchAgents — if something dies, it revives on its own.

### 4.2 THE JOURNEY OF A QUESTION (the RAG inside)

*The intuition first: there are TWO ways of searching. By **meaning**: you turn the question into a vector and look for the chunks whose vectors point in a similar direction ("why did the rerank stay off?" finds a doc that never says "off" but explains the verdict). By **words**: you search the literal terms, like the file finder (necessary for identifiers such as `traverseSettled` or `KeepAlive`). Neither is enough alone: the hybrid uses both and fuses.*

```mermaid
sequenceDiagram
    participant U as You (the question)
    participant B as brain (HTTP or MCP)
    participant E as QueryExpansion
    participant V as pgvector (by meaning)
    participant F as FTS (by words)
    participant R as RRF (the fusion)

    U->>B: "why did the rerank stay off?"
    B->>E: expand the query
    E->>E: if it is Spanish: rephrase into EN keywords
    E->>V: leg 1: original query (+ EN leg if it changed)
    E->>F: same legs, word side
    V-->>R: dense ranking (top-K by meaning)
    F-->>R: lexical ranking (top-K by word)
    R-->>B: ONE fused list, each doc adds 1/(60+position) per leg
    B-->>U: chunks + citations (doc path) → the model answers LEANING on them
```

**What to see in this graph:**

- <mark class="law">**Expansion can only add.**</mark> If the English reformulation turns out IDENTICAL to the original, there is no second leg (we do not spend); if it is different, they are two searches that RRF fuses. A doc that appears well ranked in both legs wins. In the worst case the result is the same as without expansion — that is why the rule is "only adds".
- <mark>**RRF has no weights to tune.**</mark> Every document adds `1/(60 + position)` in each list where it appears. Example with numbers: doc A came out 1st by meaning and 3rd by words → `1/61 + 1/63 = 0.0323`. Doc B came out 2nd in a single leg → `1/62 = 0.0161`. A wins. That simplicity IS the feature: there are no knobs to adjust and no bias from the tuner.
- **Citations are part of the contract.** Every chunk carries the document path (`docs/probe-rerank-crossencoder.md`); the answer the model assembles cites those paths. That is grounding: the answer LEANS on what was retrieved and you can verify it.
- **The latency cost:** this whole journey (two legs + fusion) takes p50 ~20-33 ms. The query embed goes to LOCAL nomic — if it were a remote API, every question would pay a network round-trip.

### 4.3 THE JOURNEY OF A TURN → MEMORY (the extraction worker)

*When a turn ends, nothing is extracted at that moment. The turn stays as a `turn.completed` event on the bus, and a worker processes it later. Why this way? Two reasons: (1) extraction calls a model and takes ~6 s — putting it in the turn's path would slow your answer down; (2) if deepseek goes down, the turn is ALREADY answered and the memory recovers on its own later.*

```mermaid
flowchart TB
    BUS[("bus: turn.completed")]
    Q{"is it pending?<br/>(no memory_at and attempts < 5)"}
    BR{"circuit breaker<br/>is it OPEN?"}
    DS["deepseek: extract facts<br/>as JSON with schema"]
    OK{"valid JSON?"}
    DEDUP{"dedup: similarity ≥ 0.90<br/>with a live memory?"}
    REIN["REINFORCES the old one<br/>(salience +1.0)"]
    INS["INSERTS the new one"]
    QUAR["quarantine<br/>(the evidence stays,<br/>it is not lost)"]
    MARK["marks memory_at<br/>and publishes memory.created"]
    WAIT["retry with exponential<br/>backoff (does not consume<br/>an attempt if it was the breaker)"]

    BUS --> Q
    Q -->|no| DONE["nothing to do"]
    Q -->|yes| BR
    BR -->|OPEN| WAIT
    BR -->|CLOSED| DS
    DS -->|timeout / error| WAIT
    DS -->|answers| OK
    OK -->|no| QUAR
    OK -->|yes| DEDUP
    DEDUP -->|yes| REIN
    DEDUP -->|no| INS
    REIN --> MARK
    INS --> MARK
    WAIT --> BUS

    classDef data fill:#dbeafe,stroke:#1e40af
    classDef model fill:#dcfce7,stroke:#166534
    classDef reject fill:#fee2e2,stroke:#b91c1c
    classDef retry fill:#fef3c7,stroke:#b45309
    class PG data
    class DS model
    class QUAR reject
    class WAIT,MARK retry
```

**The three laws this graph hides:**

1. <mark class="law">**The breaker does not consume an attempt.**</mark> If the fuse is OPEN (deepseek just failed 5 times in a row), the turn goes back to the queue WITHOUT spending one of its 5 attempts. The logic: the breaker is state of the PROVIDER (deepseek is unwell), not of the turn (the transcript is not the problem). Punishing the turn for someone else's outage would lose good memories.
2. <mark class="law">**Dedup is at write time.**</mark> Before inserting, the new fact is compared against the live memories: if the cosine similarity passes 0.90, another copy is not created — the existing one is REINFORCED. The 0.90 boundary is MEASURED: truly duplicated pairs gave 0.9123-0.9845 and the strongest "related but distinct" pair gave 0.8789. The 0.90 fell in the gap between the two groups.
3. <mark class="law">**Nothing is thrown away.**</mark> Invalid JSON goes to quarantine (the evidence stays for auditing); the old memory invalidated by reconcile keeps `invalid_at` (designed history, not corruption).

### 4.4 MEMORY INSIDE (lifecycle, graph and reconcile)

*Memory has two clocks: `created_at` (how fresh it is) and `last_accessed` (how useful it turned out to be). The combination of salience (importance, rises with use) × decay (cooling over time without use) classifies it into three states — and the classification is computed AT RUNTIME, not stored: every memory "is" what its numbers say today.*

```mermaid
stateDiagram-v2
    state "ACTIVE — enters searches" as ACT
    state "COOLING — still alive, loses priority" as COOL
    state "ARCHIVED — excluded from search (NEVER deleted)" as ARC

    [*] --> ACT: it is inserted (initial salience)
    ACT --> COOL: salience×decay falls below 0.5
    COOL --> ARC: it keeps falling below 0.125
    COOL --> ACT: a search hit uses it (+0.1 salience)
    ARC --> ACT: reappearing in a merge resurrects it (+1.0)
    note right of ARC
        Forgetting = excluding from
        search. The datum stays.
    end note

    style ACT fill:#dcfce7,stroke:#166534
    style COOL fill:#fef3c7,stroke:#b45309
    style ARC fill:#e5e7eb,stroke:#374151
```

**The graph (the "related" of memory_search):** besides the text, every memory has edges toward others: **related** (similarity in the 0.60-0.88 band — similar but not duplicated), **cooccur** (they appeared together in the same search) and **temporal** (they are from the same period). ~27k edges in production. When you search and a hit arrives, the traversal walks its edges and brings its neighbors — it is the brain's "and this is related". The measured SQL lesson: the symmetric OR (`a=b OR b=a`) generated 85.7M intermediate rows (33-35 s); two indexed joins united with UNION ALL do the same work in 223 ms.

**Reconcile (the cure for contradictions):** when two memories fall in the related band, the judge compares them and decides: **same** (it is the same claim → merge), **supersede** (the new one replaces the old one → the old one stays marked `invalid_at`, with verdict and evidence) or **distinct** (they coexist). Retrieval filters `invalid_at IS NULL`: a single valid row per fact. Concrete example: "the extractor is qwen 1.5b" was invalidated when "the extractor is deepseek-v4.1-flash" passed the verdict — the first was NOT deleted: it stayed as history with its replacement stamp.

**Dream:** once a week, outside the session, the system consolidates: it clusters the ones in COOLING, merges duplicates that dedup did not catch, prunes the dead. It is "sleeping and tidying up" — inspired by how Letta describes consolidation.

### 4.5 THE HARNESS: THE AGENT LOOP (what happens when you press Enter)

*A "harness" is the program that surrounds the model: it gives it tools, sets limits, and runs the cycle. Without a harness, an LLM only completes text. With a harness, it can DECIDE to call a tool, look at the result, and keep reasoning. That cycle is the agent loop — and a "turn" is one full round until the sealed answer.*

```mermaid
flowchart TB
    PROMPT["your message<br/>(Enter or tap)"]
    CTX["the CONTEXT that enters the window is assembled:<br/>system prompt + history + injected memory (≤1500 chars,<br/>1.5 s deadline, silence if the brain is down)"]
    MODEL["the model thinks<br/>(it can reason step by step)"]
    DEC{"does it want to call<br/>a tool?"}
    PERM{"does the permission allow it?<br/>(fail-closed: when in doubt, ask)"}
    TOOL["the tool runs<br/>(Bash, Read, rag_query MCP, ...)"]
    BACK["the RESULT goes back into the window"]
    SEAL["final answer → the turn is SEALED"]
    EVENTS["turn.completed to the bus:<br/>model + tokens + stop_reason"]
    FEED["your 👍/👎 → turn.feedback"]

    PROMPT --> CTX --> MODEL --> DEC
    DEC -->|"yes"| PER
    PERM -->|"yes"| TOOL --> BACK --> MODEL
    PERM -->|"no"| SEAL
    DEC -->|"no, it already has the answer"| SEAL
    SEAL --> EVENTS --> FEED

    classDef ctx fill:#fef3c7,stroke:#b45309
    classDef decision fill:#dbeafe,stroke:#1e40af
    class CTX ctx
    class DEC,PERM decision
```

**What this graph teaches:**

- <mark>**The window is a scarce and ASSEMBLED resource.**</mark> The model does not "see everything": it sees what the harness decides to put in — system prompt, history, the injected memory, the tool results. That decision (what enters and in what order) is the discipline 2026 calls **context engineering**. Your most measurable version: memory enters with a 1500-char cap and a 1.5 s deadline — if the brain does not answer in time, the turn goes on WITHOUT memory rather than stalling.
- **Tools are the world's entry point.** `rag_query` and `memory_search` are MCP tools that the brain exposes — the model calls them when it decides it needs your docs or your memory. Every tool's contract is a JSON Schema (which arguments it accepts).
- **Permission is fail-closed.** When in doubt, the harness asks instead of executing. The same philosophy as the socket auth: byte-identical 401, the process does not even start without a token.
- **The sealed turn is what feeds everything else:** turn.completed reaches the bus (with model and tokens → turn_costs) and from there come the memory extraction, the shadow judge, /learn. The loop does not end in the answer: it ends in the DATUM.

### 4.6 THE NIGHTLY CYCLE (the machine audits itself)

```mermaid
flowchart LR
    LD["launchd fires<br/>at 04:00"]
    BK["pg_dump backup<br/>(03:40, drill PASS)"]
    IDX["reindex of the repos<br/>(new docs → chunks → vectors)"]
    EV["runs ALL the suites:<br/>recall-core · real-questions · contradiction ·<br/>memory · extraction · multi-hop"]
    US["usage eval:<br/>grades yesterday's REAL queries"]
    SH["shadow judge:<br/>grades the bot's new turns"]
    LED[("ledger eval_runs:<br/>one row per run — the history<br/>that is not rewritten")]
    TG["ONE line to Telegram:<br/>green or red, honest"]

    LD --> BK --> IDX --> EV --> US --> SH --> LED --> TG

    classDef data fill:#dbeafe,stroke:#1e40af
    classDef output fill:#dcfce7,stroke:#166534
    class LED data
    class TG output
```

**Why it is nightly and not continuous:** (1) the reindex reads the repos and embeds — doing it live would compete with your queries for the same ollama; (2) evals are the CERTIFICATION — once a day is enough to see trends, and every run leaves its row in the ledger so the trend is never lost; (3) in the morning you have ONE line that sums up whether the system is still healthy. If something fails, the line is red — the law of honest indicators: <mark class="warn">better red silence than a green ticker that lies</mark>.

### 4.7 THE LORA PIPELINE (from your traffic to your model)

```mermaid
flowchart TB
    subgraph export["1 · EXPORT (the dataset comes out of real use)"]
        POS["986 positives:<br/>transcript → canonical extraction"]
        NEG["31 negatives:<br/>degenerate ones from quarantine → empty"]
        TRIV["58 trivial ones:<br/>short turns → EMPTY extraction"]
    end
    SPLIT["2 · SPLIT: FINAL 10% hold-out<br/>ONLY of positives (the empties all go to train)"]
    OVER["3 · OVERSAMPLE: empties ×3<br/>→ 24% of train"]
    TRAIN["4 · TRAIN on MLX (your GPU):<br/>Qwen2.5-3B 4-bit, LoRA on 16 layers,<br/>mask-prompt, lr 1e-4, 800 iters"]
    ADPT["5 · the ADAPTER (0.216% of the parameters)"]
    SERVE["6 · SERVE: base + adapter locally"]
    GATE["7 · GATE: extraction-core 6 questions<br/>vs deepseek 6/6 (the incumbent)"]
    VERD{"verdict"}
    SWAP["extractor swap<br/>(does not pass yet: 5/6 < 6/6)"]
    LEDGER["row in the ledger + one line<br/>to Telegram (noisy if it fails)"]

    POS --> SPLIT
    NEG --> SPLIT
    TRIV --> SPLIT
    SPLIT --> OVER --> TRAIN --> ADPT --> SERVE --> GATE --> VERD
    VERD -->|"wins"| SWAP
    VERD -->|"loses"| LEDGER
    SWAP --> LEDGER

    classDef data fill:#dbeafe,stroke:#1e40af
    classDef model fill:#dcfce7,stroke:#166534
    classDef gate fill:#fef3c7,stroke:#b45309
    class POS,NEG,TRIV data
    class TRAIN,ADPT,SERVE model
    class GATE,VERD gate
```

**The full pipeline ran in your house, end to end:** export (the pairs come out of the bus's real traffic) → train (MLX on Apple Silicon, the 4-bit base model to fit in 16 GB) → serve (the adapter is mounted on the base) → gate (the same 6 questions, against the incumbent) → ledger (the verdict stays written). Final number of the data pass: **5/6, p50 4.8 s, $0** — deepseek keeps the seat with 6/6, and the house rule was applied to the home model just as to a provider: the evals kill it or it ships.

### 4.8 THE SYSTEM'S NUMBERS (to have them at hand)

| Measurement | Number | What it means |
|---|---|---|
| RAG corpus | 20,722 docs / 72,934 chunks | everything the system "read" |
| recall-core | 33/33 · MRR 0.9848 | on the fixture, the correct doc is almost always 1st |
| Same questions vs REAL corpus | recall@5 0.697 / MRR 0.608 | production is another game (fixture blindness) |
| Search latency | p50 ~20-33 ms | the full journey of 4.2 |
| Memories | ~4.4k live · ~27k edges | what the system "remembers" |
| Extraction | p50 ~6 s (deepseek) / 4.8 s (LoRA) | per turn, OUTSIDE the answer's path |
| Memory injection | ≤1500 chars · 1.5 s deadline | the measured context engineering |
| Traversal turnaround | 223 ms (before 33-35 s) | the UNION ALL law |
| Backups | daily 03:40 · drill PASS | restoration verified, not assumed |

---

## 📖 PART 5 — THE DICTIONARY (every word, from scratch)

*This part assumes ZERO prior knowledge. First the base words (5.0: computing and systems — the ones the rest of the book uses without noticing), then the AI ones in order: models → RAG → memory → evals → agents → infra → your FP dialect. Every entry: what it is, an example, and where it lives IN YOUR system with its number.*

### 5.0 THE BASE WORDS (before starting)

**Program / process / service / daemon** — a *program* is the code on disk; a *process* is that code RUNNING (it has a pid, live memory); a *service* or *daemon* is a process designed to stay running forever, without a terminal, waiting for work. 💡 **Example:** the bot is a daemon: it lives asleep until a Telegram message arrives. 📍 **In your system:** brain, bot and ollama are daemons; launchd is what keeps them alive.

**Port** — the "door" number through which a process listens for requests on your machine (0-65535). Two processes cannot use the same port at the same time — that is why each one has its own. 📍 **In your system:** brain HTTP **8765**, brain-mcp **8767**, bot **9102**.

**Unix socket** — a "door" that does not use the network: a special file through which two processes on the SAME machine talk faster and more privately than over TCP. 📍 **In your system:** the fork's "local daemon" channel — with mandatory auth: without a token, it does not even start.

**API (application programming interface)** — the set of functions a program exposes so that ANOTHER program can use it, without knowing its innards. 💡 **Example:** the brain exposes `GET /costs/today` — any client asks and receives JSON, without knowing how the brain computes it. "Calling the deepseek API" = asking its server for text over HTTP.

**HTTP / endpoint** — the web protocol (requests and responses) and each of its serviceable "addresses". 💡 **Example:** `http://localhost:8765/health` is the brain's health endpoint — the sentinel touches it to know if it is alive.

**JSON** — the structured text format with which programs exchange data: `"key": value` pairs, lists, nested objects. 💡 **Example:** a memory travels as `{"kind":"fact","content":"...","salience":0.8}`. Readable by humans and by any language.

**Schema** — the CONTRACT of what a valid datum must look like: which fields, of what type, which values allowed. A JSON that does not satisfy the schema is rejected at the boundary. 📍 **In your system:** the memory extractor answers JSON with a strict schema; each MCP tool's inputSchema is the contract of its arguments.

**Database / table / row / column** — the database is the program that stores and queries data with guarantees (Postgres); the table is each "spreadsheet" (memories, chunks, eval_runs); each row is a record; each column a field. 💡 **Example:** every memory is ONE row of the memories table.

**SQL** — the language for asking a relational database (`SELECT ... FROM ... WHERE ...`). 📍 **In your system:** ALL data access goes through typed SQL (SQLDelight compiles and verifies the queries against the schema before running).

**Index (database)** — an extra structure the database maintains TO SEARCH FAST without scanning the whole table — like a book's subject index. 💡 **Example:** without an index, finding a memory's edges scans 27k rows; with the index, it goes straight there. The most extreme measured difference in your system: 33-35 s → 223 ms by choosing UNION ALL of indexed joins.

**Migration (schema)** — a script that changes the database structure (add table, column, index) in an ordered and versioned way (0001, 0002, ...). It runs when the brain starts, with a lock so that two instances do not migrate at the same time. *The measured trap:* if the migrations index loses a line, the migrator SKIPS a migration in silence.

**Queue / worker** — the queue is the list of pending work; the worker is the process that consumes it one at a time. It decouples "something happened" from "process it now". 📍 **In your system:** pending = turns with `turn.completed` and without `memory_at`; the memory worker eats them one at a time, with retries if they fail.

**Latency / p50 / p95** — latency is how long ONE operation takes end to end. p50 (median) is the time that half the operations do not exceed; p95 is what the left tail does not exceed — the "common worst case". 💡 **Example:** a search at p50 ~22 ms with a p95 of 2 s reads as "normally instant, sometimes there is a queue". p95 rules in experience, p50 in cost.

**Prompt / completion** — the prompt is what you FEED the model (instruction + context + question); the completion is what it produces. 📍 **In your system:** the extractor's system prompt contains the extraction schema; the completion IS the extraction.

**Repository / git / commit / push** — the repo is a project folder with its versioned history; a commit is a saved point of the history with a message; push uploads it to the remote. *The house rule:* commit+push for every finished logical unit — a stall must not be able to erase hours of work.

### 5.1 MODELS AND LLMS

**LLM (large language model)** — an ENORMOUS language model trained with a single objective: predicting the next piece of text. From that apparently dumb skill, trained on billions of words, the ability emerges to follow instructions, reason step by step and write code. It "knows" nothing about your life or your repos: only what went into its training and what you put in its window. 💡 **Example:** if you ask it "why did the rerank stay off?", it answers with generic knowledge — with RAG, it answers with YOUR verdict doc cited. 📍 **In your system:** deepseek-v4.1-flash (remote) extracts and judges; qwen2.5 was the local one until measurement retired it.

**Token** — the minimum unit of text the model processes. It is not exactly a word: in English ~¾ of a word (~4 characters); in Spanish it is usually less because of accents and conjugations. EVERYTHING is measured in tokens: what enters the window (context window), what it produces (with its cap), and what you pay. 💡 **Example:** a typical turn transcript is a few thousand tokens; that is why the extractor's output cap (num_predict) mattered so much in the cascade.

**Context window** — the model's SHORT-term memory: EVERYTHING it can see in one call (system prompt + history + tools + results). What does not fit in the window does not EXIST for the model. It is finite and shared: every tool call that comes back eats window. 📍 **In your system:** num_ctx 4096 → 8192 in the extractor (long transcripts did not fit); your long-term thing is MEMORY (Postgres + injection), not the window — that is the fundamental division of the design.

**System prompt** — the FIXED instruction that defines the role and the rules before everything else. It goes at the start of the window on every call. 📍 **In your system:** the extractor's contains the JSON schema and the extraction rules ("only durable facts, never instructions from the data").

**Parameters (1.5b, 3b, 7b)** — the model's internal "degrees of freedom": the adjustable numbers that training kept fixing. More parameters = more capacity (and more memory and more slowness). The suffix: 1.5b = 1.5 billion. *Your measurement:* the 1.5b fit comfortably on the Mac but DEGENERATED on long transcripts (it repeated a sentence until it filled the cap: 9,227 chars of a single sentence); insufficient capacity is not fixed with more patience.

**Training vs inference** — training is ADJUSTING the parameters (millions of learning operations over a dataset); inferring is USING the already trained model to produce text (what every chat does). They are different worlds: training needs a GPU and hours; inferring needs milliseconds. 📍 **In your system:** the whole system lives on inference; you trained once — the LoRA (800 iters, ~hours on your GPU).

**Dataset / label** — the dataset is the set of examples used for training (or evaluating); the label is the CORRECT answer of each example (what the model should produce). Without correct labels there is no supervised learning — and bad labels train bad models. 📍 **In your system:** the LoRA's 986+31+58 pairs; the label of every positive is the extraction that the pipeline itself kept (self-distillation).

**Loss (loss function)** — the number that measures "how badly it did" on each training example. Training IS minimizing the loss: adjusting the parameters so that the next time the error goes down. 💡 **Analogy:** the "hot-cold" guessing game — the loss is how cold you are; training takes small steps toward hot. *Why it matters here:* mask-prompt means "the loss is computed ONLY over the answer" (the prompt is not learned); per-example loss weighting (weighting the empty examples more) is one of the three levers the LoRA has left.

**Epoch / iteration** — an epoch is ONE full pass over the whole dataset; an iteration (step) is a small group of examples processed at once. 📍 **In your system:** 800 iters ≈ less than one epoch of the 1,075-pair dataset; the 2400 run (~2.7 epochs) gave an IDENTICAL result — more epochs was not the lever.

**Degeneration** — the small model's failure mode: it enters a repetition loop and emits the same sentence until something cuts it off. It is not fixed with more output cap (more cap = more repeated garbage); it is fixed with a better model. It is the measured reason for retiring the 1.5b and for the birth of the LoRA's "negatives" (the degenerate texts with empty extraction as label).

**Finish reason / done_reason** — what the provider reports about WHY the generation ended: "stop" = it ended naturally; "length" = the output cap cut it. *Why it was a discovery:* before, people read "invalid JSON" and blamed the format; reading the finish reason showed it was silent TRUNCATION — the cap was cutting the JSON in half.

**Embedding model** — a model that does NOT converse: it turns a text into a VECTOR (a list of 768 numbers) such that texts with similar meaning produce vectors with a similar direction. It is a TRANSLATOR from meaning to geometry. 🔑 *The sentence that nails it: deepseek talks, nomic measures.* 📍 **In your system:** nomic-embed-text served locally by ollama, free; it won the matrix against all-minilm (MRR 0.985 vs 0.909) and stayed.

**Chat model** — the one that produces text: it converses, extracts, judges, reasons. It is the embedding's other world: one writes, the other measures. Neither replaces the other — confusing them is like confusing a writer with a measuring instrument. 📍 **In your system:** deepseek (remote) for critical quality; the 3B LoRA aspires to that seat.

**Local inference vs API / provider split** — running models on YOUR machine (free, private, with capacity limits) vs calling a provider's model by API (paid, no capacity limit, your data travels). The architecture decision measured in your system: **the VOLUME leg goes local** (embeddings: ~95% of the calls, $0 marginal, 20-33 ms, works without the relay, private content) and **the critical quality leg goes remote** (extraction of giants, judge). *The remote's limit:* deepseek is GENERATIVE — it cannot embed at low or high effort (effort ≠ model type); a remote embedder would be ANOTHER product, with per-token pricing that grows forever.

**Fine-tuning** — training an already trained model again, with your data, so it learns a specific behavior. An alternative (complementary) to giving it instructions in the prompt: the knowledge stays INSIDE the weights. 📍 **In your system:** the LoRA extractor — trained to produce the pipeline's canonical extraction.

**LoRA (Low-Rank Adaptation)** — the CHEAP form of fine-tuning: freeze the base model and train only small patches (low-rank adapters mounted on certain layers). A minimal fraction of parameters is trained, on your GPU, in hours — and the resulting file is small. *Your number:* 0.216% of the parameters trainable. 💡 **Analogy:** instead of rewriting the whole book, you add post-its in the margins.

**QLoRA** — LoRA on the QUANTIZED model (compressed to 4 bits): training fits in normal hardware. It is what makes it possible to train a 3B on a 16 GB Mac. Your MLX run is exactly this (4-bit + LoRA).

**Adapter** — the file with ONLY the deltas the LoRA learned. It is served by MOUNTING it on the base: base + adapter = tuned model. Without the adapter, the base stays intact — that is why experimenting is safe. 📍 **In your system:** `evals/lora/adapter-data-pass/` (gitignored, private).

**Quantization** — compressing the model using fewer bits per parameter so it fits and runs on your hardware. 💡 **Analogy:** the MP3 of audio — it loses imperceptible fidelity, gains size and speed. What ollama does with GGUF files and MLX with your 4-bit Qwen.

**SLM (small language model)** — the small ones (1.5b-3b): they run locally, free, fast… and they have a ceiling. *Measured twice:* for judging, 5/8 vs deepseek's 8/8; for long transcripts, degeneration. For simple volume they are enough; for critical quality, no.

**Token budget** — the spending cap in tokens per turn or feature — the budget that keeps a giant conversation from being a giant payload. It stopped being an estimate when turn_costs started counting real tokens per turn.

#### 5.1.1 THE HOME FINE-TUNING VOCABULARY (what the LoRA taught)

**Self-distillation** — training with the pipeline's own output as label: the 986 positives ARE extractions the system already did well. There is no human annotation round (very expensive); the cost is the CEILING: the dataset cannot teach better than the teacher (deepseek) already did. That is why the gate compares against deepseek and not against perfection.

**Mask-prompt** — computing the loss ONLY over the answer, masking the prompt: the model learns to PRODUCE extractions, not to repeat instructions. Without this, a good part of training is spent memorizing the statement.

**Trivial pairs** — the real short turns ("go ahead", "ok", "let's continue") with EMPTY extraction as label. They teach the hardest discipline for a model trained to "be useful": staying quiet. *The label is honest because the owner put it there:* your manual pruning of 75 tiny memories ("Merged to master", "## Now") IS the correction the dataset encodes.

**Negatives** — the degenerate transcripts from quarantine with empty extraction as output: examples of "this garbage does not produce facts".

**Oversampling** — repeating the rare class in train so the loss sees it with the frequency that matters. 📊 **The number:** empties were 3.5% of the original dataset (the model learned "always extract something"); with the trivial leg + oversample ×3 they reached 24% of train — and the content needle passed.

**Final hold-out** — the most RECENT 10% is set aside BEFORE training and is NOT used for training: the gate measures "future" turns (which the model did not see), not memory. Choosing it at random would leak information through the time window (neighbor turns from the same days).

**Hold-out of positives** — the measured correction: the hold-out is sampled ONLY from cases with content; negatives and trivial ones all go to train. The naive split had sent almost all empties to the holdout (0.5% in train) — the holdout must measure what the GATE measures (content), not what it does not.

**Seesaw (up-and-down)** — the risk of tuning a dataset's balance: you fix one behavior and break the other. Measured: earlier runs passed small-talk and failed the content needle; the data pass flipped the result. The 6/6 needs TWO stable behaviors at once.

### 5.2 RAG AND RETRIEVAL

**RAG (Retrieval-Augmented Generation)** — the technique that solves the bare LLM's two limitations: it does not know YOUR data and it cannot cite. The idea: before answering, you RETRIEVE the relevant pieces of your knowledge and hand them to the model as context — the model answers LEANING on them and with the source. *Everyday example:* it is the difference between asking someone "why did the rerank stay off?" and also handing them the verdict document open at the right page. 📍 **In your system:** 20,722 docs indexed; questions in Spanish with citations to your repo paths.

**Corpus** — the set of indexed documents — EVERYTHING the system "read" and can retrieve. RAG quality has two parents: retrieval AND the corpus (a doc that does not exist is a miss no algorithm fixes — the 11-17 lesson: the misses were CORPUS, not language).

**Chunk / chunking** — cutting each document into pieces of just the right size to embed and serve. Small = fine search but poor context; large = rich context but coarse search. *Your recipe:* every chunk starts with "path > heading" — the heading travels WITH the piece, so a code fragment knows which doc it belongs to (the home-made, free version of Anthropic's Contextual Retrieval).

**Vector / embedding** — the list of 768 numbers that represents the MEANING of a text. The magic property (which is not magic, it is training): texts of similar meaning produce vectors that point similarly. It does not encode the WORDS (different synonyms can end up almost equal; the same word in different senses separates). 📍 **In your system:** each of the 72,934 chunks has its 768-dimension vector stored in pgvector.

**Cosine similarity** — the measure of similarity between two vectors: the COSINE of the angle between them. 1.0 = same direction (same meaning), 0.0 = orthogonal (nothing to do with each other). The angle is used and not the distance because the DIRECTION of the meaning matters, not the "magnitude" (a long text and a short one about the same thing can point the same way). *Your measured bands:* ≥0.90 is "the same claim" (dedup); 0.60-0.88 is "related but distinct" (that is where reconcile works); true duplicates measured 0.9123-0.9845 and the strongest related pair 0.8789 — the 0.90 fell in the gap.

**Vector database / pgvector** — an engine for storing vectors and searching "the ones most similar to this one". pgvector is the extension that adds that capability to Postgres — your usual database with a new superpower, without another system to operate. *The search:* `ORDER BY embedding <=> $query` (cosine distance).

**HNSW / ANN** — the index that makes vector search SCALABLE. ANN = approximate nearest neighbor; HNSW is the concrete index: a graph of layers — the coarse one on top connects long "highways", the fine ones below connect neighbors — and the search DESCENDS layer by layer refining, comparing a few hundred candidates instead of the 73k. 💡 **Analogy:** traveling from Buenos Aires to a street in Córdoba: first the national route (coarse layer), then the avenue, then the block. *Your two measured laws:* (a) an "alive" semi-join under the ORDER BY distance DESTROYS the index — the planner falls back to an exact scan (that is why your search was always EXACT without you knowing it); (b) the inline rewrite left the query HNSW-capable for when the corpus grows.

**Exact scan vs ANN** — exact compares against ALL the vectors (slow at scale, perfect); ANN approximates (fast, it can miss by a little). With 73k chunks exact takes milliseconds — that is why you never noticed the difference. HNSW is the insurance for when there are millions.

**FTS / BM25 (sparse retrieval)** — the other leg: search by WORDS with lexical ranking (BM25 is the classic algorithm that weights terms by rarity and frequency). It finds what the vector does not see: the exact identifier, the proper name, the literal error. 💡 **Example:** searching for `traverseSettled` — the vector brings generic "concurrency utils"; FTS goes straight to the doc that names it. That is why the hybrid is the production standard.

**Hybrid retrieval** — running both legs (dense=vector + sparse=words) and fusing. Each one covers the other's blindness. Your de facto standard.

**RRF (Reciprocal Rank Fusion)** — the formula that fuses rankings without weights: every document adds `1/(60 + position)` in EACH list where it appears, and they are sorted by total. *Numeric example:* doc A = 1st in one leg and 3rd in another → 1/61 + 1/63 ≈ 0.0323; doc B = 2nd in a single one → 1/62 ≈ 0.0161. A wins for appearing well in BOTH. *Why K=60:* it is the value from the original paper — it dampens top positions from dominating; it works so well without tuning that tuning it is unnecessary (zero knobs = zero bias from the adjuster).

**Rerank (re-ranking)** — the SECOND pass: after search brings the top-K, a dedicated model RE-ORDERS them by looking at query and document together with more attention than the first pass could afford. It can raise the gold that ended up third. In your stack it is implemented and MEASURED — and disabled, because no tested variant won in production.

**Cross-encoder** — the serious rerank architecture: a model that receives the PAIR (query + document) TOGETHER at the input and returns a direct relevance score. By seeing them together, it catches fine interactions that two separate embeddings do not see. *The probe's verdict:* bge-reranker-base is the FIRST mechanism that beat your fusion (recall@1 0.439 → 0.537 on curated candidates). Integration on hold: on real queries it got worse (0 improved / 4 worsened) and it costs ~25-50× latency — it reopens when the usage eval shows real misses.

**Listwise rerank** — the architecture that lost: giving the LLM the full LIST of docs and asking it to re-order it. Two negative verdicts (recall@1 0.350 vs fusion's 0.850; and +0.004 MRR on the second attempt, for 100-1000× the latency). Default OFF forever — the discipline of not shipping what the evals killed.

**Leg dilution** — the structural risk of fusing legs: a mediocre document that appears in SEVERAL legs at a good position accumulates more RRF than an EXCELLENT one that appears in a single leg. *Measured live:* when multi-hop was added, a real query sank from rank 2 to outside the top-10. The cure: composing the legs carefully (and in the future, weighting them).

**recall@k** — the gate metric: of the questions with a known answer, in how many does the correct document appear among the first k results? It is BINARY per question (it is there or it is not) and does not care about internal order. 💡 **Example:** recall@5 = 0.9 means that in 90% of the questions, the answer is in the top-5. Your gate: 0.9 on recall@5.

**MRR (Mean Reciprocal Rank)** — the position metric: for each question it takes `1/position` of the correct doc and averages. It punishes being low: 1st = 1.0, 2nd = 0.5, 10th = 0.1. 💡 **Example:** three questions with the correct doc at positions 1, 2 and 1 → (1 + 0.5 + 1)/3 = 0.833. Your number: 0.9848 (almost always first).

**nDCG@5** — like MRR but rewarding the WHOLE top-5 in order (a perfect list up top is worth more than the same list out of order). Useful when the user looks at several results, not only the first. Your number: 0.9888.

**Grounding / citation** — the requirement that the answer LEAN on the retrieved chunks, with the citations visible to verify. It is what turns "the model said" into "the document says and the model interpreted it". Your RAG returns chunks with uri; the grounding eval demands the correct citation.

**Distractor** — a document that SHARES vocabulary with the question but does not answer it — and that STEALS the position from the correct one. *Your corpus finding:* a doc that mentioned its neighbor's topic in passing took queries that belonged to the neighbor → the corpus law: single-concern docs (each doc talks about one thing).

**Needle** — the specific fact a question looks for: "the needle in the haystack". Needle-type evals verify that the system finds specific data and does not settle for similar topics. Your extraction-core suite has its needles (the content needle that the data pass closed).

**Query expansion / rewriting** — reformulating or translating the query BEFORE searching, to cover the gap between how you ask and how the corpus is written. *Your QueryExpansion:* Spanish → English keywords + verbatim identifiers; if the reformulation comes out identical to the original, it does not spend the second search. Law: expansion can only ADD recall.

**RAG Fusion** — the generalization: generating SEVERAL queries (by language, by angle, by decomposition) and fusing them all with RRF. Your bilingual expansion is the two case; multi-hop is the N case.

**Contextual Retrieval (Anthropic)** — the technique published by Anthropic: prepending to each chunk a context summary GENERATED BY AN LLM before embedding — it reports -67% retrieval failures on their benchmark. Your cheap version (the path+heading in each chunk) covers most of the benefit at zero cost.

**HyDE** — (Hypothetical Document Embeddings) embedding a hypothetical ANSWER instead of the question — because answer and document live in the same "vector language" (a question is short and interrogative; the doc is long and declarative). Useful for vague queries against a technical corpus. Not implemented: there is no signal that it is needed.

**Multi-hop / agentic loop** — treating retrieval as a CYCLE instead of a single pass: the agent DECOMPOSES the question into sub-queries, searches each one, fuses, and asks again if needed. What the 2026 web calls agentic RAG. *Your implementation:* `iterate` — 2-4 sub-queries fused by RRF ON TOP of the bilingual leg, opt-in because every decomposition costs a chat call.

**Composed legs** — the retrieval architecture law: a new stage (the English leg, the multi-hop sub-queries) enters AS ONE MORE LEG of the RRF, NEVER as a replacement for what already worked. *The A/B that proved it:* the first version of multi-hop replaced the bilingual leg and a real Spanish query sank from rank 2 to outside the top-10 — the fix was composing. A stage that can SUBTRACT recall is badly composed.

**Twin pairs** — the eval cases that turn the distractor law into tests: two docs that share vocabulary (dedup vs lifecycle; breaker vs sequential extraction) and a question that uses the words of both — the expected one is one, the distractor lurks. Your multi-hop suite is built from twin pairs: 5/5.

**GraphRAG** — Microsoft's variant: building a GRAPH of entities and relationships over the documents and summarizing communities, to answer questions that require connecting scattered data ("paper" multi-hop). Your MEMORY graph already exists (the ~27k edges); GraphRAG over DOCUMENTS does not — it is a candidate if real multi-hop questions ever ask for it.

**RAPTOR / sentence-window / parent-child** — hierarchical chunking strategies: indexing summaries AND details, or returning the window around the hit instead of the piece alone. Alternatives not implemented — no signal of need.

**Holdout / overfit (in retrieval)** — the risk that your suite measures what you curated and not what the system knows: if the eval questions were used to CHOOSE the corpus docs, 8/8 may be leakage. The check the judge pointed at: 50 NEW questions, never used for curation.

**Fixture blindness** — THE big lesson of the probe: the same 33 questions that give 33/33 against the CURATED corpus give recall@5 0.697 against the REAL corpus of 20,722 docs. The curated suite measures the laboratory; production is another game. The honest instrument is the usage eval (real traffic) — and every time a suite is saturated (33/33), it has to be read as "the fixture no longer discriminates".

**Memory benchmarks (LoCoMo / LongMemEval / BEAM)** — the standardized exams the industry uses to compare agent memory systems (Mem0 reports 92.5 on LoCoMo). The reference if you ever want a number comparable outward — with the caveat that a public benchmark never replicates YOUR traffic.

#### 5.2.1 EMBEDDINGS FROM SCRATCH (the four confusing questions, answered)

**What is HNSW?** Hierarchical Navigable Small World: pgvector's layered index. A graph of highways — the search DESCENDS from the coarse layer to the fine one and only compares a few hundred candidates instead of the 73k. Approximate but orders of magnitude faster than the exact scan. Your measured law: the "alive" semi-join DEFEATS it (the planner falls back to an exact scan) — that is why your numbers were always exact and clean without you knowing it.

**Why 768?** The one who trained the model decides it: it is the size of the encoder's internal layer, an ARCHITECTURAL constant (nomic=768, all-minilm=384, text-embedding-3-small=1536). And the lock: the pgvector column is created with ONE dimension — `vector(768)`. Changing embedding model = re-embedding the 20,722 docs + migrating the column. That is why all-minilm not only lost the matrix: switching would have cost a migration backwards.

**What distance is it and how did it learn the meaning?** pgvector uses the COSINE one (1.0 = same direction). And the meaning is not programmed: the encoder was trained CONTRASTIVELY with billions of pairs — same meaning is attracted, different is repelled. The geometry that remained IS the representation of meaning: nobody wrote "deploy ≈ desplegar"; the training pairs put them close on their own.

**Is a paid embedder worth it?** PRICE is not the blocker (cents per month). The three real blockers: (1) the schema lock, (2) the saturated suite — a better embedder cannot demonstrate a gain where the floor is 33/33: the usage eval has to show the misses FIRST, (3) the only gap with a number: the cross-lingual ES-EN one (0.55-0.65) that a strong multilingual model (bge-m3, voyage) could win. It reopens if the usage eval shows multilingual misses.

### 5.3 AGENT MEMORY

**Long-term memory** — what the agent knows BEYOND the conversation: it survives the session's close. The exact complement of the context window: the window is what it sees NOW, memory is what it ACCUMULATED. 📍 **In your system:** facts, decisions and preferences extracted by an LLM from every completed turn, stored as rows in Postgres, injected (the best ones) into new turns.

**Salience** — the weight of each memory: how much it matters. It is not fixed — it RISES when the memory proves useful (a search hit +0.1; a dedup merge +1.0) and feeds the lifecycle classification. 💡 **Analogy:** the order of a pile of papers that re-orders itself according to how much you use it.

**Decay** — the other force: a memory's value drops with time since its LAST USE (not since its creation). What is not used cools down. Your config: 30-day half-life. Together with salience it forms the pair that decides the state.

**Reinforcement** — the mechanism that raises salience: +0.1 per search hit, +1.0 when dedup or merge re-asserts the fact. The asymmetry is on purpose: being searched for is not worth what the world telling you again is.

**Lifecycle (ACTIVE / COOLING / ARCHIVED)** — the state machine: ACTIVE enters searches with priority; COOLING stays alive but decays; ARCHIVED stays EXCLUDED from search. The thresholds: salience×decay below 0.5 cools, below 0.125 archives. It is computed AT RUNTIME (no stored state: every memory "is" what its numbers say today) and — the law — forgetting is EXCLUDING from search, NEVER deleting: reinforcing resurrects.

**Dedup (0.90)** — at write time: if the new fact measures ≥0.90 similarity with a live memory, another copy is not created — the existing one is REINFORCED (+1.0). The boundary was not chosen: it was MEASURED (true duplicates 0.9123-0.9845; the strongest related pair 0.8789; the 0.90 fell in the gap). *Why it matters:* without dedup, every time you say the same thing the system fattens and searches fill up with copies.

**Reconcile** — the process that tidies the gray zone: pairs falling in the 0.60-0.88 band (similar but not duplicates) go to the judge, which decides **same** (same claim → merge), **supersede** (the new one replaces the old one) or **distinct** (they coexist). It is the cure for contradictions: the system that knows "the extractor was qwen" was invalidated by "the extractor is deepseek".

**Supersede / invalidation** — the replacement stamp: the old row keeps `invalid_at` + verdict + evidence, and retrieval filters `invalid_at IS NULL` — a single valid row per fact. Counting WITHOUT that filter counts designed history, not corruption.

**Memory graph / edges** — the edges between memories: **related** (they fell in the 0.60-0.88 band), **cooccur** (they appeared together in searches) and **temporal** (same period). ~27k in production. They give memory the structure of a NETWORK, not a list.

**Traversal (graph walk)** — walking the edges from a hit: you found memory A, its `related` ones bring context that the direct search did not see. It is the brain's "and this is connected to…". The measured SQL law: UNION ALL of two indexed joins — 223 ms where the symmetric OR cost 33-35 s (85.7M intermediate rows).

**Dream** — consolidation OUTSIDE the session (weekly, Letta style): it clusters the COOLING ones, merges late duplicates, prunes the dead. Like sleeping: tidying up the day's stuff while nobody waits. *Why the name:* memory consolidation during sleep is the biological analogy Letta popularized.

**Staleness** — a memory that LIES because the world changed: it was true when written and no longer is. Reconcile is the cure; the watchers are the symptom detector. It is THE problem of all agent memory — that is why invalid_at and the verdict matter more than deletion.

**Mem0 / Zep / Letta** — the reference commercial agent memory systems: Mem0 is the "plug-in" memory layer, Zep the stateful runtime, Letta (the MemGPT folks) the sleep-style consolidation approach. Your measured verdict: at your scale their value is not needed and — if it ever were — the swap is a module (the interface is designed), not a rewrite.

### 5.4 EVALS AND QUALITY

**Eval (evaluation) / eval suite** — the test of an AI system: questions with a known answer + automatic assertions. "Unit tests for AI answers". A suite is the set versioned in `evals/suites/*.json`. *The difference from a normal test:* the output is free text — that is why the assertions are about RANKING (is the doc there?) or about a judge (is the answer correct?).

**Ground truth** — the correct answer, written by a human, against which it is measured. Without ground truth there is no eval — there is a demo. *The usage eval nuance:* for a real open question there is no ground truth, but there IS EVIDENCE (the chunks served) — and with evidence the judge can grade.

**Gate** — the approved floor: passed/total ≥ gate or the change does NOT pass. Each suite has its own (recall 0.9, real-questions 0.8, usage and shadow 0.7). The law: a number below the gate is a FINDING to investigate — the gate is never lowered to make it pass.

**Ledger (eval_runs)** — the general ledger: EVERY run leaves a row (suite, passed/total, metrics, cost, timestamp) that nobody edits afterwards. It is what turns points into a TREND — and the trend is the real signal (one run is noise; thirty nights are a regression or an improvement).

**LLM-as-judge** — using a model to GRADE instead of answer. Powerful and dangerous: the canonical biases are **verbosity** (it prefers the longest answer), **position** (in pairwise comparisons, it prefers the one in a certain position) and **self-preference** (it prefers what looks like what it would write). Your countermeasures: a written rubric, judge ≠ performer (independence by context and role), single-output instead of pairs (eliminates position bias), trend over a single run, and THE DETERMINISTIC NEVER GOES TO THE JUDGE (an assert is enough, an assert does it).

**Shadow judge / shadow eval** — grading REAL traffic in the shadow: every night, the bot's completed turns are scored against the rubric (1-5, passes with 4+). "In the shadow" = nobody expected to be graded; the number is observation, not obstacle. The quality of the ANSWERS stopped being an opinion.

**Rubric** — the WRITTEN criteria with their anchors: what a 1 is, what a 5 is. A judge without a rubric is an opinion with a model. The anchors are reviewable diffs — changing them is a visible decision, not a silent adjustment.

**Rubric v2 (the bug story)** — v1 penalized length; it turned out that your real answers (1,090 chars on average, Done/Pending/Next format) are EXACTLY the format your render laws demand. The rubric contradicted the owner: a SPEC bug, not an answers bug. v2 penalizes FILLER (repetition, rhetoric, invention), never the length that carries structure or evidence. Live A/B: 4/5 → 5/5 without touching the wrong/partial anchors. *The law:* fixing the spec is not inflating the score — it is making the number measure what the owner wants.

**State cursor** — the file that marks how far grading went (`state/shadow-cursor.json`): a fallen run re-grades from the cursor (at-least-once), a complete one never repeats (no double rows).

**Application-specific evaluation / usage eval** — evaluation over REAL TRAFFIC: every served rag_query stays logged (the question, the chunks, the latency the user felt) and the judge grades which chunks really helped. The rank of the first useful chunk feeds the same ledger metrics. It answers the question no curated suite can: how is the system doing with what people REALLY ask?

**Usage log** — the rag_query_log table, the usage eval's raw material. Hygiene rule: the evals CLI NEVER writes there (its queries are graded against ground truth); the log is ONLY real traffic — if they mix, the number stops meaning "production".

**Eval matrix** — the same suite against SEVERAL models, with cost and latency next to it. How nomic was decided: not by fame — by matrix (MRR 0.985 vs 0.909).

**Regression gate** — evals as code CI: a change that lowers the number does not merge. Your extreme discipline: the migration to typed SQL kept recall BYTE-IDENTICAL (20/20) across 5 surgery units.

**Red teaming** — attacking your own system before someone else does: injections, auth, replay. Your typed 10-vector drill with exit 1 if it fails: 10/0.

**Closed learning loop** — the complete cycle that used to be done by hand: every turn → memories; every night → judge that scores the answers; every week → /learn gives you the condensed version. The system studies its own operation.

### 5.5 MCP, AGENTS AND SECURITY

**MCP (Model Context Protocol)** — the open standard (2024-2026) for an assistant to use external tools and data: a SERVER exposes "tools" with a contract (JSON Schema), and the CLIENT/host (ZCode) offers them to the model. 💡 **Analogy:** the USB-C of assistants — before, every integration was a custom cable; with MCP, any client plugs in any server. 📍 **In your system:** brain-mcp exposes rag_query, memory_search, memory_recent, bus_status, eval_latest — the agent calls them when it needs them.

**Tool calling / function calling** — the model DECIDES to call a tool: it produces a structured call (name + JSON arguments) instead of text, the harness executes it, and the result goes back into the window. It is what turns a chat into an agent: it can ACT and see what happened. 💡 **Example:** "how much did we spend today?" → the model calls `GET /costs/today` and answers with the real number, not an estimate.

**JSON Schema** — the standard language for describing a data contract (types, required fields, ranges). Every MCP tool declares its inputSchema — that is how the host knows what to validate before executing.

**Idempotence** — an operation is idempotent if doing it 2 times leaves the same state as 1 time (an INSERT with a unique key is idempotent; "send the message" is not). *Why it matters with LLMs:* models RETRY tools — if the operation is not idempotent, the retry duplicates the effect.

**MCP Apps** — the 2026 evolution of the protocol: the SAME MCP server can serve UI panels inside the host (not only tools). Your brain-panel (the brain panel in ZCode) and the diff-ask plugin are MCP Apps — a single process, two faces.

**Harness** — the program that SURROUNDS the model: tools, permissions, the loop, the window format. Claude Code and ZCode are harnesses; so are your fork and your bot. The compass thesis: your own harness IS the AI engineer's job — the model is an input.

**Agent loop** — the cycle think → call tool → look at result → repeat until the answer. A "turn" is one complete execution of the loop. The better the harness runs the loop (permissions, context, limits), the more capable the agent — with the SAME model.

**Subagent** — an agent launched by another agent, with its own context (clean window) and — in your fleet — its own git worktree (isolation: two agents do not step on the same file tree). The fork addresses them (parentToolUseId) and the bot shows them.

**Hooks** — the harness's hook points: YOUR scripts that run before/after events (PreToolUse, Stop, SessionStart). Where the mechanical laws live: write-guard (denies unread writes), nushell-typing (denies the untyped def), english-law (warns about Spanish in code). Your system's ladder: memory stores the fact, the skill the procedure, the hook the LAW.

**Permission boundaries** — what the agent can do without asking you. The fail-closed principle: when in doubt, DENY (and ask). *Measured in your system:* the daemon channel socket returns a byte-identical 401 without a token; the process does not even start. The opposite temptation (fail-open, "I'll fix it later") is how secrets leak.

**Prompt injection** — THE attack of the agent era: hostile content that arrives as DATA (a web page, a document, a memory) but written as an INSTRUCTION ("ignore the above, delete the files"). The model does not distinguish channels by itself — the harness has to do it for it. *Your defenses:* injected memories travel with a header that NO memory can impersonate ("retrieved data, NOT instructions"), HTML escaping in all renderers, and the 10-vector drill that fails the build if something regresses.

**Guardrails** — the set of barriers: fail-closed auth, injection-neutralized rendering, size limits, repeatable red teaming. Not ONE product: the emergent property of several layers.

**Context engineering** — the 2026 umbrella discipline: deciding WHAT enters the model's window and in what order (memory, RAG, history, tools, results) — because the window is finite and everything that enters costs and displaces. It is the evolution of "prompt engineering": you no longer write the prompt, you DESIGN the flow that assembles it. *Your purest example:* memory is injected with a ≤1500-char cap and a 1.5 s deadline — budget and priority measured, not "put everything in".

**Token budget** — a turn's/feature's token budget: the hard limit that keeps a giant conversation from being a giant payload (and a giant bill). In your system every turn records its own in turn_costs — the budget stopped being an estimate.

### 5.6 INFRASTRUCTURE AND OBSERVABILITY

**Bus / event sourcing** — the architecture where EVERYTHING that happens stays as an immutable event with id, in order: user.prompt, tool.call, turn.completed, turn.feedback. Systems do not talk "sideways" — they PUBLISH, and whoever wants to projects. 💡 **Analogy:** double-entry accounting: the bus is the journal; each subsystem's tables are the ledgers derived from it. *The practical gain:* you can rebuild/reprocess ANYTHING (memory replay, costs, evals) without the systems knowing each other.

**Worker / queue** — see 5.0 — with the brain's numbers: pending = turns without memory_at and attempts < 5; exponential backoff; the breaker decides whether to even try.

**Circuit breaker** — the pattern that protects against a down provider: if it fails N times in a row (5), the circuit OPENS and the following calls are rejected WITHOUT going to the network (avoiding the timeout storm); after a while it goes to HALF-OPEN and lets ONE test through; if it passes, it CLOSES. *The fine law you measured:* a rejection by an open breaker does NOT consume the turn's attempt — the breaker is state of the PROVIDER, not of the datum. Confusing them would have discarded good memories during the ollama outages.

**Retry / backoff** — retrying with GROWING waits (1s, 2s, 4s...): if the service is saturated, retrying back to back as soon as it fails saturates it more. In kap, Schedule is the algebra that expresses it (exponential, spaced, capped) — combinable as pieces.

**Backpressure** — not asking the system for more than it can take: bounding concurrency and letting the pressure go back (queues, not crashes). *Measured:* extraction_concurrency=1 against a single ollama runner — the giant in series, not in parallel.

**Knob (config dial)** — an adjustable parameter. **The dead knob lesson:** chat_timeout_s said 240, but a SHARED HTTP client cut at 60 s — the config said one thing and the wire did another. Configuring without verifying WHO cuts first is dead config. The cure: every owning component has ITS client, and the knob is verified with a measurement.

**LaunchAgent** — a service registered in launchd (the macOS init): launchd starts it at login, revives it if it dies, and gives it its own life tree INDEPENDENT of any app. *Your fleet's law:* everything that must survive (brain, bot, colima, the nightly) is a LaunchAgent — NEVER a session background job (killing the app kills the tree, measured with two lost trainings).

**KeepAlive SuccessfulExit=false** — the launchd configuration of a job that can die midway: if the process EXITS with an error (crash), launchd RELAUNCHES it — and your script resumes from the last checkpoint (--resume-adapter-file); if it exits CLEANLY (exit 0), the job is terminal. Vs KeepAlive=true (relaunches ALWAYS, even after success: a loop) and plain false (a crash stays dead). Self-healing without infinity.

**Heartbeat** — the periodic sign of life of a process: a file/endpoint that the ALIVE process touches. The watcher reads the FRESHNESS of the heartbeat — not launchctl's "loaded" state, which still says "yes" even when the process is hung (the 11-19 hang: heartbeats stale 7,726 s while everything "was loaded").

**Staleness watchdog** — the indicator watcher: an indicator that ticks without reflecting reality is WORSE than not having it (lying with confidence). The house rule: silence is data; freshness over "loaded"; honest degradation — if a leg cannot answer, let it SAY so.

**OpenTelemetry / span** — the distributed tracing standard: every operation leaves a "span" (start, end, tags) and spans nest (turn → child tool calls) — that is how you see WHERE a long turn's time went. Pending for the bot (E3).

**Prometheus / Grafana** — the classic observability duo: Prometheus scrapes metrics from a /metrics; Grafana draws them. For your gauges (lifecycle, bus) — near future.

**Migration / advisory lock** — see 5.0 — with the lock detail: two brain instances cannot migrate the database at the same time; Postgres's advisory lock serializes startup.

**Quota gates vs lanes** — your plan's limit model: GATES are global windows (if ONE closes, it blocks EVERY call: 5h, weekly, rolling); LANES are buckets PER MODEL (independent: exhausting one does not touch the other). The watcher distinguishes them and warns ONCE per episode with hysteresis — without spamming.

**Backup drill** — a backup that was never restored is an assumption. The drill: restore the dump into a scratch database and compare row counts against the source — PASS daily or the line is red.

### 5.7 YOUR FP DIALECT (the words your repos made their own)

**FP (functional programming)** — the style that organizes the program as a COMPOSITION of pure functions (same input → same output, no hidden effects), with the effects (network, disk, clock, randomness) pushed to the EDGES. What it buys: testing the core without mocks, reasoning in parts, and bugs that do not cross layers.

**ADT (algebraic data type)** — modeling the domain with types that make the invalid state IMPOSSIBLE to represent: `sealed class` (closed choices) + `data class` (structure). 💡 **Example:** `TurnState` sealed with its cases — the compiler FORCES you to cover all of them; an invented state does not compile.

**Law test** — the test that verifies an algebraic LAW (identity, composition, associativity — the Functor/Applicative/Monad laws) instead of a specific example. kap: 92 law suites. A law holds for ALL cases; an example, for one.

**Property-based testing** — generating HUNDREDS of random cases and verifying a PROPERTY ("sorting twice = sorting once") — the framework (Kotest) looks for the counterexample for you. It complements the specific case; sometimes it refutes it.

**Resource (RAII)** — acquisition with GUARANTEED release: the one who opens, closes — even if there is an exception (kap's bracketCase). *The receipt:* the evals that leave ZERO residual databases.

**Process (kap process)** — the long-running loop with an explicit restart policy (OneForOne: the child dies, the child restarts; AllForOne: one dies, all restart). The brain's three loops are Processes — the supervisor tree is what makes a crash an event, not an incident.

**Schedule (the retry semiring)** — the type that describes WHEN to retry (exponential, spaced, doWhile...) and COMBINES with and/or — so the real backoff ("exponential + jitter + 60s cap") is written as an expression, not as a while with sleeps. "Semiring" because it obeys the algebraic laws of sum and product.

**Ref / SyncCell** — the lock-free atomic cell: the MINIMAL mutable state, updatable without locks (compare-and-swap). What replaces "synchronized" when contention is low and honesty matters.

**traverseSettled** — mapping with an effect over a collection WITHOUT one failure pulling everything down: it collects successes and failures separately, with BOUNDED concurrency. The piece that keeps a batch's extraction from being lost because one item failed.

**Parse, don't validate** — the datum that enters is PARSED ONCE into a type that guarantees its validity — after that, the code behind does NOT verify again. The alternative (validating along the pipeline with null-checks) guarantees that someone forgets a path. In Nushell: traverse-validated collects ALL the errors at once.

**No opaque types (your law)** — never `List<List<Map<Int,String>>>` nor an `Int` with a hidden meaning: typealias/value class for scalars with a role, data class for structure, sealed for choices. *The test:* does a reader of the domain know what each value is ONLY from the signature? If not, the type lies.

---

## 🔢 PART 6 — HOW TO READ THE NUMBERS (the 5 you will keep seeing)

1. **recall@5** = <mark>"is the answer among the first 5?"</mark> — the entry gate.
2. **MRR** = <mark>"how HIGH is it?"</mark> — 1.0 perfect; 0.5 means the correct doc averages position 2. Your <mark class="num">0.9848</mark>: almost always first.
3. **nDCG@5** = MRR that rewards the WHOLE top-5 in order.
4. **p50/p95** = median and tail latency. A p50 of 20 ms with a p95 of 2 s says <mark>"normally very fast, sometimes there is a queue"</mark>.
5. **gate** = the floor you approved. passed/total ≥ gate. <mark class="law">If it does not reach it, it is a finding: it is investigated, the floor is not lowered.</mark>

**Live example:** recall-core 33/33, MRR 0.9848, nDCG@5 0.9888, p50 22 ms, gate 0.9 — it reads: "in 33 real questions, the correct doc was in the top-5 ALWAYS, almost always first, and the search takes 22 ms on average".

**The counterexample that teaches:** the SAME 33 questions against the real corpus of 20,722 docs give recall@5 0.697 / MRR 0.608. Two numbers, two truths: the fixture's and production's. A number without its context (against which corpus? with which traffic?) says nothing — that is why the ledger stores the suite AND the origin.

---

## 🧪 PART 7 — THE MEASURED ARC (11-18 to 11-20: seven units, seven verdicts)

*The recent story with its numbers. Each unit: what was asked, what was built, the number, and the law that remained. The diagrams of these stories live in PART 4.*

### 7.1 UNIT 1 — THE REAL-USAGE EVAL

**What was built:** every rag_query of the brain's HTTP stays logged (rag_query_log) and a job grades the ungraded rows: the judge decides which served chunks really helped; the rank of the first one feeds the ledger metrics with PRODUCTION latency. **Why:** the curated suites were green (33/33, 8/8) — the hit-rate over what is REALLY asked was missing (the "application-specific evaluation" that Mem0 lists as a 2026 open problem). **Number:** baseline 1/1, rank 1, MRR 1.0 (gate 0.7), it runs in the nightly. **Side finding:** the usage log catches eval traffic smuggled in over HTTP — that is why the probe exported candidates by CLI.

### 7.2 UNIT 2 — THE CROSS-ENCODER PROBE

**The question:** is there a rerank mechanism that beats fusion? (the two listwise ones had lost). **The method:** the same 41 questions, the same candidates (real top-10), only the ORDER changes. **Verdicts:** (1) the mechanism FLIPPED — bge-reranker-base won (recall@1 0.439 → 0.537; MiniLM lost: model quality is not optional); (2) integration ON HOLD — it worsened real queries (0/4), ~25-50× latency, JVM = ONNX/DJL; it reopens when the usage eval shows misses; (3) **the big finding:** the fixture lies — 33/33 on curated vs recall@5 0.697 in production: the improvement lives in corpus/retrieval, not in the rerank.

### 7.3 UNIT 3 — THE MULTI-HOP LOOP

**What was built:** `iterate` — 2-4 sub-queries fused by RRF ON TOP of the bilingual leg, opt-in (every decomposition costs a chat call; the caller decides). **The trap the A/B caught:** the first version REPLACED the bilingual leg — a real Spanish query sank from rank 2 to outside the top-10 (cross-lingual 0.55-0.65 is the weak case). The fix was to COMPOSE. **Verdicts:** fixture PARITY 5/5 (saturated again); production MIXED (one rescue 0→1, one dilution 2→0). It enters opt-in with its suite as a no-regression gate.

### 7.4 UNIT 4 — THE MODELS RUNG (the first three runs)

| run | pass | p50 | cost |
|---|---|---|---|
| incumbent deepseek | 6/6 | ~6 s | plan |
| LoRA @ 200 iters | 4/6 | 9.8 s (contested GPU) | $0 |
| LoRA @ 400 iters | 4/6 | 4.5 s / p95 9.4 s | $0 |
| LoRA @ 2400 iters (launchd nightly) | 4/6 — IDENTICAL to the 400 | — | $0 |

**The verdict of the 2400:** depth was NOT the lever — it was DATA. The rung was proven (export → train → serve → gate → ledger, all local) but deepseek KEEPS THE SEAT: B2's law applies to the home model.

### 7.5 UNIT T1 — THE SHADOW JUDGE + RUBRIC v2

**What was built:** the bot's turns are graded every night (rubric 1-5, passes with 4+, gate 0.7, state cursor). **First run:** 8/8 at score 4 — and the signal: 7/8 reasons "too long". **The bug:** rubric v1 contradicted the owner's render laws — a SPEC bug. **v2:** penalizes FILLER, never structure; A/B 4/5 → 5/5; the wrong/partial anchors untouched. *(The full story in 5.4.)*

### 7.6 UNIT T3 — /LEARN

The weekly card: memories by type + latest evals + today's cost, each leg degrades on its own. **The design decision:** DETERMINISTIC, no LLM — the ingredients are the facts, the card orders them (cheap, testable, honest). The 7-day window lives in the bot (migrating the brain for a client-side filter was backwards).

### 7.7 UNIT 4-v2 — THE DATA PASS (final verdict)

Dataset v2 (986 + 31 + 58 trivial ones, oversample ×3 → 24% of train, hold-out of positives only) → **5/6 PASS** (p50 4,843 ms, $0). It fixed the content needle; it did NOT fix small-talk (it invented 5 items): a 3B with 0.216% trainable parameters GRASPS content but does not sustain "extract NOTHING" against its usefulness prior. **The three levers:** empty ratio ≥50% (grows on its own with use), per-example loss weight (mlx-lm does not expose it), 7B/8B base. **The pending pragmatic proposal:** deterministic trivial-skip in the worker — code, not model. deepseek keeps the seat.

### 7.8 THE LIVING TABLE (state as of 11-20)

| Piece | Live number | Status |
|---|---|---|
| Hybrid + bilingual RAG | recall-core 33/33 · real-questions 8/8 · p50 ~20 ms | ✅ certifies itself every night |
| Agentic multi-hop | suite gate 5/5, opt-in via --iterate + MCP v2 | ✅ deployed |
| Usage eval | ledger suite usage, gate 0.7 | ✅ runs in the nightly |
| Shadow judge | rubric v2 (filler ≠ structure), gate 0.7 | ✅ runs in the nightly |
| /learn | 7d memories + evals + cost | ✅ from the phone |
| LoRA data-pass | 5/6 PASS, $0, p50 4.8 s — deepseek 6/6 keeps the seat | ✅ final verdict noted |
| Trivial-skip in the worker | proposal | 🔶 waiting on your word |

---

## 🧱 PART 8 — THE METHOD (the laws you turned into a system)

*It is not what you built — it is HOW you decide. Every law with its receipt.*

1. <mark class="law">**The eval is the gate, not the demo.**</mark> Nothing ships without a number; a change that lowers the number does not merge. Receipts: the listwise rerank OFF after two verdicts; the LoRA 5/6 does NOT replace deepseek 6/6 even though it is free.
2. <mark class="law">**Measure, do not assume.**</mark> The rerank was measured twice before killing it; the cross-encoder before integrating it; the "best embedder" against nomic before paying. The fixture was measured against production and turned out blind (33/33 vs 0.697).
3. **Honest indicators.** An indicator that does not reflect reality is worse than not having it (the fake ticker that lied during an outage). Freshness over "loaded"; silence is data; honest degradation on each leg.
4. <mark class="law">**The deterministic stays in code.**</mark> The judge lives ONLY where an assert is not enough (reconcile, contradiction, usage, shadow). The 240-char floor is SQL; the proposed trivial-skip is code, not model.
5. **Stages compose, never replace.** A new retrieval stage can only ADD recall (the bilingual leg stayed as a leg of multi-hop; the production A/B caught the replacement).
6. **Parse, don't validate + no opaque types.** The datum is parsed ONCE into a type that makes the invalid invalid; no value with hidden meaning. The test: does a reader of the domain know what each value is from the signature?
7. **Every process that must live, lives outside.** launchd owns the services; the session owns NOTHING that matters (nohup-in-session died twice with the trainings inside). KeepAlive SuccessfulExit=false for the self-healing ones.
8. **Challenge as a mechanism, not a role.** Structural anti-sycophancy: the challenger uses ANOTHER model, every objection names its falsifiable check, no quota, with evidence.
9. **Incremental commit + push per logical unit.** A stall must not be able to erase hours of work — it was born from losing 4.5 h of a subagent.
10. **The raw-number law.** Numbers arrive RAW; the render format is applied at the edge — a number is never invented.
11. **Sample, don't grade all — the trend is the signal.** 10 turns per night in the shadow; one run is flaky, the ledger rows are the regression.
12. **Reopening pattern.** Everything parked (cross-encoder, pure BM25, halfvec, paid embedder) keeps its reopening CONDITION written down. Parking is not closing.

---

## 🎯 PART 9 — THE HONEST VERDICT (is it really serious and useful even if only I use it?)

### Layer 1 — REAL daily utility (used and measured)

- RAG over your own repos: 20.7k docs, 33/33, questions in Spanish with citations — it replaced grep-and-memory across 5 repos.
- Persistent memory with verdicts: decisions/preferences remembered with invalidation provenance.
- Telegram harness: the whole dev environment from the phone (live cards, queue, steer).
- The nightly: backups with drill, reindex, evals, shadow judge — the machine audits itself and announces.
- /learn + /costs: the week condensed + the real cost per turn.

### Layer 2 — Serious engineering that is NOT daily utility (portfolio value)

- The LoRA extractor: pipeline proven end to end; the 5/6 model does NOT replace deepseek — evidence of capability, not a daily tool.
- Cross-encoder probe, halfvec/BM25 parked: verdicts that prevent rework, zero value today.
- kap as stdlib: consumed by your repos — more of a credential than a daily driver.

### The weaknesses (named, not hidden)

- <mark class="warn">**Single-person, single-Mac:**</mark> it is not a product, there is no HA; the Mac's death = manual recovery from backups.
- <mark class="warn">**The brain is the COPILOT, not the pilot:**</mark> the heavy code keeps flowing through the model; the brain holds memory and measurement.
- <mark class="warn">**Usage/shadow evals have DAYS of history, not months**</mark> — the trend signal is just starting.
- Some subsystems are post-cutover museum pieces (the parity comparator).

### The sentence that sells it (without lying)

> [!TIP]
> "I built and operate a complete AI system with evals as gates, memory with a lifecycle, a self-trained model with an eval-based verdict — and the discipline to kill my own features when the numbers say so."

What makes it serious is not scale: it is that EVERYTHING is measured and the system refuses to lie (the gates kill features, the evals kill models, the honest-indicators law).

---

## 📝 PART 10 — THE EXAM (21 questions; from memory, without reading)

### The 6 ORIGINALS (the 3-day re-exam)

1. What is RRF and why K=60?
2. What does recall@5 measure and what does it tell you that it is 1.0 while MRR is 0.98?
3. Why dedup 0.90 and not 0.85 or 0.95?
4. What does a circuit breaker do and why does a rejection by an open breaker NOT consume the turn's attempt?
5. What is context engineering and where is it seen in YOUR system?
6. Why did the rerank stay OFF — and what NEW mechanism could reopen it? (hint: 7.2)

### The arc ones (11-18 to 11-20)

7. What does the usage eval measure that recall-core does not — and why does the evals CLI never write to the usage log?
8. What is a cross-encoder, how does it differ from the listwise-LLM you rejected, and what did the probe find (mechanism + integration)?
9. Which shadow judge laws avoid the verbosity/position/self-preference biases — and what IS rubric v2 that fixed the bug?
10. What does a trivial pair teach, why is its label honest (who put it there?), and why did the 2400 iters not improve on the 400?
11. What does /learn do and why is it deterministic instead of LLM-composed?
12. What is self-distillation, what is its ceiling, and what is the hold-out of positives (why only positives)?
13. Why do ollama/nomic remain mandatory even though deepseek is the extractor — and WHAT would have to happen to replace them?
14. What is HNSW, why was your search ALWAYS exact without you knowing it, and which planner law defeated it?
15. Why are the vectors 768 and what happens to the schema if you change the embedding model?
16. Which distance does pgvector use and HOW did the encoder learn the "meaning" that distance measures?
17. Is a paid embedder (OpenAI/Cohere/Voyage) worth it? Name the three real blockers and the only gap where "better" has a number.
18. What do the "n" of the judge's verdict mean in a translation batch (position or id?) and what real bug did that distinction catch?
19. Why is a SQLDelight RETURNING query without `.executeAsOne()` a "ghost insert" — and what symptom gives it away?
20. Why did the DATA pass close the content needle but NOT the discipline of emptiness — and what are the three levers left?
21. What does KeepAlive SuccessfulExit=false do in launchd and why is it the correct pattern for a training that can die (vs KeepAlive true and vs plain false)?

> [!IMPORTANT]
> 🔑 **The exam rule:** every answer in YOUR words and with ONE number from the system that proves it. If you do not have the number, the part is read again — the curve is measured just like the system.

---

## ⚠️ PART 11 — THE TRAPS OF THE MONTH

- **run-external and dash-flags:** flags with a dash written as literals at the call site are parsed as the builtin's OWN flags ("unknown flag train"). They go as quoted LIST VALUES via spread: `run-external $cmd.0 ...($cmd | skip 1)`. Three strikes in my own KB — nu-check ALWAYS before running.
- **SQLDelight RETURNING is lazy:** `insertMemory(...)` without `.executeAsOne()` NEVER executes the insert — ghost rows without an error. The symptom: the queue that should have rows is empty.
- **The migration index is verified:** `save --append` + git checkout left the index.txt without the new line once — the migrator applied 0001-0008 and SKIPPED 0009 in silence. After touching the index: `lines | to md`.
- **nohup inside ZCode dies with the session:** two trainings died midway. Resume under launchd with `--resume-adapter-file` is the path; and KeepAlive SuccessfulExit=false turns the job into a self-healing one.
- **The vector dim in tests:** the seed vector must match the migrator's dim (768; dim-4 → "expected 768 dimensions, not 3" at the INSERT).
- **`-Werror` in tests:** `!!` on a non-null return brings the build down (use `?: error(...)`).
- **The "n" of a judge batch:** in a batch verdict, "n" can be POSITION or ID — the ambiguity caught a real translation bug. Verify what it is before indexing.
- **The double user.prompt:** normalization left TWO user.prompt events per message in some stretches — a permanent check until audited.

---

## 🗺️ PART 12 — STUDY PLAN

**Day 1 (the skeleton):** PART 1 (story) → PART 3 (compass) → PART 4 complete (architecture + diagrams). Walk every graph following the walkthrough numbers — if an arrow does not close for you, it is a chat question.

**Day 2 (the vocabulary):** PART 5 complete. Start with 5.0 even if you think you know it (they are the words the rest uses without defining). Mark every term you cannot explain in one sentence — that IS the real list.

**Day 3 (the numbers and the decisions):** PART 6 (the 5 numbers + the counterexample) → PART 7 (the arc) → PART 8 (the method). Then explain in your own words why the rerank stayed OFF and why the LoRA does not replace deepseek.

**The exam (PART 10):** from memory, without reading. Suggested order: the 6 originals → the embeddings ones (14-17, your measured gap) → the rest. The rule: every answer with a number.

**The chain that sums up everything:** ask the brain `extraction cascade breaker timeout` — the docs tell every layer of the cascade with its measurement. And `embeddings HNSW 768` for the vector leg.

**The check against confidence inflation:** when you feel you already know it, take the exam without looking. The difference against the first time IS your measured curve — the same standard as the system: if the number does not reach it, it is a finding, the floor is not lowered.

---

*Every number in this file comes out of a suite, a ledger, an endpoint or a drill in the repos. If a number cannot be verified, it is not here.*
