#!/opt/homebrew/bin/nu
# deploy-brain.nu — build + kill & deploy + verify for the agent-brain service.
#
# The fork's build-install.nu and the bot's deploy-daemon.nu voice, for the
# brain: 1/3 build (Gradle installDist + detekt — CI owns the test tiers),
# 2/3 LaunchAgent restart (bootout + bootstrap = the kill), 3/3 reactive
# verification of /ready. Same contract as the other deploys: the phone never
# sits blind (announce per phase), failures speak LOUD, and the script dies
# honestly instead of false-passing.
#
# Run it from Ghostty (like every deploy): this restart is a brief brain
# outage — /brain, /memory and memory_inject fail loudly while it lasts, and
# the ZCode session's brain-mcp plugin reconnects after it. The build runs
# BEFORE the restart, so the old bits serve until the swap.
#
# The verify wait is the waiting law's DEFAULT pattern (KB reactive-jobs.md
# Pattern 3): the probe loop lives INSIDE a job (the thread blocks on the
# HTTP retries — justified polling, an HTTP endpoint has no file event), the
# main thread blocks on the mailbox, and `job recv --timeout` is the deadline
# that speaks failure. Zero busy-wait on the main thread.

use /Users/lattenero/Projects/zcode-bot-kotlin/scripts/lib/deploy.nu *

const LABEL = "com.zcode.agent-brain"
const BASE = "http://127.0.0.1:8765"

# Probe /ready in a bounded retry loop INSIDE a job; success wakes the main
# thread via the mailbox. 90 tries x 500ms covers a cold JVM with margin;
# the outer recv deadline (90s) is the failure signal if the job never sends.
def wait-ready [deadline: duration]: nothing -> bool {
    let waiter = (job spawn {
        mut up: bool = false
        for _ in 1..90 {
            let ok: bool = (try { http get -m 2sec $"($BASE)/ready" | ignore; true } catch { false })
            if $ok {
                $up = true
                break
            }
            sleep 500ms
        }
        if $up { 'ready' | job send 0 }
    })
    let ok: bool = (try { job recv --timeout $deadline; true } catch { false })
    try { job kill $waiter } catch { }
    $ok
}

def main []: nothing -> nothing {
    let repo = ($env.FILE_PWD | path dirname)

    step "building (Gradle daemon)"
    announce "🧠 deploy agent-brain 1/3 — building (installDist + detekt; el build es el tramo largo)"
    cd $repo
    let build: record = (do { ^./gradlew -q :core:installDist detektAll } | complete)
    if $build.exit_code != 0 {
        print -e $build.stderr
        announce-loud "❌ <b>DEPLOY AGENT-BRAIN FALLÓ</b> — el build (Gradle/detekt) se cayó; el brain sigue sirviendo lo anterior"
        die $"the build failed: exit ($build.exit_code)"
    }
    print $build.stdout

    step $"LaunchAgent ($LABEL) — bootout + bootstrap: the kill & deploy"
    announce "🧠 deploy agent-brain 2/3 — restart del servicio (breve outage de /brain y memoria)"
    bootstrap-agent $repo $LABEL

    step "verification: /ready 200 (deadline 90 s)"
    announce "🧠 deploy agent-brain 3/3 — verificación (/ready, deadline 90 s)"
    let ok: bool = (wait-ready 90sec)
    if not $ok {
        print -e "✗ the brain did not come back — last words:"
        let state: string = (do { ^launchctl print $"gui/(user-id)/($LABEL)" } | complete | get stdout)
        print $state
        let err_log: string = ($nu.home-dir | path join "Library/Logs/agent-brain.log")
        for line in (tail-lines $err_log 5) {
            print -e $line
        }
        announce-loud "❌ <b>DEPLOY AGENT-BRAIN FALLÓ</b> — /ready no respondió en 90 s; mirá el deploy log y ~/Library/Logs/agent-brain.log"
        exit 1
    }
    announce-loud "🧠 <b>DEPLOY AGENT-BRAIN COMPLETO</b> — /ready 200; rag_query y memoria sirviendo con los bits nuevos"
    print 'done. Brain redeployed: /ready 200 on the fresh bits.'
    print 'Health: nu scripts/health-check.nu (7 layers) · drills: ops/drills/.'
}
