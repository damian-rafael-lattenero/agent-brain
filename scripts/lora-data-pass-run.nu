#!/opt/homebrew/bin/nu
# lora-data-pass-run.nu — the DATA-PASS unit (19/11): the lever is DATA, not
# epochs (2400 iters scored IDENTICAL to 400 — the failures were structural).
# 800 iters over the v2 dataset (the trivialPairs leg: short zero-reply turns
# labeled EMPTY, empties oversampled ×3), resuming from the checkpoint the
# 16:29 kill left at iter 400.
#
# MEASURED TRAP this script fixes (19/11 18:44 boot): a launchd RunAtLoad
# agent RE-FIRES on every boot. The retired com.zcode.lora-full retrained a
# COMPLETED run and clobbered the v2 split with the v1 export. So this script
# is IDEMPOTENT: a `done` marker makes a post-completion refire a no-op, and
# a boot DURING the run resumes from the latest checkpoint (save-every 400).
#
# One launchd shot: environment → dataset → training → serve → gate →
# ONE Telegram line with the verdict (loud on failure — the backup script's
# law: success is the log, failure is the alarm).
#
# MEASURED TRAP this script ALSO fixes (20/11, two forced restarts): the
# done marker was never written (the gate leg died before step 5), so the
# boot refires were NOT no-ops — the pass retrained at every boot (load
# average 272, the machine hung). Defense in depth: on completion AND on
# the no-op refire the script SELF-DISARMS launchd (see self-disarm).

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu [announce announce-loud]

const REPO: string = "/Users/lattenero/Projects/agent-brain"
const LORA: string = "/Users/lattenero/Projects/agent-brain/evals/lora"
const VENV_BIN: string = "/Users/lattenero/Projects/agent-brain/evals/lora/venv/bin"
const BASE_MODEL: string = "/Users/lattenero/Projects/agent-brain/evals/lora/base-model"
const DATASET: string = "/Users/lattenero/Projects/agent-brain/evals/lora/lora-pairs-1795114572678.jsonl"
const ADAPTER: string = "/Users/lattenero/Projects/agent-brain/evals/lora/adapter-data-pass"
const WORK: string = "/Users/lattenero/Projects/agent-brain/evals/lora/full-run"
const GATE_OUT: string = "/Users/lattenero/Projects/agent-brain/evals/lora/data-pass-gate"
const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"
const ITERS: int = 800
const EMPTY_OVERSAMPLE: int = 3
const LAUNCH_PLIST_DIR: string = "/Users/lattenero/Library/LaunchAgents"
const GRAVEYARD: string = "/Users/lattenero/Library/LaunchAgents/disabled-graveyard"
const LABELS: list<string> = ["com.zcode.lora-data-pass", "com.zcode.lora-resume"]

def fail [msg: string]: nothing -> nothing {
    print -e $"lora-data-pass: ($msg)"
    announce-loud $"🌙 <b>LoRA data-pass FAILED</b> — ($msg | str substring 0..250)"
    exit 1
}

# The self-disarm (20/11): the pass is TERMINAL once gated — launchd must
# never relaunch it. TRAP (launchd detachment law, measured 13/11): a script
# that boots out its OWN job dies mid-run with the whole tree, so this only
# DISABLES its own label (the override-DB flag survives reboots, the running
# job is untouched) and bootouts the OTHER agent (the KeepAlive resume job)
# from the side. The plists then move to the graveyard — no file left for
# any bootstrap to pick up; mv of the own plist is safe, only bootout kills.
# Best-effort and idempotent: a disarm landing twice is an honest no-op.
def self-disarm []: nothing -> nothing {
    let uid: int = (^id -u | into int)
    let steps: list<list<string>> = [
        ["bootout" $"gui/($uid)/com.zcode.lora-resume"]
        ["disable" $"gui/($uid)/com.zcode.lora-data-pass"]
        ["disable" $"gui/($uid)/com.zcode.lora-resume"]
    ]
    let landed: list<record> = ($steps | each {|step|
        let r: record = (try { do { ^launchctl ...$step } | complete } catch { {exit_code: 99} })
        {step: ($step | str join ' '), ok: ($r.exit_code == 0)}
    })
    mkdir $GRAVEYARD
    let moved: int = ($LABELS
        | each {|l| $"($LAUNCH_PLIST_DIR)/($l).plist" }
        | where {|p| $p | path exists }
        | each {|p| try { mv -f $p $GRAVEYARD; true } catch { false } }
        | where {|ok| $ok == true }
        | length)
    let ok: int = ($landed | where ok | length)
    print $"disarm: launchctl ($ok)/($landed | length) landed · ($moved) plists to the graveyard"
}

# The v2 split law (19/11 data pass, deterministic): holdout = every 10th
# POSITIVE (0-based index); every EMPTY pair (the empty-extraction JSON —
# empty facts/decisions/preferences/summary — the negatives and the trivial
# leg alike) stays in train, oversampled ×3.
# 986 positives → 99 holdout → 887 + (89 × 3) = 1154 train / 99 holdout.
def prepare-data-pass []: nothing -> record {
    mkdir $WORK
    let lines: list<string> = (open --raw $DATASET | lines | where {|l| ($l | str trim) != "" })
    let parsed: list<record> = ($lines | enumerate | each {|it|
        {idx: $it.index, row: ($it.item | from json)}
    })
    let empties: list<record> = ($parsed | where {|p|
        let ex: record = ($p.row.assistant | from json)
        (($ex.facts | is-empty) and ($ex.decisions | is-empty) and ($ex.preferences | is-empty)
            and ($ex.summary | str trim | is-empty))
    })
    let pos: list<record> = ($parsed | where {|p|
        let ex: record = ($p.row.assistant | from json)
        (not ($ex.facts | is-empty) or not ($ex.decisions | is-empty) or not ($ex.preferences | is-empty)
            or not ($ex.summary | str trim | is-empty))
    }
        | enumerate | each {|it| {n: $it.index, row: $it.item.row}})
    let toChat: closure = {|row|
        {messages: [
            {role: system, content: $row.system}
            {role: user, content: $row.user}
            {role: assistant, content: $row.assistant}
        ]} | to json -r
    }
    let holdout: list<string> = ($pos | where {|p| $p.n mod 10 == 0 } | get row | each $toChat)
    let train_pos: list<string> = ($pos | where {|p| $p.n mod 10 != 0 } | get row | each $toChat)
    let train_empties: list<string> = (seq 1 $EMPTY_OVERSAMPLE | each {|_| $empties | get row } | flatten | each $toChat)
    let train: list<string> = ($train_pos ++ $train_empties)
    ($train | str join (char nl)) | save -f $"($WORK)/train.jsonl"
    ($holdout | str join (char nl)) | save -f $"($WORK)/valid.jsonl"
    open --raw $"($WORK)/valid.jsonl" | save -f $"($WORK)/test.jsonl"
    {pairs: ($lines | length), train: ($train | length), holdout: ($holdout | length), empties: ($empties | length)}
}

def main []: nothing -> nothing {
    # 0. the boot-refire guard: a launchd RunAtLoad agent re-fires on EVERY
    #    boot — after completion this is a silent no-op (the 19/11 trap),
    #    AND the refire re-disarms launchd (defense in depth: the plists may
    #    have crept back, the disable flag may have been lifted).
    if ($"($ADAPTER)/done" | path exists) {
        print "done marker present — boot refire, no-op"
        self-disarm
        exit 0
    }
    print $"== lora-data-pass (date now | format date '%Y-%m-%d %H:%M') =="

    # 1. the environment: venv and base model are persistent; rebuild what a
    #    reboot took (the venv is a symlink-free plain dir, mlx only).
    if not ($"($VENV_BIN)/mlx_lm.lora" | path exists) {
        fail $"venv missing at ($VENV_BIN)"
    }
    if not ($"($BASE_MODEL)/model.safetensors" | path exists) {
        fail $"base model missing at ($BASE_MODEL)"
    }

    # 2. the dataset (the v2 split law, deterministic — a boot mid-run
    #    rebuilds the EXACT same split before resuming).
    let data: record = (prepare-data-pass)
    print $"dataset: ($data.pairs) pairs — empties ×($EMPTY_OVERSAMPLE) — train ($data.train) / holdout ($data.holdout)"

    # 3. the recipe (same hyperparameters the killed run recorded in
    #    adapter_config.json). The dash-flags ride as LIST VALUES via spread
    #    — run-external with LITERAL --flags at the call site parses them as
    #    its own flags (measured: "unknown flag train").
    #    Resume: the newest checkpoint, if any — a boot mid-run re-enters
    #    here and continues from save-every's last save.
    let resume: string = (
        glob $"($ADAPTER)/0*_adapters.safetensors"
        | sort
        | if ($in | is-empty) { "" } else { $in | last }
    )
    print $"resume: (if $resume == "" { "fresh" } else { $resume })"
    let train_cmd: list<string> = ([
        $"($VENV_BIN)/mlx_lm.lora"
        "--train"
        "--model" $BASE_MODEL
        "--data" $WORK
        "--fine-tune-type" "lora"
        "--batch-size" "1"
        "--iters" ($ITERS | into string)
        "--learning-rate" "1e-4"
        "--num-layers" "16"
        "--mask-prompt"
        "--max-seq-length" "1536"
        "--steps-per-eval" "400"
        "--steps-per-report" "200"
        "--save-every" "400"
        "--adapter-path" $ADAPTER
    ] ++ (if $resume == "" { [] } else { ["--resume-adapter-file" $resume] }))
    let train: record = (do { run-external $train_cmd.0 ...($train_cmd | skip 1) } | complete)
    if $train.exit_code != 0 {
        fail $"training exited ($train.exit_code): ($train.stderr | str substring 0..200)"
    }
    print $"training done: ($ITERS) iters"

    # 4. the gate: serve the fresh adapter, read the server's OWN model id,
    #    run extraction-core with env-only overrides, stop the server.
    let serve_cmd: list<string> = [
        $"($VENV_BIN)/mlx_lm.server"
        "--model" $BASE_MODEL
        "--adapter-path" $ADAPTER
        "--port" "8769"
    ]
    let srv: int = (job spawn { run-external $serve_cmd.0 ...($serve_cmd | skip 1) })
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
        do { ^$BIN eval --suite extraction-core --out $GATE_OUT } | complete
    })
    job kill $srv
    if $gate.exit_code != 0 {
        fail $"the gate failed ($gate.exit_code): extraction-core ($gate.stderr | str substring 0..200)"
    }
    let report: record = ($gate.stdout | from json)

    # 5. the idempotence marker: written ONLY after training+gate completed
    #    — a boot landing later sees it and never retrains. Then ONE honest
    #    line with the verdict vs the incumbents: deepseek 6/6, run-400 4/6.
    "ok" | save -f $"($ADAPTER)/done"
    let verdict: string = if $report.ok { "PASSED the gate" } else { "did NOT reach the gate" }
    announce $"🌙 LoRA data-pass · ($ITERS) iters (resume 400→800) · extraction-core ($report.passed)/($report.total) — ($verdict) vs deepseek 6/6 · p50 ($report.metrics.p50Ms) ms"
    print $"gate: ($report.passed)/($report.total) ok=($report.ok)"
    self-disarm
}
