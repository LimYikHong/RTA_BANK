-- V10: Add key_purpose column to merchant_key table to distinguish
-- INBOUND keys (merchant encrypts → bank decrypts) from
-- OUTBOUND keys (bank encrypts → merchant decrypts).
ALTER TABLE merchant_key ADD COLUMN key_purpose VARCHAR(20) DEFAULT 'INBOUND' AFTER keystore_alias;

-- Mark all existing keys as INBOUND (original behavior)
UPDATE merchant_key SET key_purpose = 'INBOUND' WHERE key_purpose IS NULL;
