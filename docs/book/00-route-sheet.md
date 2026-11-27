# Route sheet — Your AI Engineering book (v5)

> **How to use it.** Mark with an X every card you finish. The list is in study order: each
> card uses only what appeared before it. A new-topic card is meant to take 10 to 20 minutes;
> its **Minimum path** block is the 5-minute version (the aha, the signature, the checkpoint).
> If you got hooked, go on with the next one: they are chained on purpose.
> The answers to every retrieval question live in part G, never inside the card.

## The eight stops of every card

1. **The aha**: the idea in one sentence and its equivalent in your languages.
2. **What it denotes · in Haskell · why it exists.**
3. **In your languages**: Kotlin and Nushell, with TypeScript where it earns its place, plus the difference and the trap.
4. **📍 In your system**: the real file where it lives.
5. **✍️ Paper sheet**: copy the signature, complete the law.
6. **Drill**: the test you have to make pass.
7. **Retrieval questions**: three, from memory.
8. **Close-out**: three-line summary and checkpoint.

Every card also carries a **Minimum path** block naming the aha, the signature and the checkpoint.

## The map: 90 cards

### A — The map and the base words (7 cards)
Needs: nothing.

- [x] A0.1 API, port, socket
- [x] A0.2 JSON, schema, types
- [x] A0.3 Index, queue, latency
- [x] A0.4 Prompt, model, token
- [x] A1 The five pieces: MCP, RAG, memory, evals, harness
- [x] A2 How they talk to each other: who talks to whom
- [x] A3 The story in two pages

### B — The concepts (25 cards)
Haskell is the source. Every card ends in Kotlin and Nushell, with TypeScript where it earns its place. Needs: A0.

Functions and values:

- [ ] B01 Pure function and referential transparency
- [ ] B02 Immutable data
- [ ] B03 First-class and higher-order functions
- [ ] B04 Currying and partial application
- [ ] B05 Composition

Types and recursion:

- [ ] B06 Sum types and product types
- [ ] B07 `Maybe` and `Either`
- [ ] B08 Pattern matching and exhaustiveness
- [ ] B09 Recursion and folds

Abstractions:

- [ ] B10 Type classes
- [ ] B11 `Functor`
- [ ] B12 `Applicative`
- [ ] B13 `Monad`
- [ ] B14 `Semigroup` and `Monoid`
- [ ] B15 `Foldable` and `Traversable`

The language:

- [ ] B16 Laziness
- [ ] B17 `newtype`
- [ ] B18 Higher-kinded types
- [ ] B19 Parametricity
- [ ] B20 `IO` and effects at the edge
- [ ] B21 Total functions
- [ ] B22 Laws as properties (QuickCheck)

Applied to your code:

- [ ] B23 Free monad and tagless final (`Kap`)
- [ ] B24 Streaming and backpressure (`KapStream`)
- [ ] B25 Denotational design: shape, denotation, equations, homomorphism

### C — Your system inside (8 cards)
Needs: A1, A2.

- [ ] C1 The agent loop: what happens when you press Enter
- [ ] C2 The bus: what travels and who publishes it
- [ ] C3 A turn becomes memory: the extraction worker
- [ ] C4 The memory lifecycle
- [ ] C5 Embeddings from scratch: vector, cosine, 768, HNSW
- [x] C6 The journey of a question through the RAG
- [ ] C7 The nightly cycle: the machine audits itself
- [ ] C8 MCP: what it is and what the brain exposes

### D — The harness as a layer of laws (10 cards)
Needs: C1.

- [ ] D1 The three memories: KB, brain memories, ZCode auto-memory
- [ ] D2 Hook: what it is, when it runs, what it can do
- [ ] D3 Skill: the procedure
- [ ] D4 The ladder: fact, skill, hook
- [ ] D5 Gate and verdict: allow, guide, ask, block
- [ ] D6 The oracle: compiling is the truth
- [ ] D7 The course judge: the Brief, the ledger, the triggers
- [ ] D8 Correction, proposal, promotion
- [ ] D9 Subagents: the same laws in another session
- [ ] D10 The seal: the shape of the answer

### E — The numbers (3 cards)
Needs: C6, C7.

- [ ] E1 recall, MRR, nDCG and the gate
- [ ] E2 The three numbers: first pass, canon, course
- [ ] E3 The ledger: why it cannot be tampered with

### F — The method (2 cards)
Needs: everything above.

- [ ] F1 The laws, with their receipts
- [ ] F2 The traps of the month

### N — Nushell, one card per module (15 cards)
The hub's own library, one card per module, in dependency order. Needs: A0, B04, B06, B14.

- [ ] N01 `types.nu` — Option and Result as values
- [ ] N02 `currying.nu` — partial application as a verb
- [ ] N03 `combinators.nu` — the small verbs that compose
- [ ] N04 `algebra.nu` — monoids the shell can fold
- [ ] N05 `list.nu` — lists as the default shape
- [ ] N06 `json.nu` — `get-in`/`set-in`/`deep-merge-all`
- [ ] N07 `lazy.nu` — deferring work as a value
- [ ] N08 `wait.nu` — `wait-until`, no busy loops
- [ ] N09 `async.nu` — jobs, mailboxes, structured concurrency
- [ ] N10 `proc.nu` — external processes as records
- [ ] N11 `log.nu` — the voice of a script
- [ ] N12 `fmt.nu` — human numbers and widths
- [ ] N13 `prop.nu` — properties over examples
- [ ] N14 `highlight.nu` — the syntax highlighter
- [ ] N15 `telegram.nu` — the phone as an output

### H — The theory under everything (20 cards)
One card per theory note, in tier order. Needs: B01–B25 (read after part B).

- [ ] H01 Category: composition that behaves
- [ ] H02 Functor: the same operation on another container
- [ ] H03 Natural transformation: a uniform translation
- [ ] H04 Limit and colimit: products and sums freed from syntax
- [ ] H05 Adjunction: why currying works in both directions
- [ ] H06 Monoidal functor: effects side by side
- [ ] H07 Monoid object: what a monad buys
- [ ] H08 Kleisli category: effectful composition that looks plain
- [ ] H09 F-algebra and catamorphism: every fold is one equation
- [ ] H10 Free and forgetful functors: syntax against interpreter
- [ ] H11 Exponential and cartesian closed category: a function as an object
- [ ] H12 Yoneda: a type is what you can do with it
- [ ] H13 Kan extension: the most general way to move a construction
- [ ] H14 Contravariant functor: a consumer reverses the arrow
- [ ] H15 Profunctor: the common shape of processes and lenses
- [ ] H16 Comonad: a computation that depends on its context
- [ ] H17 Monoidal category and string diagrams: wiring that composes
- [ ] H18 Sheaf: local views that agree globally
- [ ] H19 Optics: lenses, prisms and traversals as one idea
- [ ] H20 Category theory and AI: where it shows up now

### G — Answers
- [ ] G Key to every retrieval question and to the final exam

## Where each piece lives

| Part | Canonical file | Language |
|---|---|---|
| this sheet | `agent-brain/docs/book/00-route-sheet.md` | English |
| the cards | `agent-brain/docs/book/<id>.md` | English, code and terms in English |
| the card's law | `agent-brain/docs/book/_CARD_TEMPLATE.md` | English |
| the terms and their card | `agent-brain/docs/book/glossary.tsv` | English |
| the answers | `agent-brain/docs/book/G-answers.md` | English |
| the drills | `~/Projects/canonical-drills` | one module per language |
| the plan that orders everything | `zcode-bot-kotlin/docs/plan-denotational-engine.md` | English |

The previous v4 (`agent-brain/docs/ai-engineering-map-and-dictionary.md`) stays as a historical record.

## The count

7 + 25 + 8 + 10 + 3 + 2 + 15 + 20 = **90 cards** plus the answer key.
