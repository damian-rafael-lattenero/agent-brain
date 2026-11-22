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
use /Users/lattenero/Projects/nushell-fp/mods/proc.nu fold-stderr

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"
const KB_NOTES: string = "/Users/lattenero/Projects/nushell-kb/notes"

# Above this failure rate the line speaks LOUD: the corpus is failing a third
# of what the judge grades — worth the owner's eyes now, not Sunday scroll.
const LOUD_RATE: float = 0.4

# --apply N forces ONE deferred proposal (1-based, from the latest run).
def main [
    --days: int = 7,
    --limit: int = 30,
    --apply: int
] : nothing -> nothing {
    if ($apply != null) {
        apply-proposal $apply --days $days
        return
    }
    let args: list<string> = (["doctor", "--days", ($days | into string), "--limit", ($limit | into string)])
    let run: record = (do { run-external $BIN ...$args } | complete)
    if $run.exit_code != 0 {
        # fold-stderr (nushell-fp proc.nu): the fold born in THIS script goes
        # home — the complete record's stderr arrives string OR list-of-lines,
        # one source everywhere (the 15/11 drill trap).
        let errText: string = (fold-stderr $run)
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

# --apply N: force ONE deferred proposal (the doctor's thunk). The repair is
# dispatch by cause (the mode-table law):
#   CORPUS_GAP  → scaffold the note the proposal describes (the proposal
#                 text rides IN the note as the TODO — never auto-written
#                 content, the owner fills it) + reindex the file
#   STALE_DOC   → reindex the served target (the doc exists, refresh it)
#   anything else → honest no-op (the proposal is prose for the owner)
# The thunk is built from the case data at force time — the proposal JSON IS
# the persisted thunk, the closure rebuilds itself from it.
def repair-case [case: record]: nothing -> string {
    let slug: string = ($case.query | str lowercase | str replace -a ' ' '-' | str substring 0..59)
    let repair: closure = (match $case.cause {
        "CORPUS_GAP" => {|| 
            let notePath: string = $"($KB_NOTES)/corpus-gap-($slug).md"
            if not ($notePath | path exists) {
                $"# TODO corpus gap — write me\n\nQuery that missed: ($case.query)\nJudge cause: ($case.cause)\n\nProposal:\n\n($case.proposal)\n" | save -f $notePath
            }
            let idx: record = (do { ^$BIN index $notePath } | complete)
            $"scaffolded + indexed ($notePath | path basename) — index exit ($idx.exit_code)"
        }
        "STALE_DOC" => {|| 
            let target: string = ($case.targetUri? | default "")
            if ($target | is-empty) { return "no targetUri — nothing to refresh" }
            let idx: record = (do { ^$BIN index $target } | complete)
            $"reindexed ($target | path basename) — index exit ($idx.exit_code)"
        }
        _ => {|| $"cause ($case.cause) is prose for the owner — no mechanical repair" }
    })
    # force (thunk f) with an immediate force = do f — the thunk earns its
    # keep only when the deferred decision is STORED (the proposal JSON) and
    # the closure rebuilds at force time. Here: direct call (the owner's
    # identity catch, 21/11).
    do $repair
}

def apply-proposal [n: int, --days: int = 7]: nothing -> nothing {
    let run: record = (do { run-external $BIN "doctor" "--days" ($days | into string) "--limit" "30" } | complete)
    if $run.exit_code != 0 {
        print "apply: the doctor run failed — nothing to apply"
        exit 1
    }
    let report: record = ($run.stdout | from json)
    let cases: list<record> = ($report.cases? | default [])
    let pick: list<record> = ($cases | enumerate | where {|it| ($it.index + 1) == $n } | get item)
    if ($pick | is-empty) {
        print $"apply: no proposal numbered ($n) — the doctor graded ($cases | length) cases"
        exit 1
    }
    let case: record = ($pick | get 0)
    let result: string = (repair-case $case)
    let line: string = $"🩺 proposal ($n) applied — ($case.cause): ($result)"
    announce $line
    print $line
}
