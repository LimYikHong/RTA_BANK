-- V7: Change rta_batch.merchant_id to merchant_ids (JSON array of merchant IDs)

-- Step 1: Add new column merchant_ids as TEXT to store JSON array
ALTER TABLE rta_batch ADD COLUMN merchant_ids TEXT NULL COMMENT 'JSON array of merchant IDs, e.g. ["M001","M002"]';

-- Step 2: Migrate existing data — wrap single merchant_id into JSON array
UPDATE rta_batch SET merchant_ids = CONCAT('["', merchant_id, '"]') WHERE merchant_id IS NOT NULL;

-- Step 3: Drop the old merchant_id column
ALTER TABLE rta_batch DROP COLUMN merchant_id;

-- Step 4: Also drop FK on rta_batch_encryption_key.merchant_id → merchant_info
-- (this FK caused the "MULTIPLE" FK violation)
ALTER TABLE rta_batch_encryption_key DROP FOREIGN KEY rta_batch_encryption_key_ibfk_2;
