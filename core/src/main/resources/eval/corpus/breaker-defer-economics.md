# Circuit breaker defer economics

An open circuit breaker is the provider's self-healing state, not progress on
a turn: every attempt lands instantly as a breaker-open rejection with no HTTP
call behind it. Charging those rejections to the turn's attempt budget drains
queued turns in seconds while the provider is merely waiting out its reset
window, and a turn that exhausts its budget on instant rejects is quarantined
with no model ever having seen it. The correct accounting is that a
breaker-open defer leaves the attempt counter untouched — the turn stays
pending and extracts normally once the breaker closes. Real outages
(connection refused, timeouts) still count and the cap still gives up
eventually, because those failures did reach the provider.
