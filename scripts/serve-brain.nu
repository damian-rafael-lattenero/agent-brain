#!/Users/lattenero/.local/bin/nu-nightly
# serve-brain.nu — the LaunchAgent's door to agent-brain.
#
# launchd owns this process, NOT the ZCode app: the brain must survive an app
# restart (the Telegram /brain /memory commands and memory_inject depend on it).
# Postgres lives in colima; when it is down the wrapper exits 75 (EX_TEMPFAIL)
# and launchd retries on its ThrottleInterval instead of crash-looping against
# a database that is not there yet.

const PG_HOST = "127.0.0.1"
const PG_PORT = 5433
const BIN = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

def main []: nothing -> nothing {
    let probe = (^/usr/bin/nc -z $PG_HOST $PG_PORT | complete)
    if $probe.exit_code != 0 {
        print $"agent-brain: Postgres ($PG_HOST):($PG_PORT) is down; waiting for colima + make up"
        exit 75
    }
    exec $BIN serve
}
