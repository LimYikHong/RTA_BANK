-- Report table for storing generated merchant reports
CREATE TABLE IF NOT EXISTS rta_report (
    report_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     VARCHAR(50)  NOT NULL,
    batch_file_id   BIGINT       NULL,
    batch_id        BIGINT       NULL,
    auth_batch_id   BIGINT       NULL,
    report_name     VARCHAR(255) NOT NULL,
    report_type     VARCHAR(20)  NOT NULL DEFAULT 'SUMMARY',  -- SUMMARY, RETURN_FILE
    file_format     VARCHAR(10)  NOT NULL DEFAULT 'PDF',      -- PDF, CSV, XLSX
    storage_uri     VARCHAR(500) NULL,
    output_file_uri VARCHAR(500) NULL,                        -- merchant output file (CSV/XLSX) URI
    total_records   INT          NOT NULL DEFAULT 0,
    success_count   INT          NOT NULL DEFAULT 0,
    fail_count      INT          NOT NULL DEFAULT 0,
    approved_count  INT          NOT NULL DEFAULT 0,
    declined_count  INT          NOT NULL DEFAULT 0,
    total_amount    BIGINT       NOT NULL DEFAULT 0,          -- in cents
    digital_signature TEXT       NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'GENERATED',
    send_status     VARCHAR(30)  NULL DEFAULT 'PENDING',      -- PENDING, SENT, FAILED
    sent_at         DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(100) NULL,
    INDEX idx_report_merchant (merchant_id),
    INDEX idx_report_batch_file (batch_file_id),
    INDEX idx_report_name (report_name),
    INDEX idx_report_created (created_at),
    CONSTRAINT fk_report_batch_file FOREIGN KEY (batch_file_id) REFERENCES rta_incoming_batch_file(batch_file_id) ON DELETE SET NULL,
    CONSTRAINT fk_report_batch FOREIGN KEY (batch_id) REFERENCES rta_batch(batch_id) ON DELETE SET NULL,
    CONSTRAINT fk_report_auth_batch FOREIGN KEY (auth_batch_id) REFERENCES rta_authorization_batch(auth_batch_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
