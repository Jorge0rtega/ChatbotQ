CREATE TABLE admin_refresh_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by_id UUID,

    CONSTRAINT uq_admin_refresh_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_admin_refresh_session_user
        FOREIGN KEY (user_id) REFERENCES admin_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_refresh_session_replacement
        FOREIGN KEY (replaced_by_id) REFERENCES admin_refresh_session (id) ON DELETE SET NULL,
    CONSTRAINT ck_admin_refresh_session_expiry
        CHECK (expires_at > issued_at),
    CONSTRAINT ck_admin_refresh_session_rotation
        CHECK (rotated_at IS NULL OR rotated_at >= issued_at),
    CONSTRAINT ck_admin_refresh_session_revocation
        CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE INDEX ix_admin_refresh_session_user
    ON admin_refresh_session (user_id);

CREATE INDEX ix_admin_refresh_session_family
    ON admin_refresh_session (family_id);

CREATE INDEX ix_admin_refresh_session_active_expiry
    ON admin_refresh_session (expires_at)
    WHERE rotated_at IS NULL AND revoked_at IS NULL;
