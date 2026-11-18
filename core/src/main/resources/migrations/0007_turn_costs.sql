-- The per-turn cost ledger (audit). One row per sealed turn, projected from
-- the bus's turn.completed/turn.failed payloads: the bot's frames carry the
-- real token counts, the wiring answers the model in force, and the price is
-- resolved ONCE at projection time with the declared table (eval/prices.json)
-- and stored WITH its known flag — an unpriced model stays `cost_known =
-- false`, never a zero that lies (the eval ledger's own law).
--
-- `feedback` is the owner's 👍/👎 signal, projected from turn.feedback events:
-- the first preference data this house records.
CREATE TABLE IF NOT EXISTS turn_costs (
  id           bigserial PRIMARY KEY,
  event_id     text NOT NULL UNIQUE,
  sid          text NOT NULL DEFAULT '',
  turn         text NOT NULL DEFAULT '',
  model        text,
  tokens_in    bigint,
  tokens_out   bigint,
  tokens_total bigint,
  duration_ms  bigint,
  cost_usd     numeric(12, 6) NOT NULL DEFAULT 0,
  cost_known   boolean NOT NULL DEFAULT false,
  feedback     text,
  at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS turn_costs_at_idx ON turn_costs (at DESC);
CREATE INDEX IF NOT EXISTS turn_costs_sid_idx ON turn_costs (sid, turn);
