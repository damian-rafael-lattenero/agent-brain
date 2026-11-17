#!/opt/homebrew/bin/nu
# rag-nightly.nu — the 04:00 RAG health loop: refresh the index from the
# corpus globs, then MEASURE the retrieval with the real-questions suite and
# leave the score in the ledger (and the log). A night that indexes without
# measuring cannot say whether the RAG got better or worse.
#
# The index agent (com.zcode.agent-brain-index) runs this; a failure in the
# eval leg does not fail the night — the ledger row is the record, the log
# line is the alarm.

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

def main []: nothing -> nothing {
    let stamp: string = (date now | format date '%Y-%m-%d %H:%M')
    print $"== rag-nightly ($stamp) =="

    let index: record = (do { ^$BIN index-repos } | complete)
    print $"index-repos: exit ($index.exit_code)"
    if $index.exit_code != 0 {
        print "index-repos failed — the eval leg still runs on the previous index"
        print ($index.stderr | str substring 0..400)
    }

    let eval: record = (do { ^$BIN eval --suite real-questions } | complete)
    print $"eval real-questions: exit ($eval.exit_code)"
    let score: string = ($eval.stdout | lines | where {|l| $l =~ '"passed"' } | first | default '{"passed": "?"}' | str trim)
    print $"score: ($score)"
    if $eval.exit_code != 0 {
        print ($eval.stderr | str substring 0..400)
    }
}
