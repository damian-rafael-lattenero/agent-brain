# The worker queue: pending turns and attempts

The memory worker polls the bus table for finished turns it has not seen: a
turn is pending while it is a completed turn whose processed mark is null and
whose attempt counter is still under the cap (five by default). Each attempt
that reaches the provider and fails counts and defers the turn to a later
cycle; invalid output quarantines immediately because retrying a confused
model only multiplies the confusion. A breaker-open rejection is the
provider's own state and does not count — the turn waits with its budget
intact until the breaker closes. A turn that exhausts the cap is quarantined
with its reason and marked, so nothing sits invisible: every turn is pending,
marked, or quarantined, never lost between the states. Replaying a turn is
unmarking it and zeroing the counter.
