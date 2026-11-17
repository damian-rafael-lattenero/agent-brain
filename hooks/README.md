# hooks/ — the ZCode-side hooks the brain owns

These scripts run inside the ZCode CLI (`~/.zcode/cli/config.json` → `hooks.events`)
and are installed by copying them to `~/.zcode/hooks/`. They are plain Nushell
programs that read the hook payload as JSON on stdin.

| File | Event | Job |
| --- | --- | --- |
| `write-guard.nu` | `PreToolUse` (`Write\|Edit`) | Mirror the harness read-state from the session store and deny a Write/Edit that ZCode would reject anyway — **before** the permission prompt and before the model burns a second composition. |
| `rag-guard.nu` | `PreToolUse` (`Write\|Edit`) | Query the brain before every code write and inject matched LAW hits as additionalContext — never blocks (never exit 2), honest silence on brain down/wedged, global throttle across sessions. |

## rag-guard.nu

### Why

Every non-trivial write should consult the brain's law corpus before the model
composes. The guard answers it automatically — but a PreToolUse hook runs on
EVERY Edit/Write, so its failure mode is machine-wide: the 17/11 fleet audit
found the brain query had NO deadline (a wedged brain stalls every edit) and
unguarded JSON edges (a malformed payload crashes the hook, contradicting its
own never-blocks contract).

### Contract and mechanics

- NEVER blocks: every parse/IO edge fails soft to silence (`try`/`catch` →
  `exit 0`); the brain query carries a HARD DEADLINE (`QUERY_BUDGET` = 5 s) via
  `job spawn` + tagged `job recv --timeout` + `job kill` (stale-mailbox law:
  the per-call tag isolates the mailbox; a kill cannot leak a message into an
  unrelated call).
- Returns `hookSpecificOutput.additionalContext` JSON on stdout when ≥1 law
  matches; silent exit 0 otherwise.
- Global throttle: anti-hammer floor of 60 s between speaks; the state file
  `~/.zcode/hooks/rag-guard-last` is shared across sessions.
- Installed by copying to `~/.zcode/hooks/rag-guard.nu`.

### Test

Live drill (payload on stdin, wall time must stay under the 5 s budget):

```sh
nu-check ~/.zcode/hooks/rag-guard.nu   # boolean true, never the exit code
'{ "session_id": "drill", "tool_name": "Edit", "tool_input": { "file_path": "/Users/lattenero/Projects/kap/docs/drill.md", "old_string": "a", "new_string": "job spawn mailbox timeout" } }' \
  | nu --stdin ~/.zcode/hooks/rag-guard.nu
```

## write-guard.nu

### Why

The harness rejects two cases with a tool error:

- `File has not been read yet. Read it first before writing to it.`
- `File has been modified since read, either by the user or by a linter.`

Both land **after** the model composed the whole content and **after** the owner's
permission tap. Measured in `~/.zcode/cli/db/db.sqlite` (last 30 days):

| failure | Edit | Write |
| --- | --- | --- |
| not read in this session | 510 | 52 |
| changed since read (stale) | 304 | 17 |

The guard answers the owner's question — *is this file read?* — at call time, so
the reject arrives with one instruction instead of a costed retry.

### How it decides

`PreToolUse` fires before `resolveToolPermission` in the tool executor, so a
`deny` pre-empts the approval prompt. The hook queries the same session store the
CLI persists its tool parts to (`part` table, `session_id` + `state.input.file_path`)
for the latest **completed** `Read`/`Write`/`Edit` part touching that path:

| condition | verdict |
| --- | --- |
| target does not exist (create) | allow |
| no state part in this session | `deny_not_read` |
| latest part's `state.time.end` < file mtime | `deny_stale` |
| latest Read was truncated (`isPartialView`) | `deny_partial` |
| otherwise | allow |

A deny here is always correct in the safe direction: a persisted state part is a
necessary condition for the harness to accept the write (measured: 99.6 % of
completed Edits have one), so the guard can only reject writes the harness would
reject too. When the guard cannot know — DB missing, no `session_id`, sqlite
error, unexpected payload — it **allows** and the harness keeps its own guard.

### Cost and evidence

- ~40 ms per Write/Edit (one indexed sqlite query), silent when it allows.
- Denies are appended to `~/.zcode/hooks/state/write-guard.tsv`
  (`ts`, `session`, `tool`, `tag`, `path`) so the owner can count what it caught.

### Install

```json
"PreToolUse": [
  { "matcher": "Edit|Write", "hooks": [
    { "type": "command", "command": "nu --stdin /Users/lattenero/.zcode/hooks/write-guard.nu", "timeout": 5 }
  ] }
]
```

### Test

```sh
nu tests/test_write_guard.nu   # pure core: decide / resolve-path / message
```
