# Migration runner

Plain SQL files shipped inside the jar, applied in filename order and recorded in
`schema_migrations`. Two services starting at the same moment must not race on
`CREATE TABLE`, so the runner serializes itself across processes with a
PostgreSQL advisory lock: every runner takes `pg_advisory_lock(727001)` before
reading the ledger and releases it after the last statement. The lock id is an
arbitrary constant; what matters is that every runner agrees on it.
