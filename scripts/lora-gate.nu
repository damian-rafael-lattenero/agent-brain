#!/opt/homebrew/bin/nu
# lora-gate.nu — the data-pass adapter's final gate: serve the adapter on a
# local OpenAI-compatible endpoint, probe ready, run extraction-core against
# it with env-only overrides (production config untouched), print the report.
# The report rides the ledger as its own run row — the referee is the eval.

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu [announce]

const VENV: string = "/Users/lattenero/Projects/agent-brain/evals/lora/venv/bin"
const BASE: string = "/Users/lattenero/Projects/agent-brain/evals/lora/base-model"
const ADAPTER: string = "/Users/lattenero/Projects/agent-brain/evals/lora/adapter-data-pass"
const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

def main []: nothing -> nothing {
    # run-external rejects literal --flags: they ride as quoted list values
    # via spread (KB: run-external-flag-traps).
    let serve_cmd: list<string> = [
        $"($VENV)/mlx_lm.server"
        "--model" $BASE
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
        print -e "gate: the mlx server never became ready"
        job kill $srv
        exit 1
    }
    let served: record = (curl -s http://127.0.0.1:8769/v1/models | from json)
    let model_id: string = ($served.data | first | get id)
    print $"served-model=($model_id)"
    let out: record = (with-env {
        AB_CHAT_PROVIDER: openai
        AB_CHAT_BASE_URL: "http://127.0.0.1:8769/v1"
        AB_CHAT_MODEL: $model_id
        OPENAI_API_KEY: dummy
    } {
        do { ^$BIN eval --suite extraction-core --out /Users/lattenero/Projects/agent-brain/evals/lora } | complete
    })
    job kill $srv
    print $"gate-exit=($out.exit_code)"
    let parsed: record = ($out.stdout | from json)
    print $"PASSED ($parsed.passed)/($parsed.total) gate ($parsed.gate) ok=($parsed.ok) p50=($parsed.metrics.p50Ms) ms"
    print ($parsed.cases | each {|c| $"($c.id): ok=($c.ok) fail=($c.failure | default '-')" } | str join (char nl))
    let verdict: string = if ($parsed.passed == 6) { "6/6 — EMPATA al incumbent" } else { "menos que deepseek 6/6 — el sillón no se mueve" }
    announce $"🎓 LoRA data-pass final: ($parsed.passed)/($parsed.total) — ($verdict)"
}
