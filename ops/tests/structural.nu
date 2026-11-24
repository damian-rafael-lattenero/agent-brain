# structural.nu — the ops drills family (bus-cut, channel-cut,
# memory-embedding-cut and their shared lib): operator drills, effects at the
# edge. Generic proof: parse + shebang resolves + no stale import.
# covers: /Users/lattenero/Projects/agent-brain/ops/drills/*.nu
use std/assert
use /Users/lattenero/Scripts/lib/structural.nu *

let files: list<string> = (glob '/Users/lattenero/Projects/agent-brain/ops/drills/*.nu')
assert greater ($files | length) 3 "the glob must see the family"

let found: list<Finding> = (findings $files)
assert equal $found [] ($found | to json)

print $"agent-brain drills structural ok — ($files | length) drills"
