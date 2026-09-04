-- V008: ciclo de vida explícito y trazable para embeddings de conocimiento.
-- Los registros existentes con vector válido ya están listos; los demás requieren generación.

ALTER TABLE knowledge_entry
    ADD COLUMN embedding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN embedding_revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN embedding_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN embedding_last_attempt_at TIMESTAMPTZ,
    ADD COLUMN embedding_last_error_code VARCHAR(128),
    ADD COLUMN embedding_last_error_message VARCHAR(512);

UPDATE knowledge_entry
SET embedding_status = CASE
    WHEN embedding IS NOT NULL AND embedded_at IS NOT NULL THEN 'READY'
    ELSE 'PENDING'
END;

ALTER TABLE knowledge_entry
    ADD CONSTRAINT ck_knowledge_embedding_status
        CHECK (embedding_status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    ADD CONSTRAINT ck_knowledge_embedding_revision
        CHECK (embedding_revision >= 1),
    ADD CONSTRAINT ck_knowledge_embedding_attempt_count
        CHECK (embedding_attempt_count >= 0);
