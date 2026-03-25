-- V6: Create authorization batch table and add auth_batch_id to transactions
-- rta_batch is used during file upload; rta_authorization_batch groups validated
-- transactions for sending to authorization.

-- Authorization Batch table
CREATE TABLE rta_authorization_batch (
    auth_batch_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_reference VARCHAR(50) NOT NULL UNIQUE,
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    total_amount_cents BIGINT DEFAULT 0,
    batch_status VARCHAR(30) NOT NULL DEFAULT 'READY_TO_SEND',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    remark TEXT
);

-- Add auth_batch_id column to rta_transaction (nullable — NULL means not yet batched)
ALTER TABLE rta_transaction
ADD COLUMN auth_batch_id BIGINT NULL AFTER batch_id,
ADD CONSTRAINT fk_txn_auth_batch FOREIGN KEY (auth_batch_id) REFERENCES rta_authorization_batch(auth_batch_id);

-- Add batch_status column to rta_incoming_batch_file if it doesn't exist
-- (file_status already exists so we add a processing-level batch_status)
ALTER TABLE rta_incoming_batch_file
ADD COLUMN batch_status VARCHAR(30) NULL AFTER file_status;
