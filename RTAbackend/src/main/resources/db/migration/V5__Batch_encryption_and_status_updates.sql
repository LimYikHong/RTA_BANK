-- V5: Add batch encryption key table and update status columns

-- Table to store per-batch AES encryption keys and encrypted file metadata
CREATE TABLE IF NOT EXISTS rta_batch_encryption_key (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT NOT NULL,
    aes_key_base64  TEXT          NOT NULL COMMENT 'Base64-encoded raw AES-256 key (for internal use / mock decryption)',
    iv_base64       VARCHAR(50)   NOT NULL COMMENT 'Base64-encoded AES-GCM IV (12 bytes)',
    encrypted_aes_key_base64 TEXT NOT NULL COMMENT 'Base64-encoded RSA-encrypted AES key (sent to third party)',
    merchant_id     VARCHAR(50)   NOT NULL,
    csv_filename    VARCHAR(255)  NOT NULL COMMENT 'Original CSV filename before encryption (batchId_datetime.csv)',
    encrypted_file_path VARCHAR(500) NULL COMMENT 'Path or URI to the encrypted file',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id),
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Update existing transaction statuses: SUCCESS -> PENDING (for records that haven't been auth-batched yet)
-- Existing FAILED records stay as FAILED
-- NOTE: Only run this for records not yet sent (auth_batch_id IS NULL)
UPDATE rta_transaction SET status = 'PENDING' WHERE status = 'SUCCESS' AND auth_batch_id IS NULL;
