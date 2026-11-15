# LoRA plan — the closing project's dataset

The ladder's last rung: train OUR OWN extractor instead of using a generic
1.5b. Everything it needs already exists in this repo.

## Dataset (built by `agent-brain export-lora-pairs`)

- **Positives**: processed bus turns whose memories survived — the transcript
  (exactly what the extractor saw, capped as in production) as `user`, and the
  canonical extraction JSON (facts/decisions/preferences/summary, kind-grouped,
  insertion order, invalidated rows excluded) as `assistant`. The pipeline's
  own kept output IS the label: no hand-annotation round.
- **Negatives**: quarantine rows with a non-blank raw — the degenerate answers
  (template echoes, schema-violating shapes) paired with the empty extraction.
  Unit 0's quarantine is the clean-negative supplier.
- Format: chat JSONL (`system` = the production `MemoryExtractor.SYSTEM_PROMPT`,
  `user`, `assistant`). Stats print on export (pairs, tokens estimate).

## Base model

Qwen2.5-3B (the closing project's choice) — already pulled locally
(`ollama pull qwen2.5:3b`). LoRA/QLoRA over it, exported as a GGUF adapter
merge, served by ollama; the extractor swaps by one config key
(`AB_CHAT_MODEL=qwen2.5:3b-lora-memory`).

## Gate (the discipline that makes it ours)

The eval matrix extended with the extraction + contradiction suites (E2):
a candidate adapter runs the SAME suites as the incumbent and only replaces it
when extraction precision, contradiction-verdict accuracy and latency/cost
say so. No vibe-based swap — B2's law ("the evals kill it or it ships")
applies to our own model too.

## Training recipe (pointers, not commands)

- LLaMA-Factory or unsloth for QLoRA SFT over the JSONL (chat template =
  qwen2.5's); rank 16-32, 2-3 epochs, lr 1e-4 — start from the recipe defaults,
  let the gate decide.
- Split: the export is time-ordered — hold out the most recent ~10% of pairs
  as the validation slice so the gate measures FUTURE turns, not memorized
  ones.
- The negative pairs weight LOW (0.1-0.2 loss weight) or they teach the model
  to output empty extractions too eagerly.
