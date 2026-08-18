-- V003: base de conocimiento y embeddings OpenAI text-embedding-3-small (1536 dimensiones).

CREATE TABLE knowledge_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    external_id VARCHAR(255),
    question VARCHAR(2000) NOT NULL,
    answer VARCHAR(8000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    embedding vector(1536),
    embedding_model VARCHAR(128) NOT NULL DEFAULT 'text-embedding-3-small',
    embedding_version VARCHAR(64) NOT NULL DEFAULT '1',
    embedded_at TIMESTAMPTZ,
    created_by UUID REFERENCES admin_user(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES admin_user(id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_external_id UNIQUE (project_id, external_id),
    CONSTRAINT ck_knowledge_question_not_blank CHECK (LENGTH(TRIM(question)) > 0),
    CONSTRAINT ck_knowledge_answer_not_blank CHECK (LENGTH(TRIM(answer)) > 0),
    CONSTRAINT ck_knowledge_embedding_state CHECK (
        (embedding IS NULL AND embedded_at IS NULL)
        OR (embedding IS NOT NULL AND embedded_at IS NOT NULL)
    )
);

CREATE INDEX ix_knowledge_project_active
    ON knowledge_entry (project_id, active, updated_at DESC);

-- El índice vectorial se añadirá en una migración posterior cuando el volumen real
-- permita elegir HNSW/IVFFlat con parámetros medidos. El MVP usa búsqueda exacta.
