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
