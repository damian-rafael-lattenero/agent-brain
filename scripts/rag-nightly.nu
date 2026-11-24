#!/opt/homebrew/bin/nu
# rag-nightly.nu — the 04:00 RAG health loop: refresh the index from the
# corpus globs, then MEASURE the retrieval with the real-questions suite and
# leave the score in the ledger (and the log). A night that indexes without
# measuring cannot say whether the RAG got better or worse.
#
# The index agent (com.zcode.agent-brain-index) runs this. The score ALWAYS
# rides Telegram (one honest line — the daily quality heartbeat); a score
# below the suite's gate speaks LOUD with the misses. A failure in the eval
# leg does not fail the night — the ledger row is the record, the message is
# the alarm.

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu [announce announce-loud]
use /Users/lattenero/Projects/nushell-fp/mods/proc.nu fold-stderr

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

# The type aliases (the owner's 22/11 law): the shapes read once, the
# signatures spell the name.
type EvalScore = record<passed: int, total: int, gate: float, ok: bool, misses: list<string>>

# The eval report's score as a tagged value: {passed, total, gate, ok, misses}
def read-score [stdout: string]: nothing -> EvalScore {
    let parsed: record = ($stdout | from json)
    let passed: int = ($parsed.passed? | default 0)
    let total: int = ($parsed.cases? | default [] | length)
    let gate: float = ($parsed.gate? | default 1.0)
    let misses: list<string> = ($parsed.cases? | default [] | where {|c| not ($c.ok? | default false) } | get id? | default [])
    let ok: bool = ($total > 0) and (($passed / $total) >= $gate)
    {passed: $passed, total: $total, gate: $gate, ok: $ok, misses: $misses}
}

def main []: nothing -> nothing {
    let stamp: string = (date now | format date '%Y-%m-%d %H:%M')
    print $"== rag-nightly ($stamp) =="

    # The CURATED layer first (corpus.txt: the KB notes, the Scripts hub, the
    # deploy scripts) — this is the line the header always claimed and the
    # body never ran: only index-repos was refreshed, so a note committed to
    # nushell-kb/notes stayed invisible until someone ran `index` by hand
    # (measured 24/11: the day's traps note was missing after a commit + a
    # nightly). Both legs are incremental; each reports its own exit.
    let curated: record = (do { ^$BIN index } | complete)
    print $"index (corpus.txt): exit ($curated.exit_code)"
    if $curated.exit_code != 0 {
        print "index (corpus.txt) failed — the repo walker and the eval still run"
        print (fold-stderr $curated | str substring 0..400)
    }

    let index: record = (do { ^$BIN index-repos } | complete)
    print $"index-repos: exit ($index.exit_code)"
    if $index.exit_code != 0 {
        print "index-repos failed — the eval leg still runs on the previous index"
        print (fold-stderr $index | str substring 0..400)
    }

    let eval: record = (do { ^$BIN eval --suite real-questions } | complete)
    print $"eval real-questions: exit ($eval.exit_code)"
    if $eval.exit_code != 0 {
        print (fold-stderr $eval | str substring 0..400)
        announce-loud $"🌙 <b>RAG nightly FALLÓ</b> — el eval no corrió: (fold-stderr $eval | str substring 0..200)"
        return
    }

    let score: EvalScore = (read-score $eval.stdout)
    print $"score: ($score.passed)/($score.total) gate ($score.gate) ok=($score.ok)"
    if $score.ok {
        announce $"🌙 RAG nightly: ($score.passed)/($score.total) ✅ gate ($score.gate)"
    } else {
        let misses: string = ($score.misses | str join ", ")
        announce-loud $"🌙 <b>RAG nightly BAJÓ del gate</b> — ($score.passed)/($score.total) con gate ($score.gate). Misses: ($misses)"
    }

    # The application-specific leg: grade the production rag_query log with the
    # judge. Nothing to grade exits 0 quietly; a gate miss speaks LOUD — same
    # law as the suite above.
    let usage: record = (do { ^$BIN usage-eval } | complete)
    print $"usage-eval: exit ($usage.exit_code)"
    if $usage.exit_code != 0 {
        print (fold-stderr $usage | str substring 0..300)
        announce-loud $"🌙 <b>Usage eval BAJÓ del gate</b> — (fold-stderr $usage | str substring 0..200)"
    }

    # The shadow leg (plan v2 T1): judge the newest completed bot turns on the
    # rubric — the daily quality number for the REPLIES. Nothing new exits 0
    # quietly; a gate miss speaks LOUD with the average.
    let shadow: record = (do { ^$BIN shadow-eval } | complete)
    print $"shadow-eval: exit ($shadow.exit_code)"
    if $shadow.exit_code != 0 {
        print ($shadow.stderr | str substring 0..300)
        let parsed: record = ($shadow.stdout | from json)
        let passed: int = ($parsed.passed? | default 0)
        let total: int = ($parsed.total? | default 0)
        announce-loud $"🌙 <b>Shadow eval BAJÓ del gate</b> — ($passed)/($total) turnos al rubro 4+."
    }
}
