# KAP: timeouts and retries

KAP's `retry` combinator rethrows `CancellationException` by law, and
`TimeoutCancellationException` is a subclass of it, so a plain `timeout(d)`
placed before `retry` is never retried: the timeout propagates as a
cancellation. The fallback overload `timeout(d, Kap.failed(...))` converts the
timeout into an ordinary failure first, and the retry policy then applies.
Schedules may hold state, so the retry takes a factory and builds a fresh one
per call.
