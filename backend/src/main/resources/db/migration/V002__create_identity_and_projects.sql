-- V002: identidad administrativa, proyectos y configuración multi-proyecto.

CREATE TABLE admin_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PASSWORD_RESET_REQUIRED',
    is_general_admin BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_user_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'PASSWORD_RESET_REQUIRED')),
    CONSTRAINT ck_admin_user_failed_login_count
        CHECK (failed_login_count >= 0)
);

CREATE UNIQUE INDEX ux_admin_user_email_lower ON admin_user (LOWER(email));

CREATE TABLE project (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    site_key UUID NOT NULL DEFAULT gen_random_uuid(),
    conversation_inactivity_seconds INTEGER NOT NULL DEFAULT 600,
    retention_days INTEGER NOT NULL DEFAULT 90,
    history_message_limit INTEGER NOT NULL DEFAULT 6,
    history_token_limit INTEGER NOT NULL DEFAULT 4000,
    response_token_limit INTEGER NOT NULL DEFAULT 600,
    similarity_threshold NUMERIC(4, 3) NOT NULL DEFAULT 0.700,
    retrieval_top_k INTEGER NOT NULL DEFAULT 5,
    handoff_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    handoff_after_questions INTEGER NOT NULL DEFAULT 3,
    handoff_webhook_url TEXT,
    handoff_secret_reference VARCHAR(255),
    handoff_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    handoff_max_attempts INTEGER NOT NULL DEFAULT 3,
    branding JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_site_key UNIQUE (site_key),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_project_inactivity CHECK (conversation_inactivity_seconds BETWEEN 60 AND 86400),
    CONSTRAINT ck_project_retention CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_project_history_messages CHECK (history_message_limit BETWEEN 0 AND 50),
    CONSTRAINT ck_project_history_tokens CHECK (history_token_limit BETWEEN 256 AND 32000),
    CONSTRAINT ck_project_response_tokens CHECK (response_token_limit BETWEEN 64 AND 8000),
    CONSTRAINT ck_project_similarity CHECK (similarity_threshold BETWEEN 0 AND 1),
    CONSTRAINT ck_project_top_k CHECK (retrieval_top_k BETWEEN 1 AND 20),
    CONSTRAINT ck_project_handoff_questions CHECK (handoff_after_questions BETWEEN 1 AND 100),
    CONSTRAINT ck_project_handoff_timeout CHECK (handoff_timeout_ms BETWEEN 500 AND 30000),
    CONSTRAINT ck_project_handoff_attempts CHECK (handoff_max_attempts BETWEEN 1 AND 10)
);

CREATE TABLE project_allowed_origin (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    origin VARCHAR(512) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_allowed_origin UNIQUE (project_id, origin)
);

CREATE INDEX ix_project_allowed_origin_active
    ON project_allowed_origin (project_id, active);

CREATE TABLE user_project_role (
    user_id UUID NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL DEFAULT 'PROJECT_ADMIN',
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, project_id),
    CONSTRAINT ck_user_project_role CHECK (role IN ('PROJECT_ADMIN'))
);

CREATE INDEX ix_user_project_role_project ON user_project_role (project_id, user_id);
