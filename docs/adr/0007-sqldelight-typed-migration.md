# ADR-007: Typed SQL via SQLDelight over the pgvector-capable dialect fork

Date: 2026-11-15 · Status: ACCEPTED (migration in flight: stages M0–M5)
Supersedes: the 2026-11-15 spike verdict "abort — inexpressible" (`docs/spike-sqldelight.md`,
kept as the historical record; the U5 fork invalidated its two walls).

## Context

The brain's SQL surface is ~64 raw JDBC statements across six stores
(`MemoryStore`, `MemoryGraphStore`, `EdgeBackfill`, `RagStore`, `PgBusIngest`,
`EvalLedger`), pinned by 23 ITs and the E2 eval matrix. The owner chose the
typed-SQL migration after the U5 fork (`~/Projects/sqldelight`, branch `damian`,
artifact `dev.lattenero.sqldelight:postgresql-dialect:2.4.1-brain1`) made the
full statement set — retrieval laws included — compile with byte-faithful SQL.

## Decision

1. **SQLDelight 2.4.1 over the fork** for every store statement that the fork
   compiles (all of them, measured). The artifact is VENDORED as a maven-layout
   repo at `core/libs/maven/dev/lattenero/sqldelight/…` — CI resolves it with
   zero auth (GitHub Packages would need a cross-repo PAT; Maven Central under
   `io.github.damian-rafael-lattenero` is the M5 upgrade path).
2. **`Migrator` remains the single schema owner** (advisory lock 727001,
   `schema_migrations` ledger, `__EMBED_DIM__` rendering, HNSW/GIN DDL). The
   `.sq` CREATE TABLE blocks are typing-only and never executed. No `.sqm`, no
   `deriveSchemaFromMigrations`, schema generation never invoked.
3. **One store method = one migration unit** — a transaction never mixes the
   raw and generated brackets; store public signatures are frozen (callers
   touch zero lines).
4. **Embeddings and arrays ride text literals** (`'[1,2,3]'::vector`,
   `'{1,2}'::bigint[]`) — no `PGvector` JDBC type registration on generated
   paths, no ColumnAdapters.
5. **`Database` owns the pool exclusively.** The SQLDelight driver is a
   pass-through borrower (`BrainDb(db.pool.asJdbcDriver())`, lazily); generated
   calls ride the same Loom dispatcher via `Database.withDriver`.
   Pinned by `DriverCloseLawsTest`.
6. **Byte-identical gates**: `recall-core` must not move for a CODE change
   (the 2026-11-16 corpus-growth run moved the baseline by design: the
   fixture went 13 → 23 documents and the suite 20 → 33 cases, so the gate
   is now 33/33 · MRR 0.9848 · recall@1 0.9697 — an intentionally harder
   corpus scoring better); EXPLAIN (COSTS OFF) shapes on prod are part of
   the M3 gate.

## Fork stewardship

- Coordinates never collide with upstream (`dev.lattenero.sqldelight`).
- Sync discipline = the ZCode-fork pattern: rebase `damian` on upstream
  releases, run the dialect fixture tests, bump `2.4.1-brainN`, re-vendor.
- Drop conditions: sqldelight upstream lands a generic operator token /
  pgvector support and unknown-scalar-function tolerance. NOTE: the upstream
  Grammar-Kit-Composer repo rejects LLM-generated contributions — an upstream
  PR would be the owner's own writing.
- The three fork patches (c7c9fc89c, e49e2d7e9, 32ce6a46d) carry their own
  fixture tests (`aggregate-filter`, `pgvector-cosine`) and the composer trap
  in their commit messages (comments INSIDE rule braces only; every new
  extension alternative needs a grammar rule + TypeResolver branch; no `pin`
  on operator rules).

## Consequences

- Compile-time verification of every statement and typed params on all binds
  (`::uuid`→UUID, numeric→BigDecimal, timestamptz→OffsetDateTime, arrays→
  Array<Long>); the positional-bind arithmetic class of bugs disappears.
- The SQL text becomes single-source in `.sq` files; the Kotlin string
  constants and the positional bind helper die in M4.
- New cost: the fork dependency (re-vendor on refresh; sync on upstream
  releases). The refresh procedure lives in `settings.gradle.kts` comments.
