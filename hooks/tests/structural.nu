# structural.nu — the hooks family (anti-pattern-lint, design-guard,
# nushell-typing, rag-guard, write-guard): they read a tool call on stdin and
# answer JSON, so their generic proof is parse + shebang resolves. The
# write-guard's own behaviour is proven by the repo's tests/test_write_guard.nu.
# covers: /Users/lattenero/Projects/agent-brain/hooks/*.nu
use std/assert
use /Users/lattenero/Scripts/lib/structural.nu *

let files: list<string> = (glob '/Users/lattenero/Projects/agent-brain/hooks/*.nu')
assert greater ($files | length) 3 "the glob must see the family"

let found: list<Finding> = (findings $files)
assert equal $found [] ($found | to json)

print $"agent-brain hooks structural ok — ($files | length) hooks"
