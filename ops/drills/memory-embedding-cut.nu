#!/Users/lattenero/.local/bin/nu-nightly
# T7 drill 3 — cut the embedding provider (ollama) and prove the memory leg
# degrades honestly: /ready says DEGRADED with the embed check false (it does
# not claim 'ok'), the exposition keeps answering while the dependency is
# down (monitoring must not die with what it monitors), the stored-memory
# count is never invented away, and the circuit breaker opens only if the
# extractor actually ran.
#
# No Grafana needed: /ready, /memory/status and /metrics only.
#
#   nu ops/drills/memory-embedding-cut.nu --base-url http://127.0.0.1:8765
#   nu ops/drills/memory-embedding-cut.nu --base-url http://127.0.0.1:8766 --dry-run
const HERE = (path self | path dirname)
source ($HERE | path join lib.nu)

const DEFAULT_BASE = "http://127.0.0.1:8765"
const BREAKER_POLL_S = 90

def main [
    --base-url: string = $DEFAULT_BASE
    --brew-service: string = "ollama"
    --budget-seconds: int = 240
    --dry-run
]: nothing -> nothing {
    let ready_url = $"($base_url)/ready"
    let status_url = $"($base_url)/memory/status"
    let metrics_url = $"($base_url)/metrics"

    note $"before: the brain answers at ($base_url) and the embedding provider is up"
    let ready_before = (http-json $ready_url)
    require ($ready_before.status == 200) $"/ready answered ($ready_before.status) before the cut — the embed provider is already down"
    require ($ready_before.body.checks.embed.ok == true) "/ready is 200 but checks.embed.ok is false — the report contradicts itself"
    let memory_before = (http-json $status_url).body
    require ($memory_before.breaker == "Closed") $"the breaker is already ($memory_before.breaker) before the cut"
    let metrics_before = (scrape $metrics_url)
    let memories_metric = (metric-value $metrics_before "brain_memory_memories")
    let pending_metric = (metric-value $metrics_before "brain_memory_pending_turns")
    require ($memories_metric == ($memory_before.memories | into float)) $"the exposition says ($memories_metric) memories, /memory/status says ($memory_before.memories) — the projection lies"
    require ($pending_metric == ($memory_before.pendingTurns | into float)) $"the exposition says ($pending_metric) pending, /memory/status says ($memory_before.pendingTurns) — the projection lies"
    note $"before: memories=($memory_before.memories) pending=($memory_before.pendingTurns) breaker=($memory_before.breaker)"
    note "honest projection: the /metrics values equal the /memory/status values they are derived from"

    panel "Embedding breaker" "brain_memory_breaker_state{state=\"Open\"}"
    panel "Memory — stored / pending / processed / quarantined" "brain_memory_memories"
    panel "Scrape up — bot + brain" "up{job=~\"bot|agent-brain\"}"

    if $dry_run {
        note "dry run: the 'before' assertions hold; nothing was cut"
        return
    }

    note $"cut: stopping the embedding provider — brew service ($brew_service)"
    let stop = (do { ^brew services stop $brew_service } | complete)
    if $stop.exit_code != 0 {
        error make { msg: $"brew services stop ($brew_service) failed: ($stop.stderr | str trim)" }
    }

    let degraded = (wait-until 60 {||
        let probe = (http-json $ready_url)
        ($probe.status == 503) and ($probe.body.checks.embed.ok == false)
    })
    require $degraded "/ready never turned 503 with checks.embed.ok=false — the health report is not honest about the dead provider"
    let ready_down = (http-json $ready_url)
    note $"degradation observed: /ready ($ready_down.status) status=($ready_down.body.status) embed.ok=($ready_down.body.checks.embed.ok) detail=($ready_down.body.checks.embed.detail)"

    # Monitoring must survive the dependency: the exposition is a projection of
    # stored snapshots, not of the provider.
    let still_scrapable = (reachable $metrics_url)
    require $still_scrapable "the exposition stopped answering while the provider was down — the metrics leg depends on the provider (it must not)"
    let memories_down = (metric-value (scrape $metrics_url) "brain_memory_memories")
    require ($memories_down == $memories_metric) $"brain_memory_memories changed from ($memories_metric) to ($memories_down) with no write — a value was invented"
    note $"during the cut: /metrics still answers; brain_memory_memories stayed ($memories_down)"

    # The breaker is the extractor's, so it can only open if there was work.
    let opened = (wait-until $BREAKER_POLL_S {|| (http-json $status_url).body.breaker != "Closed" })
    if $memory_before.pendingTurns == 0 {
        note "breaker: pendingTurns was 0, so the extractor never ran — the breaker staying Closed is the honest absence, not a pass"
    } else {
        require $opened $"the breaker stayed Closed with ($memory_before.pendingTurns) pending turns — a failing extractor must trip it"
        let breaker_now = (http-json $status_url).body.breaker
        note $"breaker: opened as ($breaker_now) under real work"
    }

    note $"restore: starting the embedding provider again"
    let start = (do { ^brew services start $brew_service } | complete)
    if $start.exit_code != 0 {
        error make { msg: $"brew services start ($brew_service) failed: ($start.stderr | str trim)" }
    }
    let healed = (wait-until $budget_seconds {|| (http-json $ready_url).status == 200 })
    require $healed $"/ready did not return to 200 within ($budget_seconds)s after the provider came back"
    let memory_after = (http-json $status_url).body
    require ($memory_after.memories >= $memory_before.memories) $"memories went DOWN from ($memory_before.memories) to ($memory_after.memories) — nothing may be silently deleted"
    note $"recovery observed: /ready 200, breaker=($memory_after.breaker), memories=($memory_after.memories)"
    note "PASS: the embedding cut was honest on health, on the exposition and on the stored truth"
}
