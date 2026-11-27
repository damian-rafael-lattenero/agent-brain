# G · Answers to the retrieval questions

Answers live here, never inside the card: read the card, close it, answer from memory, then check.
Each block answers the three questions of that card, in order.

## A0.1

1. The port finds the process on the machine; the API makes the call meaningful. A socket is the endpoint the operating system hands out, a port distinguishes many sockets on one host, and the API is the contract of names and shapes both sides accept.
2. Because a 200 only says that something answered that path. Another program may hold the number, and a route that answers is not a route whose shape you agreed on — the third layer is the one with no check behind it.
3. The HTTP surface on 127.0.0.1:8765 (routes declared in the brain's `Server.kt`), a narrower token-gated process on 127.0.0.1:8767, Postgres on 5433, and the Unix socket at `~/.zcode/daemon-local.sock`, which is found by path instead of number.

## A0.2

1. Object, array, string, number, boolean, null. Nothing else: no date, no decimal, no comment.
2. It says which forms may appear where and which fields are required, so a value can be refused instead of used. A type is the same promise written where the language can check it before the program runs.
3. `get -o` returns nothing in both cases, so the key must be checked before it is read: `columns` lists the keys of a record, including the ones whose value is null, while a missing key never appears there.

## A0.3

1. It spends work once — building the arranged copy, and in the brain's case the content hash comparison that skips unchanged documents — and it saves that work on every later lookup.
2. Latency is one answer's elapsed time; throughput is answers per unit of time. A poll interval puts a floor under latency: the answer cannot arrive sooner than the next tick.
3. In Postgres, in the `bus_events` table, selected by the worker's pending query. Because it is a table, it survives a restart; a job in a shell does not, so durable work must live outside the shell.

## A0.4

1. A token is the unit the model reads and writes, roughly three quarters of an English word. The window is measured in tokens because that is the unit the model actually consumes, prompt and answer together.
2. It removes the sampling: at temperature 0 the most likely next token is taken every time, so the same prompt tends to give the same answer — which is what you want for extraction and schemas.
3. `done_reason` (in Ollama's answer): `length` means the output cap cut the text, `stop` means the model finished. A cut answer is still shape-valid, so the flag is the only honest signal.

## A1

1. RAG and memory are the pieces the brain implements end to end. MCP, evals and the harness are contracts: a protocol, a suite format with a pass mark, and a set of files and hooks around the agent.
2. RAG returns passages with their sources — uri, title, heading — ranked for the question. It refuses to return an opinion: the answer is composed by the caller, never by the store.
3. RAG. A fluent, confident, unsourced answer is exactly what a model produces when nobody gave it the corpus; memory would have supplied the past, evals would have caught the miss after the fact.

## A2

1. In a call both sides must be alive at the same moment, because one waits for the other. In an event the producer appends a line and is free; the consumer reads it whenever it can, even if the producer is long gone.
2. Because delivery is at-least-once: the same line can arrive twice, and nothing inside it says which copy is the copy. Consumers deduplicate by the event id — the ingest inserts by id and ignores the duplicate.
3. The memory worker, which takes the unprocessed completions, and the cost projection, which turns each completion into a row of the cost table.

## A3

1. The bot receives on the polling loop or on the webhook server on 127.0.0.1:8443; the reply leaves through the Telegram client module as one message.
2. A memory preamble, fetched from the brain's search door before the turn starts. If the brain is down the call becomes `BrainUnavailable` and the turn proceeds without the preamble — the memory is best effort, never a condition for answering.
3. At the record end: the bot's hooks append `turn.completed` to the bus file, the brain ingests it by id, and the memory worker turns it into rows keyed by `source_event_id`. That event id is the join.

## C6

1. Because the brain has no opinion to give: retrieval returns ranked passages and the caller — bot, agent, eval — composes the answer. Ranking under a budget is the whole job, and the citation is what makes the result checkable.
2. The vector leg catches meaning and paraphrase but misses exact names; the full-text leg catches exact words but misses paraphrase. RRF refuses to compare their scores: it uses only ranks, one over sixty plus the rank per list.
3. Words no note contains — including ordinary words like "what" and "the", because the `simple` configuration keeps stopwords and the query ANDs every token. You see it as an empty `text_rank` on every hit while the vector leg still answers.

## D5

1. Allow (nothing attached), guide (a note rides along), ask (one question to you), block (a refusal with its reason). Each constructor carries exactly what the next step needs, and the caller dispatches on the tag.
2. It cites one written, self-tested pattern and never a judgement call — and it never throws: it answers with a value, so the caller stays a dispatch table.
3. In `~/.zcode/hooks/state/denials.tsv`, one row per blocked action, audited by `Scripts/tests/scripts/denial-ledger.nu`.

## D7

1. `goal · constraints · nonGoals · acceptance`. A request that is only conversational — a greeting, thanks, a question about the conversation itself — earns no card: the draft answers null and says why.
2. The repeated-call streak twice past the static guard's count, and a stop after two or more model steps. The stopped attempt is judged at the next turn's start so the teardown path spawns no model call — the note then rides the fresh attempt.
3. `aligned` (nothing happens), `minor_drift` (a note rides to the agent), `drift` (one question to you). A judge that speaks without quoting owes the turn an aligned verdict: drift without quotes is not a verdict, it is an impression.

## D10

1. Whether every acceptance statement is checkable as done from the reply, and whether the reply carries the three parts of the shape. The first miss bounces the turn ONCE with the gaps and the shape written down; the second miss is a notice on your card, and only your tap sends the work back.
2. Uncertainty fails because an acceptance line the reply cannot prove is not met — "probably done" is not a measurement. A broken seal passes the turn because the seal measures, it never traps: a judge that cannot read must not bounce work, and its failure is logged to be counted instead.
3. `result` — what is done; `evidence` — the proof that it is done (a command, a test, a number, a cited file); `next` — the one next step, or that nothing remains. The seal refuses an answer with no proof or no direction, and it does not grade prose style.

## C2

1. `id · ts · kind · sid · turn · source · payload`. An unknown kind fails the decode because the taxonomy is a TYPE: no downstream code may ever see an off-taxonomy event, while unknown payload fields are ignored so producers can grow without a lockstep release.
2. Because the tailer or the consumer can be down when a line lands, and the line waits — more than once is the honest guarantee. A replay is harmless because the durable row's id IS the event id and the ingest inserts with `ON CONFLICT DO NOTHING`: the table after a replay is byte-identical.
3. In the daemon's wiring, never in the channel (the producer maps tap shapes to kinds). The law: the bus never breaks a turn — every tap swallows its own failure into one log line, which is why counting the rows is the only way to catch a tap that silently emits nothing.

## C3

1. One `Transcript` (prompt, reply, which calls ran, title) becomes one `Extraction`: facts, decisions, preferences, one summary — typed rows keyed by `source_event_id`, so a replayed turn extracts once.
2. Because garbage must be a finding, never a dropped turn: the `Invalid` arm carries the reason and the raw text into the invalid bin, while the caps in the validator keep a schema-valid but empty answer from becoming rows.
3. The worker moves on — one failed call never stops the queue (bounded batches, settled one by one) — and the pass report's counts (scanned, extracted, invalid) plus the store's counts by kind are how you see it happened.

## C4

1. Freshness runs from `created_at` (rank decay reads it); usefulness runs from `last_accessed` — how long since anybody needed the row (the lifecycle decay reads it). Two clocks, two meanings.
2. Because the row stays: an archived memory leaves both search legs — it cannot fuse and cannot take a candidate slot — but remains countable, and reinforcement resurrects it. Movement is one-way; a duplicate found later is superseded instead.
3. The floors are exact powers of two, so every boundary is exact in floating point; the laws (monotonicity, the birth case, exact boundaries, resurrection, one-way movement) are pinned in `LifecycleLawsTest`, with `MemoryStore.SEARCH` as the SQL twin pinned by an integration test.

## D8

1. `steer` (your redirection words, mid-flight), `stop` and `cancel` (you cutting it, no words), `explicit` (the named fix, reserved until a real surface exists). Attribution: the live turn when steering, else the last sealed turn for that session, else unknown-but-recorded.
2. That the extractor tried and had nothing to say: the row keeps its attempt stamp and stays pending so the worker retries it — an honest queue entry. Killing the row would pretend the words carried no rule, which was never measured.
3. You do, by hand: `agent-brain corrections accept <id>` flips `proposed` to `accepted` (guarded; only from pending or proposed). Promotion still does NOT write a hook or a KB note — the accepted sentence lives in the table, and turning it into a running gate is a separate, explicitly approved step.

## D6

1. A gate refuses BEFORE the action (deterministic, one written pattern); an oracle answers AFTER it and can never refuse — PostToolUse carries no blocking power, so the compiler's verdict rides as a note the writer reads seconds after the write.
2. The TIME CAP (gradle 45 s, ghc 20 s — a cap is announced, never hidden) and the COOLDOWN (one judgment per file per window, and the stamp is written only after a real judgment — a crashed leg does not consume it). Every run lands one row in oracles.tsv.
3. rag-guard now injects every `*-kb/notes/` tree (the old list named only nushell-kb, so the Kotlin, Haskell and theory notes never rode along); design-guard covers `.hs` and queries the law notes live at a Haskell new-file moment. Each waited for the oracle because they are the other two halves of the same write-time truth: mechanics refuse, retrieval guides, the compiler measures.

## E3

1. Because the row's value is its birth moment: it was written by the process that saw the event, when the event happened. The moment an editor can touch it, the number stops being a fact about the past and becomes whatever the editor wants — the only honest repair is a new row that says what changed and when.
2. Because the history underneath stays append-only: the bus JSONL keeps the owner's words verbatim forever, and the table's flip is a guarded transition (a decided row closes forever), never an edit of what was said. Tampering would be rewriting the bus line; the flip only adds a decision on top.
3. Append-only growth: every one of them only gains rows, each row written by the process that witnessed the event at the moment it happened, and every number anyone quotes is re-derivable by folding over the rows.

## B06

1. The enum with a total parse makes the taxonomy a CLOSED type: the type checker knows every branch, so dispatch coverage is provable and a typo lands on the degraded constructor instead of compiling into silence. The string set proves nothing — any string compiles, and the set and its dispatcher agree only by luck.
2. Because it is partial: `enumValueOf` throws `IllegalArgumentException` on an unknown wire, and a wire boundary sees unknown wires by definition — the total `byWire ?: degraded` parse is the only shape where every input lands somewhere.
3. Because the `else` swallows the type checker's exhaustiveness proof: the `when` compiles forever, and the branch added next year falls into `else` silently. Without the `else`, adding a branch is a compile error at every unfinished dispatch — the type checker becomes the checklist.


