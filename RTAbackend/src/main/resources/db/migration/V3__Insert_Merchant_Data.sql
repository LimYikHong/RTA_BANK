-- Insert merchant bank accounts
INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M123-001', 'Acme Corp', 'MYR', 'MYR', TRUE, 'system');

INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-BANK001-001', 'RTA Bank', 'MYR', 'MYR', TRUE, 'system');

INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M789-001', 'Tan Supplies Trading', 'MYR', 'MYR', TRUE, 'system');

-- Insert merchant info for existing users (M123, BANK001) + new merchant (M789)
INSERT INTO merchant_info (merchant_id, account_id, merchant_name, merchant_bank, merchant_code, merchant_phone_num, merchant_address, merchant_contact_person, merchant_status, create_by)
VALUES ('M123', (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M123-001'), 'Acme Corp', 'RTA Bank', 'M123', '555-0101', '123 Main St', 'John', 'ACTIVE', 'system');

INSERT INTO merchant_info (merchant_id, account_id, merchant_name, merchant_bank, merchant_code, merchant_phone_num, merchant_address, merchant_contact_person, merchant_status, create_by)
VALUES ('BANK001', (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-BANK001-001'), 'RTA Bank', 'RTA Bank', 'BANK001', '123-4567', 'Bank HQ', 'Admin', 'ACTIVE', 'system');

INSERT INTO merchant_info (merchant_id, account_id, merchant_name, merchant_bank, merchant_code, merchant_phone_num, merchant_address, merchant_contact_person, merchant_status, create_by)
VALUES ('M789', (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M789-001'), 'Tan Supplies Trading', 'RTA Bank', 'M789', '+60 12-345 6789', '32A, Jalan SS15/4, Subang Jaya, Selangor', 'John Tan', 'ACTIVE', 'system');

-- Insert merchant user into rta_bank_user
INSERT INTO rta_bank_user (
    username, password, email, full_name,
    merchant_id, company, address, contact,
    phone_number, status, is_enabled, created_at, created_by
) VALUES (
    'merchant1', '123456', 'john.tan@example.com', 'John Tan',
    'M789', 'Tan Supplies Trading', '32A, Jalan SS15/4, Subang Jaya, Selangor', 'John Tan',
    '+60 12-345 6789', 'ACTIVE', TRUE, NOW(), 'system'
);

-- Assign ADMIN role to merchant1
INSERT INTO rta_user_role (user_id, role_id, assigned_by)
VALUES (
    (SELECT id FROM rta_bank_user WHERE username = 'merchant1'),
    (SELECT id FROM rta_role WHERE role_name = 'ADMIN'),
    'system'
);
