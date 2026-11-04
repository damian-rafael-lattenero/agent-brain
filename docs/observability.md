# Observability — dashboards (E4) and chaos drills (T7)

Written 2026-11-03 (U5d). The system exposes two Prometheus surfaces and one
compose stack reads them; the panels and the drills are both projections of
real events, never decoration. The daemon's own inventory (E2/E3) lives in
`zcode-bot-kotlin/docs/observability.md` — this file is the brain's half plus
the wiring between the two.

- brain: `GET http://127.0.0.1:8765/metrics` (this branch) — a pure
  projection of the snapshots `/bus/status`, `/rag/stats`, `/memory/status`
  already serve as JSON. `/bus/status` and friends are untouched (compat).
- daemon: `GET http://127.0.0.1:9102/metrics` (E2, `metrics` knob) — the
  turn/bus/memory/zconn families.

## The network verdict (host ↔ container)

Both expositions bind **127.0.0.1 only** (the daemon's socket by design, the
brain's by `http_host` default), and the containers run in colima's Linux VM.
The question was whether a container can reach the host's *loopback*.

**Verdict: yes, through `host.docker.internal`, with no rebind and no
forwarder.** Measured live on colima (Virtualization.framework, aarch64,
docker 29.5.2):

```
$ docker run --rm busybox nslookup host.docker.internal
Name:    host.docker.internal
Address: 192.168.5.2                     # colima's host address (gvproxy)

$ docker run --rm curlimages/curl -s -D- -o /dev/null http://host.docker.internal:9102/metrics
HTTP/1.1 200 OK
Content-type: text/plain; version=0.0.4; charset=utf-8
Content-length: 513
```

Prometheus (in the compose network) scrapes the same two targets and both
answer `up=1` (evidence below). Nothing is bound to `0.0.0.0` on the host: the
published ports (`127.0.0.1:9090`, `127.0.0.1:3000`) are loopback on the Mac,
and the exposition itself never leaves loopback.

The honest alternatives, if a runtime ever refuses that route:

1. **Prometheus on the host** (native, scraping both 127.0.0.1 endpoints), with
   Grafana in compose pointing its datasource at `host.docker.internal:9090`.
   Costs a second process manager; the expositions stay loopback-only.
2. **A forwarder/sidecar** (`socat` on the host exposing 9102 on a private
   interface) — rejected: it turns a loopback-only surface into a network one
   and buys nothing over (1).
3. **Rebinding to 0.0.0.0** — rejected outright: it breaks the E2 contract
   ("the exposition never leaves the machine") to satisfy a tool.

On Docker Desktop the route is native; on Linux hosts a container cannot reach
the host's loopback at all, so option (1) is the answer there (or
`extra_hosts: host-gateway` plus a host-side listener bound to the bridge —
again a rebind, so option 1 is the honest one).

## Running the stack

```nu
make up            # docker compose up -d --wait + migrations (now includes prometheus + grafana)
# Grafana:  http://127.0.0.1:3000  → dashboard "Agent Brain — honest indicators (E4)"
# Prometheus: http://127.0.0.1:9090
make down          # stops the containers, keeps every volume (pgdata, promdata, grafanadata)
```

Provisioned as code, no clicking:

| Path | What it is |
|---|---|
| `ops/prometheus/prometheus.yml` | the two scrape targets (daemon 9102, brain 8765) |
| `ops/grafana/provisioning/datasources/prometheus.yml` | the Prometheus datasource, fixed `uid: prometheus` |
| `ops/grafana/provisioning/dashboards/dashboards.yml` | the dashboard provider (file-based, UI edits refused) |
| `ops/grafana/dashboards/agent-brain.json` | the dashboard: 16 panels, every one with its exact PromQL and a `Proves:` description |

Grafana is provisioned **anonymous Viewer with no login form** and the ports
are loopback-published only; no credential is written in the repo. (For a
throwaway local admin session, unset `GF_AUTH_DISABLE_LOGIN_FORM` in a compose
override and Grafana generates an admin password into its log.)

The scrape interval is 15s, timeout 5s, retention 15 days. A `docker compose
down -v` would delete the dashboards' history along with the databases — don't.

## The panels (what each one proves)

Rendered names; the panel titles are the ones in the JSON.

| Panel | PromQL | Proves |
|---|---|---|
| Scrape up — bot + brain | `up{job=~"bot\|agent-brain"}` | Prometheus can reach both loopback expositions; 0 is a failed scrape, not a stale value |
| Bus stream age | `time() - brain_bus_last_event_timestamp_seconds{transport="file"}` | the stream is flowing (seconds since the ingest's last accepted event) |
| Channel down (zconn) | `max(bot_zconn_down)` | the episode machine's persisted `state/zconn.json` at scrape; 1 = the model wire is down |
| Channel episodes | `sum(increase(bot_zconn_transitions_total{direction="down"}[15m]))`, `...{direction="up"}` | the transitions the machine actually SPOKE (the same ⚠️/✅ notices the chat got) |
| Send lanes | `bot_outbox_depth{lane="main"}`, `bot_outbox_depth{lane="firehose"}`, `bot_cooldown_active{lane="main"}` | the two send throats are not silently backing up |
| Brain bus lag | `brain_bus_lag_bytes{transport="file"}` | bytes the tailer has not consumed (fileBytes − offset): a real stream gap |
| Stream drops | `increase(brain_bus_events_duplicates_total{transport="file"}[15m])`, `..._lines_malformed_total`, `..._file_shrinks_total` | what the ingest ignored or refused, counted as real events |
| Ingest throughput | `rate(brain_bus_events_seen_total{transport="file"}[5m])`, `..._inserted_total` | the ingest is consuming; a persistent gap between seen and inserted IS the drop count |
| Turns sealed by outcome | `sum by (outcome) (increase(bot_turns_sealed_total[15m]))` | turns reach their seal (`task_complete`/`task_error`) |
| Seal latency | `sum by (outcome) (rate(bot_turn_duration_seconds_sum[15m])) / sum by (outcome) (rate(bot_turn_duration_seconds_count[15m]))`, `max by (outcome) (bot_turn_duration_seconds_max)` | the witnessed start→seal duration; mid-flight turns count on the seal but NOT on the timer |
| Turns born vs sealed | `rate(bot_turns_started_total[5m])`, `sum(rate(bot_turns_sealed_total[5m]))` | a widening gap is a turn that never closed |
| Bot↔brain leg | `rate(bot_brain_failures_total[5m])`, `rate(bot_memory_inject_failures_total[5m])`, `rate(bot_memory_injected_total[5m])` | a dead brain shows as failures, never as a silent "no memories" |
| Brain corpus | `brain_rag_documents`, `brain_rag_chunks` | the RAG corpus the database actually holds |
| Memory | `brain_memory_memories`, `brain_memory_pending_turns`, `brain_memory_processed_turns`, `brain_memory_quarantined` | memories are written and worked through; quarantined attempts are kept, not dropped |
| Embedding breaker | `brain_memory_breaker_state{state=~"Open\|HalfOpen"}` | embeddings fail hard enough to trip the breaker, and it recovers |
| Bus liveness | `brain_bus_restarts_total{transport="file"}`, `brain_bus_heartbeat_age_seconds{transport="file"}` | the tailer *loop* is alive (restarts, heartbeat), not merely the HTTP server |

Absent families are honest: `bot_turns_*` only appears after a turn was
witnessed, the bus gauges only with `event_bus` ON, `brain_*` only when that
component is wired. A panel with no data means "that surface never ran", which
is why the first panel (`up`) exists — to tell "not running" apart from "down".

## The brain's exposition (U5d)

`GET /metrics` is a pure projection: the renderer (`MetricFamily.kt` +
`MetricsInventory.kt`) takes a `MetricsSnapshot` and produces the text; the
route (`Server.kt`) gathers the same three snapshots the JSON routes answer.
Laws, each pinned by `MetricsProjectionTest` / `MetricsEndpointTest`:

- **absent is absent** — an unwired leg, a null liveness handle (`restarts`,
  `heartbeatAgeMs` when no loop runs) or an unparseable `lastTs` omits its
  series; nothing is ever rendered as an invented 0;
- **ms → plain seconds** (`0.518`, `1762215295.315`), never an exponent;
- **the breaker is one-hot** over `Closed/Open/HalfOpen` so `{state="Open"}` is
  0 while healthy (a stat panel must not confuse healthy with scrape-down); an
  unknown state is exposed as itself, not swallowed;
- **the help text names the provenance** (`BusStatus.seen`, `RagStats.chunks`,
  `MemoryStatus.pendingTurns`, …) so a panel can be judged against its event;
- **`/metrics` never depends on the embedding provider** — the drill proves it
  keeps answering while ollama is down (monitoring must not die with what it
  monitors).

Counters (`*_total`) are process-lifetime and reset on restart — Prometheus
handles the reset; the channel drill asserts the reset is *visible*.

## The chaos drills (T7)

All three are Nushell (`#!/Users/lattenero/.local/bin/nu-nightly`), typed, no
bash, no `while (true)` (a wait is a bounded counter against `--budget-seconds`).
They assert against `/metrics`, `/ready` and `/bus/status` only — **no Grafana
needed**, so they run in CI. Each prints the panel's exact PromQL at the end.

The target is always explicit: `--label <launchd-job>` (KeepAlive is the
supervisor that restarts it) or `--pid <n>` (whatever supervises it must
restart it). A drill never guesses what to kill; running it with neither is a
parser-level error.

```nu
# 1. channel: cut the daemon that owns the channel; the scrape must be REFUSED,
#    recovery must be a visibly NEW process (counters reset), and the zconn
#    gauge must equal state/zconn.json's own `down`.
nu ops/drills/channel-cut.nu --label com.zcode.bot-daemon
nu ops/drills/channel-cut.nu --label com.zcode.bot-daemon --dry-run   # asserts only

# 2. bus: cut the brain; /ready and /metrics must be refused, and after the
#    restart the tailer resumes from its persisted offset (lag 0, no re-ingest).
nu ops/drills/bus-cut.nu --label com.zcode.agent-brain
nu ops/drills/bus-cut.nu --label com.zcode.agent-brain --health-url http://127.0.0.1:8766 \
    --metrics-url http://127.0.0.1:8766/metrics          # a branch/dev instance

# 3. embedding: stop ollama; /ready must say DEGRADED (embed.ok=false) with the
#    real reason, /metrics must keep answering, memories must not be invented
#    away, and the breaker opens only if the extractor had work (pendingTurns>0).
nu ops/drills/memory-embedding-cut.nu --base-url http://127.0.0.1:8765
```

Static gate for every script (same as the rest of the ecosystem):

```nu
~/.local/bin/nu-nightly -c "nu-check --debug ops/drills/channel-cut.nu"   # → true
```

Notes that keep the drills honest:

- **`bot_zconn_down` is the model wire's state, not the fork process's.** A fork
  app that is closed publishes no `model.network.*` frames, so the machine's
  persisted `down` stays whatever it was; the drill cross-checks the gauge
  against that persisted truth rather than pretending to detect a dead app.
- The breaker in drill 3 is the *extractor's*; with `pendingTurns == 0` the
  extractor never runs, so the breaker staying `Closed` is the honest absence —
  the drill prints exactly that instead of claiming a pass.
- Prometheus sees a cut as `up=0` on the scrape that lands in the window; the
  drills assert the stronger, immediate fact (the socket refuses).

## Live evidence (2026-11-03, colima)

```
$ COMPOSE_PROJECT_NAME=agent-brain docker compose up -d --wait
 Container agent-brain-prometheus  Healthy
 Container agent-brain-grafana     Healthy
 Container agent-brain-pg          Healthy
 Container agent-brain-redpanda    Healthy

$ curl -s http://127.0.0.1:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health}'
agent-brain  http://host.docker.internal:8766/metrics  health=up
bot          http://host.docker.internal:9102/metrics  health=up

$ curl -s 'http://127.0.0.1:9090/api/v1/query?query=up'
agent-brain=1 | bot=1

$ promtool check config /etc/prometheus/prometheus.yml
 SUCCESS: /etc/prometheus/prometheus.yml is valid prometheus config file syntax

$ curl -s http://127.0.0.1:3000/api/health
{"database":"ok","version":"12.1.0",...}

$ curl -s http://127.0.0.1:3000/api/search?query=Agent%20Brain
[{"type":"dash-db","uid":"agent-brain-e4","title":"Agent Brain — honest indicators (E4)","tags":["agent-brain","e4","honest-indicators"],...}]

# a panel's value, through Grafana's own datasource proxy:
$ POST /api/ds/query {"expr":"brain_rag_documents"}
Time=..., brain_rag_documents=17460          # the live corpus, not a fixture

# and the other panel query: up{job=~"bot|agent-brain"} → 1, 1
```

The live rehearsal pointed the brain target at a **branch instance on 8766**
(the deployed brain at 8765 predates `/metrics`); everything else — bot target,
compose file, provisioning, dashboard — was the committed artifact untouched.
After the merge and `make up`/deploy, the same `docker compose up -d` answers
`up=1` for `host.docker.internal:8765` with no config change.

Drill evidence from the same session (full transcripts in the U5d report):
`channel-cut` reset `bot_poller_cycles_total` 349 → 1 after a visible refusal;
`bus-cut` refused `/ready`+`/metrics`, then resumed at offset EOF with
`duplicates` delta 0; `memory-embedding-cut` served `/ready` 503
`embed.ok=false` with `detail="ollama unreachable … Connection refused"` while
`/metrics` kept answering and `brain_memory_memories` stayed 44.
