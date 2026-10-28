# ADR-005: Kotlin core, Python only at two glue points

**Status:** accepted (2026-10-28)

## Context

The core was started in Python (aiohttp + psycopg) and stopped after a day.
The question was asked twice, with two different lenses.

**Lens 1, engineering fit alone.** For an LLM-centric service (embeddings,
RAG, prompt-driven extraction, evals) Python has the shortest path: the
reference MCP SDK, every provider SDK, tokenizers, Telethon, promptfoo
integration, and one shared test harness with the bot. TypeScript is close
for the plugin side. Go is the best pure-infrastructure daemon but the
thinnest LLM ecosystem. Kotlin is strong and typed, with the canonical Kafka
client and `Flow` backpressure, at the cost of JVM footprint, a slower
prompt-iteration loop and more hand-written glue.

**Lens 2, what the project must also be.** A system its author can defend
line by line in JVM engineering interviews, compounding with a Java 25
concurrency lab and a Kotlin ingestion kata built the same month.

## Decision

The core is Kotlin on Amazon Corretto 25: Gradle with a version catalog, ktor
(CIO) server and client, kotlinx-serialization, coroutines with JDBC on a
virtual-thread dispatcher, HikariCP + pgjdbc + `com.pgvector:pgvector`,
kafka-clients (P4), the official MCP Kotlin SDK (P5), JUnit 5 + AssertJ +
Awaitility. Warnings are errors.

Python remains exactly where it is the native language of the host:

1. the bus publisher inside the bot (standard library only, the bot's ADR-010
   is untouched);
2. the tier-5 test driver that speaks MTProto through Telethon, a
   development-only tool, never part of the product.

Go was explicitly not chosen for this project.

## Consequences

- The architecture did not change: the bus is a file, migrations are SQL,
  compose is compose. The swap cost one day of Python.
- Where Python would have offered a library, Kotlin gets a small, tested
  module. Each such module is documented in the README's Verification table.
- The service runs with `-Xmx256m` and the serial collector; it is a sidecar,
  not a cluster.
