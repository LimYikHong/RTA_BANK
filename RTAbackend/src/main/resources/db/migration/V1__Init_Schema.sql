-- User Table (Consolidated rta_bank_user and rta_role management)
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
    
    -- Merchant/Profile fields
    merchant_id VARCHAR(50),
    company VARCHAR(100),
    address VARCHAR(255),
    contact VARCHAR(100),
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

-- User Role Table (Many-to-Many with audit)
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

-- Role Permission Table (Many-to-Many with audit)
CREATE TABLE rta_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    assigned_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (role_id) REFERENCES rta_role(id),
    FOREIGN KEY (permission_id) REFERENCES rta_permission(id),
    UNIQUE KEY uk_role_permission (role_id, permission_id)
);

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
    account_id BIGINT,
    merchant_name VARCHAR(100) NOT NULL,
    merchant_bank VARCHAR(100),
    merchant_code VARCHAR(50),
    merchant_phone_num VARCHAR(20),
    merchant_address VARCHAR(255),
    merchant_contact_person VARCHAR(100),
    merchant_status VARCHAR(20),
    profile_photo_url VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    deleted_at DATETIME,
    FOREIGN KEY (account_id) REFERENCES merchant_bank_acc(account_id)
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

-- RTA Batch Table
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

-- RTA Incoming Batch File Table
CREATE TABLE rta_incoming_batch_file (
    batch_file_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    batch_id BIGINT NOT NULL,
    original_filename VARCHAR(255),
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
    transaction_record_remark TEXT,
    deleted_at DATETIME,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id)
);

-- RTA Transaction Table
CREATE TABLE rta_transaction (
    transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(50) NOT NULL,
    batch_file_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    batch_seq INT,
    merchant_batch_seq INT,
    bxn_ref VARCHAR(100),
    masked_pan VARCHAR(100),
    expiry_date VARCHAR(10),
    merchant_customer VARCHAR(100),
    merchant_billing_ref VARCHAR(100),
    transaction_description VARCHAR(255),
    recurring_indicator VARCHAR(10),
    amount_cents BIGINT,
    currency VARCHAR(10),
    authorization_datetime DATETIME,
    actual_billing_date DATE,
    status VARCHAR(20),
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchant_info(merchant_id),
    FOREIGN KEY (batch_file_id) REFERENCES rta_incoming_batch_file(batch_file_id),
    FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id)
);
