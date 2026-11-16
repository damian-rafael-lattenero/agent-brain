# The slow-embedding incident and the timeout band-aid

An embedding call hung during a busy session, the plain timeout fired, and the
retry never happened: KAP's retry rethrows cancellation by law, and a timeout
raises TimeoutCancellationException, which is a cancellation subclass — so the
first timeout silently defeated every retry written above it. The incident
looked like a flaky provider; the fix was shape, not knob: the
failure-converting overload turns the
timeout into an ordinary failure first, so the retry policy sees a failure it
is allowed to retry, and the retry policy itself sits inside the embedding
breaker, so one exhausted retry sequence counts as a single breaker failure.
The lesson generalizes: a timeout is not an error until something converts it.
