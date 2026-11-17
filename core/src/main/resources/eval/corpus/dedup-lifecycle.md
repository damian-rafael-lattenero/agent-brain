# Duplicate lifecycle: reinforce, then reconcile

Near-duplicate memories are not corruption; they are a lifecycle. When a new
fact lands at or above the similarity threshold, the write path absorbs it
into the oldest twin and reinforces that row instead of inserting another
copy. Later, the reconcile pass re-reads the pairs inside the related band and
stamps a verdict: same claims merge, superseding claims invalidate the old
row, distinct claims both stay. So the table accumulates rows whose content
repeats while exactly one of each pair stays retrievable, because every read
path filters on the invalidation column. Any audit query counting duplicate
pairs must carry that same invalidation filter the write path uses — counting
without it counts the designed history, not a defect.
