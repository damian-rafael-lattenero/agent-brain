#!/opt/homebrew/bin/nu
# brain-doctor.nu — the weekly corpus doctor pass, as a one-shot command the
# LaunchAgent calls. Runs the doctor CLI over the graded failures of the
# rag_query log (7d window) and announces ONE honest line on Telegram
# (send-only; the deploy lib's rule). The doctor CLI self-archives: it writes
# doctor-<stamp>.{md,json} + latest-doctor.{md,json} into evals/artifacts
# relative to ITS cwd — the plist sets WorkingDirectory to the repo, the same
# way the nightly's artifacts land there.
#
# Reactive by law: no sleeps anywhere — the CLI runs to completion and the
# exit code speaks (`complete` folds stderr's list-or-string shape first, the
# 15/11 drill trap). A failed doctor run is a LOUD failure (announce + exit 1),
# never a silent skip; the LaunchAgent one-shot means a failure is just next
# Sunday's retry. A run WITH failures is a finding, not a broken run: the
# exit stays 0 and the LOUD gate is the failure RATE (>= LOUD_RATE speaks
# bold), never the mere presence of failures.

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu [announce announce-loud]

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

# Above this failure rate the line speaks LOUD: the corpus is failing a third
# of what the judge grades — worth the owner's eyes now, not Sunday scroll.
const LOUD_RATE: float = 0.4

def main [
    --days: int = 7,
    --limit: int = 30
] : nothing -> nothing {
    let args: list<string> = (["doctor", "--days", ($days | into string), "--limit", ($limit | into string)])
    let run: record = (do { run-external $BIN ...$args } | complete)
    if $run.exit_code != 0 {
        # `complete`'s stderr may arrive as a string or a list of lines — fold to
        # text FIRST (the drill of 15/11 crashed here and hid the real error).
        let errText: string = (if ($run.stderr | describe | str starts-with "list") { $run.stderr | str join "\n" } else { $run.stderr })
        let why: string = (if ($errText | is-empty) { "no stderr" } else { $errText | lines | last 1 })
        announce-loud $"🩺 <b>Corpus doctor FALLÓ</b> — ($why | str substring 0..160)"
        print $"corpus doctor failed: ($why)"
        exit 1
    }
    let report: record = ($run.stdout | from json)
    let graded: int = ($report.gradedTotal? | default 0)
    let failed: int = ($report.failedTotal? | default 0)
    if $graded == 0 {
        let line: string = "🩺 Corpus doctor: the judge graded nothing in the window — nothing to diagnose (the honest zero)."
        announce $line
        print $line
        return
    }
    let rate: float = ($failed / $graded)
    let causes: string = (
        $report.causes?
        | default {}
        | items {|wire, n| $"($wire): ($n)" }
        | str join " · "
    )
    let line: string = (
        $"🩺 Corpus doctor: ($failed) fallos / ($graded) gradadas ((($rate * 100 | math round) | into string)%) — ($causes)"
    )
    if $rate >= $LOUD_RATE {
        announce-loud $"🩺 <b>Corpus doctor ALTO</b> — ($line)"
    } else {
        announce $line
    }
    print $line
}
