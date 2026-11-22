#!/opt/homebrew/bin/nu
# PreToolUse(Edit|Write) on .nu files: the STATIC TYPING law as mechanics.
# DENY (exit 2): def without an IO signature, or params without `: type`.
# Blind sleep >=10s is denied too.
# WARN: let/const/mut without annotation · prose-parens inside $"..." ·
# polling loop (while/loop + sleep).
# Only COMPLETE def headers (with `]` closed) are judged; ambiguous
# fragments pass.
# Verified 0.116.1: IO signatures are checked at parse time
# (output_type_mismatch), so annotating turns nu-check into real static
# checking.

def untyped-params [params: string]: nothing -> list<string> {
  $params
  | split row ','
  | each {|p| $p | str trim }
  | where {|p| (not ($p | is-empty)) and (not ($p | str starts-with '--')) and (not ($p =~ ':')) }
}

def defs-without-signature [src: string]: nothing -> list<string> {
  let todos: table = ($src | parse -r 'def\s+(?<n>[\w\-!]+)\s*\[(?<p>[^\]]*)\]' | uniq-by n)
  let withSig: list<string> = ($src | parse -r 'def\s+(?<n>[\w\-!]+)\s*\[[^\]]*\]\s*:' | get -o n | default [])
  $todos | where {|d| not ($d.n in $withSig) } | get -o n | default []
}

def untyped-bindings [src: string]: nothing -> list<string> {
  let all: list<string> = ($src | parse -r '(?m)^\s*(?:let|const|mut)\s+(?<n>[\w\-]+)\s*=' | get -o n | default [])
  let typed: list<string> = ($src | parse -r '(?m)^\s*(?:let|const|mut)\s+(?<n>[\w\-]+)\s*:' | get -o n | default [])
  $all | where {|n| not ($n in $typed) } | uniq
}

# Agent micro-errors (6/11, x4 that day): PROSE in literal parens inside
# $"..." — the parser reads it as a command call and it explodes at runtime.
# 11/11 (fork.nu: prose parens ate a deploy announce): comma-less prose
# counts too — 3+ consecutive lowercase words with no $, | or digits is not
# a legal subexpression. Real ones: ($var), (cmd), (cmd | pipeline),
# (ansi cyan) — none match.
def prose-in-interpolation [src: string]: nothing -> list<string> {
  $src
  | lines
  | where {|l| ($l =~ '\$\"') and (($l =~ '\((?!\$)[a-záéíóúüñ]+[,:;]') or ($l =~ '\((?!\$)[a-záéíóúüñ]+(?: [a-záéíóúüñ]+){2,}\)')) }
  | each {|l| $l | str substring 0..120 }
}

# REACTIVE LAW: blind sleep >= 10s forbidden (job spawn/send/recv
# --timeout, or watch). Short sleeps (<10s) stay legal as measured
# retry-gaps (e.g. the 2s bootout->bootstrap race gap).
def long-sleep [src: string]: nothing -> string {
  let matches: table = ($src | parse -r '(?i)\bsleep\s+(?<n>\d+(?:\.\d+)?)\s*(?<u>seconds?|secs?|min(?:ute)?s?|m|hours?|hr|h)?\b')
  let offender: string = ($matches
    | where {|mm|
        let f: float = (match $mm.u {
          null => 1.0
          's' => 1.0
          'sec' => 1.0
          'secs' => 1.0
          'second' => 1.0
          'seconds' => 1.0
          'm' => 60.0
          'min' => 60.0
          'mins' => 60.0
          'minute' => 60.0
          'minutes' => 60.0
          'h' => 3600.0
          'hr' => 3600.0
          'hrs' => 3600.0
          'hour' => 3600.0
          'hours' => 3600.0
        })
        (($mm.n | into float) * $f) >= 10.0
      }
    | each {|mm| $"($mm.n)($mm.u | default '')" }
    | first
    | default '')
  $offender
}

# polling loop: while/loop with a sleep inside — warn even when the sleep
# is short
def sleep-in-loop [src: string]: nothing -> bool {
  ($src =~ '(?i)(while|loop)[^\n]{0,80}\bsleep\b') or ($src =~ '(?i)^\s*(while|loop)\s*\{[^}]*\bsleep\b')
}

# THE TYPE-ALIAS LAW (owner, 22/11) as mechanics: a def signature or
# binding inlining a DEEP ANONYMOUS record (3+ fields = 2+ commas inside
# record<...>) should carry a `type` alias instead. Alias DEFINITIONS
# (`type X = record<...>`) are the fix, not the crime — lines starting
# with `type` are exempt. Single-line detection: multi-line record spans
# pass (the tree-sitter-nu upgrade will see them).
def anon-record-shapes [src: string]: nothing -> list<string> {
  $src
  | lines
  | where {|l|
      (not ($l | str trim | str starts-with 'type ')) and ($l =~ 'record<[^>,]+,[^>,]+,[^>]*>')
    }
  | each {|l| $l | str substring 0..100 }
}

def main []: string -> nothing {
  let payload: record = ($in | from json)
  let ti: record = ($payload | get -o tool_input | default {})
  let file: string = ($ti | get -o file_path | default '')
  if not ($file =~ '\.nu$') { exit 0 }
  let body: string = (if ($payload | get -o tool_name | default '') == 'Write' { $ti | get -o content } else { $ti | get -o new_string } | default '')
  if ($body | is-empty) { exit 0 }

  # DENY always: hyphenated variable (invalid in nu — hyphens are for
  # COMMANDS, not variables)
  let hyphenVars: list<string> = ($body | parse -r '(?m)^\s*(?:let|const|mut)\s+(?<n>[\w]+-[\w\-]*)\s*[=:]' | get -o n | default [])
  if not ($hyphenVars | is-empty) {
    print -e $"⛔ Nushell law — hyphenated variables are INVALID: ($hyphenVars | str join ', '). Rename them camelCase: hyphens are for commands, not variables."
    exit 2
  }

  # DENY reactive: blind sleep >= 10s
  let sl: string = (long-sleep $body)
  if not ($sl | is-empty) {
    print -e $"⛔ Reactive law — blind sleep ($sl) FORBIDDEN: be event-driven with `job spawn` + `job send 0` + `job recv --timeout` — the timeout raises and goes in try/catch — or `watch` as a stream. Recipe: KB notes/reactive-jobs.md. Sleeps <10s as measured retry-gaps are fine."
    exit 2
  }

  # a def with an open bracket (Edit fragment) → do not judge defs in this payload
  let openDef: bool = ($body =~ 'def\s+[\w\-!]+\s*\[[^\]]*$')
  if not $openDef {
    let missingSig: list<string> = (defs-without-signature $body)
    if not ($missingSig | is-empty) {
      print -e $"⛔ Typing law — defs WITHOUT an IO signature: ($missingSig | str join ', '). Add `]: type -> type`, e.g.: nothing -> nothing."
      exit 2
    }
    let badParams: list<string> = ($body
      | parse -r 'def\s+[\w\-!]+\s*\[(?<p>[^\]]*)\]'
      | get -o p | default []
      | each {|p| untyped-params $p }
      | flatten
      | uniq)
    if not ($badParams | is-empty) {
      print -e $"⛔ Typing law — params WITHOUT a type: ($badParams | str join ', '). Annotate `name: type`; bare --flags are bool, ok."
      exit 2
    }
  }

  let looseBindings: list<string> = (untyped-bindings $body)
  let proseLines: list<string> = (prose-in-interpolation $body)
  let inLoop: bool = (sleep-in-loop $body)
  let anonRecords: list<string> = (anon-record-shapes $body)
  if (not ($looseBindings | is-empty)) or (not ($proseLines | is-empty)) or $inLoop or (not ($anonRecords | is-empty)) {
    let part1: string = (if ($looseBindings | is-empty) { '' } else { $"untyped bindings: ($looseBindings | first 4 | str join ', ') — annotate as `let n: int = ...` unless the type is evident on the line. " })
    let part2: string = (if ($proseLines | is-empty) { '' } else { $"possible LITERAL PARENS in interpolation — they are command calls: ($proseLines | str join ' ⏎ ') — escape them or drop the prose parens. " })
    let part3: string = (if $inLoop { 'polling loop detected — sleep inside while/loop: replace with `job recv --timeout` or `watch` (reactive, zero busy-wait).' } else { '' })
    let part4: string = (if ($anonRecords | is-empty) { '' } else { $"deep anonymous record<...> in a signature — THE TYPE-ALIAS LAW (owner 22/11): name it with `type X = record<...>` (evidence: ($anonRecords | first | str trim | str substring 0..80)). " })
    let ctx: string = $"⚖️ Nushell law on ($file | path basename): ($part1)($part2)($part3)($part4)"
    { hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: $ctx } } | to json -r | print
  }
  exit 0
}
