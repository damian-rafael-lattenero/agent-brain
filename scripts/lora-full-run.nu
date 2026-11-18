#!/opt/homebrew/bin/nu
# lora-full-run.nu — the overnight FULL recipe (unit 4's second half, the
# owner's word 18/11): 2-3 epochs over the self-distilled pairs, the two
# gate failures (trivial-turn emptiness, content needle) being its homework.
# One launchd shot: environment → dataset → training → serve → gate → ledger
# → ONE Telegram line with the verdict (loud on failure — the backup script's
# law: success is the log, failure is the alarm).
#
# Recipe deltas vs run 400 (docs/lora-run-400.md):
#   - iters 2400 ≈ 2.7 epochs over 892 train pairs (~10 h at the measured
#     ~15 s/iter, ollama sharing the GPU);
#   - the 31 negatives ride at their NATURAL ratio (3% of pairs) — mlx has no
#     per-example loss weight, so the plan's 0.1-0.2 weight is approximated by
#     the ratio itself;
#   - fresh run: the iter-400 adapter stays archived in evals/lora/adapter-400
#     and the verdicts compare against deepseek 6/6 with the same gate.
#
# Everything this script needs survives reboots: the venv, the base model and
# the dataset live under evals/lora/ (gitignored — private transcripts).

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu [announce announce-loud]

const REPO: string = "/Users/lattenero/Projects/agent-brain"
const LORA: string = "/Users/lattenero/Projects/agent-brain/evals/lora"
const VENV_BIN: string = "/Users/lattenero/Projects/agent-brain/evals/lora/venv/bin"
const BASE_MODEL: string = "/Users/lattenero/Projects/agent-brain/evals/lora/base-model"
const DATASET: string = "/Users/lattenero/Projects/agent-brain/evals/lora/lora-pairs-1795035398463.jsonl"
const WORK: string = "/Users/lattenero/Projects/agent-brain/evals/lora/full-run"
const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"
const ITERS: int = 2400
const HOLDOUT_FRACTION: float = 0.1

def fail [msg: string]: nothing -> nothing {
    print -e $"lora-full: ($msg)"
    announce-loud $"🌙 <b>LoRA full run FALLÓ</b> — ($msg | str substring 0..250)"
    exit 1
}

# The dataset of record (flat system/user/assistant) rewrapped into the chat
# messages format MLX trains on, then split time-ordered: the LAST hold-out
# fraction is valid/test (future turns, never memorized), the rest is train.
def prepare-data []: nothing -> record {
    mkdir $WORK
    let lines: list<string> = (open --raw $DATASET | lines | where {|l| ($l | str trim) != "" })
    let chat: list<string> = ($lines | each {|line|
        let row: record = ($line | from json)
        {messages: [
            {role: system, content: $row.system}
            {role: user, content: $row.user}
            {role: assistant, content: $row.assistant}
        ]} | to json -r
    })
    let holdout: int = (($chat | length) * $HOLDOUT_FRACTION | math floor)
    let holdout: int = if $holdout < 1 { 1 } else { $holdout }
    let cut: int = (($chat | length) - $holdout)
    ($chat | first $cut | str join (char nl)) | save -f $"($WORK)/train.jsonl"
    ($chat | skip $cut | str join (char nl)) | save -f $"($WORK)/valid.jsonl"
    open --raw $"($WORK)/valid.jsonl" | save -f $"($WORK)/test.jsonl"
    {pairs: ($chat | length), train: $cut, holdout: $holdout}
}

def main []: nothing -> nothing {
    print $"== lora-full-run (date now | format date '%Y-%m-%d %H:%M') =="

    # 1. the environment: venv and base model are persistent; rebuild what a
    #    reboot took (the venv is a symlink-free plain dir, mlx only).
    if not ($"($VENV_BIN)/mlx_lm.lora" | path exists) {
        fail $"venv missing at ($VENV_BIN)"
    }
    if not ($"($BASE_MODEL)/model.safetensors" | path exists) {
        fail $"base model missing at ($BASE_MODEL)"
    }

    # 2. the dataset (deterministic split, the plan's hold-out law).
    let data: record = (prepare-data)
    print $"dataset: ($data.pairs) pairs — train ($data.train) / holdout ($data.holdout)"

    # 3. the full recipe. A non-zero exit here is the failure path: loud.
    #    run-external because the binary path is an interpolated string.
    let train: record = (do {
        run-external $"($VENV_BIN)/mlx_lm.lora" --train --model $BASE_MODEL --data $WORK
            --fine-tune-type lora --batch-size 1 --iters $ITERS --learning-rate 1e-4
            --num-layers 16 --mask-prompt --max-seq-length 1536 --steps-per-eval 400
            --steps-per-report 200 --save-every 400 --adapter-path $"($LORA)/adapter-full"
    } | complete)
    if $train.exit_code != 0 {
        fail $"training exited ($train.exit_code): ($train.stderr | str substring 0..200)"
    }
    print $"training done: ($ITERS) iters"

    # 4. the gate: serve the fresh adapter, read the server's OWN model id,
    #    run extraction-core with env-only overrides, stop the server.
    let srv: int = (job spawn {
        run-external $"($VENV_BIN)/mlx_lm.server" --model $BASE_MODEL --adapter-path $"($LORA)/adapter-full" --port 8769
    })
    mut ready: bool = false
    for _ in (seq 1 30) {
        let probe: record = (do { curl -s http://127.0.0.1:8769/v1/models } | complete)
        if $probe.exit_code == 0 { $ready = true; break }
        sleep 5sec
    }
    if not $ready {
        job kill $srv
        fail "the mlx server never became ready"
    }
    let served: record = (curl -s http://127.0.0.1:8769/v1/models | from json)
    let model_id: string = ($served.data | first | get id)
    let gate: record = (with-env {
        AB_CHAT_PROVIDER: openai
        AB_CHAT_BASE_URL: "http://127.0.0.1:8769/v1"
        AB_CHAT_MODEL: $model_id
        OPENAI_API_KEY: dummy
    } {
        do { ^$BIN eval --suite extraction-core --out $"($LORA)/full-run" } | complete
    })
    job kill $srv
    if $gate.exit_code != 0 {
        fail $"the gate failed ($gate.exit_code): extraction-core ($gate.stderr | str substring 0..200)"
    }
    let report: record = ($gate.stdout | from json)

    # 5. ONE honest line with the verdict: the incumbent is deepseek 6/6.
    let verdict: string = if $report.ok { "PASÓ el gate" } else { "NO llegó al gate" }
    announce $"🌙 LoRA full ($ITERS iters): ($report.passed)/($report.total) — ($verdict) vs deepseek 6/6. p50 ($report.metrics.p50Ms) ms · el incumbent conserva el sillón si ($report.passed) < 6"
    print $"gate: ($report.passed)/($report.total) ok=($report.ok)"
}
