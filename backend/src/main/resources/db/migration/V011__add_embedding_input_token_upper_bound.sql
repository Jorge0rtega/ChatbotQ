-- V011: conservative UTF-8-byte upper bound reserved for each embedding input.
-- Every token consumes at least one UTF-8 byte, so this deterministic bound never under-reserves.

ALTER TABLE knowledge_entry
    ADD COLUMN embedding_input_token_upper_bound INTEGER;

UPDATE knowledge_entry
SET embedding_input_token_upper_bound = OCTET_LENGTH(question);

ALTER TABLE knowledge_entry
    ALTER COLUMN embedding_input_token_upper_bound SET NOT NULL,
    ADD CONSTRAINT ck_knowledge_embedding_input_token_upper_bound_positive
        CHECK (embedding_input_token_upper_bound > 0);
