# ADR-002: Configuration is one immutable object, env over file over defaults

**Status:** accepted (2026-10-28)

## Context

The bot that this platform serves learned the hard way (its ADR-018) that
configuration copied at import time diverges: one module read the test
fixture while another read production, and the bug was invisible until it
was not. The same platform must also be driven as a black box by tests that
spawn it as a subprocess.

## Decision

- `Config` is an immutable data class built **once** at startup by
  `Config.load()` and passed by constructor to every component. No globals,
  no re-reading, no import-time copies.
- Resolution per knob: `AB_<NAME>` environment variable, then
  `~/.zcode/agent-brain/config.json`, then `Config.DEFAULTS`. Environment
  values are coerced to the type of the default.
- An unknown key in the file is a startup error listing the known keys. A
  typo must not degrade into a silently applied default.
- Secrets (`GLM_API_KEY`, `ZAI_API_KEY`) are environment-only; they never
  land in the file and never appear in `/health` or the CLI (`snapshot()`
  redacts).
- `AB_*` variables are the only contract black-box tests rely on; `AB_BASE`
  relocates every derived path so a test never touches the real state.

## Consequences

- Tests build their own `Config` (or copy one with changes); subprocesses get
  theirs from the environment. Both paths are exercised by the suite.
- Adding a knob means adding a default, a field and a line in the README table.
