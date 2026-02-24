-- V3: Add recurring columns and additional_data JSON column to rta_transaction table

ALTER TABLE rta_transaction
ADD COLUMN is_recurring BOOLEAN DEFAULT FALSE AFTER recurring_indicator,
ADD COLUMN recurring_reference VARCHAR(100) AFTER is_recurring;

-- Note: recurring_type and frequency_value already exist in the field mappings
-- but we need to ensure they are stored in the transaction table
-- recurring_type is already mapped to recurring_indicator
-- frequency_value needs to be added

ALTER TABLE rta_transaction
ADD COLUMN frequency_value INT AFTER recurring_reference;

-- Add additional_data column to store custom fields added by merchant as JSON
ALTER TABLE rta_transaction
ADD COLUMN additional_data JSON AFTER frequency_value;
