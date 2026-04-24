ALTER TABLE rta_transaction ADD COLUMN validation_status VARCHAR(30) DEFAULT NULL;

-- Backfill existing data: transactions with status='FAILED' and no auth datetime are validation-failed
UPDATE rta_transaction SET validation_status = 'FAILED' WHERE status = 'FAILED' AND authorization_datetime IS NULL;
UPDATE rta_transaction SET validation_status = 'PASSED' WHERE validation_status IS NULL;
