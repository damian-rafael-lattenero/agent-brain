# Embedding circuit breaker

Calls to the embedding provider go through a circuit breaker that opens after
five consecutive failures and moves to half-open after a thirty-second reset
timeout. While open, every call fails fast with `CircuitBreakerOpenException`
without touching the provider; one successful probe in half-open closes it
again. The retry policy sits inside the breaker, so a whole exhausted retry
sequence counts as one failure.
