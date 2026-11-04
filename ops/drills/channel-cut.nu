#!/Users/lattenero/.local/bin/nu-nightly
# T7 drill 1 — cut the daemon that owns the channel, and prove the degradation
# is honest: the scrape is REFUSED while it is down (not served from a cache),
# the restarted process is visibly NEW (its counters reset), and the channel
# gauge still equals the episode machine's persisted truth.
#
# No Grafana needed: every assertion is /metrics. The panel lines printed at
# the end are the same facts as PromQL.
#
#   nu ops/drills/channel-cut.nu --label com.zcode.bot-daemon        # live
#   nu ops/drills/channel-cut.nu --pid 1234                          # CI (supervised pid)
#   nu ops/drills/channel-cut.nu --label com.zcode.bot-daemon --dry-run
const HERE = (path self | path dirname)
source ($HERE | path join lib.nu)

const DEFAULT_METRICS = "http://127.0.0.1:9102/metrics"
const DEFAULT_STATE = "~/.zcode/bot-kotlin/state/zconn.json"
const PUMP_WARMUP_S = 30

def main [
    --metrics-url: string = $DEFAULT_METRICS
    --label: string = ""
    --pid: int = 0
    --domain: string = "gui"
    --budget-seconds: int = 90
    --dry-run
]: nothing -> nothing {
    let has_label = (not ($label | is-empty))
    let has_pid = ($pid > 0)
    if $has_label == $has_pid {
        error make { msg: "give exactly one of --label (launchd job) or --pid (bare process)" }
    }

    note $"before: the channel pump is running at ($metrics_url)"
    let before_body = (scrape $metrics_url)
    let cycles_before = (metric-value $before_body "bot_poller_cycles_total")
    require ($cycles_before > 0) $"bot_poller_cycles_total is ($cycles_before) — the pump never ran, nothing to cut"
    let warmed = (wait-until $PUMP_WARMUP_S {|| (metric-value (scrape $metrics_url) "bot_poller_cycles_total") > 5 })
    require $warmed "the poller counter did not pass 5 in the warmup window — the reset would not be provable"
    let before_cycles = (metric-value (scrape $metrics_url) "bot_poller_cycles_total")
    note $"before: bot_poller_cycles_total = ($before_cycles)"

    # The channel gauge must be the episode machine's own persisted truth
    # (state/zconn.json), not a second opinion. Unreadable state is "absent"
    # for the gauge too, so the check only runs when the file parses.
    let state_path = ($DEFAULT_STATE | path expand)
    if (($state_path | path type) == "file") {
        let truth = (open $state_path | get down)
        let gauge = (metric-value $before_body "bot_zconn_down")
        let expected = (if $truth { 1.0 } else { 0.0 })
        require ($gauge == $expected) $"bot_zconn_down is ($gauge) but the persisted machine says down=($truth)"
        note $"honest projection: bot_zconn_down = ($gauge) == state/zconn.json down=($truth)"
    } else {
        note $"state file absent at ($state_path) — the gauge's own degrade is 'not down', nothing to cross-check"
    }

    panel "Channel down (zconn)" "max(bot_zconn_down)"
    panel "Scrape up — bot + brain" "up{job=~\"bot|agent-brain\"}"
    panel "Turns/memory leg" "rate(bot_brain_failures_total[5m])"

    if $dry_run {
        note "dry run: the 'before' assertions hold; nothing was cut"
        return
    }

    note "cut: killing the channel's owner"
    if $has_label {
        stop-label $label $domain
    } else {
        stop-pid $pid
    }

    let degraded = (wait-until 30 {|| not (reachable $metrics_url) })
    require $degraded "the scrape kept answering after the cut — either the cut missed or the exposition is cached"
    note "degradation observed: the scrape is REFUSED (no cached last value, no invented 'up')"

    let recovered = (wait-until $budget_seconds {|| reachable $metrics_url })
    require $recovered $"the scrape did not come back within ($budget_seconds)s — the supervisor did not restart the daemon"

    let pumping = (wait-until $budget_seconds {||
        (try { (metric-value (scrape $metrics_url) "bot_poller_cycles_total") > 0.0 } catch {|_e| false })
    })
    require $pumping "the restarted daemon answers but never completed a poller cycle — the channel is not pumping"
    let after_body = (scrape $metrics_url)
    let cycles_after = (metric-value $after_body "bot_poller_cycles_total")
    require ($cycles_after < $before_cycles) $"the counter did NOT reset: ($cycles_after) >= ($before_cycles) — the numbers are stale, not a new process"
    note $"recovery observed: the pump is back at cycle ($cycles_after), below the pre-cut ($before_cycles) — a visible reset, not a fabricated continuation"

    let zconn_after = (try { metric-value $after_body "bot_zconn_down" } catch {|_e| -1.0 })
    note $"after: bot_zconn_down = ($zconn_after) — the machine's own persisted state, re-read at scrape"
    note "PASS: channel cut was observable, honest and recovered"
}
