-- Add stored_filename column to rta_incoming_batch_file table
-- This stores the renamed filename (timestamp_originalFilename format)

ALTER TABLE rta_incoming_batch_file
ADD COLUMN stored_filename VARCHAR(255) AFTER original_filename;

-- Backfill existing records: extract stored filename from storage_uri
-- The storage_uri contains the full path, stored_filename is the last segment
UPDATE rta_incoming_batch_file 
SET stored_filename = SUBSTRING_INDEX(storage_uri, '\\', -1)
WHERE stored_filename IS NULL AND storage_uri IS NOT NULL;

-- For Unix-style paths
UPDATE rta_incoming_batch_file 
SET stored_filename = SUBSTRING_INDEX(storage_uri, '/', -1)
WHERE stored_filename IS NULL AND storage_uri IS NOT NULL AND storage_uri LIKE '%/%';
