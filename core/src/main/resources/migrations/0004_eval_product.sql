-- 0004_eval_product: a run is a product row, not just a pass count. It records
-- what it measured (kind, axis), how it was graded (gate, ok), what it cost in
-- time and tokens (p50/p95, tokens, whether the price was known) and which
-- matrix it belongs to. Existing rows keep their defaults (a pre-0004 run was
-- a recall/embed run with a bare array of questions in `detail`).
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS matrix_id uuid;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'recall';
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS axis text NOT NULL DEFAULT 'embed';
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS gate double precision NOT NULL DEFAULT 0.9;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS ok boolean NOT NULL DEFAULT false;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS tokens_prompt bigint NOT NULL DEFAULT 0;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS tokens_completion bigint NOT NULL DEFAULT 0;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS cost_known boolean NOT NULL DEFAULT true;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS p50_ms int;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS p95_ms int;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS duration_ms int;
ALTER TABLE eval_runs ADD COLUMN IF NOT EXISTS started_at timestamptz;

-- The trend query (`WHERE suite = ? ORDER BY ts DESC`) and the matrix read
-- (`WHERE matrix_id = ?`) both go through these.
CREATE INDEX IF NOT EXISTS eval_runs_suite_ts ON eval_runs (suite, ts DESC);
CREATE INDEX IF NOT EXISTS eval_runs_matrix ON eval_runs (matrix_id) WHERE matrix_id IS NOT NULL;
