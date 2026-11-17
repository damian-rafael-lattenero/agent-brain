# Chat client timeout ownership

Every outbound HTTP call rides a client whose request timeout must match the
call's latency class. Embeddings answer in milliseconds, so the shared client
built from http_timeout_s is right for them; chat generations run for minutes,
so chat and judge hold a dedicated client built from chat_timeout_s. Sharing
one 60-second client across both classes makes the chat timeout knob dead
config: the client's cut fires first and every generation longer than a minute
dies with a transport timeout, no matter what the chat knob says. The fix is
structural, not numeric: a dedicated chat HTTP client owned by the composition
root, released with the other components, and a timing law pinning that a
client cuts a request at exactly the timeout it was created with, never at a
shared default.
