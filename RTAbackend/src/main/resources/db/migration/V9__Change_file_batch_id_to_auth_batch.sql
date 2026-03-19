-- V9: Change batch_id on rta_incoming_batch_file to reference
-- rta_authorization_batch(auth_batch_id) instead of rta_batch(batch_id).
-- The Batch ID shown in Incoming Batch File is now a direct FK to Batch Maintenance.

-- 1. Drop existing FK to rta_batch
ALTER TABLE rta_incoming_batch_file DROP FOREIGN KEY fk_incoming_batch_file_batch;

-- 2. Clear any existing batch_id values (they pointed to rta_batch, not auth batch)
UPDATE rta_incoming_batch_file SET batch_id = NULL;

-- 3. Add new FK to rta_authorization_batch
ALTER TABLE rta_incoming_batch_file ADD CONSTRAINT fk_incoming_file_auth_batch
    FOREIGN KEY (batch_id) REFERENCES rta_authorization_batch(auth_batch_id);
