# Memory extraction model and schema

Finished turns are distilled into memories by a chat model — deepseek-v4.1-flash
through an OpenAI-compatible relay in production, with qwen2.5:1.5b as the
local alternative — prompted for one
JSON object with typed groups: facts, decisions, preferences and a summary.
The schema is enforced, not suggested: an item outside the groups, a missing
summary or a shape the parser cannot read sends the whole extraction to
quarantine with its raw text. A turn with nothing durable comes back empty,
and caps bound how many items one turn may yield, so a chatty transcript
cannot flood the store. Transport failures (the provider is down) are retried;
invalid output is not, because retrying a confused model only multiplies the
confusion. A small local model can also degenerate into a repetition loop on
giant transcripts, filling the whole output cap with one repeated sentence:
the cap bounds the damage, the quarantine keeps the raw evidence, and a
stronger remote model does not loop where the small one does.
