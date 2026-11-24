#!/opt/homebrew/bin/nu
# memory-translate-run.nu — ONE-SHOT wrapper for the English-corpus
# migration: runs translate-legacy over ALL untranslated memories (backup
# first, judge batch per 12, re-embedded in place, translated_at marker) and
# speaks ONE Telegram line with the verdict (loud on failure — the house
# law). The translation costs deepseek tokens by the owner's explicit OK.

use /Users/lattenero/Scripts/lib/deploy.nu [announce announce-loud]

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

def main []: nothing -> nothing {
    print $"== memory-translate (date now | format date '%Y-%m-%d %H:%M') =="

    let run: record = (do { ^$BIN translate-legacy } | complete)
    print $"translate-legacy: exit ($run.exit_code)"
    if $run.exit_code != 0 {
        print ($run.stderr | str substring 0..400)
        announce-loud $"🌐 <b>memory-translate FALLÓ</b> — ($run.stderr | str substring 0..200)"
        exit 1
    }
    let report: record = ($run.stdout | from json)
    announce $"🌐 memory-translate: ($report.translated) memorias traducidas en ($report.batches) batches (pendientes falladas: ($report.failedItems)) — backup en ($report.backupTable)"
}
