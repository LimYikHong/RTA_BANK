-- V9: Add column for unencrypted output file URI (for viewing in portal)
ALTER TABLE rta_report ADD COLUMN raw_output_file_uri VARCHAR(500) NULL AFTER output_file_uri;
