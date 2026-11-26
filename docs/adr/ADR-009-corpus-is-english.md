# ADR-009: The corpus is English

**Status:** accepted (2026-11-26; supersedes the "the corpus mixes Spanish and
English" line of ADR-001)

## Context

The store's retrievers are monolingual in practice: `nomic-embed-text` is an
English-centric embedder, and the full-text leg's `tsvector` never crosses
languages. Measured 26/11: a Spanish question ranked a Spanish document above
the Canon note it was about — five of the nine slots over it were Spanish
documents at the noise floor. The owner's law (2026-11-06, restated 26/11 and
extended that same evening): everything English — documents, memories, and
every tracked file including eval suites. Spanish remains the conversation
language with the owner; the owner asks in Spanish.

## Decision

1. **The door.** `Corpus.scan` refuses any document whose prose reads as
   foreign (`English.foreignDocument`: ≥ 8 Spanish stopwords and more Spanish
   than English) and reports it with its counts, like an oversized file.
2. **The boundary.** A foreign question reaches the store only as its English
   restatement (`QueryExpansion.legs`); the reply's `searched` field and the
   query log carry what was searched, never the foreign text. It fails open:
   when the restatement call degrades, the raw query runs and the eval records
   it.
3. **Files.** Suite files, notes, docs, commits — every tracked file is
   English. The suite that measures concept recall (`canon-recall`) therefore
   carries two English phrasings per concept (plain + paraphrase); Spanish-input
   coverage is ad-hoc probes, never a shipped file.
4. **Memories.** Extraction is always-English; legacy rows are translated in
   place by `translate-legacy` and re-embedded.
5. **Code is code.** String literals, identifiers and placeholders inside code
   examples stay verbatim (the bot's Spanish UI strings included); comments are
   prose and obey the law.

## Consequences

- The store inventories at zero Spanish documents (measured 26/11: 2,381 docs,
  `spanish 0`; the non-English residue is code files' comment lines, tolerated
  by rule 5).
- The first-place rate of concept questions moved from 34/45 to 41/45 English
  and 23/45 to 39/45 Spanish-restated (with the curated vote, ADR-less lever in
  `Rag.sq`); the remaining misses are abstract tier-2 concepts whose full-text
  leg is blocked by the AND gate of `websearch_to_tsquery`.
- A Spanish document on disk is invisible to the RAG by construction; to publish
  it, translate it first.
- The nightly can gate on the inventory: any `spanish` verdict is a defect.
