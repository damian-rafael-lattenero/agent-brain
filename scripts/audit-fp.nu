#!/usr/bin/env nu
# U0 of the FP/concurrency audit plan for agent-brain (2026-11-08) — the same
# machinery the bot repo ran (scripts/audit-fp.nu there), same categories,
# same patterns, scope adapted to this repo's main sources (core + brain-mcp).
# rg scans, nu classifies; stdout is a markdown report — the CALLER redirects
# it to docs/audit/ (effects at the edge, the script itself stays pure).
#
# Categories (identical to the bot audit):
#   forbidden  — reactive/blocking constructs the laws ban
#   handrolled — hand-rolled concurrency where a kap primitive exists
#   impure     — clock/randomness/file IO suspects inside main sources
#   errors     — broad catches, not-null assertions, throw statements
#
# Every hit is a SUSPECT for the U1 semantic review, not a verdict.

const REPO: string = '/Users/lattenero/Projects/agent-brain'

def scan-one [label: string, pattern: string]: nothing -> table<category: string, file: string, line: int, text: string> {
  let res: record = (do { rg -n --no-heading -e $pattern $REPO -g 'core/src/main/**' -g 'brain-mcp/src/main/**' } | complete)
  if $res.exit_code != 0 { return [] } # rg exit 1 = zero matches: honest empty
  $res.stdout | lines | each {|l|
    let parts: list<string> = ($l | split row ':')
    {
      category: $label
      file: ($parts | first | str replace ($REPO + '/') '')
      line: ($parts | get 1 | into int)
      text: ($parts | skip 2 | str join ':' | str trim | str substring 0..170)
    }
  }
}

def main []: nothing -> nothing {
  let forbidden: table = (scan-one 'forbidden' 'while\s*\((true|1)\)|\bdo\s*\{|Thread\.sleep|runBlocking|GlobalScope|\bThread\(')
  let handrolled: table = (scan-one 'handrolled' 'synchronized\s*\(|@Volatile|AtomicLong|AtomicInteger|AtomicBoolean|AtomicReference|ReentrantLock|java\.util\.concurrent')
  let impure: table = (scan-one 'impure' 'System\.currentTimeMillis|System\.nanoTime|Instant\.now\(|LocalDateTime\.now|LocalDate\.now|java\.time\.Clock|Random\(|java\.io\.[A-Z]|FileWriter|FileInputStream|FileOutputStream')
  let errors: table = (scan-one 'errors' 'catch\s*\(\s*\w+\s*:\s*\w*Exception|!!|\bthrow\b')
  let all: table = ($forbidden | append $handrolled | append $impure | append $errors)

  print '# Concurrency/FP audit — U0 mechanical inventory for agent-brain (generated 2026-11-08)'
  print ''
  print 'Scope: `core/src/main` + `brain-mcp/src/main` (tests excluded).'
  print 'Every hit is a SUSPECT for the U1 semantic review, not a verdict.'
  print ''
  for cat in ['forbidden' 'handrolled' 'impure' 'errors'] {
    let n: int = ($all | where category == $cat | length)
    print $'- **($cat)**: ($n)'
  }
  print ''
  for cat in ['forbidden' 'handrolled' 'impure' 'errors'] {
    print ($'## ($cat)')
    print ''
    let rows: table = ($all | where category == $cat)
    if ($rows | is-empty) {
      print '(none)'
    } else {
      print '| file | line | text |'
      print '|---|---|---|'
      for r in $rows {
        print $"| `($r.file)` | ($r.line) | `($r.text | str replace '|' '\\|')` |"
      }
    }
    print ''
  }
}
