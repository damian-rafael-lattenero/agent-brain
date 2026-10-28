# Bus tailer and truncation

The tailer reads the bus file from a persisted byte offset and hands complete
lines to the handler; a partial trailing line waits for its newline. If the
file is shorter than the saved offset, it was truncated or rotated, and the
tailer restarts from byte zero after counting a shrink. Delivery is therefore
at-least-once and the database deduplicates by event id.
