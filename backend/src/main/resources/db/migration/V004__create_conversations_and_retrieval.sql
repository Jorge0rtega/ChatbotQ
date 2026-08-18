-- V004: conversaciones anónimas, mensajes y trazabilidad de recuperación RAG.

CREATE TABLE conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    user_question_count INTEGER NOT NULL DEFAULT 0,
    handoff_invitation_shown BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT ck_conversation_status
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'HANDED_OFF', 'CLOSED')),
    CONSTRAINT ck_conversation_question_count CHECK (user_question_count >= 0),
    CONSTRAINT ck_conversation_expiration CHECK (expires_at >= started_at)
);

CREATE INDEX ix_conversation_project_activity
    ON conversation (project_id, last_activity_at DESC);
CREATE INDEX ix_conversation_expiration
    ON conversation (status, expires_at) WHERE status = 'ACTIVE';

CREATE TABLE conversation_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    outcome VARCHAR(24),
    input_tokens INTEGER,
    output_tokens INTEGER,
    first_token_latency_ms INTEGER,
    total_latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversation_message_sequence UNIQUE (conversation_id, sequence_number),
    CONSTRAINT ck_conversation_message_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_conversation_message_outcome
        CHECK (outcome IS NULL OR outcome IN ('ANSWERED', 'FALLBACK', 'ERROR', 'TRANSFERRED')),
    CONSTRAINT ck_conversation_message_content CHECK (LENGTH(content) BETWEEN 1 AND 16000),
    CONSTRAINT ck_conversation_message_tokens
        CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0))
);

CREATE INDEX ix_conversation_message_order
    ON conversation_message (conversation_id, sequence_number);

CREATE TABLE retrieval_trace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    query_embedding vector(1536),
    embedding_model VARCHAR(128) NOT NULL,
    similarity_threshold NUMERIC(4, 3) NOT NULL,
    top_k INTEGER NOT NULL,
    decision VARCHAR(24) NOT NULL,
    prompt_version VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_retrieval_trace_message UNIQUE (message_id),
    CONSTRAINT ck_retrieval_trace_similarity CHECK (similarity_threshold BETWEEN 0 AND 1),
    CONSTRAINT ck_retrieval_trace_top_k CHECK (top_k BETWEEN 1 AND 20),
    CONSTRAINT ck_retrieval_trace_decision
        CHECK (decision IN ('EVIDENCE_FOUND', 'NO_EVIDENCE', 'PROVIDER_ERROR'))
);

CREATE INDEX ix_retrieval_trace_project_created
    ON retrieval_trace (project_id, created_at DESC);

CREATE TABLE retrieval_candidate (
    retrieval_trace_id UUID NOT NULL REFERENCES retrieval_trace(id) ON DELETE CASCADE,
    rank INTEGER NOT NULL,
    knowledge_entry_id UUID NOT NULL REFERENCES knowledge_entry(id) ON DELETE RESTRICT,
    cosine_distance DOUBLE PRECISION NOT NULL,
    similarity_score DOUBLE PRECISION NOT NULL,
    question_snapshot VARCHAR(2000) NOT NULL,
    answer_snapshot VARCHAR(8000) NOT NULL,
    PRIMARY KEY (retrieval_trace_id, rank),
    CONSTRAINT ck_retrieval_candidate_rank CHECK (rank BETWEEN 1 AND 20),
    CONSTRAINT ck_retrieval_candidate_distance CHECK (cosine_distance >= 0),
    CONSTRAINT ck_retrieval_candidate_similarity CHECK (similarity_score BETWEEN -1 AND 1)
);
