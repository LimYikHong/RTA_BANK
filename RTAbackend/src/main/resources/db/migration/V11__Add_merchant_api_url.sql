-- Add merchant-specific API endpoint configuration for sending return batch + report
ALTER TABLE merchant_info
    ADD COLUMN api_base_url VARCHAR(500) NULL COMMENT 'Merchant system base URL, e.g. https://merchant-host:8881',
    ADD COLUMN batch_return_path VARCHAR(255) NULL DEFAULT '/api/internal/batch-return' COMMENT 'Path for receiving return batch files',
    ADD COLUMN report_path VARCHAR(255) NULL DEFAULT '/api/internal/report' COMMENT 'Path for receiving report summaries',
    ADD COLUMN api_key VARCHAR(255) NULL COMMENT 'API key for authenticating with merchant system';
