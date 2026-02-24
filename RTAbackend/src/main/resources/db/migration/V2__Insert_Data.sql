-- Insert master roles into rta_role table so they can be assigned to users
INSERT INTO rta_role (role_name, description, created_by) VALUES ('SUPER_ADMIN', 'Super Administrator with full access', 'system');
INSERT INTO rta_role (role_name, description, created_by) VALUES ('ADMIN', 'Administrator with limited access', 'system');
-- Insert Users into rta_bank_user table

-- Bank Admin
INSERT INTO rta_bank_user (
    username, password, email, full_name, 
    user_id, company, address, contact, 
    phone_number, status, is_enabled, created_at
) VALUES (
    'bank_admin', 'Pass@123', 'admin@rtabank.com', 'Super Admin',
    'BANK001', 'RTA Bank', 'Bank HQ', 'Admin',
    '123-4567', 'ACTIVE', TRUE, NOW()
);

-- Insert merchant info for existing users (M123, BANK001) + new merchant (M789)

-- First insert bank accounts so merchant_info can reference them
INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M001-001', 'Acme Corp Account', 'MYR', 'MYR', TRUE, 'system');

INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M002-001', 'Tan Supplies Account', 'MYR', 'MYR', TRUE, 'system');

INSERT INTO merchant_info (
    merchant_id,
    account_id,
    name,
    address,
    phone,
    email,
    username,
    password,
    company,
    contact,
    created_at,
    create_by
)
VALUES (
    'M001',
    (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M001-001'),
    'Acme Corp',
    '123 Main St',
    '555-0101',
    'john@example.com',
    'johnuser',
    'password123',
    'Acme Corp',
    'John',
    CURRENT_TIMESTAMP,
    'system'
);

INSERT INTO merchant_info (
    merchant_id,
    account_id,
    name,
    address,
    phone,
    email,
    username,
    password,
    company,
    contact,
    created_at,
    create_by
)
VALUES (
    'M002',
    (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M002-001'),
    'Tan Supplies Trading',
    '32A, Jalan SS15/4, Subang Jaya, Selangor',
    '+60 12-345 6789',
    'tan@example.com',
    'tanuser',
    'password456',
    'Tan Supplies Trading',
    'John Tan',
    CURRENT_TIMESTAMP,
    'system'
);
