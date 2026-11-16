# Memory extraction model and schema

Finished turns are distilled into memories by a local chat model — qwen2.5:1.5b
by default, with glm-4.5-flash as the hosted alternative — prompted for one
JSON object with typed groups: facts, decisions, preferences and a summary.
The schema is enforced, not suggested: an item outside the groups, a missing
summary or a shape the parser cannot read sends the whole extraction to
quarantine with its raw text. A turn with nothing durable comes back empty,
and caps bound how many items one turn may yield, so a chatty transcript
cannot flood the store. Transport failures (the provider is down) are retried;
invalid output is not, because retrying a confused model only multiplies the
confusion.
