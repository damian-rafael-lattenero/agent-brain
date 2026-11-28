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

## B21

1. The degraded value keeps the program's story going and can be logged, counted and corrected — a bare NPE ends the story at a line that names nothing: no row, no field, no context. A slightly-wrong value is evidence; a nameless crash is silence.
2. The method's voidness on the JVM: an expression-bodied test infers its tail's type, and a nullable tail makes the signature `Unit?` — which JUnit does not recognize as a test method, so it SILENTLY SKIPS it while the suite reports green. `: Unit` pins the contract.
3. The optional read (`get -o` plus `default`), the shape check before a read (`describe` prefix-matched — `from json` on garbage returns the string itself), and the completed edge (`complete` for every external, an exit code never ignored).

## B02

1. Because every write reaches every read without either mentioning the other: whoever holds the slot can change what everyone else sees, mid-flight, invisibly. An immutable value severs the channel — what a function received is what it keeps.
2. Nothing: the input record stays exactly as it was. `update` answers a NEW record; the old binding keeps the old shape and remains valid, which is why the drill's test can pin both at once.
3. In test sources — fixtures and `lateinit` wiring live there, measured as most of the fleet's rebindable-slot debt. It is legal because test state is read within one test's lifetime and the gate's jurisdiction deliberately stops at `src/test/`.

## B05

1. Because the grouping never changes the answer — `(f . g) . h` and `f . (g . h)` answer identically — so re-grouping a pipeline's stages is provably safe and refactors cannot silently change behavior.
2. A `let`-chain five deep: an imperative program wearing a costume, where each step's purpose hides in the nesting. The replacement: extract the named pure steps and compose THEM, left-to-right.
3. The direction of the arrow: `|` composes forwards in the order the data flows, every stage a visible line, while a nested call composes inside-out and must be read from the middle.

## B08

1. As a STATEMENT the `when` only warns on missing branches; as an EXPRESSION over a sealed tree the type checker must produce a value for every constructor, so a forgotten one is a compile error — the proof lives in the expression position.
2. NOTHING — the dispatch silently answers nothing, which is the bug wearing silence. The census test over every constructor is the only coverage proof nushell has.
3. The parts of the value: `Just (x:xs)` binds the head and the tail as it matches, so the clause's body works on the pieces without a second extraction step.

## B20

1. It catches `Throwable` — and CancellationException IS a `Throwable` on the JVM — so the caller's give-up signal is laundered into a value: the rescue leg runs in a cancelled scope, structured concurrency hangs or loses work. Over pure CPU code (a json parse) there is nothing to cancel, so the net is honest there.
2. The typed value (expected, actionable failures — the caller handles them), throw (bugs and invariant violations, honest crashes), cancellation (cooperative control flow — rethrown always), and border convert (a throwing library converted ONCE at the edge: the known case to the domain, the unknown rethrown).
3. A `complete` record — `{stdout, stderr, exit_code}` — and the script decides on the exit code as a value; nothing is ignored, and stderr is carried as the reason.

## B03

1. Because the steps travel as DATA: the loop (a fold) applies whatever the list carries, so a new step is a new row in the list and no caller's code changes.
2. A dispatch table's payload fields evaluate at CONSTRUCTION, so a payload that is a bare expression runs even if its row never wins; the law makes payloads CLOSURES — thunks built once, called only by the winner (fp-kb's defer law).
3. Because `suspend (A) -> B` is a different type from `(A) -> B`: the shape of the value says whether it may wait, and forcing one into the other's place is the smell the gate names.

## B04

1. `a -> (b -> c)`: a function from one argument to a function of the remaining one — the brackets are already there, currying just makes them invisible.
2. Because its parameter list is a TUPLE: evaluation waits for all of them, so partial application must be written on purpose as a chain of single-parameter functions.
3. The captured VALUE freezes at creation — a partial application over a changing slot keeps the old value forever, so the law is: capture values, never slots (the same law the mut-accumulator trap teaches).

## B01

1. The hidden read: a companion-object field, a global, a clock — state the signature never mentions, so the answer has a second author the reader cannot see.
2. Because the expected answer is a VALUE you can pin once: the same input must answer the same forever, so one assertEquals per input is a proof, not a sample.
3. Effects live at the edges — the suspend/Kotlin functions and the nushell scripts that call the pure cores; the structure says so by layout (mods carry cores, scripts carry the world).

## B09

1. The edge case becomes the fold's starting value; the step becomes the combining function — both lifted out of the recursive original and given names.
2. The right fold consumes one constructor at a time, so it can stop before the list ends; the strict left fold walks the whole structure first.
3. The `--fold` identity supplies the starting accumulator, so an empty input answers the identity instead of raising — totality over every list.

## B10

1. The equations: a type class states laws between its methods (decode of encode is the original), and generic code may rely on those laws, not just on the method names.
2. The instance lives outside the type it serves and nothing global enforces coherence — two codec records for one shape can disagree, and only its own law test holds an instance to its class's equations.
3. One instance per type, everywhere — the compiler resolves and checks it globally, so no two call sites can see different behavior from the same type.

## B11

1. Identity (`map` of identity is identity) and composition (mapping the composition equals the composition of the mappings) — together they prove the shape survives.
2. The shape: a map that filters or drops answers a different length or order, and the identity law fails first on anything that drops.
3. kap's LAWS.md (the executable Functor laws) and `nushell-fp/mods/types.nu` (option-map with its law tests in-file).

## B12

1. Accumulation needs BOTH results kept, but the monad's chain builds the second computation from the first's success — the first failure makes the rest unreachable, so the accumulation shape is applicative-only by design.
2. `with`, `zipV`, `traverseV` — and their laws are executable in kap's LAWS.md (Applicative Identity among them).
3. All-or-nothing: any unknown row lands the whole traversal on the err constructor, which names the offending row.

## B13

1. The next computation is BUILT from the previous value — a lookup whose continuation needs the user it found — and the chain operator carries that dependency in its type.
2. Associativity: re-grouping the chain answers identically, so steps can be extracted and re-laid without changing behavior.
3. When the steps merely forward each other's values — the chain is nesting ceremony; the fleet's law extracts named functions returning Either (direct style).

## B15

1. The collapse answers one value and loses the shape; the traversal keeps the shape INSIDE the verdict of a fallible step — the twist is the verdict traveling with the walk.
2. Because the traverse's answer is the shape inside an applicative verdict, and a dependent chain would build each step from the last — losing the all-or-nothing accumulation that makes forms honest.
3. `traverseV` keeps EVERY error (the accumulating mode); `traverseSettled` runs the siblings even when one fails (the isolation mode).

## B16

1. The answers nobody reads: strictness computes every binding the moment its line runs; laziness computes on first touch only.
2. `lazy {}` / `by lazy` (the deferred value), `sequence {}` and `asSequence()` (the on-demand generators).
3. The Unit?-typed JUnit method that silently skipped — in the value dimension: a lazy value never read is a guard that never runs, while the suite stays green.

## B17

1. The alias gives a nickname only — the swap still compiles, because it is the SAME type; the wrapper gives a distinct identity the checker enforces.
2. For the instance space: a newtype can carry instances the wrapped type does not have, at zero runtime cost (the wrapper erases).
3. The tagged record with wrap/unwrap functions as the only doors — and the census tests police the kind, since describe cannot prove it.

## B18

1. A constructor kind (`* -> *`): the signature speaks about every shape with an instance, so `fmap` is written once instead of per shape.
2. The JVM's ceiling: no type-constructor abstraction exists, so the generality would cost reflection or raw types — the concrete operators (map/with/andThen per shape) are the honest answer.
3. That instances written YEARS later work with generic functions written today — `doubled` needed no tree-specific code, because the tree only supplies the instance.

## B19

1. Exactly two honest bodies (return `a` or return `b`): the type parameter carries no operations, so the body cannot peek, compare, default, or build a `T`.
2. Erasure plus runtime inspection: `is` checks and reflection can meet `T` at runtime, so the blindness is a discipline review keeps, not a machine proof.
3. Signature honesty: concrete parameter types wherever the shape is known, the typing hook enforcing the annotations, and the census tests as the only theorems.




## B07

1. The meaning it stands in for (there IS no turn / no title) and the meaning it wears (this string is a real, empty value) — and every consumer must hold them apart from memory, forever, with nothing in the type to help.
2. At the wire edge only: `put("turn", turnId ?: "")` is the one honest translation. It never belongs in a value — an id or a key that is `""` is an invented identity that consumers cannot distinguish from a real one.
3. Because it answers true for both `''` and `null`, so a guard on it cannot tell a deliberately empty value from a missing one. `describe` asks instead: `'nothing'` is the absence's name, anything else is a value.






## B14

1. The associative combine and its identity — named once, so one fold (`foldMap`/`mconcat`) serves every case instead of a hand-rolled accumulator per combine.
2. Because a number carries ONE combine (`+`) and the laws demand an honest identity — `Sum` and `Product` wrap the number to give each combine its own zero (0 and 1), and the wrapper keeps the instances apart.
3. An identity: `reduce` without `--fold` has no empty and RAISES on an empty list; the monoid's fold answers the empty with `empty`, not an error.

## B22

1. Because the compiler checks shapes, not behavior — the Typeclassopedia's instance typechecks and still violates identity; the property run over GENERATED cases is what closes the gap.
2. Functions are not comparable, so `fmap id = id` runs as `fmap id x == x` for generated `x`; a green run is evidence, never a proof — one counterexample falsifies.
3. The generators are pure functions of an explicit seed (nushell's `random` is not seedable), so every run replays exactly and a failure reproduces.

## B23

1. "Free" because the tree is built from ANY functor's instructions without running them; `iter` collapses the tree by giving each instruction its meaning — the interpreter is a fold.
2. The sealed instruction tree plus a fold IS the free shape, and the operations interface IS tagless final — no HKT needed for either.
3. A closure field that RUNS while the script is being built makes the program eager; the payload stays inert (strings, ids, numbers) and only the interpreter does work.

## B24

1. Lazy IO ties effect order to evaluation order — hard to reason about in the large; pipes/conduit replace it with an await/yield PROTOCOL and a composition operator, so memory is constant.
2. DropOldest(n) kills the OLDEST buffered elements and keeps the newest n; DropLatest(n) kills the INCOMING element and keeps the buffered ones — the victim differs exactly as the name says.
3. Bounded fan-out (`traverse-jobs --timeout`), mandatory deadlines (`wait-until`), and `job recv --timeout` raising as the honest failure signal — budgets compensate for the missing strategy channels.

## B25

1. Form first; choose the denotation; specify by equations; the homomorphism principle (a failing law is a design signal — redesign the denotation, never patch the code); derive the implementation last.
2. Its denotation is "accumulate ALL errors" — a monad's bind short-circuits on the first failure and would destroy the accumulation, so the abstraction is applicative-only, on purpose.
3. The instance that typechecks and fails `fmap id = id` — it implements first and never chose a meaning; step 2 of the method exists precisely to stop that.

## N01

1. So every value carries the same shape — `{tag, value}` / `{tag, value, error}` — and every signature stays exact: a consumer never probes whether a field exists, the tag decides.
2. `traverse-validated` accumulates (applicative, deliberately not a monad — the kap ruling, decided in B25's design); `traverse-option` and `fold-result` short-circuit, because a sequence of dependent reads has nothing to accumulate.
3. It runs an unsafe closure — file ops, externals, anything that can throw — and answers an `ok` or an `err` VALUE; a raised error becomes `tag: err` with the message, never a crash mid-pipeline.

## N02

1. Because arity 2..23 hand-written is copy-paste; the generator emits the ladder (Kotlin's 23-argument ceiling mirrored) and the round-trip laws are pinned once.
2. A partial application: `apply-args` applies one `do` per rung and a short list leaves the remaining rungs as the returned closure.
3. It lets every failed field re-raise AFTER the stream, naming each one — an applicative accumulation over a record instead of a first-failure abort.

## N03

1. Measured live: closures stored in variables, passed as arguments, returned from other closures on 0.117 — the four verbs are just functions over that fact.
2. Haskell's `.` composes right-to-left, so `compose f g` means "do f, then g"; `thread` (in currying.nu) is the left-to-right twin for pipeline-shaped code.
3. Its stdlib already has identity, constants, `andThen`/`compose` and method references — the gaps-only law leaves nothing for a library to fill.

## N04

1. A typeclass becomes a record of operations the caller hands in: `{empty, combine}` — the classic dictionary-passing translation, since nushell has no typeclasses either.
2. `reduce` without `--fold`: it has no identity, so it RAISES on an empty list — the half-monoid.
3. kap's `fold` already carries an identity, so the do-not-reinvent rule applies there; the shell's fold lacks one, so the dictionary library fills a real gap.

## N05

1. Builtin first (`each`, `where`, `reduce`, `zip`, `chunks`, `uniq`, `flatten`, `transpose`, `interleave`), then this library, then new code — the fp-first-nushell order.
2. `{emit, next}` — the value to yield and the next seed; `null` ends the list (the anamorphism).
3. `ap-list`, `lift-a2`, `filter-m`, `replicate-m` — every closure over every value, the list applicative.

## N06

1. `merge deep` is already deep-merge, and `get -o`/`upsert` cover literal cell paths — so the module keeps only dynamic paths and honest defaults.
2. The path is a list of keys held as a value (computed, passed, stored), and the caller hands the reader the fallback for a missing branch.
3. The bot parses JSON ONCE at the boundary into typed wire records, so a missing field is a typed absence, not a probe.

## N07

1. Eager: the binding (`let x = expensive`), table payloads, and list literals; free islands: `and`/`or` short-circuit, streaming pipelines, guards before builds.
2. Because re-forcing runs again by design — the shell has no memoization primitive, so the caching policy belongs to the caller (B16's Kotlin `by lazy` is the memoized twin).
3. Wrapping an always-used value in a thunk: it builds eagerly anyway and now costs a `force` — premature laziness.

## N08

1. An unbounded loop is the reactive law's forbidden shape, and `nushell-law.nu` denies a blind sleep at ten seconds — the deadline is the structure that replaces it.
2. `false` at exactly the budget — and the CALLER decides what a timeout means (retry, degrade, fail loud).
3. Boot-time work: launchd's exit-75 retry (the stack-up pattern) already owns the retry cadence, and nothing in-session needs to block.

## N09

1. The background `job` is the deferred value; the mailbox delivers out-of-order, and sorting on the carried index restores structure order.
2. A killed job's dying catch can still deliver its outcome — the tag keeps that stale message away from every later call (the mailbox isolation law).
3. Jobs are threads: they die with the nu process, so durable work goes to a LaunchAgent or a Ghostty tab.

## N10

1. String or list-of-lines — depending on how the external chunked its output (measured 15/11).
2. The empty string, so a missing error never crashes the error path.
3. The real error: the slice crashed on the list shape, and the message it was trying to show vanished with the exception.

## N11

1. Because two doors drift: one call appends to the log exactly the line stdout showed, so the receipt IS what the user saw.
2. `~/Library/Logs/<name>.log`, one per script — post-mortems and `fleet healthcheck` read them.
3. The Telegram announce rung: deploy phases and seal verdicts speak to the log AND the phone, same discipline.

## N12

1. "1000K" reads as a mistake at the exact moment a human expects the new unit — the M rung fires AT 1,000,000, trailing zeros trimmed.
2. The rendered table: a token wider than the cell breaks the border (the measured 405-char law), so `hard-wrap` chunks it with spaces first.
3. In the bot's quota surfaces — `remainingPct` rounds the same way, the number law pinned by tests on both sides.

## N13

1. Nushell's `random` is not seedable (verified 0.117), so generators are pure functions of an explicit seed — the failure replays exactly.
2. The failing index, ITS seed, and the sample — enough to re-run only that case.
3. The first candidate that still fails — a `[]` counterexample is the clearest message, a 12-int one is noise.

## N14

1. `nu-highlight` speaks only Nushell and `bat` ships no nu grammar — so the router sends each language to the one tool that knows it.
2. A structured string leaf (JSON, csv, embedded code): serializers escape strings by design, so nothing else will ever look inside — the tree walk does.
3. In the fork's code viewer: shiki v4 ships the nu/kt/hs grammars and `EXTENSION_TO_LANGUAGE` routes by extension.

## N15

1. A deploy must not depend on the phone being awake — missing config, empty token, dead network are all no-ops; the deploy's own gate decides success.
2. The same announce block lived in the bot, whatsdigest and the fork's fork-ops; the audit wave deduped it into `mods/telegram.nu`.
3. The bot daemon (BotCommands, live card, seal verdicts); this module carries only the deploy announce rung.

## H01

1. Associativity — `h ∘ (g ∘ f) = (h ∘ g) ∘ f` — and identity, `f ∘ id = f` and `id ∘ f = f`. Nothing else is required; nothing about the objects enters the definition.
2. The `Category` class cannot represent anything wider than types; partial functions (bottom) are not arrows; and the laws are unchecked, since equality is not defined for functions.
3. "A pipeline is composed of three parts: the input, the filter, and the output", and the arrow is declared in a custom command's signature: `def "str stats" []: string -> record`.

## H02

1. `F (g ∘ f) = F g ∘ F f` and `F id_a = id_{F a}` — on `F` itself, the same two laws `fmap` carries.
2. The reader instance: `fmap f g = f . g` — post-composition, nothing iterated, so `fmap` cannot mean "loop".
3. It allows smashing and gluing; it forbids breaking apart — no element created, dropped, or reordered by the map.

## H03

1. For every arrow `f : a → b`, `G f ∘ α_a = α_b ∘ F f` — the translation commutes with everything, one square per arrow.
2. Parametricity: one formula for ALL types cannot inspect them, so it cannot do anything but commute; it breaks for class-constrained functions, which are given per instance.
3. `Raise<E>` functions transforming into `Either`, `Ior`, `Result`, `Option` or nullable — with no uniformity law stated.

## H04

1. Every other cone factors through it by a UNIQUE morphism; uniqueness bans junk — the triple with an ignored component has two factorizers, so it is not a product.
2. `(a, b)` and `Either e a`; the encoding leaves out limits of general diagrams — no pullbacks or pushouts in the mirrored manuals.
3. Compound types (`record<...>`, `table<...>`) are the product shape; union types (`oneof<...>`) are the coproduct shape.

## H05

1. `η : I_D → R ∘ L` and `ε : L ∘ R → I_C`; the triangles say `ε_{L d} ∘ L η_d = id` and `R ε_c ∘ η_{R c} = id` — the outer legs are identities, nothing more.
2. It holds the factorization for ALL objects at once (a global hom-set isomorphism), while being weaker than equivalence: the reversed composites `R ∘ L → I_D` and `I_C → L ∘ R` need not exist.
3. `[2, 3]` is a new element, not the product: the free construction remembers instead of computing, and only the counit folds it back — one direction each.

## H06

1. `unit :: f ()` and `(**) :: f a -> f b -> f (a, b)`; "`pure` and `(<*>)` are equivalent in power to `unit` and `(**)`".
2. Lax forbids the reverse map `f (a, b) -> (f a, f b)`; the naturality law is a free theorem — a consequence of parametricity, not a check anyone runs.
3. In `zipOrAccumulate` / `mapOrAccumulate`: both operands are values from the start, so both errors surface — a bind builds the second from the first and hides it.

## H07

1. `μ : m ⊗ m → m` (multiplication) and `η : i → m` (unit); associativity through the associator, unit laws through the unitors.
2. "The laws simply state that `return` is the identity of `(>=>)`, and that `(>=>)` is associative" — the monad laws read as a monoid's.
3. In the category of endofunctors, with composition as tensor — not in the category of types, where `Monad` is written with bind and no transformation named.

## H08

1. Arrows `a → m b`; identity `return`; composition `(>=>)` threading the wrapped value — a category in the full sense of H01.
2. Plain composition needs the output type to match the input type: `Option[Int]` is not `Int`, so `Function1.compose` does not type — `>=>` threads and unwraps.
3. http4s: "Since middleware returns a `Kleisli`, you can compose it with another middleware" — a middleware IS one such arrow, service to service.

## H09

1. A carrier `a` and an evaluator `f : F a → a`; initiality promises exactly one homomorphism from the initial algebra to every other.
2. `foldr`'s combine is `alg`, its starting value is the algebra's carrier on the empty shape — the two arguments are the two components.
3. `j` is an isomorphism: the initial algebra's evaluation has an inverse, which is what makes the fixed point exact.

## H10

1. Every monoid homomorphism `F x → m` corresponds to exactly one function `x → U m` on generators — and conversely.
2. It builds the "maximum" monoid on the generators, imposing nothing beyond unit and associativity; "free" does NOT mean cheap or loose — it means structure-free.
3. In tagless final the syntax is the interface (`class Expr rep`) and the interpreter is the instance; with `Free` the syntax is data (`Pure`/`Free`) and the interpreter pattern-matches.

## H11

1. A terminal object, a product of any pair, and an exponential for any pair.
2. For every `g : z × a → b` there is a unique `h : z → (a ⇒ b)` — the product-with-`a` adjunction, evaluation its counit; currying is that correspondence.
3. Eager memoization of all function values — a function is code, not a stored table; the object exists, the tabulation does not.

## H12

1. `Nat(C(a, −), F) ≅ F a`; the transformation is fixed by `α_a(id_a)` — one value at the identity.
2. Parametricity: one formula for all types can only use what it already holds, so its behavior is forced; `Reader () a -> Maybe a` has exactly two inhabitants.
3. `forall r. (a -> r) -> r ≅ a` — continuation passing style, "common in UIs, in asynchronous systems, and in concurrent programming".

## H13

1. The left carries `η : D → Lan_K D ∘ K`, the right `ε : Ran_K D ∘ K → D`; both universal among constructions of their shape.
2. Ends become (co)ends written as quantifiers, hom-sets become function types — `Ran` and `Lan` are newtypes over `forall` and `exists` shapes.
3. Limits, colimits, adjunctions, monads and the Yoneda lemma — the saying is that all of them derive.

## H14

1. `f : b → a` maps to `G f : G a → G b`; the laws keep identity and reverse composition: `G (g ∘ f) = G f ∘ G g`.
2. It satisfies the same two laws read in `C^op` — the opposite category's arrows are the originals reversed.
3. You cannot build `b -> r` from an `a -> b` and an `a -> r`; fix the argument (`instance Functor (Either a)`), or use `Op`.

## H15

1. Contravariant in the first argument, covariant in the second; `dimap` pre-composes the first against the flow and post-composes the second with it.
2. `(q ∘ p) a b = ∫^c p c a × q b c` — one summand per intermediary; the hom-functor is the unit, and the laws hold up to natural isomorphism.
3. Because for `(->)` the lift is `cd . bc . ab`: to turn `p b c` into `p a c` you must go from `a` to `b` first.

## H16

1. `extract` is the dual of `return`; `duplicate` of `join`; `extend` of `(=<<)` — the same three arrows, all reversed.
2. The stream is the context: a co-Kleisli arrow sees the focus and its surroundings, "a co-Kleisli arrow for a stream is just a digital filter", and `extend` maps it over every focus to produce the filtered stream.
3. It cannot insert values — "a comonad ... does not give the means to insert values"; the context arrives pre-filled and may only be read and extended.

## H17

1. The associator `(a ⊗ b) ⊗ c → a ⊗ (b ⊗ c)` and the left and right unitors `i ⊗ a → a`, `a ⊗ i → a` — natural isomorphisms bound by coherence conditions; symmetry adds `a ⊗ b → b ⊗ a`.
2. `(x, (y, z))` and `((x, y), z)` are different values, so reassociation is a real arrow carrying data — "we can't just write point-free" the laws as equalities.
3. Electrical and digital circuits, signal flow graphs, Petri nets, Bayesian networks, neural networks — the same boxes-and-wires calculus.

## H18

1. A presheaf only assigns local data; a sheaf adds the condition that local data agreeing on overlaps glue into one global section.
2. How far the local views are from being mutually consistent; it "places a lower bound on the amount of distortion incurred by the fusion process".
3. In the task sheaf of Felber et al.: "terminating solutions are precisely its global sections".

## H19

1. `get : a → s` and `set : a → s → a`; as one arrow, a coalgebra `a → Store s a` with `Store s a = Store (s -> a) s`.
2. The lens reads and writes a field; the prism handles a variant (partial focus); the traversal reaches every element of a container.
3. `Store` is a comonad — `extract (Store f s) = f s`, `duplicate` re-focuses — and the lens is a coalgebra for it, which is what makes GETPUT the first coherence law.

## H20

1. H06 (backprop as a monoidal functor, Fong-Spivak-Tuyéras), H13 (agent memory carried by `Lan_u`, Buehler), H17 (meaning as a diagrammatic calculus, Coecke et al.).
2. "Library-grounded abstraction" over 100 formalized tasks; the best model "solves only 12.0% of tasks at pass@4", collapsing from 55.0% on easy to 0.0% on the hardest.
3. The field "moves fastest of all" and most rows were read as abstract pages only — a stale row is a wrong citation, and the note keeps the read dates for exactly that check.
