-- V015: fence import execution behind a durable database-clock lease.

ALTER TABLE knowledge_import_job
    ADD COLUMN execution_claim_token UUID,
    ADD COLUMN execution_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN execution_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_execution_error_code VARCHAR(128);

-- No worker could hold a V015 fencing token before this migration.
UPDATE knowledge_import_job
SET status = 'READY'
WHERE status = 'PROCESSING';

ALTER TABLE knowledge_import_job
    ADD CONSTRAINT ck_import_execution_attempt_count
        CHECK (execution_attempt_count BETWEEN 0 AND 5),
    ADD CONSTRAINT ck_import_execution_claim_lease
        CHECK (
            (status = 'PROCESSING'
                AND execution_claim_token IS NOT NULL
                AND execution_lease_expires_at IS NOT NULL)
            OR (status <> 'PROCESSING'
                AND execution_claim_token IS NULL
                AND execution_lease_expires_at IS NULL)
        );

CREATE INDEX ix_import_job_expired_processing
    ON knowledge_import_job (execution_lease_expires_at, id)
    WHERE status = 'PROCESSING';

ALTER TABLE knowledge_import_row
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN execution_error_code VARCHAR(128);

ALTER TABLE knowledge_import_row
    DROP CONSTRAINT ck_import_row_status,
    ADD CONSTRAINT ck_import_row_status
        CHECK (status IN ('VALID', 'INVALID', 'PROCESSING', 'IMPORTED', 'FAILED')),
    ADD CONSTRAINT ck_import_row_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_import_row_imported_execution
        CHECK (status <> 'IMPORTED' OR (knowledge_entry_id IS NOT NULL AND execution_error_code IS NULL)) NOT VALID;
