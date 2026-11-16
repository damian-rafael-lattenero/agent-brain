#!/opt/homebrew/bin/nu
# Pure-core tests for hooks/write-guard.nu — no DB, no filesystem, no hooks.
# Run: nu tests/test_write_guard.nu   (or via the repo test gate)

use std/assert
use ../hooks/write-guard.nu *

# --- resolve-path -----------------------------------------------------------

assert equal (resolve-path '/abs/x.md' '/cwd') '/abs/x.md'
assert equal (resolve-path 'rel/x.md' '/cwd') '/cwd/rel/x.md'
assert equal (resolve-path 'rel/x.md' '') 'rel/x.md'

# --- decide -----------------------------------------------------------------

# New files never need a Read.
assert equal (decide false false false 0 0) 'allow'

# Existing file with no state part in the session: the harness will reject.
assert equal (decide true false false 0 0) 'deny_not_read'

# Existing file with a prior Read/Write/Edit and unchanged mtime: allow.
assert equal (decide true true false 1000 1) 'allow'

# File mtime (seconds) newer than the state part completion (ms): stale.
assert equal (decide true true false 1000 2) 'deny_stale'
assert equal (decide true true false 1000 1) 'allow'

# Unknown completion time (older rows without state.time.end): fail-open.
assert equal (decide true true false 0 99) 'allow'

# Truncated Read: the harness rejects it too.
assert equal (decide true true true 1000 1) 'deny_partial'

# --- message ----------------------------------------------------------------

let not_read: string = (message 'deny_not_read' '/x/y.md' 'Edit')
assert ($not_read | str contains '/x/y.md')
assert ($not_read | str contains 'Edit')
assert ($not_read | str contains 'File has not been read yet')
assert ($not_read | str contains 'resend')

let stale: string = (message 'deny_stale' '/x/y.md' 'Write')
assert ($stale | str contains 'File has been modified since read')

let partial: string = (message 'deny_partial' '/x/y.md' 'Edit')
assert ($partial | str contains 'full Read')

assert equal (message 'allow' '/x/y.md' 'Edit') ''

print 'write-guard: ALL GREEN'
