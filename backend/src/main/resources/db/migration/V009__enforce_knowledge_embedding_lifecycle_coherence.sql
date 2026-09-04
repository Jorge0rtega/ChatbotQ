-- V009: cada estado del ciclo de embedding tiene una representación de vector coherente.

ALTER TABLE knowledge_entry
    ADD CONSTRAINT ck_knowledge_embedding_lifecycle_coherence
        CHECK (
            (embedding_status = 'READY' AND embedding IS NOT NULL AND embedded_at IS NOT NULL)
            OR (embedding_status IN ('PENDING', 'PROCESSING', 'FAILED')
                AND embedding IS NULL AND embedded_at IS NULL)
        );
