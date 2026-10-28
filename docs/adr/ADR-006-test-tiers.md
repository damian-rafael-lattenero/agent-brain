# ADR-006: Five test tiers, services required in CI

**Status:** accepted (2026-10-28)

## Context

An earlier project of the same author was rejected on the feedback that its
tests did not demonstrate the system end to end. A green suite that skips the
tiers needing a database or a model proves little; a suite that mocks the
database proves less.

## Decision

| Tier | Tag | Real | Virtualized |
|---|---|---|---|
| 1 unit | none | pure code | everything else |
| 2 integration | `integration` | Postgres/pgvector, Ollama, GLM | nothing |
| 3 e2e | `e2e` | database, routes, in-process server | the embedding model (fake) |
| 4 black-box local | `bb-local` | the installed distribution as a subprocess, database, model | only the Telegram wire |
| 5 black-box real | `bb-real` | the author's Telegram account over MTProto | nothing |

Rules:

- Every phase closes with a tier-4 scenario green in CI, and a tier-5 run as
  acceptance.
- Locally an unreachable service **skips** a tier with a printed reason. With
  `AB_REQUIRE_SERVICES=1` (CI, `make bb`, `make ci`) the same condition
  **fails**. The one documented exception is GLM, which needs a paid key.
- Test classes own throwaway databases created on demand and dropped at JVM
  exit; no test depends on another test's leftovers or on execution order.
- A `@Test` method JUnit would silently ignore (non-void return) fails the
  build (`TestMethodsReturnUnitGate`).
- Black-box tests talk to the product only through its ports: HTTP, CLI
  stdout, the database, the bus file. Nothing in-process.

## Consequences

- CI installs Ollama and pulls the model on every run; that minute is the
  price of exercising the default provider for real.
- The suite is honest about what it proves, and the README's Verification
  table maps each claim to the command that checks it.
