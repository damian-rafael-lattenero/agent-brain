# Memory deduplication at insert

Before a new memory is stored, its embedding is compared by cosine similarity
against the rows already embedded with the same model — across every kind and
every session, because the measured duplicates cross both. At 0.90 or above
the row is the same memory and nothing is inserted: the existing row is
reinforced instead (salience and last-accessed bump) and its identity comes
back to the caller. The 0.90 threshold sits inside an empty gap measured on
the real corpus (no genuine pair lands between 0.8789 and 0.9123), so the
choice came from measurement, not from a round number. A replayed save is
therefore idempotent, and the store only grows when the user said something
genuinely new.
