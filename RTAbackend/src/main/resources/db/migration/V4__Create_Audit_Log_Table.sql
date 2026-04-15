-- V4: Create audit_log table for user activity and system activity logging

CREATE TABLE IF NOT EXISTS audit_log (
    log_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_type        VARCHAR(20)   NOT NULL COMMENT 'USER or SYSTEM',
    action          VARCHAR(100)  NOT NULL COMMENT 'e.g. LOGIN, LOGOUT, UPLOAD_FILE, CREATE_USER, CREATE_MERCHANT, EDIT_USER, DELETE_USER, EDIT_MERCHANT, DELETE_MERCHANT, INCOMING_BATCH, RUN_BATCH, DECRYPT_FILE, AUTH_SEND, REPORT_SEND',
    user_id         VARCHAR(50)   NULL     COMMENT 'The user who performed the action (NULL for system actions)',
    target_id       VARCHAR(255)  NULL     COMMENT 'The affected entity ID (created user ID, merchant ID, file name, etc.)',
    description     VARCHAR(1000) NULL     COMMENT 'Human-readable description of the activity',
    status          VARCHAR(50)   NULL     COMMENT 'SUCCESS, FAILED, PENDING, etc.',
    ip_address      VARCHAR(50)   NULL     COMMENT 'Client IP address (for user actions)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_audit_log_type (log_type),
    INDEX idx_audit_user_id (user_id),
    INDEX idx_audit_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
