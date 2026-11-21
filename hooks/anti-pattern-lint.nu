#!/opt/homebrew/bin/nu
# anti-pattern-lint.nu — PreToolUse(Edit|Write) on .nu files: the MECHANICAL
# half of the giant-linter vision (owner 21/11: detect anti-patterns AT
# writing time, and the rag-guard retrieval serves the matching laws BEFORE
# writing — the two halves of one linter over the same law corpus).
#
# The two-layer split (the rag-guard hook is the OTHER half):
#   - rag-guard  = retrieval: the matching laws reach the model BEFORE writing
#   - THIS hook  = deterministic greps: the mechanically-detectable
#                  anti-patterns are DENIED here, with the law citation
#
# Lint runs on the text being WRITTEN (Edit: new_string; Write: content) —
# the existing file's old patterns are not this write's fault. Laws lints:
#   1. orphaned branch: a line starting with else/or/and (parse error family)
#   2. dispatch chain: 3+ `} else if` arms → the mode-table recipe
#   3. `last 1` → returns a list; the value form is `| last`
#   4. `str downcase` → deprecated, `str lowercase`
#   5. `(let ...)` → parse error (mirrors the Bash-side law)
# Deny = exit 2 + stderr citing the anti-patterns-law entry. Never silent.

# Only these rules run; each carries its law citation + the Semgrep-style
# severity (HIGH denies the write, MEDIUM/LOW warn and let it through).
const RULES: list<record> = [
    {
        id: "orphaned-branch"
        severity: "HIGH"
        pattern: '(?m)^\s*(else\b|or\b|and\b)'
        msg: "anti-pattern orphaned-branch (law entry 1+2): a line starting with else/or/and parses as a COMMAND. Glue it: `} else {` — or restructure into the mode table."
    }
    {
        id: "dispatch-chain"
        severity: "HIGH"
        pattern: '\}\s*else if\b'
        minHits: 2
        msg: "anti-pattern dispatch-chain (law entry 1): 3+ arms is a dispatch chain — the chain must DIE, not relocate. Classify the flags once into a mode record (the priority-ordered predicate table picks the winner) and dispatch via a table of closures. Reference: ~/Scripts/brain.nu pick-mode + dispatch-table."
    }
    {
        id: "paren-let"
        severity: "HIGH"
        pattern: '\(\s*let\b'
        msg: "paren-wrapped let: `let` is a statement — `(let x = ...)` is always a parse error. Keep let top-level; the second binding means a .nu script file."
    }
    {
        id: "last-one"
        severity: "MEDIUM"
        pattern: '\|\s*last\s+1\b'
        msg: "nu trap `last 1`: it returns a LIST, not the element. The value form is `| last`."
    }
    {
        id: "downcase"
        severity: "LOW"
        pattern: 'str downcase'
        msg: "deprecated `str downcase` — use `str lowercase`."
    }
]

def main []: string -> nothing {
    let payload: record = ($in | from json)
    let tool: string = ($payload | get -o tool_name | default '')
    if not ($tool in ["Edit" "Write"]) { exit 0 }
    let input: record = ($payload | get -o tool_input | default {})
    let path: string = ($input | get -o file_path | default '')
    if not ($path | str lowercase | str ends-with '.nu') { exit 0 }
    # Edit lints the INCOMING text (new_string); Write lints the whole content.
    let text: string = (if $tool == "Edit" {
        $input | get -o new_string | default ''
    } else {
        $input | get -o content | default ''
    })
    if ($text | is-empty) { exit 0 }

    # HIGH severity denies (exit 2, the model fixes before the write lands);
    # MEDIUM/LOW warn on stderr and let the write through (visible in the
    # transcript). HIGH rules sort first (alphabetical asc: HIGH < LOW <
    # MEDIUM) so the deny wins immediately. minHits gates the count rules
    # (one else-if = 2 legit arms; two = the chain).
    mut warnings: list<string> = []
    for rule in ($RULES | sort-by severity) {
        let hits: int = ($text | parse -r $rule.pattern | length)
        if $hits >= ($rule.minHits? | default 1) {
            let count: string = ($hits | into string)
            let line: string = $"anti-pattern-lint · ($rule.id) · ($rule.severity) · ($count) hit\(s\) — ($rule.msg)"
            if $rule.severity == "HIGH" {
                print -e $"⛔ ($line)"
                exit 2
            } else {
                $warnings = ($warnings | append $line)
            }
        }
    }
    for w in $warnings { print -e $"⚠️ ($w)" }
    exit 0
}
