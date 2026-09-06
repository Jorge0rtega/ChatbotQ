-- V012: durable, account-global embedding budget reservations.
CREATE TABLE embedding_budget_reservation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_entry_id UUID NOT NULL REFERENCES knowledge_entry(id) ON DELETE RESTRICT,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    embedding_revision BIGINT NOT NULL CHECK (embedding_revision > 0),
    claim_token UUID NOT NULL,
    input_token_upper_bound INTEGER NOT NULL CHECK (input_token_upper_bound > 0),
    reserved_cost_usd NUMERIC(20,8) NOT NULL CHECK (reserved_cost_usd >= 0),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reserved_utc_day DATE NOT NULL,
    reserved_utc_month DATE NOT NULL,
    CONSTRAINT ck_embedding_budget_reservation_month_start
        CHECK (reserved_utc_month = date_trunc('month', reserved_utc_month)::date),
    CONSTRAINT uq_embedding_budget_reservation_claim
        UNIQUE (knowledge_entry_id, embedding_revision, claim_token)
);

CREATE INDEX ix_embedding_budget_reservation_utc_day
    ON embedding_budget_reservation (reserved_utc_day);
CREATE INDEX ix_embedding_budget_reservation_utc_month
    ON embedding_budget_reservation (reserved_utc_month);
