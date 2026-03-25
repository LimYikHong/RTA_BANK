-- V10: Revert batch_id on rta_incoming_batch_file to reference rta_batch(batch_id)
-- instead of rta_authorization_batch(auth_batch_id).
--
-- The batch_id in rta_incoming_batch_file is now derived from the rta_batch table.
-- When a batch job executes, it creates an RtaBatch record and assigns its batch_id
-- to the corresponding incoming file records.

-- 1. Drop the FK that points to rta_authorization_batch
ALTER TABLE rta_incoming_batch_file DROP FOREIGN KEY fk_incoming_file_auth_batch;

-- 2. Clear any existing batch_id values (they pointed to auth batch, not rta_batch)
--    and reset batchStatus to PENDING so the scheduler will re-process them
UPDATE rta_incoming_batch_file SET batch_id = NULL, batch_status = 'PENDING'
WHERE batch_id IS NOT NULL OR batch_status = 'BATCHED';

-- 3. Re-add FK to rta_batch
ALTER TABLE rta_incoming_batch_file ADD CONSTRAINT fk_incoming_batch_file_batch
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id);
