-- V010: each active embedding worker owns a renewable-free lease token.
-- A reclaimed revision receives a new token so a late prior worker cannot finalize it.

ALTER TABLE knowledge_entry
    ADD COLUMN embedding_processing_claim_token UUID,
    ADD COLUMN embedding_processing_lease_expires_at TIMESTAMPTZ;

-- A pre-lease worker has no durable ownership token. Requeue it safely; any late
-- pre-migration completion no longer satisfies the lifecycle state required to write.
UPDATE knowledge_entry
SET embedding_status = 'PENDING'
WHERE embedding_status = 'PROCESSING';

ALTER TABLE knowledge_entry
    ADD CONSTRAINT ck_knowledge_embedding_processing_lease
        CHECK (
            (embedding_status = 'PROCESSING'
                AND embedding_processing_claim_token IS NOT NULL
                AND embedding_processing_lease_expires_at IS NOT NULL)
            OR (embedding_status <> 'PROCESSING'
                AND embedding_processing_claim_token IS NULL
                AND embedding_processing_lease_expires_at IS NULL)
        );

CREATE INDEX ix_knowledge_embedding_expired_processing
    ON knowledge_entry (embedding_processing_lease_expires_at, id)
    WHERE embedding_status = 'PROCESSING';
