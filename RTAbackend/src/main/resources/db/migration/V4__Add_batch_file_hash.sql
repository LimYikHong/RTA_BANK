-- RTA Batch File Table for duplicate file detection
CREATE TABLE rta_batch_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    original_filename VARCHAR(255),
    stored_filename VARCHAR(255),
    file_hash VARCHAR(64) NOT NULL,
    uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50),
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    UNIQUE KEY uk_merchant_file_hash (merchant_id, file_hash)
);

-- Add unique constraint on rta_transaction to prevent duplicate transactions
-- Combination of merchant_id, customer_reference (merchant_customer), amount, and transaction_date must be unique
ALTER TABLE rta_transaction 
ADD CONSTRAINT uk_transaction_unique 
UNIQUE (merchant_id, merchant_customer, amount_cents, actual_billing_date);
