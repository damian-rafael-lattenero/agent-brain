# Metrics and external monitoring

The brain exposes a Prometheus-format /metrics endpoint with the whole bus and
retrieval inventory: brain_bus_events_seen_total, inserted and duplicates
counters, malformed lines, file shrinks, the lag in bytes between the file and
the saved offset, the heartbeat age and the rag document and chunk counts,
alongside retrieval latency. A rising lag or a stale heartbeat means the
consumer is down, which is exactly what an external watcher should page on.
The Mac side pairs the endpoint with a healthchecks.io heartbeat and a sentinel
that polls the public API, so silence is detected from outside the machine —
the endpoint alone cannot report its own death.
