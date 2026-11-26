# Route sheet — Your AI Engineering book (v5)

> **How to use it.** Mark with an X every card you finish. The list is in study order: each
> card uses only what appeared before it. A new-topic card is meant to take 10 to 20 minutes.
> If you got hooked, go on with the next one: they are chained on purpose.
> The answers to every retrieval question live in part G, never inside the card.

## The eight stops of every card

1. **The aha**: the idea in one sentence and its equivalent in your languages.
2. **What it denotes · in Haskell · why it exists.**
3. **In your languages**: Kotlin, Nushell, TypeScript, with the difference and the trap.
4. **📍 In your system**: the real file where it lives.
5. **✍️ Paper sheet**: copy the signature, complete the law.
6. **Drill**: the test you have to make pass.
7. **Retrieval questions**: three, from memory.
8. **Close-out**: three-line summary and checkpoint.

## The map: 53 cards

### A — The map and the base words (7 cards)
Needs: nothing.

- [ ] A0.1 API, port, socket
- [ ] A0.2 JSON, schema, types
- [ ] A0.3 Index, queue, latency
- [ ] A0.4 Prompt, model, token
- [ ] A1 The five pieces: MCP, RAG, memory, evals, harness
- [ ] A2 How they talk to each other: who talks to whom
- [ ] A3 The story in two pages

### B — The concepts (25 cards)
Haskell is the source. Every card ends in Kotlin, Nushell and TypeScript. Needs: A0.

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
- [ ] C6 The journey of a question through the RAG
- [ ] C7 The nightly cycle: the machine audits itself
- [ ] C8 MCP: what it is and what the brain exposes

### D — The harness as a layer of laws (8 cards)
Needs: C1.

- [ ] D1 The three memories: KB, brain memories, ZCode auto-memory
- [ ] D2 Hook: what it is, when it runs, what it can do
- [ ] D3 Skill: the procedure
- [ ] D4 The ladder: fact, skill, hook
- [ ] D5 Gate and verdict: allow, guide, ask, block
- [ ] D6 The oracle: compiling is the truth
- [ ] D7 The course judge: The Request, the ledger, the triggers
- [ ] D8 Correction, proposal, promotion

### E — The numbers (3 cards)
Needs: C6, C7.

- [ ] E1 recall, MRR, nDCG and the gate
- [ ] E2 The three new numbers: first pass, canon, course
- [ ] E3 The ledger: why it cannot be tampered with

### F — The method (2 cards)
Needs: everything above.

- [ ] F1 The laws, with their receipts
- [ ] F2 The traps of the month

### G — Answers
- [ ] G Key to every retrieval question and to the final exam

## Where each piece lives

| Part | Canonical file | Language |
|---|---|---|
| this sheet | `agent-brain/docs/book/00-route-sheet.md` | English |
| the cards | `agent-brain/docs/book/<id>-<topic>.md` | English, code and terms in English |
| the drills | `~/Projects/canonical-drills` | one module per language |
| the plan that orders everything | `zcode-bot-kotlin/docs/plan-denotational-engine.md` | English |

The previous v4 (`agent-brain/docs/ai-engineering-map-and-dictionary.md`) stays as a historical record.
