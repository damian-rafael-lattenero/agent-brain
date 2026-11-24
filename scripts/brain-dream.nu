#!/opt/homebrew/bin/nu
# brain-dream.nu — the weekly dream pass, as a one-shot command the LaunchAgent
# calls. Runs the dream CLI over the COOLING clusters, keeps the DreamReport as
# a dated artifact (the measurement of what the system consolidated) and
# announces one honest line on Telegram (send-only; the deploy lib's same rule).
#
# Reactive by law: no sleeps anywhere — the CLI runs to completion and the
# exit code speaks. A failed dream is a LOUD failure (announce + exit 1), never
# a silent skip, but it must never take the agent down: the LaunchAgent runs
# this one-shot with StartCalendarInterval, so a failure is just next Sunday's
# retry.

use /Users/lattenero/Scripts/lib/deploy.nu [announce]
use /Users/lattenero/Projects/nushell-fp/mods/proc.nu fold-stderr

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"
const ARTIFACTS: string = "/Users/lattenero/Projects/agent-brain/evals/artifacts"

# Max COOLING memories examined per pass.
def main [
    --limit: int = 200,
    --dry-run
] : nothing -> nothing {
    let args: list<string> = (["dream", "--limit", ($limit | into string)] ++ (if $dry_run { ["--dry-run"] } else { [] }))
    let stamp: string = (date now | format date "%Y%m%d-%H%M%S")
    let artifact: string = ($"($ARTIFACTS)/dream-($stamp).json")
    let mode: string = (if $dry_run { "dry-run" } else { "apply" })

    let run: record = (do { run-external $BIN ...$args } | complete)
    if $run.exit_code != 0 {
        # fold-stderr (nushell-fp proc.nu): the complete record's stderr
        # arrives string OR list-of-lines — one fold, everywhere (the 15/11
        # drill trap's single source, adopted here).
        let errText: string = (fold-stderr $run)
        let why: string = (if ($errText | is-empty) { "no stderr" } else { $errText | lines | last 1 })
        announce $"😴 dream FAILED — ($why | str substring 0..160)"
        print $"dream failed: ($why)"
        exit 1
    }
    # The report is the artifact AND the summary: judged/invalidated/merged per cluster.
    $run.stdout | save -f $artifact
    let report: record = ($run.stdout | from json)
    let line: string = (
        $"😴 dream ($mode): scanned ($report.scanned) · clusters ($report.clusters) · " +
        $"judged ($report.judged) · invalidated ($report.invalidated) · merged ($report.merged) · " +
        $"distinct ($report.distinct) · artifact ($artifact | path basename)"
    )
    announce $line
    print $line
    # The full report rides the artifact; the console gets the one line and the
    # machine-readable tail (nushell has no printJson — that is the CLI's name).
    print ($report | to json -r)
}
