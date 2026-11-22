#!/Users/lattenero/.local/bin/nu-nightly
# stack-up.nu — the END-OF-BOOT orchestrator: when Postgres answers, it
# kickstarts the one-shot agents that ran too early at login (the brain's
# index and the bot's jobs) and finishes.
#
# The launchd pattern, zero sleeps: if pg does not answer it exits 75
# (EX_TEMPFAIL) and the plist (KeepAlive SuccessfulExit=false,
# ThrottleInterval 15) relaunches it until it works. On success it exits 0
# and launchd stops relaunching.

const PG_HOST: string = "127.0.0.1"
const PG_PORT: int = 5433

def kick [label: string]: nothing -> nothing {
    let r: record = (^/bin/launchctl kickstart $"gui/501/($label)" | complete)
    print $"kick ($label): exit ($r.exit_code)"
}

def main []: nothing -> nothing {
    let probe: record = (^/usr/bin/nc -z $PG_HOST $PG_PORT | complete)
    if $probe.exit_code != 0 {
        print $"stack-up: pg ($PG_HOST):($PG_PORT) not answering; launchd retries"
        exit 75
    }
    print "stack-up: pg ready; firing the one-shots"
    kick "com.zcode.agent-brain-index"
    kick "com.zcode.bot-recorder"
    kick "com.zcode.bot-comparator"
    kick "com.zcode.bot-healer"
}
