# Event bus contract

Every line of the bus file is one JSON object with exactly seven top-level
fields: `id`, `ts`, `kind`, `sid`, `turn`, `source` and `payload`. The `kind`
is one of `user.prompt`, `turn.started`, `tool.call`, `tool.result`,
`turn.completed` or `turn.failed`; the `source` is `bot` for phone turns and
`hook` for the Mac's own sessions. Unknown fields are ignored so producers can
grow the payload without a lockstep release.
