-- Merchant Bank Account Table
CREATE TABLE merchant_bank_acc (
    account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_acc_num VARCHAR(50) NOT NULL,
    merchant_acc_name VARCHAR(100) NOT NULL,
    transaction_currency VARCHAR(10) NOT NULL,
    settlement_currency VARCHAR(10) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    create_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    delete_at DATETIME
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
    create_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    last_modified_by VARCHAR(100),
    delete_at DATETIME,
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
    create_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    batch_status VARCHAR(20),
    deleted_at DATETIME,
    last_modified_by VARCHAR(100),
    last_modified_at DATETIME ON UPDATE CURRENT_TIMESTAMP
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
