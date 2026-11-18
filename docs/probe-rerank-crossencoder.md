# Probe: cross-encoder rerank over the production corpus (unit 2 of the arc)

> Created: 2026-11-18 · Status: VERDICT'D, integration PARKED with reopen conditions
> The probe law: a probe is worth running only if it can FLIP a decision. Two
> models ran; one flipped the mechanism question, and both exposed a bigger one.

## Method

For every case of `recall-core` (33) and `real-questions` (8): retrieve the
top-10 fused hits from the PRODUCTION corpus (20.7k docs / 72.9k chunks) via
the in-process CLI (`agent-brain query --k 10` — never the HTTP boundary, so
the usage-query log stays real traffic), score every (query, chunk-content)
pair with a local cross-encoder, re-rank the top-10, and grade BOTH orders
against the suites' ground truth with EvalRunner's own match rule
(`uri.endsWith(expected)`) and metric definitions. Scratch engine: a venv with
`sentence-transformers` on Apple-Silicon MPS; candidates exported once to
`/tmp/rerank-probe/candidates.json` (41 cases × 10 hits = 410 pairs).

## Numbers (41 cases, identical candidate sets, only the order differs)

| engine | MRR | recall@1 | recall@5 | nDCG@5 | improved / regressed |
|---|---|---|---|---|---|
| fused order (the hybrid RRF, untouched) | 0.554 | 0.439 | 0.659 | 0.614 | — |
| `cross-encoder/ms-marco-MiniLM-L-6-v2` | 0.457 | 0.317 | 0.683 | 0.540 | 4 / 13 |
| `BAAI/bge-reranker-base` | **0.608** | **0.537** | **0.732** | **0.655** | **10 / 3** |

Per-suite, bge-reranker-base vs fused: recall-core improved 10 / regressed 3;
real-questions improved 0 / **regressed 4** (3 of the 8 real questions never had
the expected doc inside the candidate set — no reranker can recover what was
never retrieved).

## Verdicts

1. **Mechanism (the question the probe was built to flip): the cross-encoder
   WINS — the first rerank mechanism that ever beat the fusion on this house's
   data.** The two listwise-LLM verdicts stand (B2: recall@1 0.350 vs 0.850;
   U2: +0.004 MRR for 100–1000× latency), but those tested the WRONG mechanism.
   A proper pointwise cross-encoder lifts recall@1 +0.098 and recall@5 +0.073
   over the identical candidates. MiniLM (general domain, 22M) still loses —
   model quality is not optional.
2. **Integration: PARKED, not shipped.** What blocks it:
   - the real-queries subset regressed (0 improved / 4 regressed) — the lift
     lives on the curated cases, not (yet) on what production actually gets
     asked;
   - latency ~25–50× on the interactive path (MPS ~0.5–1 s per query's top-10
     vs 20–33 ms retrieval), and integration means a JVM inference engine
     (ONNX Runtime/DJL) plus a model artifact;
   - the usage eval (unit 1) is the instrument that will say whether production
     traffic actually misses at the top ranks — reopen when it does.
   Reopen conditions: (a) the usage ledger shows sustained rank misses on real
   traffic, (b) an ONNX/DJL path makes the second stage cheap enough, (c) a
   reranker tuned for technical corpora.
3. **THE BIGGER FINDING — the fixture suite is blind to production.** The same
   33 recall-core questions that score 33/33 (recall@5 1.0, MRR 0.985) against
   the curated fixture corpus score **recall@5 0.697 / MRR 0.608** against the
   full production corpus. The suite measures the fixture; production is a
   different, harder game. The usage eval is now the honest instrument, and
   corpus/retrieval quality — not reranking — is where the headroom lives.
   (Candidate follow-up: a production-grounded recall suite — same questions,
   corpus = the real 20.7k docs — as the bridge between the two worlds.)

## Relation to the recorded verdicts

- B2 (`ca787d7`) and U2 stand UNCHANGED: listwise-LLM rerank stays default OFF.
- This probe adds the mechanism taxonomy to the runbook: listwise-LLM ✗ (×2),
  pointwise cross-encoder ✓ on curated candidates / ✗ on real queries (so far),
  bi-encoder second leg (the bilingual expansion) ✓ additive by construction.
