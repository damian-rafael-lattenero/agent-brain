# The extraction quarantine

When memory extraction goes wrong the turn is not dropped and not silently
retried forever: it lands in the quarantine table with its raw text and a
reason, so nothing the model mangled is lost. The reasons are a small
vocabulary — template-echo when the model parroted the prompt's own field
names instead of filling them, invalid when the JSON failed the schema, and
gave-up when the retries were exhausted. A provider outage is the one case
that defers rather than quarantines, because a down provider is not a bad
answer. Status reports bucket the rows by reason; a quarantine that only grows
is a model problem, not a bus problem, and it is the first place to look.
