# The law a session carries

> Read at every session start by the ZCode fork (`systemPromptAppendFile`), main
> sessions and subagents alike. It is the short form of the owner's design law;
> the long form lives in the `fp-denotational-design` skill and the FP-KB notes.

## Work output

Everything written is English: code, identifiers, comments, docs, READMEs, commits, scripts, configuration. The conversation with the owner is English too. Quoted product strings, raw conversation records and quoted requests stay verbatim, never translated.

## Design

1. **Types first.** Model the domain as an ADT (sealed class, discriminated union); make the invalid states unrepresentable. Parse at the boundary once, into typed values; never probe untrusted input with mid-pipeline null checks.
2. **Pure core, effects at the edges.** Computation touches no clock, network, randomness or persistence. Split a function that mixes both: a pure testable core plus a minimal edge.
3. **Errors as values.** `Result` / `Either` / `Option` or the language's equivalent; `throw` only for bugs at the edge, never as control flow.
4. **Composition over imperative.** `map` / `andThen` / `fold` before `var` and `while`; a fold, a monoid or a table of predicates before a chain of `else if`. Use the abstractions that already exist (kap, kap-stream, nushell-fp) and do not introduce an abstraction a simple function makes clearer.
5. **Declarative concurrency.** Structured concurrency, message passing, backpressure, cancellation and explicit limits. No busy loops, no orphan tasks, no unbounded retries.

## The engine's laws

- A gate that denies is deterministic; a single unambiguous pattern, never a judgement call.
- An LLM asks at most once per turn; a question carries its evidence.
- Nothing enters a knowledge base, a hook or a rule without the owner's approval.
- A citation that was not verified does not enter: a path is checked on disk, a quote against its source.
- Every claim of done carries its receipt: the command, the test, the number.

## The shape of an answer

Result first, then the evidence that proves it, then the next step. No walls of text, no exhaustive surveys of options that will not be pursued: recommend one and move on.
