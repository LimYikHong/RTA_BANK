package rta.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.entity.MerchantInfo;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;
import rta.repository.MerchantInfoRepository;
import rta.service.FileProfileService;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * IncomingBatchController - HTTPS endpoint for merchant-side applications to
 * upload batch files. - Receives multipart file uploads over TLS. - Creates
 * rta_batch + rta_incoming_batch_file records. - Stores the file under
 * /incoming-uploads/.
 */
@RestController
@RequestMapping("/api/incoming")
public class IncomingBatchController {

    private final RtaBatchRepository batchRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaTransactionRepository transactionRepository;
    private final MerchantInfoRepository merchantInfoRepository;
    private final FileProfileService fileProfileService;

    private static final String UPLOAD_DIR = "incoming-uploads";

    public IncomingBatchController(RtaBatchRepository batchRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            FileProfileService fileProfileService) {
        this.batchRepository = batchRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.fileProfileService = fileProfileService;
    }

    /**
     * POST /api/incoming/upload - Merchant-side app uploads a batch file over
     * HTTPS. - Params: file (multipart), merchantId, createdBy (optional) -
     * Creates batch record + incoming file record, stores file on disk.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> receiveIncomingFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "createdBy", required = false, defaultValue = "merchant") String createdBy) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
            }

            // Validate file extension
            String lowerName = originalFilename.toLowerCase();
            if (!lowerName.endsWith(".csv") && !lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xls") && !lowerName.endsWith(".txt")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unsupported file type. Allowed: csv, xlsx, xls, txt"));
            }

            // Validate merchant exists in merchant_info table
            if (merchantInfoRepository.findByMerchantId(merchantId).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Merchant not found",
                        "detail", "Merchant ID '" + merchantId + "' does not exist in the system. Please register the merchant first."
                ));
            }

            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String storedFileName = System.currentTimeMillis() + "_" + originalFilename;
            Path filePath = uploadPath.resolve(storedFileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // Validate file format against merchant's file profile and insert transaction records
            List<String> validationErrors = new ArrayList<>();
            String validationStatus = "RECEIVED";
            String validationRemark = null;
            int totalRecordCount = 0;
            int successCount = 0;
            int failCount = 0;
            long totalAmountCents = 0;
            List<RtaTransaction> transactionsToSave = new ArrayList<>();

            if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
                    List<String> allLines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allLines.add(line);
                    }

                    if (!allLines.isEmpty()) {
                        // Determine delimiter from merchant's file profile
                        String delimiter = ","; // default
                        var profileOpt = fileProfileService.getActiveProfile(merchantId);
                        RtaFileProfile profile = null;
                        List<RtaFieldMapping> mappings = new ArrayList<>();

                        if (profileOpt.isPresent()) {
                            profile = profileOpt.get();
                            if (profile.getFieldDelimiter() != null) {
                                delimiter = profile.getFieldDelimiter();
                            }
                            mappings = fileProfileService.getFieldMappings(profile.getProfileId());
                        }

                        String[] headerRow = allLines.get(0).split(
                                java.util.regex.Pattern.quote(delimiter), -1);

                        // Build header map for column lookup
                        Map<String, Integer> headerMap = new HashMap<>();
                        for (int i = 0; i < headerRow.length; i++) {
                            headerMap.put(headerRow[i].trim().toLowerCase(), i);
                        }

                        List<String[]> dataRows = new ArrayList<>();
                        for (int i = 1; i < allLines.size(); i++) {
                            if (!allLines.get(i).trim().isEmpty()) {
                                dataRows.add(allLines.get(i).split(
                                        java.util.regex.Pattern.quote(delimiter), -1));
                            }
                        }

                        // First validate headers
                        validationErrors = fileProfileService.validateFile(
                                merchantId, headerRow, dataRows);

                        totalRecordCount = dataRows.size();

                        // Determine if header validation passed (check for missing column errors)
                        boolean headerValid = validationErrors.stream()
                                .noneMatch(e -> e.startsWith("Missing required column"));

                        if (!headerValid || !validationErrors.isEmpty() && validationErrors.stream()
                                .anyMatch(e -> e.contains("No active file profile") || e.contains("No field mappings"))) {
                            validationStatus = "VALIDATION_FAILED";
                            validationRemark = String.join("\n",
                                    validationErrors.subList(0, Math.min(validationErrors.size(), 50)));
                        } else {
                            // Process each data row individually
                            validationStatus = "VALIDATED";

                            for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                String[] row = dataRows.get(rowIdx);
                                List<String> rowErrors = new ArrayList<>();
                                String txnStatus = "SUCCESS";

                                // Extract field values from the row
                                String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");

                                // Validate required fields are not null/empty
                                for (RtaFieldMapping mapping : mappings) {
                                    if (Boolean.TRUE.equals(mapping.getRequired())) {
                                        String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                        if (val == null || val.trim().isEmpty()) {
                                            rowErrors.add("Empty value for required field '" + mapping.getCanonicalField() + "'");
                                            txnStatus = "FAILED";
                                        } else {
                                            // Type validation
                                            if (mapping.getDataType() != null) {
                                                switch (mapping.getDataType().toUpperCase()) {
                                                    case "INTEGER":
                                                        try {
                                                            Long.parseLong(val.trim());
                                                        } catch (NumberFormatException e) {
                                                            rowErrors.add("Invalid integer for '" + mapping.getCanonicalField() + "': " + val);
                                                            txnStatus = "FAILED";
                                                        }
                                                        break;
                                                    case "DECIMAL":
                                                        try {
                                                            Double.parseDouble(val.trim());
                                                        } catch (NumberFormatException e) {
                                                            rowErrors.add("Invalid decimal for '" + mapping.getCanonicalField() + "': " + val);
                                                            txnStatus = "FAILED";
                                                        }
                                                        break;
                                                    case "DATE":
                                                        if (profile != null && profile.getDateFormat() != null) {
                                                            try {
                                                                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                                LocalDate.parse(val.trim(), fmt);
                                                            } catch (Exception e) {
                                                                rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                                txnStatus = "FAILED";
                                                            }
                                                        }
                                                        break;
                                                }
                                            }
                                        }
                                    }
                                }

                                // Parse amount
                                Long amountCents = null;
                                if (amountStr != null && !amountStr.trim().isEmpty()) {
                                    try {
                                        double amt = Double.parseDouble(amountStr.trim());
                                        amountCents = Math.round(amt * 100);
                                    } catch (NumberFormatException e) {
                                        // already caught in validation above
                                    }
                                }

                                // Parse transaction date
                                LocalDate txnDate = null;
                                if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile != null) {
                                    try {
                                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                        txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                    } catch (Exception ignored) {
                                    }
                                }

                                // Build transaction entity (will be saved after batch is created)
                                RtaTransaction txn = new RtaTransaction();
                                txn.setMerchantId(merchantId);
                                txn.setBatchSeq(rowIdx + 1);
                                txn.setMerchantCustomer(customerRef);
                                txn.setMaskedPan(accountNum);
                                txn.setMerchantBillingRef(bankCode);
                                txn.setAmount(amountCents);
                                txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                                txn.setActualBillingDate(txnDate);
                                txn.setRecurringIndicator(recurringType);
                                txn.setTransactionDescription(
                                        "freq=" + (freqValueStr != null ? freqValueStr.trim() : "")
                                        + ", start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                txn.setStatus(txnStatus);
                                txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                txn.setCreatedAt(LocalDateTime.now());

                                transactionsToSave.add(txn);

                                if ("SUCCESS".equals(txnStatus)) {
                                    successCount++;
                                    if (amountCents != null) {
                                        totalAmountCents += amountCents;
                                    }
                                } else {
                                    failCount++;
                                }
                            }

                            if (failCount > 0) {
                                validationStatus = "PARTIAL";
                                validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                            }
                        }
                    }
                } catch (Exception e) {
                    validationStatus = "VALIDATION_ERROR";
                    validationRemark = "Error during validation: " + e.getMessage();
                }
            }

            // 1. Create RtaBatch record
            RtaBatch batch = new RtaBatch();
            batch.setFileName(storedFileName);
            batch.setOriginalFileName(originalFilename);
            batch.setMerchantId(merchantId);
            batch.setStatus(validationStatus);
            batch.setCreatedBy(createdBy);
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalCount(totalRecordCount);
            batch.setTotalSuccessCount(successCount);
            batch.setTotalFailCount(failCount);
            RtaBatch savedBatch = batchRepository.save(batch);

            // 2. Save transaction records linked to the batch
            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatch(savedBatch);
                txn.setBatchFileId(0L); // will update after incoming file is created
            }

            // 3. Create RtaIncomingBatchFile record
            RtaIncomingBatchFile incomingFile = new RtaIncomingBatchFile();
            incomingFile.setMerchantId(merchantId);
            incomingFile.setBatchId(savedBatch.getBatchId());
            incomingFile.setOriginalFilename(originalFilename);
            incomingFile.setStorageUri(filePath.toString());
            incomingFile.setSizeBytes(file.getSize());
            incomingFile.setTotalRecordCount(totalRecordCount);
            incomingFile.setSuccessCount(successCount);
            incomingFile.setFailCount(failCount);
            incomingFile.setFileStatus(validationStatus);
            incomingFile.setCreateBy(createdBy);
            incomingFile.setCreatedAt(LocalDateTime.now());
            incomingFile.setTransactionRecordRemark(validationRemark);
            RtaIncomingBatchFile savedFile = incomingFileRepository.save(incomingFile);

            // 4. Now update and save all transactions with the correct batchFileId
            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatchFileId(savedFile.getBatchFileId());
            }
            transactionRepository.saveAll(transactionsToSave);

            // Response
            Map<String, Object> response = new HashMap<>();
            response.put("message", failCount == 0
                    ? "File received and validated successfully"
                    : failCount == totalRecordCount
                            ? "File received but all records failed validation"
                            : "File received with " + failCount + " failed records out of " + totalRecordCount);
            response.put("batchId", savedBatch.getBatchId());
            response.put("batchFileId", savedFile.getBatchFileId());
            response.put("fileName", originalFilename);
            response.put("sizeBytes", file.getSize());
            response.put("status", validationStatus);
            response.put("totalRecords", totalRecordCount);
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            response.put("totalAmount", totalAmountCents / 100.0);
            if (!validationErrors.isEmpty()) {
                response.put("validationErrors", validationErrors);
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to store file: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * GET /api/incoming/files - List all incoming batch files (optionally
     * filtered by merchantId).
     */
    @GetMapping("/files")
    public ResponseEntity<List<RtaIncomingBatchFile>> getIncomingFiles(
            @RequestParam(value = "merchantId", required = false) String merchantId) {
        List<RtaIncomingBatchFile> files;
        if (merchantId != null && !merchantId.isBlank()) {
            files = incomingFileRepository.findByMerchantId(merchantId);
        } else {
            files = incomingFileRepository.findAll();
        }
        return ResponseEntity.ok(files);
    }

    /**
     * GET /api/incoming/files/{id} - Get a specific incoming batch file by ID.
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<?> getIncomingFileById(@PathVariable Long id) {
        return incomingFileRepository.findById(id)
                .map(f -> ResponseEntity.ok((Object) f))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/incoming/batch-summary/{batchId} - Get batch summary with counts
     * and total amount.
     */
    @GetMapping("/batch-summary/{batchId}")
    public ResponseEntity<?> getBatchSummary(@PathVariable Long batchId) {
        Optional<RtaBatch> batchOpt = batchRepository.findById(batchId);
        if (batchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RtaBatch batch = batchOpt.get();
        int total = transactionRepository.countByBatchBatchId(batchId);
        int success = transactionRepository.countByBatchBatchIdAndStatus(batchId, "SUCCESS");
        int fail = transactionRepository.countByBatchBatchIdAndStatus(batchId, "FAILED");
        long totalAmountCents = 0;
        try {
            totalAmountCents = transactionRepository.sumAmountByBatchIdAndStatusSuccess(batchId);
        } catch (Exception ignored) {
        }

        // Get validation remark from incoming file
        String validationRemark = null;
        List<RtaIncomingBatchFile> incomingFiles = incomingFileRepository.findByBatchId(batchId);
        if (!incomingFiles.isEmpty()) {
            validationRemark = incomingFiles.get(0).getTransactionRecordRemark();
            // Use totalRecordCount from incoming file if no transactions created
            if (total == 0 && incomingFiles.get(0).getTotalRecordCount() != null) {
                total = incomingFiles.get(0).getTotalRecordCount();
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batchId", batchId);
        summary.put("fileName", batch.getOriginalFileName());
        summary.put("merchantId", batch.getMerchantId());
        summary.put("status", batch.getStatus());
        summary.put("totalRecords", total);
        summary.put("successCount", success);
        summary.put("failCount", fail);
        summary.put("totalAmount", totalAmountCents / 100.0);
        summary.put("createdAt", batch.getCreatedAt());
        summary.put("createdBy", batch.getCreatedBy());
        summary.put("validationRemark", validationRemark);

        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/incoming/transactions/{batchId} - Get transactions for a batch.
     * Optional query param: status (e.g., FAILED to get only failed records).
     */
    @GetMapping("/transactions/{batchId}")
    public ResponseEntity<?> getTransactionsByBatch(
            @PathVariable Long batchId,
            @RequestParam(value = "status", required = false) String status) {

        List<RtaTransaction> transactions;
        if (status != null && !status.isBlank()) {
            transactions = transactionRepository.findByBatchBatchIdAndStatus(batchId, status.toUpperCase());
        } else {
            transactions = transactionRepository.findByBatchBatchId(batchId);
        }

        // Map to DTOs to avoid lazy loading issues
        List<Map<String, Object>> result = new ArrayList<>();
        for (RtaTransaction txn : transactions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("transactionId", txn.getId());
            map.put("batchSeq", txn.getBatchSeq());
            map.put("merchantId", txn.getMerchantId());
            map.put("customerReference", txn.getMerchantCustomer());
            map.put("accountNum", txn.getMaskedPan());
            map.put("bankCode", txn.getMerchantBillingRef());
            map.put("amount", txn.getAmount() != null ? txn.getAmount() / 100.0 : null);
            map.put("currency", txn.getCurrency());
            map.put("transactionDate", txn.getActualBillingDate());
            map.put("recurringType", txn.getRecurringIndicator());
            map.put("description", txn.getTransactionDescription());
            map.put("status", txn.getStatus());
            map.put("remark", txn.getRemark());
            map.put("createdAt", txn.getCreatedAt());
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Helper: extract a field value from a row by canonical field name using
     * the header map and mappings.
     */
    private String getFieldValue(String[] row, Map<String, Integer> headerMap,
            List<RtaFieldMapping> mappings, String canonicalField) {
        for (RtaFieldMapping mapping : mappings) {
            if (canonicalField.equalsIgnoreCase(mapping.getCanonicalField())) {
                // Try header-based lookup first
                String sourceCol = mapping.getSourceColumnName() != null
                        ? mapping.getSourceColumnName().toLowerCase()
                        : mapping.getCanonicalField().toLowerCase();
                Integer idx = headerMap.get(sourceCol);
                if (idx == null && mapping.getSourceColumnIdx() != null) {
                    idx = mapping.getSourceColumnIdx();
                }
                if (idx != null && idx < row.length) {
                    return row[idx].trim();
                }
                return null;
            }
        }
        return null;
    }
}
