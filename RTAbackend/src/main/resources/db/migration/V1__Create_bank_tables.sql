-- User Info Table
CREATE TABLE rta_bank_user_info (
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
    FOREIGN KEY (user_id) REFERENCES rta_bank_user_info(id)
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
    FOREIGN KEY (user_id) REFERENCES rta_bank_user_info(id),
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
