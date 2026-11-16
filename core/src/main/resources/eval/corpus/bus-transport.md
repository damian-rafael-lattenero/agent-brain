# Bus transports: file and Kafka

The brain consumes the bot's event bus through one of two transports, selected
by the bus_transport knob. The file transport reads a JSONL file from a
persisted byte offset, remembers the offset across restarts and tolerates a
truncated trailing line; the Kafka transport joins a consumer group and
commits its position. Either value is the only valid spelling — a typo fails
loudly at startup naming the key and its valid values, never silently idling.
Both transports hand the same seven-field events to the same ingest, which
deduplicates by event id, so switching transports or replaying a segment never
duplicates a row: delivery is at-least-once and the database is the idempotent
sink.
