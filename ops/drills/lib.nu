# ops/drills/lib.nu — the typed helpers every T7 drill shares.
#
# House rules encoded here: no bash, no `while (true)` (a wait is a bounded
# counter against the caller's budget), and every assertion reads the live
# surface — /metrics, /ready, /bus/status — never Grafana, so a drill runs in
# CI as-is.

# One metric line's value, by the exact series as rendered (name + labels).
export def metric-value [body: string, series: string]: nothing -> float {
    let hit = ($body | lines | where {|line| $line | str starts-with $"($series) " })
    if ($hit | is-empty) {
        error make { msg: $"metric absent: ($series) — the surface never ran, or the name changed" }
    }
    $hit | last | split row ' ' | last | into float
}

# Whether a series is in the exposition at all: absence is a fact, not an error.
export def metric-present [body: string, series: string]: nothing -> bool {
    ($body | lines | where {|line| $line | str starts-with $"($series) " } | is-not-empty)
}

# The exposition body. A non-200 is an error that says which endpoint and
# which build the drill expects.
export def scrape [url: string]: nothing -> string {
    let response = (http get -r -m 3sec --full --allow-errors $url)
    if $response.status != 200 {
        error make {
            msg: $"scrape ($url) answered ($response.status) — this drill asserts the exposition; deploy the U5d build first"
        }
    }
    $response.body
}

# A JSON endpoint's status and parsed body, whatever the status is (the
# caller decides what a non-200 means; /ready's 503 is an answer, not a fault).
export def http-json [url: string]: nothing -> record {
    let response = (http get -r -m 3sec --full --allow-errors $url)
    { status: $response.status, body: ($response.body | from json) }
}

# An endpoint's HTTP status only.
export def http-status [url: string]: nothing -> int {
    let response = (http get -r -m 2sec --full --allow-errors $url)
    $response.status
}

# Whether a request succeeds at all: connection refused and timeout are false,
# which is exactly what "the process is down" looks like from here.
export def reachable [url: string]: nothing -> bool {
    try {
        http get -r -m 2sec --full $url | ignore
        true
    } catch {|_e|
        false
    }
}

# Bounded wait: at most budget_seconds probes, one second apart. A probe that
# throws counts as "not yet" — the thing being waited on is often the endpoint
# that is down.
export def wait-until [budget_seconds: int, probe: closure]: nothing -> bool {
    mut waited = 0
    mut ok = false
    while ($waited < $budget_seconds) and (not $ok) {
        $ok = (try { do $probe | into bool } catch {|_e| false })
        if not $ok {
            sleep 1sec
            $waited += 1
        }
    }
    $ok
}

# The drill's assertion: a false condition stops the run with its message.
export def require [condition: bool, message: string]: nothing -> nothing {
    if not $condition {
        error make { msg: $"ASSERT FAILED: ($message)" }
    }
}

# The drill's evidence line.
export def note [message: string]: nothing -> nothing {
    print $"[drill] ($message)"
}

# The panel's exact PromQL, printed so the same fact can be read in Grafana.
export def panel [panel_title: string, promql: string]: nothing -> nothing {
    print $"[panel] ($panel_title): ($promql)"
}

# Cuts a launchd job; KeepAlive is the supervisor that brings it back.
export def stop-label [label: string, domain: string]: nothing -> nothing {
    let uid = (^id -u | str trim)
    let target = $"($domain)/($uid)/($label)"
    let result = (do { ^launchctl kill SIGKILL $target } | complete)
    if $result.exit_code != 0 {
        error make { msg: $"launchctl kill ($target) failed: ($result.stderr | str trim)" }
    }
    note $"cut: launchctl kill SIGKILL ($target)"
}

# Cuts a bare pid. Whatever supervises it (if anything) owns the recovery —
# the drill never starts processes behind the supervisor's back.
export def stop-pid [pid: int]: nothing -> nothing {
    kill --signal 9 $pid
    note $"cut: SIGKILL ($pid)"
}
