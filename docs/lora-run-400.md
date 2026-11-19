# LoRA run 400 — the models rung, measured end-to-end (unit 4 of the arc)

> Created: 2026-11-18 · Status: pipeline PROVEN, gate PASSED, incumbent keeps
> the seat. UPDATE 19/11: the overnight FULL recipe (2400 iters ≈ 2.7 epochs,
> `com.zcode.lora-full` one-shot) FINISHED — gate **4/6 again, identical to
> 400 iters**, p50 4.9 s. The two failures are STRUCTURAL, not epoch-limited:
> the 31 negatives at their natural 3% ratio are too weak to teach trivial-
> turn emptiness, and content needles need broader pair coverage. The next
> LoRA lever is DATA (oversampled empties, more diverse content pairs), not
> epochs. deepseek keeps the seat. Adapters: `evals/lora/adapter-full/`
> (6 checkpoints), ledger rows 19/11 01:15.

## What ran (the first model this house ever trained)

- **Dataset** (`evals/lora/lora-pairs-*.jsonl`, gitignored — private
  transcripts): 961 positives (transcript as `user`, the canonical extraction
  as `assistant` — self-distillation: the pipeline's own kept output IS the
  label) + 31 negatives (quarantine raws → empty extraction), ~748k tokens.
  Split time-ordered: last 10% held out (99 pairs) as valid/test.
- **Training**: MLX (Apple Silicon) over `mlx-community/Qwen2.5-3B-Instruct-
  4bit`, LoRA on 16 layers, seq 1536, mask-prompt (loss only on the
  extraction), lr 1e-4, batch 1, 400 iters ≈ 1.7 h with the live worker
  sharing the machine. Measured pace: ~15 s/iter at seq 1536 (the 2000-iter
  full recipe ≈ 8 h → the overnight run).
- **Serving**: `mlx_lm.server` (OpenAI-compatible, port 8769) with the
  adapter; the gate reads the server's OWN model id from `/v1/models` — a
  mismatched name makes mlx fetch the name as an HF repo (measured 404).
- **Gate**: `extraction-core` (6 cases, gate 0.6) via env-only overrides —
  the production config untouched, the row rides the real ledger.

## Numbers

| run | model | pass/total | gate | p50 ms | p95 ms | cost |
|---|---|---|---|---|---|---|
| incumbent | deepseek-v4.1-flash | 6/6 | 0.6 | ~6000 | — | plan tokens |
| LoRA @ iter 200 | mlx-community/Qwen2.5-3B-Instruct-4bit (adapter) | 4/6 PASS | 0.6 | 9803 | 178263 (GPU-contended) | $0 |
| **LoRA @ iter 400 (final)** | same | **4/6 PASS** | 0.6 | **4488** | **9436** | **$0** |

The two failures, both at the full 400 iters:

1. `small-talk-nothing-durable` — a trivial turn must extract NOTHING; the
   adapter invented 3 items ("The conversation is taking place in Spanish").
   The empty-extraction discipline is exactly what the 31 negatives + loss
   weighting (0.1-0.2, per lora-plan.md) teach — 0.4 epochs did not get there.
2. `eval-clock-decision` — the content needle (clock/pure/inyectado) missed;
   generic preferences instead. Content needs the full 2-3 epochs.

Latency at p50 4.5 s / p95 9.4 s is COMPARABLE to the incumbent (~6 s/case),
and marginal cost is $0 — the economics are already there; quality is what
the full recipe owes.

## Verdict

- **The rung is proven**: export → train → serve → gate → ledger, all local,
  all measured. The extractor stays deepseek (B2's law: the evals kill it or
  it ships — 4/6 vs 6/6 keeps the seat).
- The artifacts live locally: adapter snapshots in `evals/lora/adapter-400/`
  (gitignored), the trained run's ledger rows in `eval_runs`.
- **Next (owner's word)**: the overnight full recipe — 2-3 epochs, negatives
  weighted 0.1-0.2, ~8 h as a launchd one-shot — targeting the two failures
  specifically; same gate decides.

## Traps pinned (for the next run)

- `export-lora-pairs` writes its `--out` default RELATIVE to the process CWD —
  run from the repo root or pass `--out` (the first export landed in another
  repo and was moved).
- mlx-lm's new CLI has no `--rank`/`--adapter-file`: rank comes from the
  defaults/config, adapters save to `--adapter-path`. Invoke as
  `mlx_lm.lora …`, not `python -m mlx_lm.lora`.
- hf_hub downloads hung twice (unauthenticated throttle); direct `curl -L -C -`
  of the repo files into a local model dir is the reliable path.
- The dataset JSONL is flat `{system,user,assistant}` — MLX chat training
  wants `{messages:[...]}`; a one-pass nu conversion rewraps it.
- The mlx server answers by the model id it LOADED: read `/v1/models` and send
  that id, or it 404s trying to download the requested name.
