INSERT INTO rta_batch (file_name, status, created_at, created_by, merchant_id)
VALUES ('batch_aug.csv', 'Processed', '2025-09-10 12:00:00', 'system', 'M456');

INSERT INTO rta_batch (file_name, status, created_at, created_by, merchant_id)
VALUES ('batch_sep.csv', 'Ready', '2025-09-20 09:00:00', 'system', 'M789');

INSERT INTO rta_user (merchant_id, name, address, phone, email, password, username, company, contact, joined_on)
VALUES ('M123', 'John Doe', '123 Main St', '555-0101', 'john@example.com', 'password123', 'merchant_user', 'Acme Corp', 'John', '2025-01-01 10:00:00');

INSERT INTO rta_bank_user_info (username, password, email, first_name, last_name, full_name, status, is_enabled, created_by, created_at)
VALUES ('bank_admin', 'Pass@123', 'admin@rtabank.com', 'Super', 'Admin', 'Super Admin', 'ACTIVE', TRUE, 'system', NOW());
