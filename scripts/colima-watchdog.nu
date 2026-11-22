#!/Users/lattenero/.local/bin/nu-nightly
# colima-watchdog.nu — keeps the colima VM UP.
# launchd calls it at every login (RunAtLoad) and every 2 minutes
# (StartInterval): if colima is down it starts it; if up it exits 0
# untouched. The process is launchd's, not ZCode's: the VM survives app
# restarts (the AGENTS.md rule "processes that must survive ZCode" — colima
# NEVER from a ZCode session, measured 3/11/2026).

const COLIMA: string = "/opt/homebrew/bin/colima"

def main []: nothing -> nothing {
    let st: record = (run-external $COLIMA "status" | complete)
    if $st.exit_code == 0 {
        exit 0
    }
    print "colima down; starting..."
    let up: record = (run-external $COLIMA "start" | complete)
    if $up.exit_code == 0 {
        print "colima ✓"
    } else {
        print $"colima start failed: ($up.stderr | lines | last 3 | str join ' | ')"
        exit 1
    }
}
