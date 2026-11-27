#!/opt/homebrew/bin/nu
# memory-translate-run.nu — ONE-SHOT wrapper for the English-corpus
# migration: runs translate-legacy over ALL untranslated memories (backup
# first, judge batch per 12, re-embedded in place, translated_at marker) and
# speaks ONE Telegram line with the verdict (loud on failure — the house
# law). The translation costs deepseek tokens by the owner's explicit OK.

use /Users/lattenero/Scripts/lib/deploy.nu [announce announce-loud]
use /Users/lattenero/Scripts/nushell-fp/mods/wait.nu wait-until

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

# The brain's Postgres answers on its port.
def db-up []: nothing -> bool {
    (do { ^nc -z 127.0.0.1 5433 } | complete).exit_code == 0
}

def main []: nothing -> nothing {
    print $"== memory-translate (date now | format date '%Y-%m-%d %H:%M') =="

    # At login the database is not up yet (measured 26/11: the boot run died on
    # "Connection is not available"). Wait for it, bounded; a database that
    # never comes up is a real failure and is said loud.
    if not (wait-until 3min {|| db-up } --interval 2sec) {
        announce-loud "🌐 <b>memory-translate SKIPPED</b> — the database did not come up within 3 minutes"
        exit 1
    }

    let run: record = (do { ^$BIN translate-legacy } | complete)
    print $"translate-legacy: exit ($run.exit_code)"
    if $run.exit_code != 0 {
        print ($run.stderr | str substring 0..400)
        announce-loud $"🌐 <b>memory-translate FAILED</b> — ($run.stderr | str substring 0..200)"
        exit 1
    }
    let report: record = ($run.stdout | from json)
    announce $"🌐 memory-translate: ($report.translated) memories translated in ($report.batches) batches (failed items: ($report.failedItems)) — backup in ($report.backupTable)"
}
