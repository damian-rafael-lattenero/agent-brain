# The 12:33 message storm

At 12:33 the bot sent 118 messages for a single turn. The deduplication key used
`m['id']`, the Telegram message id, instead of `info.id`, the event id, so every
poll of the same event looked new. Zero 429 responses came back, which is why
the rate limiter never kicked in. The fix was the key; the postmortem added a
golden test that replays the same event twice and expects one send.
