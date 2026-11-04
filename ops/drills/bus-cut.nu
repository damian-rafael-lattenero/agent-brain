#!/Users/lattenero/.local/bin/nu-nightly
# T7 drill 2 — cut the brain (the bus tailer's host) and prove the stream's
# numbers are honest: the health and the exposition are REFUSED while it is
# down, and after the supervisor restarts it the tailer resumes from its
# persisted offset (lag drains to 0, the same lines are not re-ingested).
#
# No Grafana needed: /ready, /bus/status and /metrics only.
#
#   nu ops/drills/bus-cut.nu --label com.zcode.agent-brain --health-url http://127.0.0.1:8765
#   nu ops/drills/bus-cut.nu --label com.zcode.agent-brain-u5d \
#       --health-url http://127.0.0.1:8766 --metrics-url http://127.0.0.1:8766/metrics
#   nu ops/drills/bus-cut.nu --label ... --dry-run
const HERE = (path self | path dirname)
source ($HERE | path join lib.nu)

const DEFAULT_BASE = "http://127.0.0.1:8765"
# The tailer may re-read the one line it was mid-batch on; more is a bug.
const MAX_REINGESTED = 1

def main [
    --health-url: string = $DEFAULT_BASE
    --metrics-url: string = $"($DEFAULT_BASE)/metrics"
    --label: string = ""
    --pid: int = 0
    --domain: string = "gui"
    --budget-seconds: int = 180
    --dry-run
]: nothing -> nothing {
    let has_label = (not ($label | is-empty))
    let has_pid = ($pid > 0)
    if $has_label == $has_pid {
        error make { msg: "give exactly one of --label (launchd job) or --pid (bare process)" }
    }

    note $"before: the brain answers at ($health_url) and exposes at ($metrics_url)"
    let ready_before = (http-json $"($health_url)/ready")
    require ($ready_before.status == 200) $"/ready answered ($ready_before.status) before the cut — fix the stack first"
    let bus_before = (http-json $"($health_url)/bus/status").body
    require ($bus_before.lagBytes == 0) $"lagBytes is ($bus_before.lagBytes) before the cut — the tailer is already behind"
    let metrics_before = (scrape $metrics_url)
    let restarts_before = (metric-value $metrics_before "brain_bus_restarts_total{transport=\"file\"}")
    let heartbeat_before = (metric-value $metrics_before "brain_bus_heartbeat_age_seconds{transport=\"file\"}")
    note $"before: offset=($bus_before.offset) fileBytes=($bus_before.fileBytes) lag=($bus_before.lagBytes) duplicates=($bus_before.duplicates) inserted=($bus_before.inserted)"
    note $"before: heartbeat_age=($heartbeat_before)s restarts=($restarts_before) — the loop is beating"

    panel "Brain bus lag" "brain_bus_lag_bytes{transport=\"file\"}"
    panel "Bus liveness" "brain_bus_restarts_total{transport=\"file\"}"
    panel "Scrape up — bot + brain" "up{job=~\"bot|agent-brain\"}"
    panel "Bus stream age" "time() - brain_bus_last_event_timestamp_seconds{transport=\"file\"}"

    if $dry_run {
        note "dry run: the 'before' assertions hold; nothing was cut"
        return
    }

    note "cut: killing the brain (the tailer's host)"
    if $has_label {
        stop-label $label $domain
    } else {
        stop-pid $pid
    }

    let degraded = (wait-until 30 {|| (not (reachable $"($health_url)/ready")) and (not (reachable $metrics_url)) })
    require $degraded "the health or the exposition kept answering — either the cut missed or something caches the last value"
    note "degradation observed: /ready and /metrics are REFUSED (Prometheus records up=0 for the scrape that lands here)"

    let recovered = (wait-until $budget_seconds {|| (reachable $"($health_url)/ready") })
    require $recovered $"the brain did not come back within ($budget_seconds)s — the supervisor did not restart it"
    note "recovery observed: /ready answers again"

    let drained = (wait-until $budget_seconds {|| (http-json $"($health_url)/bus/status").body.lagBytes == 0 })
    require $drained $"the tailer did not drain to lag 0 within ($budget_seconds)s after the restart"
    let bus_after = (http-json $"($health_url)/bus/status").body
    let reingested = ($bus_after.duplicates - $bus_before.duplicates)
    let inserted = ($bus_after.inserted - $bus_before.inserted)
    require ($reingested <= $MAX_REINGESTED) $"($reingested) events were re-ingested after the restart — the tailer did not resume from its persisted offset"
    note $"after: offset=($bus_after.offset) fileBytes=($bus_after.fileBytes) lag=($bus_after.lagBytes)"
    note $"after: duplicates delta=($reingested), at most ($MAX_REINGESTED) allowed; inserted delta=($inserted) — the stream resumed, nothing was replayed"

    let metrics_after = (scrape $metrics_url)
    let lag_after = (metric-value $metrics_after "brain_bus_lag_bytes{transport=\"file\"}")
    let heartbeat_after = (metric-value $metrics_after "brain_bus_heartbeat_age_seconds{transport=\"file\"}")
    let restarts_after = (metric-value $metrics_after "brain_bus_restarts_total{transport=\"file\"}")
    require ($lag_after == 0.0) $"brain_bus_lag_bytes reads ($lag_after) after recovery"
    require ($heartbeat_after < 60.0) $"the fresh process's heartbeat is ($heartbeat_after)s old — the loop is not alive"
    note $"after: brain_bus_lag_bytes=($lag_after) heartbeat_age=($heartbeat_after)s restarts=($restarts_after) — a new process starts at 0, so the gauge is the CURRENT loop's count, not history"
    note "PASS: bus cut was observable, honest and recovered without re-ingesting the stream"
}
