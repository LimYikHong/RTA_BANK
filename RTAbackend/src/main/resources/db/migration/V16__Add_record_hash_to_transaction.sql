-- V16: Add record_hash column to rta_transaction for transaction-level duplicate detection
ALTER TABLE rta_transaction ADD COLUMN record_hash VARCHAR(64) NULL;

-- Index for fast duplicate lookups
CREATE INDEX idx_transaction_record_hash ON rta_transaction (record_hash);
