-- V005: transferencia humana, importaciones CSV y consumo de proveedores.

CREATE TABLE handoff_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL DEFAULT gen_random_uuid(),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_http_status INTEGER,
    response_message TEXT,
    redirect_url TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_handoff_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_handoff_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_handoff_attempt_count CHECK (attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_handoff_http_status
        CHECK (last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599)
);

CREATE INDEX ix_handoff_project_requested
    ON handoff_request (project_id, requested_at DESC);

CREATE TABLE knowledge_import_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    created_by UUID REFERENCES admin_user(id) ON DELETE SET NULL,
    file_name VARCHAR(512) NOT NULL,
    strategy VARCHAR(24) NOT NULL DEFAULT 'UPSERT',
    status VARCHAR(24) NOT NULL DEFAULT 'VALIDATING',
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    invalid_rows INTEGER NOT NULL DEFAULT 0,
    imported_rows INTEGER NOT NULL DEFAULT 0,
    error_summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_import_strategy CHECK (strategy IN ('CREATE_ONLY', 'UPSERT')),
    CONSTRAINT ck_import_status
        CHECK (status IN ('VALIDATING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_import_counts CHECK (
        total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0 AND imported_rows >= 0
        AND valid_rows + invalid_rows <= total_rows
        AND imported_rows <= valid_rows
    )
);

CREATE INDEX ix_import_job_project_created
    ON knowledge_import_job (project_id, created_at DESC);

CREATE TABLE knowledge_import_row (
    import_job_id UUID NOT NULL REFERENCES knowledge_import_job(id) ON DELETE CASCADE,
    row_number INTEGER NOT NULL,
    external_id VARCHAR(255),
    question VARCHAR(2000),
    answer VARCHAR(8000),
    status VARCHAR(24) NOT NULL,
    errors JSONB NOT NULL DEFAULT '[]'::JSONB,
    knowledge_entry_id UUID REFERENCES knowledge_entry(id) ON DELETE SET NULL,
    PRIMARY KEY (import_job_id, row_number),
    CONSTRAINT ck_import_row_number CHECK (row_number > 0),
    CONSTRAINT ck_import_row_status CHECK (status IN ('VALID', 'INVALID', 'IMPORTED', 'FAILED'))
);

CREATE TABLE provider_usage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    provider VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms INTEGER NOT NULL,
    estimated_cost_usd NUMERIC(18, 8),
    successful BOOLEAN NOT NULL,
    error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_provider_usage_operation CHECK (operation IN ('EMBEDDING', 'CHAT_COMPLETION')),
    CONSTRAINT ck_provider_usage_tokens
        CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)),
    CONSTRAINT ck_provider_usage_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_provider_usage_cost CHECK (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)
);

CREATE INDEX ix_provider_usage_project_created
    ON provider_usage (project_id, created_at DESC);
