-- V007: versioned project site-key rotation metadata without retaining old keys.
ALTER TABLE project
    ADD COLUMN site_key_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN site_key_rotated_at TIMESTAMPTZ,
    ADD COLUMN site_key_rotated_by UUID REFERENCES admin_user(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_project_site_key_version CHECK (site_key_version >= 1);

UPDATE project
SET site_key_rotated_at = created_at
WHERE site_key_rotated_at IS NULL;

ALTER TABLE project
    ALTER COLUMN site_key_rotated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN site_key_rotated_at SET NOT NULL;
