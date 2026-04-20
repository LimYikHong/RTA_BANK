-- Tracks system-level RSA key requests from consumer/auth third party
CREATE TABLE system_rsa_key_request (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    requested_by VARCHAR(50)  NOT NULL COMMENT 'userId of the SUPER_ADMIN who requested',
    public_key_pem TEXT       NOT NULL COMMENT 'RSA public key received from consumer',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, EXPIRED',
    requested_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   DATETIME    NOT NULL,
    ip_address   VARCHAR(50) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
