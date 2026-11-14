#!/Users/lattenero/.local/bin/nu-nightly
# brain-sentinel.nu — the 24/7 external watcher of the brain's architecture.
#
# Patterns (researched 2026-11-14, see memory):
#   - LAYERED BLACK-BOX checks: reuses health-check.nu's engine verbatim
#     (launchd chain, colima, containers, serve /health, ollama, RAG e2e,
#     memory search, index freshness). Nobody trusts the system's own word
#     alone — layer 6 queries the RAG like a real user.
#   - TRANSITION-ONLY alerts (alert-fatigue law): a Telegram message fires
#     on down->up and up->down changes only, never per-pass. Every alert
#     carries its runbook hint.
#   - DEAD MAN'S SWITCH (heartbeat inversion): every ALL-GREEN pass pings
#     the healthchecks.io check; the SILENCE alerts. This is the only layer
#     that lives outside the Mac — it catches the death of the sentinel
#     itself, launchd, or the whole machine.
#   - SEPARATE FAILURE DOMAIN: launchd-owned (survives ZCode restarts),
#     zero dependency on the brain to ALERT (Telegram direct, token via
#     curl config on stdin — the bot-healer pattern, token never in argv).
#
# Wiring: com.zcode.brain-sentinel LaunchAgent, StartInterval 120.
# State: ~/.zcode/agent-brain/sentinel-state.json
# Heartbeat URL: ~/.zcode/agent-brain/sentinel-heartbeat.url (0600; missing
#   file = honest degrade, the sentinel logs it once per pass, no alerts)
# Secrets: token/chat come from ~/.zcode/telegram-remote.json (never here).

use health-check.nu checks

# launchd agents run with a minimal PATH (/usr/bin:/bin:...) — docker, colima
# and friends live in the homebrew/local bins. Fix it once, up front, so every
# layer resolves its externals (the colima-watchdog idiom, one level up).
$env.PATH = ($env.PATH | append [/opt/homebrew/bin /usr/local/bin])

const STATE: string = "/Users/lattenero/.zcode/agent-brain/sentinel-state.json"
const HEARTBEAT: string = "/Users/lattenero/.zcode/agent-brain/sentinel-heartbeat.url"

# Stateless sendMessage — the bot-healer pattern: token rides curl's config
# on stdin, never argv; it never touches getUpdates so it cannot fight the
# daemon's polling monopoly.
def tg-send [text: string]: nothing -> nothing {
    let cfg: any = (try { open --raw ~/.zcode/telegram-remote.json | from json } catch { null })
    if $cfg == null { return }
    let token: string = ($cfg | get --optional token | default "")
    let chat: string = ($cfg | get --optional chat_id | default "")
    if ($token | is-empty) or ($chat | is-empty) { return }
    let payload: string = ({chat_id: ($chat | into string), text: $text} | to json)
    let curlCfg: string = $"url = \"https://api.telegram.org/bot($token)/sendMessage\"\n"
    $curlCfg | curl -sS --config - --header "Content-Type: application/json" --data $payload -o /dev/null
}

# One pass over every layer; returns the rows with their verdicts.
def run-layers []: nothing -> list<record<layer: string, check: string, ok: bool, detail: string, hint: string>> {
    checks | each {|c|
        let r: any = (try { do $c.f } catch {|err| {ok: false, detail: $"exception: ($err.msg)", hint: "review the script"}})
        {layer: $c.layer, check: $c.check, ok: ($r.ok | default false), detail: ($r.detail | default "?"), hint: ($r.hint | default "")}
    }
}

def load-state []: nothing -> record<down: list<string>> {
    let s: any = (try { open --raw $STATE | from json } catch { null })
    if $s == null { {down: []} } else { {down: ($s.down? | default [])} }
}

def main [--dry-run(-d), --status(-s)]: nothing -> nothing {
    if $status {
        print (load-state | to json)
        return
    }
    let rows: list<record<layer: string, check: string, ok: bool, detail: string, hint: string>> = (run-layers)
    let bad: list<record> = ($rows | where {|r| not $r.ok})
    let prev: record<down: list<string>> = (load-state)
    let nowDown: list<string> = ($bad | get layer)
    let newDowns: list<record> = ($bad | where {|r| $r.layer not-in $prev.down})
    let newUps: list<string> = ($prev.down | where {|l| $l not-in $nowDown})

    let stamp: string = (date now | format date "%Y-%m-%d %H:%M:%S")
    if not ($dry_run) {
        # transitions only — one message per state change, hint included
        $newDowns | each {|r|
            tg-send $"🧠⚠️ brain-sentinel — capa CAÍDA: ($r.layer) (($r.check))\n($r.detail)\n🔧 ($r.hint)"
        }
        $newUps | each {|l|
            tg-send $"🧠✅ brain-sentinel — capa RECUPERADA: ($l)"
        }
        # the dead man's switch: ping ONLY on a fully green pass
        if ($bad | is-empty) {
            let hb: string = (try { open --raw $HEARTBEAT | str trim } catch { "" })
            if not ($hb | is-empty) { http get $hb | ignore }
        }
        {down: $nowDown} | to json | save --force $STATE
    }
    let verdict: string = (if ($bad | is-empty) { "ALL GREEN" } else { $"($bad | length) down: ($nowDown | str join ', ')" })
    print $"($stamp) ($verdict)(if $dry_run { ' [dry-run]' } else { '' })"
    if ($bad | is-empty) { exit 0 } else { exit 1 }
}
