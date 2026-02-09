-- Insert master roles into rta_role table so they can be assigned to users
INSERT INTO rta_role (role_name, description, created_by) VALUES ('SUPER_ADMIN', 'Super Administrator with full access', 'system');
INSERT INTO rta_role (role_name, description, created_by) VALUES ('ADMIN', 'Administrator with limited access', 'system');
-- Insert Users into rta_bank_user table
-- User 1
INSERT INTO rta_bank_user (
    username, password, email, full_name, 
    merchant_id, company, address, contact, 
    phone_number, status, is_enabled, created_at
) VALUES (
    'merchant_user', 'password123', 'john@example.com', 'John Doe',
    'M123', 'Acme Corp', '123 Main St', 'John',
    '555-0101', 'ACTIVE', TRUE, '2025-01-01 10:00:00'
);

-- Bank Admin
INSERT INTO rta_bank_user (
    username, password, email, full_name, 
    merchant_id, company, address, contact, 
    phone_number, status, is_enabled, created_at
) VALUES (
    'bank_admin', 'Pass@123', 'admin@rtabank.com', 'Super Admin',
    'BANK001', 'RTA Bank', 'Bank HQ', 'Admin',
    '123-4567', 'ACTIVE', TRUE, NOW()
);
