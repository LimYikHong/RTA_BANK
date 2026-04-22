-- ============================================================
-- V2: Seed Data (roles, default users, sample merchants)
-- ============================================================

-- Insert master roles
INSERT INTO rta_role (role_name, description, created_by) VALUES ('SUPER_ADMIN', 'Super Administrator with full access', 'system');
INSERT INTO rta_role (role_name, description, created_by) VALUES ('ADMIN', 'Administrator with limited access', 'system');

-- Insert default bank admin user
INSERT INTO rta_bank_user (
    username, password, email, email_address,
    user_id, company, address, contact,
    phone_number, status, is_enabled, created_at
) VALUES (
    'bank_admin', 'Pass@123', 'admin@rtabank.com', 'admin@rtabank.com',
    'BANK001', 'RTA Bank', 'Bank HQ', 'Admin',
    '123-4567', 'ACTIVE', TRUE, NOW()
);

-- Assign SUPER_ADMIN role to bank_admin
INSERT INTO rta_user_role (user_id, role_id, assigned_by)
SELECT u.id, r.id, 'system'
FROM rta_bank_user u, rta_role r
WHERE u.username = 'bank_admin' AND r.role_name = 'SUPER_ADMIN';

-- Insert sample merchant bank accounts
INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M001-001', 'Acme Corp Account', 'MYR', 'MYR', TRUE, 'system');

INSERT INTO merchant_bank_acc (merchant_acc_num, merchant_acc_name, transaction_currency, settlement_currency, is_default, create_by)
VALUES ('ACC-M002-001', 'Tan Supplies Account', 'MYR', 'MYR', TRUE, 'system');

-- Insert sample merchants
INSERT INTO merchant_info (
    merchant_id, account_id, name, address, phone, email,
    username, password, company, contact, created_at, create_by
) VALUES (
    'M001',
    (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M001-001'),
    'Acme Corp', '123 Main St', '555-0101', 'john@example.com',
    'johnuser', 'password123', 'Acme Corp', 'John',
    CURRENT_TIMESTAMP, 'system'
);

INSERT INTO merchant_info (
    merchant_id, account_id, name, address, phone, email,
    username, password, company, contact, created_at, create_by
) VALUES (
    'M002',
    (SELECT account_id FROM merchant_bank_acc WHERE merchant_acc_num = 'ACC-M002-001'),
    'Tan Supplies Trading', '32A, Jalan SS15/4, Subang Jaya, Selangor',
    '+60 12-345 6789', 'tan@example.com',
    'tanuser', 'password456', 'Tan Supplies Trading', 'John Tan',
    CURRENT_TIMESTAMP, 'system'
);

-- ============================================================
-- Permissions
-- ============================================================
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('USER_CREATE',    'Create new users',              'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('USER_EDIT',      'Edit existing users',           'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('USER_DELETE',    'Delete users',                  'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('USER_VIEW',      'View user list and profiles',   'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('MERCHANT_CREATE','Create new merchants',          'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('MERCHANT_EDIT',  'Edit existing merchants',       'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('MERCHANT_DELETE','Delete merchants',              'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('MERCHANT_VIEW',  'View merchant list and details','system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('BATCH_VIEW',     'View batch list and details',   'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('BATCH_UPLOAD',   'Upload batch files',            'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('ROLE_EDIT',      'Edit user roles',               'system');
INSERT INTO rta_permission (permission_name, description, created_by) VALUES ('PROFILE_EDIT',   'Edit own profile',              'system');

-- ============================================================
-- SUPER_ADMIN gets ALL permissions
-- ============================================================
INSERT INTO rta_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM rta_role r, rta_permission p WHERE r.role_name = 'SUPER_ADMIN';

-- ============================================================
-- ADMIN gets limited permissions (view + own profile + batch)
-- ============================================================
INSERT INTO rta_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM rta_role r, rta_permission p
WHERE r.role_name = 'ADMIN' AND p.permission_name IN (
    'USER_VIEW', 'MERCHANT_VIEW', 'BATCH_VIEW', 'BATCH_UPLOAD', 'PROFILE_EDIT'
);
