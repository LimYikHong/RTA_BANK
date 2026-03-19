-- V8: Make batch_id nullable in rta_incoming_batch_file and rta_transaction
-- Batch ID is now assigned only when the scheduled "run batch" executes,
-- not at upload time.

-- 1. rta_incoming_batch_file: drop FK, alter column, re-add FK
ALTER TABLE rta_incoming_batch_file DROP FOREIGN KEY rta_incoming_batch_file_ibfk_2;
ALTER TABLE rta_incoming_batch_file MODIFY COLUMN batch_id BIGINT NULL;
ALTER TABLE rta_incoming_batch_file ADD CONSTRAINT fk_incoming_batch_file_batch
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id);

-- 2. rta_transaction: drop FK, alter column, re-add FK
ALTER TABLE rta_transaction DROP FOREIGN KEY rta_transaction_ibfk_3;
ALTER TABLE rta_transaction MODIFY COLUMN batch_id BIGINT NULL;
ALTER TABLE rta_transaction ADD CONSTRAINT fk_transaction_batch
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id);
