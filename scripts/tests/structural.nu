# structural.nu — the brain's script family (deploys, nightlies, watchdogs,
# LoRA runs, the sentinel): effects only, so their generic proof is parse +
# shebang resolves + no stale pre-hub import. Semantics live in each script.
# covers: /Users/lattenero/Projects/agent-brain/scripts/*.nu
# covers: /Users/lattenero/Projects/agent-brain/bootstrap.nu
use std/assert
use /Users/lattenero/Scripts/lib/structural.nu *

let files: list<string> = ((glob '/Users/lattenero/Projects/agent-brain/scripts/*.nu') | append '/Users/lattenero/Projects/agent-brain/bootstrap.nu')
assert greater ($files | length) 15 "the glob must see the family"

let found: list<Finding> = (findings $files)
assert equal $found [] ($found | to json)

print $"agent-brain scripts structural ok — ($files | length) scripts"
