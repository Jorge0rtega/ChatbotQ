-- V013: split embedding budget reservations by outbound provider attempt and retain settlement outcome.
ALTER TABLE embedding_budget_reservation
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1 CHECK (attempt_number > 0),
    ADD COLUMN settled_at TIMESTAMPTZ,
    ADD COLUMN outcome VARCHAR(16);

ALTER TABLE embedding_budget_reservation
    DROP CONSTRAINT uq_embedding_budget_reservation_claim;

ALTER TABLE embedding_budget_reservation
    ADD CONSTRAINT uq_embedding_budget_reservation_attempt
        UNIQUE (knowledge_entry_id, embedding_revision, claim_token, attempt_number),
    ADD CONSTRAINT ck_embedding_budget_reservation_outcome
        CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'FAILURE', 'CANCELLED', 'UNKNOWN')),
    ADD CONSTRAINT ck_embedding_budget_reservation_settlement_coherence
        CHECK ((outcome IS NULL AND settled_at IS NULL) OR (outcome IS NOT NULL AND settled_at IS NOT NULL));

UPDATE embedding_budget_reservation
SET outcome = 'UNKNOWN', settled_at = reserved_at
WHERE outcome IS NULL;

CREATE INDEX ix_embedding_budget_reservation_unsettled
    ON embedding_budget_reservation (id)
    WHERE outcome IS NULL;
