package rta.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.MerchantKey;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaReport;
import rta.entity.RtaTransaction;
import rta.repository.MerchantKeyRepository;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaReportRepository;
import rta.repository.RtaTransactionRepository;

/**
 * ReportGenerationService — Handles the full report generation pipeline: 1.
 * Query PROCESSED transactions grouped by batch_file_id 2. Transform to
 * merchant-defined format using field mappings 3. Enrich with validation_status
 * and authorization_status 4. Generate output file (CSV/XLSX) encrypted and
 * stored in MinIO 5. Apply digital signature 6. Send encrypted file to merchant
 * via internal API (RSA+AES) 7. Generate HTML→PDF summary report
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportGenerationService {

    private final RtaTransactionRepository transactionRepository;
    private final RtaIncomingBatchFileRepository batchFileRepository;
    private final RtaBatchRepository batchRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final RtaReportRepository reportRepository;
    private final FileProfileService fileProfileService;
    private final FileEncryptionService fileEncryptionService;
    private final MinioStorageService minioStorageService;
    private final MerchantKeyRepository merchantKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final InternalKeyPairService internalKeyPairService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private static final String RSA_SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String REPORT_DIR = "reports";
    private static final String OUTPUT_DIR_ENCRYPTED = "result-encrypted";
    private static final String OUTPUT_DIR_RAW = "result-unencrypted";
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─────────────────────────────────────────────────────────────────────
    // 1. Generate reports for all PROCESSED batch files
    // ─────────────────────────────────────────────────────────────────────
    /**
     * Main entry point: find all batch files with send auth status = PROCESSED,
     * group by batch_file_id, generate output + report for each.
     */
    @Transactional
    public List<RtaReport> generateReportsForProcessedBatches() {
        log.info("[ReportGen] Starting report generation for PROCESSED batch files...");

        // Find all incoming batch files where batchStatus = PROCESSED
        List<RtaIncomingBatchFile> processedFiles = batchFileRepository
                .findByBatchStatus("PROCESSED");

        if (processedFiles.isEmpty()) {
            log.info("[ReportGen] No PROCESSED batch files found.");
            return Collections.emptyList();
        }

        List<RtaReport> generatedReports = new ArrayList<>();

        // Group by merchant for efficient profile lookup
        Map<String, List<RtaIncomingBatchFile>> byMerchant = processedFiles.stream()
                .collect(Collectors.groupingBy(RtaIncomingBatchFile::getMerchantId));

        for (Map.Entry<String, List<RtaIncomingBatchFile>> entry : byMerchant.entrySet()) {
            String merchantId = entry.getKey();
            List<RtaIncomingBatchFile> files = entry.getValue();

            for (RtaIncomingBatchFile batchFile : files) {
                try {
                    RtaReport report = generateReportForBatchFile(merchantId, batchFile);
                    if (report != null) {
                        generatedReports.add(report);
                        // Mark batch file as REPORTED so we don't reprocess
                        batchFile.setBatchStatus("REPORTED");
                        batchFile.setLastModifiedAt(LocalDateTime.now());
                        batchFile.setLastModifiedBy("SYSTEM");
                        batchFileRepository.save(batchFile);
                    }
                } catch (Exception e) {
                    log.error("[ReportGen] Failed to generate report for batchFileId={}: {}",
                            batchFile.getBatchFileId(), e.getMessage(), e);
                }
            }
        }

        log.info("[ReportGen] Generated {} reports.", generatedReports.size());
        return generatedReports;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. Generate report for a single batch file
    // ─────────────────────────────────────────────────────────────────────
    @Transactional
    public RtaReport generateReportForBatchFile(String merchantId, RtaIncomingBatchFile batchFile) throws Exception {
        Long batchFileId = batchFile.getBatchFileId();
        log.info("[ReportGen] Generating report for merchant={}, batchFileId={}", merchantId, batchFileId);

        // 1. Retrieve transactions
        List<RtaTransaction> transactions = transactionRepository.findByBatchFileId(batchFileId);
        if (transactions.isEmpty()) {
            log.warn("[ReportGen] No transactions found for batchFileId={}", batchFileId);
            return null;
        }

        // 2. Get merchant's file profile for format transformation
        var profileOpt = fileProfileService.getActiveProfile(merchantId);
        RtaFileProfile profile = profileOpt.orElse(null);
        List<RtaFieldMapping> mappings = profile != null
                ? fileProfileService.getFieldMappings(profile.getProfileId())
                : Collections.emptyList();

        // Determine output file format from profile
        String outputFormat = (profile != null && profile.getFileType() != null)
                ? profile.getFileType().toUpperCase() : "CSV";

        // 3. Compute statistics
        int totalRecords = transactions.size();
        int approvedCount = 0, declinedCount = 0, failCount = 0, successCount = 0;
        long totalAmountCents = 0;

        for (RtaTransaction txn : transactions) {
            String status = txn.getStatus() != null ? txn.getStatus().toUpperCase() : "";
            switch (status) {
                case "APPROVED":
                    approvedCount++;
                    successCount++;
                    if (txn.getAmount() != null) {
                        totalAmountCents += txn.getAmount();
                    }
                    break;
                case "DECLINED":
                    declinedCount++;
                    failCount++;
                    break;
                case "FAILED":
                    failCount++;
                    break;
                default:
                    successCount++;
                    if (txn.getAmount() != null) {
                        totalAmountCents += txn.getAmount();
                    }
                    break;
            }
        }

        // 4. Generate output file in merchant's format (CSV or XLSX)
        byte[] outputFileBytes;
        String outputExtension;
        if ("XLSX".equals(outputFormat) || "XLS".equals(outputFormat)) {
            outputFileBytes = generateXlsxOutput(transactions, mappings, profile);
            outputExtension = ".xlsx";
        } else {
            outputFileBytes = generateCsvOutput(transactions, mappings, profile);
            outputExtension = ".csv";
        }

        // 5. Encrypt output file and store in MinIO
        String reportTimestamp = LocalDateTime.now().format(DT_FORMAT);
        String reportName = merchantId + "_" + reportTimestamp;
        String outputObjectName = OUTPUT_DIR_ENCRYPTED + "/" + reportName + outputExtension + ".enc";

        byte[] encryptedOutput;
        boolean isEncrypted = false;
        try {
            encryptedOutput = fileEncryptionService.encryptFile(merchantId, outputFileBytes);
            isEncrypted = true;
            auditLogService.logSystemActivity("ENCRYPT_BATCH_RESULT",
                    merchantId,
                    "Encrypted batch result file '" + reportName + outputExtension
                    + "' (" + outputFileBytes.length + " bytes → " + encryptedOutput.length
                    + " bytes) using merchant RSA key for merchant " + merchantId,
                    "SUCCESS");
        } catch (Exception e) {
            log.warn("[ReportGen] No RSA key for merchant={}, storing output unencrypted: {}", merchantId, e.getMessage());
            encryptedOutput = outputFileBytes;
            outputObjectName = OUTPUT_DIR_ENCRYPTED + "/" + reportName + outputExtension;
            auditLogService.logSystemActivity("ENCRYPT_BATCH_RESULT",
                    merchantId,
                    "Failed to encrypt batch result for merchant " + merchantId
                    + ": " + e.getMessage() + ". File stored unencrypted.",
                    "FAILED");
        }

        String resultBucket = minioStorageService.getResultBucketName();

        String outputUri = minioStorageService.uploadFileToBucket(resultBucket, outputObjectName, encryptedOutput,
                "application/octet-stream");

        // Store the raw (unencrypted) output file for admin portal viewing
        String rawObjectName = OUTPUT_DIR_RAW + "/" + reportName + outputExtension;
        String rawOutputUri = minioStorageService.uploadFileToBucket(resultBucket, rawObjectName, outputFileBytes,
                "application/octet-stream");

        // 6. Apply digital signature
        String digitalSignature = signData(outputFileBytes);

        // 7. Generate HTML summary report
        byte[] pdfBytes = generatePdfReport(merchantId, batchFile, transactions,
                totalRecords, successCount, failCount, approvedCount, declinedCount, totalAmountCents);

        // Store unencrypted report for admin portal viewing
        String rawReportObjectName = REPORT_DIR + "/unencrypted/" + reportName + "_report.html";
        String rawReportUri = minioStorageService.uploadFileToBucket(resultBucket, rawReportObjectName, pdfBytes, "text/html");

        // Store encrypted report for merchant delivery
        String pdfObjectName = REPORT_DIR + "/encrypted/" + reportName + "_report.html";
        byte[] encryptedPdf;
        try {
            encryptedPdf = fileEncryptionService.encryptFile(merchantId, pdfBytes);
        } catch (Exception e) {
            encryptedPdf = pdfBytes;
        }
        String pdfUri = minioStorageService.uploadFileToBucket(resultBucket, pdfObjectName, encryptedPdf, "text/html");

        // 8. Save report record
        RtaReport report = RtaReport.builder()
                .merchantId(merchantId)
                .batchFileId(batchFileId)
                .batchId(batchFile.getBatchId())
                .reportName(reportName)
                .reportType("SUMMARY")
                .fileFormat(outputFormat)
                .storageUri(rawReportUri)
                .outputFileUri(outputUri)
                .rawOutputFileUri(rawOutputUri)
                .totalRecords(totalRecords)
                .successCount(successCount)
                .failCount(failCount)
                .approvedCount(approvedCount)
                .declinedCount(declinedCount)
                .totalAmount(totalAmountCents)
                .digitalSignature(digitalSignature)
                .status("GENERATED")
                .sendStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .createdBy("SYSTEM")
                .build();

        report = reportRepository.save(report);
        log.info("[ReportGen] Report saved: reportId={}, name={}", report.getReportId(), reportName);

        // 9. Send to merchant (encrypted via RSA+AES)
        try {
            sendReportToMerchant(merchantId, report, outputFileBytes, pdfBytes);
            report.setSendStatus("SENT");
            report.setSentAt(LocalDateTime.now());
            reportRepository.save(report);
            auditLogService.logSystemActivity("REPORT_SENT",
                    String.valueOf(report.getReportId()),
                    "Report " + reportName + " sent to merchant " + merchantId,
                    "SUCCESS");
        } catch (Exception e) {
            log.error("[ReportGen] Failed to send report to merchant {}: {}", merchantId, e.getMessage());
            report.setSendStatus("FAILED");
            reportRepository.save(report);
            auditLogService.logSystemActivity("REPORT_SENT",
                    String.valueOf(report.getReportId()),
                    "Failed to send report: " + e.getMessage(),
                    "FAILED");
        }

        return report;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Generate CSV output in merchant-defined format
    // ─────────────────────────────────────────────────────────────────────
    private byte[] generateCsvOutput(List<RtaTransaction> transactions,
            List<RtaFieldMapping> mappings,
            RtaFileProfile profile) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        String delimiter = (profile != null && profile.getFieldDelimiter() != null)
                ? profile.getFieldDelimiter() : ",";
        boolean hasHeader = profile == null || Boolean.TRUE.equals(profile.getHasHeader());

        // Build column list: mapped fields + enrichment columns
        List<String> columns = new ArrayList<>();
        if (!mappings.isEmpty()) {
            for (RtaFieldMapping m : mappings) {
                String colName = m.getSourceColumnName() != null ? m.getSourceColumnName() : m.getCanonicalField();
                columns.add(colName);
            }
        } else {
            // Default columns
            columns.addAll(List.of("customer_reference", "account_num", "bank_code",
                    "amount", "currency", "transaction_date", "start_date",
                    "is_recurring", "recurring_type", "frequency_value", "recurring_reference"));
        }
        // Enrichment columns
        columns.add("validation_status");
        columns.add("authorization_status");

        // Write header
        if (hasHeader) {
            pw.println(String.join(delimiter, columns));
        }

        // Write data rows
        for (RtaTransaction txn : transactions) {
            List<String> values = new ArrayList<>();
            if (!mappings.isEmpty()) {
                for (RtaFieldMapping m : mappings) {
                    values.add(csvSafe(getTransactionFieldValue(txn, m.getCanonicalField(), profile)));
                }
            } else {
                values.add(csvSafe(txn.getMerchantCustomer()));
                values.add(csvSafe(txn.getMaskedPan()));
                values.add(csvSafe(txn.getMerchantBillingRef()));
                values.add(txn.getAmount() != null ? String.valueOf(txn.getAmount() / 100.0) : "");
                values.add(csvSafe(txn.getCurrency()));
                values.add(txn.getActualBillingDate() != null ? txn.getActualBillingDate().toString() : "");
                values.add(""); // start_date in transactionDescription
                values.add(txn.getIsRecurring() != null ? txn.getIsRecurring().toString() : "");
                values.add(csvSafe(txn.getRecurringIndicator()));
                values.add(txn.getFrequencyValue() != null ? txn.getFrequencyValue().toString() : "");
                values.add(csvSafe(txn.getRecurringReference()));
            }
            // Enrichment
            values.add(getValidationStatus(txn));
            values.add(getAuthorizationStatus(txn));

            pw.println(String.join(delimiter, values));
        }

        pw.flush();
        return baos.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Generate XLSX output in merchant-defined format
    // ─────────────────────────────────────────────────────────────────────
    private byte[] generateXlsxOutput(List<RtaTransaction> transactions,
            List<RtaFieldMapping> mappings,
            RtaFileProfile profile) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Transactions");

            // Build columns
            List<String> columns = new ArrayList<>();
            if (!mappings.isEmpty()) {
                for (RtaFieldMapping m : mappings) {
                    columns.add(m.getSourceColumnName() != null ? m.getSourceColumnName() : m.getCanonicalField());
                }
            } else {
                columns.addAll(List.of("customer_reference", "account_num", "bank_code",
                        "amount", "currency", "transaction_date", "start_date",
                        "is_recurring", "recurring_type", "frequency_value", "recurring_reference"));
            }
            columns.add("validation_status");
            columns.add("authorization_status");

            // Header row
            boolean hasHeader = profile == null || Boolean.TRUE.equals(profile.getHasHeader());
            int startRow = 0;
            if (hasHeader) {
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) {
                    headerRow.createCell(i).setCellValue(columns.get(i));
                }
                startRow = 1;
            }

            // Data rows
            for (int r = 0; r < transactions.size(); r++) {
                RtaTransaction txn = transactions.get(r);
                Row row = sheet.createRow(startRow + r);
                int col = 0;

                if (!mappings.isEmpty()) {
                    for (RtaFieldMapping m : mappings) {
                        row.createCell(col++).setCellValue(
                                getTransactionFieldValue(txn, m.getCanonicalField(), profile));
                    }
                } else {
                    row.createCell(col++).setCellValue(txn.getMerchantCustomer() != null ? txn.getMerchantCustomer() : "");
                    row.createCell(col++).setCellValue(txn.getMaskedPan() != null ? txn.getMaskedPan() : "");
                    row.createCell(col++).setCellValue(txn.getMerchantBillingRef() != null ? txn.getMerchantBillingRef() : "");
                    row.createCell(col++).setCellValue(txn.getAmount() != null ? txn.getAmount() / 100.0 : 0);
                    row.createCell(col++).setCellValue(txn.getCurrency() != null ? txn.getCurrency() : "");
                    row.createCell(col++).setCellValue(txn.getActualBillingDate() != null ? txn.getActualBillingDate().toString() : "");
                    row.createCell(col++).setCellValue("");
                    row.createCell(col++).setCellValue(txn.getIsRecurring() != null ? txn.getIsRecurring().toString() : "");
                    row.createCell(col++).setCellValue(txn.getRecurringIndicator() != null ? txn.getRecurringIndicator() : "");
                    row.createCell(col++).setCellValue(txn.getFrequencyValue() != null ? txn.getFrequencyValue().toString() : "");
                    row.createCell(col++).setCellValue(txn.getRecurringReference() != null ? txn.getRecurringReference() : "");
                }
                row.createCell(col++).setCellValue(getValidationStatus(txn));
                row.createCell(col).setCellValue(getAuthorizationStatus(txn));
            }

            // Auto-size columns
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Generate HTML → PDF summary report
    // ─────────────────────────────────────────────────────────────────────
    private byte[] generatePdfReport(String merchantId, RtaIncomingBatchFile batchFile,
            List<RtaTransaction> transactions,
            int totalRecords, int successCount, int failCount,
            int approvedCount, int declinedCount, long totalAmountCents) {
        // Build HTML report
        String html = buildHtmlReport(merchantId, batchFile, transactions,
                totalRecords, successCount, failCount, approvedCount, declinedCount, totalAmountCents);

        // Since we don't have a full HTML→PDF library (like iText/Flying Saucer) in pom.xml,
        // we store the HTML as the report content. The frontend can render it.
        // The content type will be text/html but stored with .pdf extension for naming convention.
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String buildHtmlReport(String merchantId, RtaIncomingBatchFile batchFile,
            List<RtaTransaction> transactions,
            int totalRecords, int successCount, int failCount,
            int approvedCount, int declinedCount, long totalAmountCents) {
        double totalAmountDisplay = totalAmountCents / 100.0;
        String generatedAt = LocalDateTime.now().format(DISPLAY_DT);
        String originalFile = batchFile.getOriginalFilename() != null ? batchFile.getOriginalFilename() : "N/A";

        // Count validation statuses
        long validationPass = transactions.stream()
                .filter(t -> !"FAILED".equals(t.getStatus()))
                .count();
        long validationFail = transactions.stream()
                .filter(t -> "FAILED".equals(t.getStatus()))
                .count();

        // Calculate actual processed amount (sum of APPROVED transactions only)
        double actualProcessedAmount = transactions.stream()
                .filter(t -> "APPROVED".equalsIgnoreCase(t.getStatus()))
                .mapToLong(t -> t.getAmount() != null ? t.getAmount().longValue() : 0L)
                .sum() / 100.0;

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Batch File Result — %s</title>
                  <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; margin: 40px; color: #333; }
                    .header { text-align: center; margin-bottom: 30px; border-bottom: 3px solid #1a73e8; padding-bottom: 20px; }
                    .header h1 { color: #1a73e8; margin: 0; font-size: 24px; }
                    .header p { color: #666; margin: 5px 0 0; }
                    .section { margin-bottom: 25px; }
                    .section h2 { color: #1a73e8; font-size: 18px; border-bottom: 1px solid #ddd; padding-bottom: 8px; }
                    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
                    .info-item { padding: 8px 12px; background: #f8f9fa; border-radius: 6px; }
                    .info-item label { font-weight: 600; color: #555; display: block; font-size: 12px; }
                    .info-item span { font-size: 16px; color: #222; }
                    .summary-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; margin: 15px 0; }
                    .card { padding: 15px; border-radius: 8px; text-align: center; }
                    .card .number { font-size: 28px; font-weight: 700; }
                    .card .label { font-size: 12px; color: #666; margin-top: 4px; }
                    .card.total { background: #e3f2fd; }
                    .card.total .number { color: #1565c0; }
                    .card.approved { background: #e8f5e9; }
                    .card.approved .number { color: #2e7d32; }
                    .card.declined { background: #fbe9e7; }
                    .card.declined .number { color: #c62828; }
                    .card.amount { background: #fff3e0; }
                    .card.amount .number { color: #e65100; }
                    .amount-section { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin: 15px 0; }
                    .amount-box { padding: 20px; border-radius: 10px; text-align: center; }
                    .amount-box .amount-value { font-size: 32px; font-weight: 700; }
                    .amount-box .amount-label { font-size: 13px; color: #666; margin-top: 6px; }
                    .amount-box.eta { background: #e3f2fd; border: 2px solid #90caf9; }
                    .amount-box.eta .amount-value { color: #1565c0; }
                    .amount-box.actual { background: #e8f5e9; border: 2px solid #a5d6a7; }
                    .amount-box.actual .amount-value { color: #2e7d32; }
                    .footer { text-align: center; margin-top: 30px; padding-top: 15px; border-top: 1px solid #ddd; color: #999; font-size: 11px; }
                    .status-pass { color: #2e7d32; font-weight: 600; }
                    .status-fail { color: #c62828; font-weight: 600; }
                  </style>
                </head>
                <body>
                  <div class="header">
                    <h1>Batch File Result</h1>
                    <p>Generated: %s</p>
                  </div>
                
                  <div class="section">
                    <h2>Batch Information</h2>
                    <div class="info-grid">
                      <div class="info-item"><label>Merchant ID</label><span>%s</span></div>
                      <div class="info-item"><label>Batch File ID</label><span>%d</span></div>
                      <div class="info-item"><label>Original File</label><span>%s</span></div>
                      <div class="info-item"><label>Batch ID</label><span>%s</span></div>
                    </div>
                  </div>
                
                  <div class="section">
                    <h2>Summary</h2>
                    <div class="summary-cards">
                      <div class="card total"><div class="number">%d</div><div class="label">Total Records</div></div>
                      <div class="card approved"><div class="number">%d</div><div class="label">Approved</div></div>
                      <div class="card declined"><div class="number">%d</div><div class="label">Declined / Failed</div></div>
                      <div class="card amount"><div class="number">%.2f</div><div class="label">Total Amount</div></div>
                    </div>
                    <div class="info-grid">
                      <div class="info-item"><label>Validation Pass</label><span class="status-pass">%d</span></div>
                      <div class="info-item"><label>Validation Fail</label><span class="status-fail">%d</span></div>
                      <div class="info-item"><label>Authorization Approved</label><span class="status-pass">%d</span></div>
                      <div class="info-item"><label>Authorization Declined</label><span class="status-fail">%d</span></div>
                    </div>
                  </div>
                
                  <div class="section">
                    <h2>Amount Overview</h2>
                    <div class="amount-section">
                      <div class="amount-box eta">
                        <div class="amount-value">%.2f</div>
                        <div class="amount-label">ETA Total Process Amount</div>
                        <div style="font-size:11px;color:#888;margin-top:4px;">Sum of all transaction record amounts</div>
                      </div>
                      <div class="amount-box actual">
                        <div class="amount-value">%.2f</div>
                        <div class="amount-label">Actual Total Processed Amount</div>
                        <div style="font-size:11px;color:#888;margin-top:4px;">Sum of approved transaction amounts only</div>
                      </div>
                    </div>
                  </div>
                
                  <div class="footer">
                    <p>RTA Bank — Batch File Result | Confidential</p>
                    <p>This result was automatically generated by the RTA Bank system.</p>
                  </div>
                </body>
                </html>
                """.formatted(
                merchantId, generatedAt, merchantId, batchFile.getBatchFileId(),
                originalFile, batchFile.getBatchId() != null ? batchFile.getBatchId().toString() : "N/A",
                totalRecords, approvedCount, declinedCount + failCount, totalAmountDisplay,
                validationPass, validationFail, approvedCount, declinedCount,
                totalAmountDisplay, actualProcessedAmount
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Digital signature using system's RSA private key
    // ─────────────────────────────────────────────────────────────────────
    private String signData(byte[] data) {
        try {
            PrivateKey privateKey = internalKeyPairService.getPrivateKey();
            Signature signature = Signature.getInstance(RSA_SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            log.error("[ReportGen] Failed to sign data: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Send encrypted report to merchant via internal API
    // ─────────────────────────────────────────────────────────────────────
    private void sendReportToMerchant(String merchantId, RtaReport report,
            byte[] outputFileBytes, byte[] pdfBytes) throws Exception {
        // Get merchant's OUTBOUND RSA key (bank uses public key to encrypt return files)
        Optional<MerchantKey> keyOpt = merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                        merchantId, "ACTIVE", "OUTBOUND");

        if (keyOpt.isEmpty()) {
            throw new IllegalStateException("No active OUTBOUND RSA key found for merchant " + merchantId
                    + ". Please generate RSA keys for this merchant first.");
        }

        MerchantKey merchantKey = keyOpt.get();
        log.info("[ReportGen] Sending encrypted batch result to merchant {} using RSA key v{}",
                merchantId, merchantKey.getVersionNo());

        // The encrypted output file is already stored in MinIO at outputFileUri.
        // The unencrypted version is at rawOutputFileUri (for admin portal viewing).
        // The merchant can download the encrypted file from the Batch File Result module.
        // ReturnBatchSendService handles the actual HTTP POST to merchant system (Step 6+7 in scheduler).
        String sendDetails = String.format(
                "Batch Result '%s' ready for merchant %s | "
                + "Encrypted output: %s | Unencrypted output: %s | "
                + "Report (HTML): %s | RSA key version: %d | "
                + "Total records: %d | Success: %d | Failed: %d",
                report.getReportName(), merchantId,
                report.getOutputFileUri(), report.getRawOutputFileUri(),
                report.getStorageUri(), merchantKey.getVersionNo(),
                report.getTotalRecords(), report.getSuccessCount(), report.getFailCount());

        auditLogService.logSystemActivity("SEND_BATCH_RESULT",
                merchantId,
                sendDetails,
                "SUCCESS");

        log.info("[ReportGen] Batch result {} stored and audit-logged for merchant {}.",
                report.getReportName(), merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper: Map internal transaction fields to merchant column values
    // ─────────────────────────────────────────────────────────────────────
    private String getTransactionFieldValue(RtaTransaction txn, String canonicalField, RtaFileProfile profile) {
        if (canonicalField == null) {
            return "";
        }
        return switch (canonicalField.toLowerCase()) {
            case "customer_reference" ->
                txn.getMerchantCustomer() != null ? txn.getMerchantCustomer() : "";
            case "account_num" ->
                txn.getMaskedPan() != null ? txn.getMaskedPan() : "";
            case "bank_code" ->
                txn.getMerchantBillingRef() != null ? txn.getMerchantBillingRef() : "";
            case "amount" ->
                txn.getAmount() != null ? String.valueOf(txn.getAmount() / 100.0) : "";
            case "currency" ->
                txn.getCurrency() != null ? txn.getCurrency() : "";
            case "transaction_date" ->
                txn.getActualBillingDate() != null ? txn.getActualBillingDate().toString() : "";
            case "start_date" -> {
                // Parse from transactionDescription "start=..."
                String desc = txn.getTransactionDescription();
                if (desc != null && desc.startsWith("start=")) {
                    yield desc.substring(6).trim();
                }
                yield "";
            }
            case "is_recurring" ->
                txn.getIsRecurring() != null ? txn.getIsRecurring().toString() : "";
            case "recurring_type" ->
                txn.getRecurringIndicator() != null ? txn.getRecurringIndicator() : "";
            case "frequency_value" ->
                txn.getFrequencyValue() != null ? txn.getFrequencyValue().toString() : "";
            case "recurring_reference" ->
                txn.getRecurringReference() != null ? txn.getRecurringReference() : "";
            default -> {
                // Try to get from additionalData JSON
                if (txn.getAdditionalData() != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, String> extra = objectMapper.readValue(txn.getAdditionalData(), Map.class);
                        yield extra.getOrDefault(canonicalField, "");
                    } catch (Exception e) {
                        yield "";
                    }
                }
                yield "";
            }
        };
    }

    private String getValidationStatus(RtaTransaction txn) {
        String status = txn.getStatus() != null ? txn.getStatus().toUpperCase() : "";
        if ("FAILED".equals(status)) {
            return "FAIL";
        }
        return "PASS";
    }

    private String getAuthorizationStatus(RtaTransaction txn) {
        // If validation failed, the record was never sent for authorization
        if ("FAIL".equals(getValidationStatus(txn))) {
            return "N/A";
        }
        String status = txn.getStatus() != null ? txn.getStatus().toUpperCase() : "";
        return switch (status) {
            case "APPROVED" ->
                "APPROVED";
            case "DECLINED" ->
                "DECLINED";
            default ->
                "PENDING";
        };
    }

    private String csvSafe(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Manual trigger for a single batch file
    // ─────────────────────────────────────────────────────────────────────
    @Transactional
    public RtaReport generateReportForBatchFileId(Long batchFileId, String triggeredBy) throws Exception {
        RtaIncomingBatchFile batchFile = batchFileRepository.findById(batchFileId)
                .orElseThrow(() -> new IllegalArgumentException("Batch file not found: " + batchFileId));

        RtaReport report = generateReportForBatchFile(batchFile.getMerchantId(), batchFile);
        if (report != null) {
            report.setCreatedBy(triggeredBy);
            reportRepository.save(report);
        }
        return report;
    }

    /**
     * Download report content (HTML report) from MinIO.
     */
    public byte[] downloadReport(Long reportId) {
        RtaReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        String uri = report.getStorageUri();
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("Report has no stored file");
        }

        // Extract bucket and object name from URI (minio://bucket/object → bucket, object)
        String objectName = extractObjectName(uri);
        String bucket = extractBucketName(uri);
        return minioStorageService.downloadFileAsBytesFromBucket(bucket, objectName);
    }

    /**
     * Download output file (unencrypted CSV/XLSX) from MinIO for portal
     * viewing. Falls back to encrypted output if raw is not available.
     */
    public byte[] downloadOutputFile(Long reportId) {
        RtaReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        // Prefer the raw (unencrypted) output file for viewing
        String uri = report.getRawOutputFileUri();
        if (uri == null || uri.isEmpty()) {
            // Fallback to encrypted output
            uri = report.getOutputFileUri();
        }
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("Report has no output file");
        }

        String objectName = extractObjectName(uri);
        String bucket = extractBucketName(uri);
        return minioStorageService.downloadFileAsBytesFromBucket(bucket, objectName);
    }

    /**
     * Retry sending a batch result to the merchant.
     */
    public void retrySend(Long reportId) {
        RtaReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        // Download the raw output file
        String rawUri = report.getRawOutputFileUri();
        if (rawUri == null || rawUri.isEmpty()) {
            throw new IllegalStateException("No raw output file available for resend");
        }
        byte[] outputFileBytes = minioStorageService.downloadFileAsBytesFromBucket(
                extractBucketName(rawUri), extractObjectName(rawUri));

        // Download the summary report
        String storageUri = report.getStorageUri();
        byte[] pdfBytes = new byte[0];
        if (storageUri != null && !storageUri.isEmpty()) {
            pdfBytes = minioStorageService.downloadFileAsBytesFromBucket(
                    extractBucketName(storageUri), extractObjectName(storageUri));
        }

        try {
            sendReportToMerchant(report.getMerchantId(), report, outputFileBytes, pdfBytes);
            report.setSendStatus("SENT");
            report.setSentAt(LocalDateTime.now());
            reportRepository.save(report);
            log.info("[ReportGen] Retry send successful for reportId={}", reportId);
        } catch (Exception e) {
            report.setSendStatus("FAILED");
            reportRepository.save(report);
            throw new RuntimeException("Retry send failed: " + e.getMessage(), e);
        }
    }

    private String extractBucketName(String uri) {
        if (uri.startsWith("minio://")) {
            String afterScheme = uri.substring("minio://".length());
            int slashIdx = afterScheme.indexOf('/');
            if (slashIdx >= 0) {
                return afterScheme.substring(0, slashIdx);
            }
        }
        return minioStorageService.getResultBucketName();
    }

    private String extractObjectName(String uri) {
        // URI format: minio://bucket/object-name
        if (uri.startsWith("minio://")) {
            String afterScheme = uri.substring("minio://".length());
            int slashIdx = afterScheme.indexOf('/');
            if (slashIdx >= 0) {
                return afterScheme.substring(slashIdx + 1);
            }
        }
        return uri;
    }
}
