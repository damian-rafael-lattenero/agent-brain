#!/opt/homebrew/bin/nu
# PreToolUse(Edit|Write): RAG GUARD — before every code write, query the brain
# and inject matched LAW hits (fp-kb, nushell-kb/notes, runbook, kap *.md) as
# additionalContext.
# Guard's own laws: NEVER blocks (never exit 2); brain down/wedged -> honest
# silence; global throttle (state file shared across sessions) to save context.
# Audit 17/11 fixes: the brain query carries a HARD DEADLINE (tagged mailbox +
# job recv --timeout + job kill — no more infinite stall on a wedged brain), and
# every parse/IO edge fails soft to silence instead of crashing the hook.

const BIN: string = '/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain'
const STATE: string = '/Users/lattenero/.zcode/hooks/rag-guard-last'
const FLOOR_S: int = 60
const QUERY_BUDGET: duration = 5sec

def is-law [uri: string]: nothing -> bool {
  [
    ($uri | str contains '/fp-kb/')
    ($uri | str contains '/nushell-kb/notes/')
    ($uri =~ '/zcode-bot-kotlin/docs/runbook\.md')
    (($uri | str contains '/kap/') and ($uri =~ '\.md$'))
  ] | any {|x| $x }
}

def excluded [p: string]: nothing -> bool {
  [
    ($p =~ '/(\.zcode|memories|node_modules|\.gradle|/build/|/target/|/dist/|\.worktrees|/tmp/|/var/|/private/|Library/Logs)/')
    ($p =~ '\.(log|lock|json|ya?ml|toml|csv)$')
  ] | any {|x| $x }
}

# The brain query with a HARD DEADLINE: the child job blocks on the external
# (no busy-wait) and mails the complete record back with a per-call tag
# (stale-mailbox law: a kill cannot leak a message into an unrelated call).
# On deadline the child is killed and an honest synthetic failure is returned.
def query-brain [q: string]: nothing -> record<exit_code: int, stdout: string, stderr: string> {
  let tag: int = (random int 1..100000)
  let id: int = (job spawn --description 'rag-guard brain query' {
    ^$BIN query $q --k 16 | complete | job send 0 --tag $tag
  })
  try { job recv --tag $tag --timeout $QUERY_BUDGET } catch {
    job kill $id
    { exit_code: 124, stdout: '', stderr: 'deadline exceeded' }
  }
}

def main []: string -> nothing {
  let raw: any = (try { $in | from json } catch { null })
  let payload: record = (if (($raw | describe) | str starts-with 'record') { $raw } else { {} })
  let ti: record = ($payload | get -o tool_input | default {})
  let file: string = ($ti | get -o file_path | default '')
  let body: string = (if ($payload | get -o tool_name | default '') == 'Write' { $ti | get -o content } else { $ti | get -o new_string } | default '')
  if ($file | is-empty) or ($body | is-empty) { exit 0 }
  if (excluded $file) { exit 0 }

  let now: int = (date now | format date '%s' | into int)
  let last: int = (try { open --raw $STATE | str trim | into int | default 0 } catch { 0 })
  # global anti-hammer floor (rapid edits); the big throttle is stamped ONLY when we speak
  if (($now - $last) < $FLOOR_S) { exit 0 }

  let sample: string = $"($file | path basename): ($body | str substring 0..600)"
  let res: record = (query-brain $sample)
  if $res.exit_code != 0 { exit 0 }

  let hits: list<any> = (try { $res.stdout | from json | get -o hits | default [] | uniq-by uri } catch { [] })
  let laws: list<any> = ($hits | where {|h| is-law ($h | get -o uri | default '') } | first 2)
  if ($laws | is-empty) { exit 0 }
  # something worth saying: stamp the throttle (silences burn no context,
  # only ~2s of local query — retrying on the next edit is cheap)
  try { $now | save -f $STATE } catch { null }

  let text: string = ($laws
    | each {|h|
        $"• (($h | get -o heading | default '') | str replace -a "\n" ' ' | str substring 0..90)\n(($h | get -o content | default '') | str replace -a "\n" ' ' | str substring 0..500)\n  ↳ ($h | get -o uri | default '')"
      }
    | str join "\n\n")
  let ctx: string = $"🧠 RAG guard — auto before editing ($file | path basename) — brain laws matching this pattern:\n($text)\nIf the design touches something non-trivial with no hit, run rag_query yourself BEFORE continuing."
  { hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: $ctx } } | to json -r | print
}
