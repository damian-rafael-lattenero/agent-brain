# Corpus Doctor (plan v2 B6)

The usage eval's closing half. The usage eval grades the production
`rag_query_log` nightly (judge, FIFO sampler, ~20/night) and marks the
failures — and nobody consumed them. The doctor consumes them: it reads the
graded failures, one judge call per failure, and classifies WHY the retrieval
failed into repair proposals. **READ-ONLY by design: it proposes, it never
writes the corpus.** The owner (or an agent session on request) applies the
proposals; the daily 04:00 nightly (index + real-questions + usage gate)
verifies them for free — repaired gaps move the number.

Shipped 2026-11-21 · master `822b773` · deployed (ready 200) · live first run:
`1 fallo / 38 gradadas (2.6%) — corpus_gap: 1` with a real proposal.

## The loop

1. **Measure** — the usage eval grades production queries nightly; a graded
   row with `relevant_count = 0` is a hard failure.
2. **Diagnose** — weekly (`doctor --days 7 --limit 30`), one judge call per
   failure: the question + the chunks that were served and judged irrelevant
   → `{cause, target_uri, proposal}`. Identical queries dedup; the cap
   truncates honestly (`truncated: true`).
3. **Propose** — `doctor-<stamp>.{md,json}` + `latest-doctor.{md,json}` in
   `evals/artifacts/` + one honest Telegram line (see below).
4. **Apply** — human-in-the-loop: create/edit the note the proposal names
   (curated corpus: `nushell-kb/notes`, `fp-kb/notes`, `kotlin-kb/notes`;
   repos: `index-repos` covers kap/bot/zcode-bot-kotlin/agent-brain).
5. **Verify** — free: the next nightly re-indexes and re-runs the suites;
   the weekly failure-rate trend IS the receipt. A gap that persists
   re-proposes weekly until fixed (honest signal, not noise).

## The causes

| cause | meaning | typical repair |
|---|---|---|
| `corpus_gap` | the knowledge does not exist in the corpus | create the note |
| `phrasing_mismatch` | a served chunk is on-topic but the answer is buried under other wording | expand that chunk's doc (target_uri) |
| `stale_chunk` | the chunk is outdated for this question | refresh the chunk's doc (target_uri) |
| `unanswerable` | the question is outside what this personal corpus should answer | none — honesty wins, not every failure is a gap |
| `unclassified` | judge unreadable / chat failure / pruned chunks / zero hits | evidence kept on the case |

The diagnosis parser is TOTAL (`DoctorVerdict.parse`): any judge string
decides or degrades to UNCLASSIFIED, never throws — the VerdictParser law.
Non-string fields are noise, dropped (the UsageVerdict out-of-range
precedent): the cause that DID parse keeps its signal.

## The clock (the measured trap)

The window filters on **`judged_at`, not `at`**: the usage judge grades the
OLDEST ungraded rows first (FIFO), so a steady stream is graded days after
it was asked. A graded verdict describes the corpus AS OF the grading time —
which is what the weekly report means. Measured live: an ask-time 7d window
graded NOTHING (the whole 38-row backlog is older than 7d); the judged-time
window sees it all.

## The weekly line

```
🩺 Corpus doctor: 1 fallos / 38 gradadas (3%) — corpus_gap: 1 · phrasing_mismatch: 0 · ...
```

- One line per week, Sunday 04:30 (LaunchAgent `com.zcode.agent-brain-doctor`,
  script `scripts/brain-doctor.nu`, log `~/Library/Logs/brain-doctor.log`,
  plist sets `WorkingDirectory` to the repo — the doctor CLI's artifacts are
  cwd-relative, the index plist's own law).
- **LOUD** (`announce-loud`) only on two doors: the CLI failed (announce +
  exit 1 — the dream law) or the failure rate ≥ 0.4 (named constant
  `LOUD_RATE`). A run WITH failures is a finding, not a broken run.
- The honest zero (nothing graded in the window) announces quietly and exits 0.

## Testing

- `CorpusDoctorLawsTest` — 5 laws: parser totality (any string → a verdict,
  kotest property), the vocabulary decides (case/whitespace), unreadable →
  UNCLASSIFIED, the report renders the cases it holds, the empty window
  renders a clean bill.
- `CorpusDoctorIT` — 7 laws over real Postgres: only graded failures are
  diagnosed, zero-hit queries degrade with evidence, chat failures degrade
  with evidence, identical queries dedup while failedTotal keeps the raw
  count, outside-window failures are invisible, the cap truncates honestly,
  the markdown carries the proposals. Each test starts from an emptied log
  (order independence — the doctor reads the WHOLE window).
