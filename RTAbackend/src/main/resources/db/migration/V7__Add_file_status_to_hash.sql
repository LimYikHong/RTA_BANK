-- V7: Add upload_count to rta_uploaded_file_hash
-- upload_count: how many times this same file content has been uploaded (max 5)
-- The unique constraint uk_merchant_file_hash is KEPT — one row per merchant+hash.
-- Re-upload is only allowed when status = 'WRONG_FILE_FORMAT' and upload_count < 5.
-- On re-upload, the existing row is updated (count incremented).

-- Add upload_count column (default 1 for first upload)
ALTER TABLE rta_uploaded_file_hash
ADD COLUMN upload_count INT DEFAULT 1 AFTER status;

-- Backfill existing records
UPDATE rta_uploaded_file_hash SET upload_count = 1 WHERE upload_count IS NULL;
