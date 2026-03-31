-- ============================================================
-- V1: Complete Schema (consolidated from all previous migrations)
-- ============================================================

-- Merchant Bank Account Table
CREATE TABLE merchant_bank_acc (
    account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_acc_num VARCHAR(50) NOT NULL,
    merchant_acc_name VARCHAR(100) NOT NULL,
    transaction_currency VARCHAR(10) NOT NULL,
    settlement_currency VARCHAR(10) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME
);

-- Merchant Info Table
CREATE TABLE merchant_info (
    merchant_id VARCHAR(50) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(255),
    email VARCHAR(255),
    password VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    company VARCHAR(255) NOT NULL,
    contact VARCHAR(255) NOT NULL,
    joined_on DATETIME,
    profile_photo_url VARCHAR(255),
    two_factor_secret VARCHAR(255),
    is_two_factor_enabled BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME,
    FOREIGN KEY (account_id) REFERENCES merchant_bank_acc(account_id)
);

-- User Table
CREATE TABLE rta_bank_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    full_name VARCHAR(255),
    phone_number VARCHAR(50),
    office_number VARCHAR(50),
    profile_photo VARCHAR(255),
    status VARCHAR(50) DEFAULT 'ACTIVE',
    last_login_at DATETIME,
    failed_attempts INT DEFAULT 0,
    is_enabled BOOLEAN DEFAULT TRUE,
    user_id VARCHAR(50),
    company VARCHAR(255),
    address VARCHAR(255),
    contact VARCHAR(255),
    two_factor_secret VARCHAR(255),
    is_two_factor_enabled BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME
);

-- User MFA Table
CREATE TABLE rta_bank_user_mfa (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    mfa_type VARCHAR(50) NOT NULL,
    secret_key VARCHAR(255),
    is_enabled BOOLEAN DEFAULT FALSE,
    last_verified_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES rta_bank_user(id)
);

-- Role Table
CREATE TABLE rta_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME
);

-- Permission Table
CREATE TABLE rta_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permission_name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME
);

-- User Role Table (Many-to-Many)
CREATE TABLE rta_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assigned_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(100),
    FOREIGN KEY (user_id) REFERENCES rta_bank_user(id),
    FOREIGN KEY (role_id) REFERENCES rta_role(id),
    UNIQUE KEY uk_user_role (user_id, role_id)
);

-- Role Permission Table (Many-to-Many)
CREATE TABLE rta_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    assigned_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (role_id) REFERENCES rta_role(id),
    FOREIGN KEY (permission_id) REFERENCES rta_permission(id),
    UNIQUE KEY uk_role_permission (role_id, permission_id)
);

-- Merchant Key Table
CREATE TABLE merchant_key (
    key_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    version_no INT,
    key_provider VARCHAR(50),
    keystore_alias VARCHAR(100),
    public_key_pem TEXT,
    status VARCHAR(20),
    activated_at DATETIME,
    expires_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id)
);

-- RTA File Profile Table
CREATE TABLE rta_file_profile (
    profile_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    version_no INT,
    file_type VARCHAR(20),
    encoding VARCHAR(20),
    field_delimiter VARCHAR(5),
    quote_char VARCHAR(5),
    escape_char VARCHAR(5),
    has_header BOOLEAN,
    has_footer BOOLEAN,
    line_ending VARCHAR(10),
    compression VARCHAR(20),
    date_format VARCHAR(20),
    datetime_format VARCHAR(20),
    record_layout TEXT,
    extra_rules_json TEXT,
    status VARCHAR(20),
    effective_from DATETIME,
    effective_to DATETIME,
    sample_uri VARCHAR(255),
    schema_hash VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id)
);

-- RTA Field Mapping Table
CREATE TABLE rta_field_mapping (
    mapping_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    canonical_field VARCHAR(100),
    data_type VARCHAR(20),
    required BOOLEAN,
    source_column_name VARCHAR(100),
    source_column_idx INT,
    fixed_start_pos INT,
    fixed_end_pos INT,
    transform_expr VARCHAR(255),
    default_value VARCHAR(255),
    validation_regex VARCHAR(255),
    allowed_values TEXT,
    null_values VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (profile_id) REFERENCES rta_file_profile(profile_id)
);

-- RTA Batch Table (created during scheduled batch grouping)
CREATE TABLE rta_batch (
    batch_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    total_count INT DEFAULT 0,
    total_success_count INT DEFAULT 0,
    total_fail_count INT DEFAULT 0,
    processed_by VARCHAR(100),
    file_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255),
    merchant_id VARCHAR(50) NOT NULL,
    batch_status VARCHAR(20),
    last_modified_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at DATETIME
);

-- Authorization Batch Table (groups validated transactions for authorization)
CREATE TABLE rta_authorization_batch (
    auth_batch_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_reference VARCHAR(50) NOT NULL UNIQUE,
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    total_amount_cents BIGINT DEFAULT 0,
    batch_status VARCHAR(30) NOT NULL DEFAULT 'READY_TO_SEND',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    remark TEXT
);

-- RTA Incoming Batch File Table
-- batch_id is nullable (assigned later by scheduled batch job), FK to rta_batch
CREATE TABLE rta_incoming_batch_file (
    batch_file_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    batch_id BIGINT NULL,
    original_filename VARCHAR(255),
    stored_filename VARCHAR(255),
    storage_uri VARCHAR(255),
    size_bytes BIGINT,
    total_record_count INT,
    success_count INT,
    fail_count INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    file_status VARCHAR(20),
    batch_status VARCHAR(30) NULL,
    transaction_record_remark TEXT,
    deleted_at DATETIME,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id)
);

-- RTA Transaction Table
-- batch_id nullable (assigned by scheduler), auth_batch_id nullable (assigned by batch maintenance)
CREATE TABLE rta_transaction (
    transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    batch_file_id BIGINT NOT NULL,
    batch_id BIGINT NULL,
    auth_batch_id BIGINT NULL,
    batch_seq INT,
    merchant_batch_seq INT,
    bxn_ref VARCHAR(100),
    masked_pan VARCHAR(100),
    expiry_date VARCHAR(10),
    merchant_customer VARCHAR(100),
    merchant_billing_ref VARCHAR(100),
    transaction_description VARCHAR(255),
    recurring_indicator VARCHAR(10),
    is_recurring BOOLEAN DEFAULT FALSE,
    recurring_reference VARCHAR(100),
    frequency_value INT,
    additional_data JSON,
    amount_cents BIGINT,
    currency VARCHAR(10),
    authorization_datetime DATETIME,
    actual_billing_date DATE,
    status VARCHAR(20),
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    FOREIGN KEY (batch_file_id) REFERENCES rta_incoming_batch_file(batch_file_id),
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id),
    CONSTRAINT fk_txn_auth_batch FOREIGN KEY (auth_batch_id) REFERENCES rta_authorization_batch(auth_batch_id),
    CONSTRAINT uk_transaction_unique UNIQUE (merchant_id, merchant_customer, amount_cents, actual_billing_date)
);

-- RTA Uploaded File Hash Table (duplicate file detection)
CREATE TABLE rta_uploaded_file_hash (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    original_filename VARCHAR(255),
    stored_filename VARCHAR(255),
    file_hash VARCHAR(64) NOT NULL,
    uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50),
    upload_count INT DEFAULT 1,
    validation_remark TEXT,
    created_by VARCHAR(100),
    size_bytes BIGINT,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    UNIQUE KEY uk_merchant_file_hash (merchant_id, file_hash)
);
