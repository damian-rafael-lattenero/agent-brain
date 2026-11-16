#!/Users/lattenero/.local/bin/nu-nightly
# serve-brain.nu — the LaunchAgent's door to agent-brain.
#
# launchd owns this process, NOT the ZCode app: the brain must survive an app
# restart (the Telegram /brain /memory commands and memory_inject depend on it).
# Postgres lives in colima; when it is down the wrapper exits 75 (EX_TEMPFAIL)
# and launchd retries on its ThrottleInterval instead of crash-looping against
# a database that is not there yet.

const PG_HOST: string = "127.0.0.1"
const PG_PORT: int = 5433
const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

def main []: nothing -> nothing {
    let probe: record = (^/usr/bin/nc -z $PG_HOST $PG_PORT | complete)
    if $probe.exit_code != 0 {
        print $"agent-brain: Postgres ($PG_HOST):($PG_PORT) is down; waiting for colima + make up"
        exit 75
    }
    # JUDGE_API_KEY: the judge leg (judge_provider=openai, deepseek via
    # OpenCode Zen) reads the key from the ENVIRONMENT (Config.kt); the secret
    # lives in a local file so the launchd plist never carries it. A missing
    # or blank file leaves the variable unset — the judge is optional by
    # design, but the openai leg fails at construction without it, so the
    # service would not start: this is the one place that bridges the file.
    let key_file: path = ($nu.home-dir | path join ".zcode/agent-brain/judge-api-key")
    if ($key_file | path exists) {
        let key: string = (open --raw $key_file | str trim)
        if ($key | is-not-empty) { $env.JUDGE_API_KEY = $key }
    }
    exec $BIN serve
}
