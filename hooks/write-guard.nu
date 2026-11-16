#!/opt/homebrew/bin/nu
# PreToolUse(Write|Edit) — WRITE GUARD (agent-brain hook)
#
# Problem: the harness rejects Write/Edit on an existing file that was not Read in
# this session ("File has not been read yet") and on a file modified after the last
# Read/Write ("File has been modified since read"). Both rejects land AFTER the
# model composed the whole content and AFTER the owner's permission tap — measured
# in the local session store: ~560 not-read + ~320 stale rejects in 30 days.
#
# This hook mirrors the harness read-state from the session store (the same
# evidence the CLI itself uses) and denies FIRST, before the permission prompt,
# with one instruction: Read, then resend the same call. Deny is always correct:
# a persisted state part is a necessary condition for the harness to accept the
# write, so the guard can only reject writes the harness would reject.
#
# Fail-open by contract: missing payload/DB/session, sqlite errors and any
# unexpected exception exit 0 (the harness keeps its own guard). Nothing here
# can allow a write the harness would block.

const DB_PATH: string = '/Users/lattenero/.zcode/cli/db/db.sqlite'
const SQLITE: string = '/usr/bin/sqlite3'
const LOG_PATH: path = '/Users/lattenero/.zcode/hooks/state/write-guard.tsv'

# ---------------------------------------------------------------------------
# Pure core
# ---------------------------------------------------------------------------

# Resolve the tool's file_path against the event cwd (payload paths can be relative).
export def resolve-path [raw: string, cwd: string]: nothing -> string {
    if ($raw =~ '^/') or ($cwd | is-empty) {
        $raw
    } else {
        $cwd | path join $raw
    }
}

# Verdict tags: allow | deny_not_read | deny_stale | deny_partial
# `found` = a completed Read/Write/Edit part exists for this path in this session.
# `end_ms` = completion time of the latest state part; `mtime_s` = file mtime in
# epoch seconds (0 = unknown). Second precision can only under-report staleness,
# never invent it.
export def decide [
    exists: bool
    found: bool
    partial: bool
    end_ms: int
    mtime_s: int
]: nothing -> string {
    if not $exists {
        return 'allow'
    }
    if not $found {
        return 'deny_not_read'
    }
    if $partial {
        return 'deny_partial'
    }
    if ($end_ms > 0) and ($mtime_s > 0) and (($mtime_s * 1000) > $end_ms) {
        return 'deny_stale'
    }
    'allow'
}

# Model-visible reason for a deny tag (English, short, one next action).
export def message [tag: string, path: string, tool: string]: nothing -> string {
    match $tag {
        'deny_not_read' => $'write-guard: ($path) was not Read in this session, so ZCode will reject this ($tool) with "File has not been read yet". Do this: Read ($path) as a full read, no offset/limit, then resend this same call unchanged. No recomposition needed.'
        'deny_stale' => $'write-guard: ($path) changed on disk after your last Read/Write of it, so ZCode will reject this ($tool) with "File has been modified since read". Read the file again, adapt old_string if the change touched it, then resend.'
        'deny_partial' => $'write-guard: your last Read of ($path) was truncated as a partial view, which ZCode does not accept before Write/Edit. Do a full Read, no offset/limit, then resend.'
        _ => ''
    }
}

# ---------------------------------------------------------------------------
# Effects
# ---------------------------------------------------------------------------

def sql-str [s: string]: nothing -> string {
    "'" + ($s | str replace -a "'" "''") + "'"
}

# Latest completed state-establishing part (Read/Write/Edit) for `paths` in `sid`.
# Returns {found, partial, end_ms}; found=false on any doubt (fail-open upstream).
def latest-state [sid: string, paths: list<string>]: nothing -> record {
    let in_list: string = ($paths | each {|p| sql-str $p } | str join ',')
    let q: string = ([
        "select json_extract(data,'$.tool'),"
        " coalesce(json_extract(data,'$.state.time.end'),0),"
        " coalesce(json_extract(data,'$.metadata.readFileState.isPartialView'),0)"
        " from part where session_id=" (sql-str $sid)
        " and json_extract(data,'$.tool') in ('Read','Write','Edit')"
        " and json_extract(data,'$.state.input.file_path') in (" $in_list ")"
        " and json_extract(data,'$.state.status')='completed'"
        " order by coalesce(json_extract(data,'$.state.time.end'),time_created) desc limit 1;"
    ] | str join '')
    let res = (do { ^$SQLITE $DB_PATH $q } | complete)
    if $res.exit_code != 0 {
        return {found: false, partial: false, end_ms: 0}
    }
    let line: string = ($res.stdout | str trim)
    if ($line | is-empty) {
        return {found: false, partial: false, end_ms: 0}
    }
    let cols: list<string> = ($line | split row '|')
    {
        found: true
        partial: ((($cols | get -o 2 | default '0') | str trim) == '1')
        end_ms: ((($cols | get -o 1 | default '0') | str trim) | into int)
    }
}

def mtime-seconds [p: string]: nothing -> int {
    try { ^stat -f %m $p | str trim | into int } catch { 0 }
}

def log-deny [sid: string, tool: string, tag: string, path: string]: nothing -> nothing {
    try {
        mkdir ($LOG_PATH | path dirname)
        let line: string = ([(date now | format date '%FT%T') $sid $tool $tag $path] | str join "\t")
        ($line + "\n") | save --append $LOG_PATH
    } catch { }
}

def emit-deny [reason: string]: nothing -> nothing {
    { hookSpecificOutput: {
        hookEventName: 'PreToolUse'
        permissionDecision: 'deny'
        permissionDecisionReason: $reason
    } } | to json -r | print
}

def main []: string -> nothing {
    let payload: record = ($in | from json)
    let tool: string = ($payload | get -o tool_name | default '')
    if ($tool != 'Edit') and ($tool != 'Write') { exit 0 }

    let ti: record = ($payload | get -o tool_input | default {})
    let raw: string = ($ti | get -o file_path | default '')
    let sid: string = ($payload | get -o session_id | default '')
    if ($raw | is-empty) or ($sid | is-empty) { exit 0 }

    let path: string = (resolve-path $raw ($payload | get -o cwd | default ''))
    let exists: bool = ($path | path exists)
    if not $exists { exit 0 }

    let state: record = (latest-state $sid [$raw $path])
    let tag: string = (decide $exists $state.found $state.partial $state.end_ms (mtime-seconds $path))
    if $tag == 'allow' { exit 0 }

    log-deny $sid $tool $tag $path
    emit-deny (message $tag $path $tool)
}
