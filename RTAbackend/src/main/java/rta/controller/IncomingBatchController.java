package rta.controller;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import rta.entity.RtaBatch;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.entity.RtaUploadedFileHash;
import rta.repository.MerchantInfoRepository;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;
import rta.repository.RtaUploadedFileHashRepository;
import rta.service.AuditLogService;
import rta.service.FileDecryptionService;
import rta.service.FileProfileService;
import rta.service.MinioStorageService;

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
    private final RtaUploadedFileHashRepository uploadedFileHashRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaTransactionRepository transactionRepository;
    private final MerchantInfoRepository merchantInfoRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final FileProfileService fileProfileService;
    private final MinioStorageService minioStorageService;
    private final FileDecryptionService fileDecryptionService;
    private final AuditLogService auditLogService;

    private static final String UPLOAD_DIR = "incoming-uploads";

    public IncomingBatchController(RtaBatchRepository batchRepository,
            RtaUploadedFileHashRepository uploadedFileHashRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            RtaAuthorizationBatchRepository authBatchRepository,
            FileProfileService fileProfileService,
            MinioStorageService minioStorageService,
            FileDecryptionService fileDecryptionService,
            AuditLogService auditLogService) {
        this.batchRepository = batchRepository;
        this.uploadedFileHashRepository = uploadedFileHashRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.authBatchRepository = authBatchRepository;
        this.fileProfileService = fileProfileService;
        this.minioStorageService = minioStorageService;
        this.fileDecryptionService = fileDecryptionService;
        this.auditLogService = auditLogService;
    }

    /**
     * POST /api/incoming/upload - Merchant-side app uploads a batch file over
     * HTTPS. - Params: file (multipart), merchantId, createdBy (optional),
     * fileName (renamed file), originalFileName (original file name) - Creates
     * batch record + incoming file record, stores file on disk.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> receiveIncomingFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "createdBy", required = false, defaultValue = "merchant") String createdBy,
            @RequestParam(value = "fileName", required = false) String fileNameParam,
            @RequestParam(value = "originalFileName", required = false) String originalFileNameParam,
            @RequestParam(value = "encrypted", required = false, defaultValue = "false") boolean encrypted) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
            }

            // Get original filename - from param or multipart
            String originalFilename = (originalFileNameParam != null && !originalFileNameParam.isBlank())
                    ? originalFileNameParam
                    : file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid original file name"));
            }

            // Get renamed filename (merchantId_datetime format) - from param or generate
            String renamedFilename = (fileNameParam != null && !fileNameParam.isBlank())
                    ? fileNameParam
                    : originalFilename;

            // Strip .enc suffix if the file was encrypted, then validate the real extension
            String lowerName = renamedFilename.toLowerCase();
            if (encrypted && lowerName.endsWith(".enc")) {
                lowerName = lowerName.substring(0, lowerName.length() - 4); // remove ".enc"
                renamedFilename = renamedFilename.substring(0, renamedFilename.length() - 4);
            }
            if (!lowerName.endsWith(".csv") && !lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xls") && !lowerName.endsWith(".txt")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unsupported file type. Allowed: csv, xlsx, xls, txt"));
            }

            // Validate merchant exists and is not soft-deleted
            if (merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantId).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Merchant not found",
                        "detail", "Merchant ID '" + merchantId + "' does not exist or has been deleted. Please register the merchant first."
                ));
            }

            // --- Decrypt file if uploaded as encrypted ---
            byte[] rawFileBytes = file.getBytes();
            if (encrypted) {
                try {
                    rawFileBytes = fileDecryptionService.decryptFile(merchantId, rawFileBytes);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    String errMsg = ex.getMessage() != null ? ex.getMessage()
                            : ex.getClass().getSimpleName() + " (no message)";
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "File decryption failed",
                            "detail", "Could not decrypt the uploaded file for merchant '" + merchantId + "'. " + errMsg
                    ));
                }
            }

            // Generate SHA-256 hash of file content to detect duplicates
            String fileHash = generateSHA256Hash(rawFileBytes);

            // Statuses that indicate the file has valid/processed transactions — reject re-upload globally
            java.util.Set<String> BLOCKING_STATUSES = java.util.Set.of(
                    "PARTIAL", "PASS", "SUCCESS", "VALIDATED", "PARTIAL_SUCCESS", "PROCESSED");

            // Check for duplicate file across ALL merchants first
            List<rta.entity.RtaUploadedFileHash> allHashRecords = uploadedFileHashRepository.findByFileHash(fileHash);
            for (rta.entity.RtaUploadedFileHash anyRecord : allHashRecords) {
                String anyStatus = anyRecord.getStatus() != null ? anyRecord.getStatus() : "";
                if (BLOCKING_STATUSES.contains(anyStatus)) {
                    // Same file content was already accepted (with valid transactions) by some merchant — reject
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Duplicate file detected",
                            "detail", "This file has already been uploaded and accepted"
                            + (anyRecord.getMerchantId().equals(merchantId) ? "."
                            : " by another merchant (" + anyRecord.getMerchantId() + ")."),
                            "duplicateFileInfo", Map.of(
                                    "id", anyRecord.getId(),
                                    "originalFilename", anyRecord.getOriginalFilename() != null ? anyRecord.getOriginalFilename() : "",
                                    "merchantId", anyRecord.getMerchantId(),
                                    "uploadedAt", anyRecord.getUploadedAt() != null ? anyRecord.getUploadedAt().toString() : "N/A",
                                    "status", anyStatus,
                                    "uploadCount", anyRecord.getUploadCount() != null ? anyRecord.getUploadCount() : 1
                            )
                    ));
                }
            }

            // Check for same-merchant duplicate (handles WRONG_FILE_FORMAT re-upload and upload limit)
            Optional<RtaUploadedFileHash> existingHash = uploadedFileHashRepository
                    .findByMerchantIdAndFileHash(merchantId, fileHash);
            if (existingHash.isPresent()) {
                RtaUploadedFileHash existing = existingHash.get();
                int currentCount = existing.getUploadCount() != null ? existing.getUploadCount() : 1;
                String existingStatus = existing.getStatus() != null ? existing.getStatus() : "";

                // Only WRONG_FILE_FORMAT is allowed to re-upload
                if (!"WRONG_FILE_FORMAT".equals(existingStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Duplicate file detected",
                            "detail", "This file has already been uploaded.",
                            "duplicateFileInfo", Map.of(
                                    "id", existing.getId(),
                                    "originalFilename", existing.getOriginalFilename(),
                                    "uploadedAt", existing.getUploadedAt() != null ? existing.getUploadedAt().toString() : "N/A",
                                    "status", existingStatus,
                                    "uploadCount", currentCount
                            )
                    ));
                }

                // Block if upload count has reached the maximum (5 attempts)
                if (currentCount >= 5) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Upload limit reached",
                            "detail", "This file has been uploaded " + currentCount + " times and all attempts failed. Maximum 5 attempts allowed. Please contact support or upload a different file.",
                            "duplicateFileInfo", Map.of(
                                    "id", existing.getId(),
                                    "originalFilename", existing.getOriginalFilename(),
                                    "uploadedAt", existing.getUploadedAt() != null ? existing.getUploadedAt().toString() : "N/A",
                                    "status", existingStatus,
                                    "uploadCount", currentCount
                            )
                    ));
                }

                // WRONG_FILE_FORMAT with count < 5 — allow re-upload (will update the existing row later)
            }

            // Generate stored filename: MERCHANTID_DATETIME.ext (e.g. M001_20260331143052.csv)
            String fileExtension = "";
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex >= 0) {
                fileExtension = originalFilename.substring(dotIndex); // includes the dot
            }
            String dateTimeSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String storedFileName = merchantId + "_" + dateTimeSuffix + fileExtension;
            String objectName = UPLOAD_DIR + "/" + storedFileName;

            // Upload file to MinIO (store the decrypted/plain content)
            String storageUri;
            if (encrypted) {
                // Upload decrypted bytes to MinIO so stored files are always plaintext
                String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
                storageUri = minioStorageService.uploadFile(objectName, rawFileBytes, contentType);
            } else {
                storageUri = minioStorageService.uploadFile(objectName, file);
            }

            // Use decrypted bytes for validation (skip re-download when encrypted)
            byte[] fileContent = encrypted ? rawFileBytes : minioStorageService.downloadFileAsBytes(objectName);

            // Validate file format against merchant's file profile and insert transaction records
            List<String> validationErrors = new ArrayList<>();
            String validationStatus = "RECEIVED";
            String validationRemark = null;
            int totalRecordCount = 0;
            int successCount = 0;
            int failCount = 0;
            long totalAmountCents = 0;
            List<RtaTransaction> transactionsToSave = new ArrayList<>();

            // Step 1: Check if merchant has an active file profile
            var profileOpt = fileProfileService.getActiveProfile(merchantId);
            if (profileOpt.isEmpty()) {
                validationStatus = "NO_FILE_PROFILE";
                validationRemark = "No active file profile found for merchant: " + merchantId;
            } else {
                RtaFileProfile profile = profileOpt.get();
                List<RtaFieldMapping> mappings = fileProfileService.getFieldMappings(profile.getProfileId());

                if (mappings.isEmpty()) {
                    validationStatus = "NO_FIELD_MAPPING";
                    validationRemark = "No field mappings configured for merchant: " + merchantId;
                } else {
                    // Step 2: Check file format matches profile's fileType
                    String profileFileType = profile.getFileType() != null ? profile.getFileType().toLowerCase() : "";
                    String uploadedFileType = "";
                    if (lowerName.endsWith(".csv")) {
                        uploadedFileType = "csv";
                    } else if (lowerName.endsWith(".xlsx")) {
                        uploadedFileType = "xlsx";
                    } else if (lowerName.endsWith(".xls")) {
                        uploadedFileType = "xls";
                    } else if (lowerName.endsWith(".txt")) {
                        uploadedFileType = "txt";
                    }

                    // Check if file type matches (allow txt to match csv and vice versa as they are both delimited)
                    boolean fileTypeMatches = profileFileType.equals(uploadedFileType)
                            || (profileFileType.equals("csv") && uploadedFileType.equals("txt"))
                            || (profileFileType.equals("txt") && uploadedFileType.equals("csv"))
                            || (profileFileType.equals("xlsx") && uploadedFileType.equals("xls"))
                            || (profileFileType.equals("xls") && uploadedFileType.equals("xlsx"));

                    if (!profileFileType.isEmpty() && !fileTypeMatches) {
                        validationStatus = "WRONG_FILE_FORMAT";
                        validationRemark = "File format mismatch. Expected: " + profileFileType.toUpperCase() + ", Uploaded: " + uploadedFileType.toUpperCase();
                    } else {
                        // Proceed with file parsing and validation
                        if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt")) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(fileContent)))) {
                                List<String> allLines = new ArrayList<>();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    allLines.add(line);
                                }

                                if (allLines.isEmpty()) {
                                    validationStatus = "VALIDATION_FAILED";
                                    validationRemark = "File is empty";
                                } else {
                                    // Determine delimiter from merchant's file profile
                                    String delimiter = ","; // default
                                    if (profile.getFieldDelimiter() != null) {
                                        delimiter = profile.getFieldDelimiter();
                                    }

                                    String[] headerRow = allLines.get(0).split(
                                            java.util.regex.Pattern.quote(delimiter), -1);

                                    // Step 3: Check if profile expects header but file doesn't have proper header
                                    boolean profileExpectsHeader = Boolean.TRUE.equals(profile.getHasHeader());
                                    if (profileExpectsHeader) {
                                        // Validate that header row matches expected column names from mappings
                                        Map<String, Integer> headerMap = new HashMap<>();
                                        for (int i = 0; i < headerRow.length; i++) {
                                            headerMap.put(headerRow[i].trim().toLowerCase(), i);
                                        }

                                        // Check if required columns exist in header
                                        List<String> missingColumns = new ArrayList<>();
                                        for (RtaFieldMapping mapping : mappings) {
                                            if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                String sourceCol = mapping.getSourceColumnName() != null
                                                        ? mapping.getSourceColumnName().toLowerCase()
                                                        : mapping.getCanonicalField().toLowerCase();
                                                if (!headerMap.containsKey(sourceCol) && mapping.getSourceColumnIdx() == null) {
                                                    missingColumns.add(sourceCol);
                                                }
                                            }
                                        }

                                        if (!missingColumns.isEmpty()) {
                                            validationStatus = "MISSING_HEADER";
                                            validationRemark = "Missing required columns in header: " + String.join(", ", missingColumns);
                                        } else {
                                            // Header is valid, proceed with data validation
                                            List<String[]> dataRows = new ArrayList<>();
                                            for (int i = 1; i < allLines.size(); i++) {
                                                if (!allLines.get(i).trim().isEmpty()) {
                                                    dataRows.add(allLines.get(i).split(
                                                            java.util.regex.Pattern.quote(delimiter), -1));
                                                }
                                            }

                                            totalRecordCount = dataRows.size();
                                            validationStatus = "VALIDATED";

                                            // ObjectMapper for JSON serialization of custom fields
                                            ObjectMapper objectMapper = new ObjectMapper();

                                            for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                                String[] row = dataRows.get(rowIdx);
                                                List<String> rowErrors = new ArrayList<>();
                                                String txnStatus = "PENDING";

                                                // Extract field values from the row - Transaction fields
                                                String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                                String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                                String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                                String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                                String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                                String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                                String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");

                                                // Extract recurring fields
                                                String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                                                String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                                String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                                String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                                                // Extract custom/additional fields (non-required fields added by merchant)
                                                Map<String, String> additionalData = new LinkedHashMap<>();
                                                for (RtaFieldMapping mapping : mappings) {
                                                    String fieldName = mapping.getCanonicalField();
                                                    // Skip required fields - they are stored in dedicated columns
                                                    if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                                        String value = getFieldValue(row, headerMap, mappings, fieldName);
                                                        if (value != null && !value.trim().isEmpty()) {
                                                            additionalData.put(fieldName, value.trim());
                                                        }
                                                    }
                                                }

                                                // Validate required fields are not null/empty
                                                // Determine upfront if this row is non-recurring so we can relax recurring-only required fields
                                                boolean rowIsNonRecurring = isRecurringStr != null
                                                        && !isRecurringStr.trim().isEmpty()
                                                        && ("false".equals(isRecurringStr.trim().toLowerCase())
                                                        || "0".equals(isRecurringStr.trim())
                                                        || "no".equals(isRecurringStr.trim().toLowerCase())
                                                        || "n".equals(isRecurringStr.trim().toLowerCase()));
                                                java.util.Set<String> RECURRING_ONLY_FIELDS = java.util.Set.of(
                                                        "recurring_reference", "recurring_type", "frequency_value");
                                                for (RtaFieldMapping mapping : mappings) {
                                                    if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                        // Skip recurring-only fields if this row is non-recurring
                                                        if (rowIsNonRecurring && RECURRING_ONLY_FIELDS.contains(mapping.getCanonicalField())) {
                                                            continue;
                                                        }
                                                        String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                                        if (val == null || val.trim().isEmpty()) {
                                                            rowErrors.add("Empty value for required field '" + mapping.getCanonicalField() + "'");
                                                            txnStatus = "FAILED";
                                                        } else {
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
                                                                        if (profile.getDateFormat() != null) {
                                                                            try {
                                                                                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                                                LocalDate.parse(val.trim(), fmt);
                                                                            } catch (Exception e) {
                                                                                rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                                                txnStatus = "FAILED";
                                                                            }
                                                                        }
                                                                        break;
                                                                    case "BOOLEAN":
                                                                        String boolVal = val.trim().toLowerCase();
                                                                        if (!boolVal.equals("true") && !boolVal.equals("false")
                                                                                && !boolVal.equals("1") && !boolVal.equals("0")
                                                                                && !boolVal.equals("yes") && !boolVal.equals("no")
                                                                                && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                                            rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                                            txnStatus = "FAILED";
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
                                                if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile.getDateFormat() != null) {
                                                    try {
                                                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                        txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                                    } catch (Exception ignored) {
                                                    }
                                                }

                                                // Parse is_recurring boolean
                                                Boolean isRecurring = null;
                                                if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                                    String val = isRecurringStr.trim().toLowerCase();
                                                    isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                                                }

                                                // Parse frequency_value integer
                                                Integer freqValue = null;
                                                if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                                    try {
                                                        freqValue = Integer.parseInt(freqValueStr.trim());
                                                    } catch (NumberFormatException ignored) {
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
                                                // Set recurring fields
                                                txn.setIsRecurring(isRecurring);
                                                txn.setRecurringIndicator(recurringType);
                                                txn.setFrequencyValue(freqValue);
                                                txn.setRecurringReference(recurringRef);
                                                txn.setTransactionDescription(
                                                        "start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                                txn.setStatus(txnStatus);
                                                txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                                txn.setCreatedAt(LocalDateTime.now());

                                                // Set additional data as JSON (custom fields added by merchant)
                                                if (!additionalData.isEmpty()) {
                                                    try {
                                                        txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                                    } catch (Exception e) {
                                                        // Log error but continue - additional data is optional
                                                    }
                                                }

                                                transactionsToSave.add(txn);

                                                if ("PENDING".equals(txnStatus)) {
                                                    successCount++;
                                                    if (amountCents != null) {
                                                        totalAmountCents += amountCents;
                                                    }
                                                } else {
                                                    failCount++;
                                                }
                                            }

                                            if (failCount > 0) {
                                                if (successCount == 0) {
                                                    validationStatus = "INVALID_FILE_CONTENT";
                                                    validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                                                } else {
                                                    validationStatus = "PARTIAL";
                                                    validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                                                }
                                            }
                                        }
                                    } else {
                                        // Profile doesn't expect header, use column index based mapping
                                        List<String[]> dataRows = new ArrayList<>();
                                        for (int i = 0; i < allLines.size(); i++) {
                                            if (!allLines.get(i).trim().isEmpty()) {
                                                dataRows.add(allLines.get(i).split(
                                                        java.util.regex.Pattern.quote(delimiter), -1));
                                            }
                                        }

                                        totalRecordCount = dataRows.size();
                                        validationStatus = "VALIDATED";
                                        Map<String, Integer> headerMap = new HashMap<>(); // empty for index-based

                                        ObjectMapper objectMapper = new ObjectMapper();

                                        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                            String[] row = dataRows.get(rowIdx);
                                            List<String> rowErrors = new ArrayList<>();
                                            String txnStatus = "PENDING";

                                            String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                            String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                            String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                            String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                            String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                            String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                            String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");
                                            String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                                            String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                            String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                            String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                                            Map<String, String> additionalData = new LinkedHashMap<>();
                                            for (RtaFieldMapping mapping : mappings) {
                                                String fieldName = mapping.getCanonicalField();
                                                if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                                    String value = getFieldValue(row, headerMap, mappings, fieldName);
                                                    if (value != null && !value.trim().isEmpty()) {
                                                        additionalData.put(fieldName, value.trim());
                                                    }
                                                }
                                            }

                                            boolean rowIsNonRecurring = isRecurringStr != null
                                                    && !isRecurringStr.trim().isEmpty()
                                                    && ("false".equals(isRecurringStr.trim().toLowerCase())
                                                    || "0".equals(isRecurringStr.trim())
                                                    || "no".equals(isRecurringStr.trim().toLowerCase())
                                                    || "n".equals(isRecurringStr.trim().toLowerCase()));
                                            java.util.Set<String> RECURRING_ONLY_FIELDS = java.util.Set.of(
                                                    "recurring_reference", "recurring_type", "frequency_value");
                                            for (RtaFieldMapping mapping : mappings) {
                                                if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                    if (rowIsNonRecurring && RECURRING_ONLY_FIELDS.contains(mapping.getCanonicalField())) {
                                                        continue;
                                                    }
                                                    String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                                    if (val == null || val.trim().isEmpty()) {
                                                        rowErrors.add("Empty value for required field '" + mapping.getCanonicalField() + "'");
                                                        txnStatus = "FAILED";
                                                    } else {
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
                                                                    if (profile.getDateFormat() != null) {
                                                                        try {
                                                                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                                            LocalDate.parse(val.trim(), fmt);
                                                                        } catch (Exception e) {
                                                                            rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                                            txnStatus = "FAILED";
                                                                        }
                                                                    }
                                                                    break;
                                                                case "BOOLEAN":
                                                                    String boolVal = val.trim().toLowerCase();
                                                                    if (!boolVal.equals("true") && !boolVal.equals("false")
                                                                            && !boolVal.equals("1") && !boolVal.equals("0")
                                                                            && !boolVal.equals("yes") && !boolVal.equals("no")
                                                                            && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                                        rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                                        txnStatus = "FAILED";
                                                                    }
                                                                    break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Long amountCents = null;
                                            if (amountStr != null && !amountStr.trim().isEmpty()) {
                                                try {
                                                    double amt = Double.parseDouble(amountStr.trim());
                                                    amountCents = Math.round(amt * 100);
                                                } catch (NumberFormatException ignored) {
                                                }
                                            }

                                            LocalDate txnDate = null;
                                            if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile.getDateFormat() != null) {
                                                try {
                                                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                    txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                                } catch (Exception ignored) {
                                                }
                                            }

                                            Boolean isRecurring = null;
                                            if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                                String val = isRecurringStr.trim().toLowerCase();
                                                isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                                            }

                                            Integer freqValue = null;
                                            if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                                try {
                                                    freqValue = Integer.parseInt(freqValueStr.trim());
                                                } catch (NumberFormatException ignored) {
                                                }
                                            }

                                            RtaTransaction txn = new RtaTransaction();
                                            txn.setMerchantId(merchantId);
                                            txn.setBatchSeq(rowIdx + 1);
                                            txn.setMerchantCustomer(customerRef);
                                            txn.setMaskedPan(accountNum);
                                            txn.setMerchantBillingRef(bankCode);
                                            txn.setAmount(amountCents);
                                            txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                                            txn.setActualBillingDate(txnDate);
                                            txn.setIsRecurring(isRecurring);
                                            txn.setRecurringIndicator(recurringType);
                                            txn.setFrequencyValue(freqValue);
                                            txn.setRecurringReference(recurringRef);
                                            txn.setTransactionDescription("start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                            txn.setStatus(txnStatus);
                                            txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                            txn.setCreatedAt(LocalDateTime.now());

                                            if (!additionalData.isEmpty()) {
                                                try {
                                                    txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                                } catch (Exception ignored) {
                                                }
                                            }

                                            transactionsToSave.add(txn);

                                            if ("PENDING".equals(txnStatus)) {
                                                successCount++;
                                                if (amountCents != null) {
                                                    totalAmountCents += amountCents;
                                                }
                                            } else {
                                                failCount++;
                                            }
                                        }

                                        if (failCount > 0) {
                                            if (successCount == 0) {
                                                validationStatus = "INVALID_FILE_CONTENT";
                                                validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                                            } else {
                                                validationStatus = "PARTIAL";
                                                validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                validationStatus = "VALIDATION_ERROR";
                                validationRemark = "Error during validation: " + e.getMessage();
                            }
                        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                            // Process Excel files (xlsx/xls)
                            try (InputStream is = new ByteArrayInputStream(fileContent); Workbook workbook = lowerName.endsWith(".xlsx") ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {

                                Sheet sheet = workbook.getSheetAt(0);
                                if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                                    validationStatus = "VALIDATION_FAILED";
                                    validationRemark = "Excel file is empty or has no sheets";
                                } else {
                                    // Read header row
                                    Row headerRow = sheet.getRow(0);
                                    DataFormatter formatter = new DataFormatter();

                                    // Step 3: Check if profile expects header
                                    boolean profileExpectsHeader = Boolean.TRUE.equals(profile.getHasHeader());

                                    if (profileExpectsHeader) {
                                        if (headerRow == null) {
                                            validationStatus = "MISSING_HEADER";
                                            validationRemark = "Excel file has no header row but profile expects headers";
                                        } else {
                                            // Build header array and map
                                            int headerCellCount = headerRow.getLastCellNum();
                                            String[] headerArr = new String[headerCellCount];
                                            Map<String, Integer> headerMap = new HashMap<>();

                                            for (int i = 0; i < headerCellCount; i++) {
                                                Cell cell = headerRow.getCell(i);
                                                String val = (cell != null) ? formatter.formatCellValue(cell).trim() : "";
                                                headerArr[i] = val;
                                                headerMap.put(val.toLowerCase(), i);
                                            }

                                            // Check if required columns exist in header
                                            List<String> missingColumns = new ArrayList<>();
                                            for (RtaFieldMapping mapping : mappings) {
                                                if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                    String sourceCol = mapping.getSourceColumnName() != null
                                                            ? mapping.getSourceColumnName().toLowerCase()
                                                            : mapping.getCanonicalField().toLowerCase();
                                                    if (!headerMap.containsKey(sourceCol) && mapping.getSourceColumnIdx() == null) {
                                                        missingColumns.add(sourceCol);
                                                    }
                                                }
                                            }

                                            if (!missingColumns.isEmpty()) {
                                                validationStatus = "MISSING_HEADER";
                                                validationRemark = "Missing required columns in header: " + String.join(", ", missingColumns);
                                            } else {
                                                // Build data rows
                                                List<String[]> dataRows = new ArrayList<>();
                                                for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                                                    Row row = sheet.getRow(rowIdx);
                                                    if (row != null) {
                                                        String[] rowData = new String[headerCellCount];
                                                        boolean hasData = false;
                                                        for (int colIdx = 0; colIdx < headerCellCount; colIdx++) {
                                                            Cell cell = row.getCell(colIdx);
                                                            String val = (cell != null) ? formatter.formatCellValue(cell).trim() : "";
                                                            rowData[colIdx] = val;
                                                            if (!val.isEmpty()) {
                                                                hasData = true;
                                                            }
                                                        }
                                                        if (hasData) {
                                                            dataRows.add(rowData);
                                                        }
                                                    }
                                                }

                                                totalRecordCount = dataRows.size();
                                                validationStatus = "VALIDATED";
                                                ObjectMapper objectMapper = new ObjectMapper();

                                                for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                                    String[] row = dataRows.get(rowIdx);
                                                    List<String> rowErrors = new ArrayList<>();
                                                    String txnStatus = "PENDING";

                                                    // Extract field values
                                                    String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                                    String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                                    String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                                    String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                                    String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                                    String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                                    String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");
                                                    String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                                                    String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                                    String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                                    String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                                                    // Extract additional/custom fields
                                                    Map<String, String> additionalData = new LinkedHashMap<>();
                                                    for (RtaFieldMapping mapping : mappings) {
                                                        String fieldName = mapping.getCanonicalField();
                                                        if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                                            String value = getFieldValue(row, headerMap, mappings, fieldName);
                                                            if (value != null && !value.trim().isEmpty()) {
                                                                additionalData.put(fieldName, value.trim());
                                                            }
                                                        }
                                                    }

                                                    // Validate required fields
                                                    boolean rowIsNonRecurring = isRecurringStr != null
                                                            && !isRecurringStr.trim().isEmpty()
                                                            && ("false".equals(isRecurringStr.trim().toLowerCase())
                                                            || "0".equals(isRecurringStr.trim())
                                                            || "no".equals(isRecurringStr.trim().toLowerCase())
                                                            || "n".equals(isRecurringStr.trim().toLowerCase()));
                                                    java.util.Set<String> RECURRING_ONLY_FIELDS = java.util.Set.of(
                                                            "recurring_reference", "recurring_type", "frequency_value");
                                                    for (RtaFieldMapping mapping : mappings) {
                                                        if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                            if (rowIsNonRecurring && RECURRING_ONLY_FIELDS.contains(mapping.getCanonicalField())) {
                                                                continue;
                                                            }
                                                            String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                                            if (val == null || val.trim().isEmpty()) {
                                                                rowErrors.add("Empty value for required field '" + mapping.getCanonicalField() + "'");
                                                                txnStatus = "FAILED";
                                                            } else {
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
                                                                            if (profile.getDateFormat() != null) {
                                                                                try {
                                                                                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                                                    LocalDate.parse(val.trim(), fmt);
                                                                                } catch (Exception e) {
                                                                                    rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                                                    txnStatus = "FAILED";
                                                                                }
                                                                            }
                                                                            break;
                                                                        case "BOOLEAN":
                                                                            String boolVal = val.trim().toLowerCase();
                                                                            if (!boolVal.equals("true") && !boolVal.equals("false")
                                                                                    && !boolVal.equals("1") && !boolVal.equals("0")
                                                                                    && !boolVal.equals("yes") && !boolVal.equals("no")
                                                                                    && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                                                rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                                                txnStatus = "FAILED";
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
                                                        } catch (NumberFormatException ignored) {
                                                        }
                                                    }

                                                    // Parse transaction date
                                                    LocalDate txnDate = null;
                                                    if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile.getDateFormat() != null) {
                                                        try {
                                                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                            txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                                        } catch (Exception ignored) {
                                                        }
                                                    }

                                                    // Parse is_recurring boolean
                                                    Boolean isRecurring = null;
                                                    if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                                        String val = isRecurringStr.trim().toLowerCase();
                                                        isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                                                    }

                                                    // Parse frequency_value integer
                                                    Integer freqValue = null;
                                                    if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                                        try {
                                                            freqValue = Integer.parseInt(freqValueStr.trim());
                                                        } catch (NumberFormatException ignored) {
                                                        }
                                                    }

                                                    // Build transaction entity
                                                    RtaTransaction txn = new RtaTransaction();
                                                    txn.setMerchantId(merchantId);
                                                    txn.setBatchSeq(rowIdx + 1);
                                                    txn.setMerchantCustomer(customerRef);
                                                    txn.setMaskedPan(accountNum);
                                                    txn.setMerchantBillingRef(bankCode);
                                                    txn.setAmount(amountCents);
                                                    txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                                                    txn.setActualBillingDate(txnDate);
                                                    txn.setIsRecurring(isRecurring);
                                                    txn.setRecurringIndicator(recurringType);
                                                    txn.setFrequencyValue(freqValue);
                                                    txn.setRecurringReference(recurringRef);
                                                    txn.setTransactionDescription(
                                                            "start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                                    txn.setStatus(txnStatus);
                                                    txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                                    txn.setCreatedAt(LocalDateTime.now());

                                                    if (!additionalData.isEmpty()) {
                                                        try {
                                                            txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                                        } catch (Exception ignored) {
                                                        }
                                                    }

                                                    transactionsToSave.add(txn);

                                                    if ("PENDING".equals(txnStatus)) {
                                                        successCount++;
                                                        if (amountCents != null) {
                                                            totalAmountCents += amountCents;
                                                        }
                                                    } else {
                                                        failCount++;
                                                    }
                                                }

                                                if (failCount > 0) {
                                                    if (successCount == 0) {
                                                        validationStatus = "INVALID_FILE_CONTENT";
                                                        validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                                                    } else {
                                                        validationStatus = "PARTIAL";
                                                        validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Profile doesn't expect header, use column index based mapping
                                        int colCount = headerRow != null ? headerRow.getLastCellNum() : 0;
                                        if (colCount == 0 && sheet.getRow(0) != null) {
                                            colCount = sheet.getRow(0).getLastCellNum();
                                        }

                                        List<String[]> dataRows = new ArrayList<>();
                                        for (int rowIdx = 0; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                                            Row row = sheet.getRow(rowIdx);
                                            if (row != null) {
                                                String[] rowData = new String[colCount];
                                                boolean hasData = false;
                                                for (int colIdx = 0; colIdx < colCount; colIdx++) {
                                                    Cell cell = row.getCell(colIdx);
                                                    String val = (cell != null) ? formatter.formatCellValue(cell).trim() : "";
                                                    rowData[colIdx] = val;
                                                    if (!val.isEmpty()) {
                                                        hasData = true;
                                                    }
                                                }
                                                if (hasData) {
                                                    dataRows.add(rowData);
                                                }
                                            }
                                        }

                                        totalRecordCount = dataRows.size();
                                        validationStatus = "VALIDATED";
                                        Map<String, Integer> headerMap = new HashMap<>(); // empty for index-based
                                        ObjectMapper objectMapper = new ObjectMapper();

                                        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                            String[] row = dataRows.get(rowIdx);
                                            List<String> rowErrors = new ArrayList<>();
                                            String txnStatus = "PENDING";

                                            String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                            String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                            String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                            String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                            String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                            String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                            String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");
                                            String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                                            String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                            String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                            String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                                            Map<String, String> additionalData = new LinkedHashMap<>();
                                            for (RtaFieldMapping mapping : mappings) {
                                                String fieldName = mapping.getCanonicalField();
                                                if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                                    String value = getFieldValue(row, headerMap, mappings, fieldName);
                                                    if (value != null && !value.trim().isEmpty()) {
                                                        additionalData.put(fieldName, value.trim());
                                                    }
                                                }
                                            }

                                            boolean rowIsNonRecurring = isRecurringStr != null
                                                    && !isRecurringStr.trim().isEmpty()
                                                    && ("false".equals(isRecurringStr.trim().toLowerCase())
                                                    || "0".equals(isRecurringStr.trim())
                                                    || "no".equals(isRecurringStr.trim().toLowerCase())
                                                    || "n".equals(isRecurringStr.trim().toLowerCase()));
                                            java.util.Set<String> RECURRING_ONLY_FIELDS = java.util.Set.of(
                                                    "recurring_reference", "recurring_type", "frequency_value");
                                            for (RtaFieldMapping mapping : mappings) {
                                                if (Boolean.TRUE.equals(mapping.getRequired())) {
                                                    if (rowIsNonRecurring && RECURRING_ONLY_FIELDS.contains(mapping.getCanonicalField())) {
                                                        continue;
                                                    }
                                                    String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                                    if (val == null || val.trim().isEmpty()) {
                                                        rowErrors.add("Empty value for required field '" + mapping.getCanonicalField() + "'");
                                                        txnStatus = "FAILED";
                                                    } else {
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
                                                                    if (profile.getDateFormat() != null) {
                                                                        try {
                                                                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                                            LocalDate.parse(val.trim(), fmt);
                                                                        } catch (Exception e) {
                                                                            rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                                            txnStatus = "FAILED";
                                                                        }
                                                                    }
                                                                    break;
                                                                case "BOOLEAN":
                                                                    String boolVal = val.trim().toLowerCase();
                                                                    if (!boolVal.equals("true") && !boolVal.equals("false")
                                                                            && !boolVal.equals("1") && !boolVal.equals("0")
                                                                            && !boolVal.equals("yes") && !boolVal.equals("no")
                                                                            && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                                        rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                                        txnStatus = "FAILED";
                                                                    }
                                                                    break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Long amountCents = null;
                                            if (amountStr != null && !amountStr.trim().isEmpty()) {
                                                try {
                                                    double amt = Double.parseDouble(amountStr.trim());
                                                    amountCents = Math.round(amt * 100);
                                                } catch (NumberFormatException ignored) {
                                                }
                                            }

                                            LocalDate txnDate = null;
                                            if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile.getDateFormat() != null) {
                                                try {
                                                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                                    txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                                } catch (Exception ignored) {
                                                }
                                            }

                                            Boolean isRecurring = null;
                                            if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                                String val = isRecurringStr.trim().toLowerCase();
                                                isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                                            }

                                            Integer freqValue = null;
                                            if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                                try {
                                                    freqValue = Integer.parseInt(freqValueStr.trim());
                                                } catch (NumberFormatException ignored) {
                                                }
                                            }

                                            RtaTransaction txn = new RtaTransaction();
                                            txn.setMerchantId(merchantId);
                                            txn.setBatchSeq(rowIdx + 1);
                                            txn.setMerchantCustomer(customerRef);
                                            txn.setMaskedPan(accountNum);
                                            txn.setMerchantBillingRef(bankCode);
                                            txn.setAmount(amountCents);
                                            txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                                            txn.setActualBillingDate(txnDate);
                                            txn.setIsRecurring(isRecurring);
                                            txn.setRecurringIndicator(recurringType);
                                            txn.setFrequencyValue(freqValue);
                                            txn.setRecurringReference(recurringRef);
                                            txn.setTransactionDescription("start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                            txn.setStatus(txnStatus);
                                            txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                            txn.setCreatedAt(LocalDateTime.now());

                                            if (!additionalData.isEmpty()) {
                                                try {
                                                    txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                                } catch (Exception ignored) {
                                                }
                                            }

                                            transactionsToSave.add(txn);

                                            if ("PENDING".equals(txnStatus)) {
                                                successCount++;
                                                if (amountCents != null) {
                                                    totalAmountCents += amountCents;
                                                }
                                            } else {
                                                failCount++;
                                            }
                                        }

                                        if (failCount > 0) {
                                            if (successCount == 0) {
                                                validationStatus = "INVALID_FILE_CONTENT";
                                                validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                                            } else {
                                                validationStatus = "PARTIAL";
                                                validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                validationStatus = "VALIDATION_ERROR";
                                validationRemark = "Error during Excel validation: " + e.getMessage();
                            }
                        }
                    }
                }
            }

            // 1. Save or update uploaded file hash record for duplicate detection
            Optional<RtaUploadedFileHash> existingHashRecord = uploadedFileHashRepository
                    .findByMerchantIdAndFileHash(merchantId, fileHash);
            RtaUploadedFileHash uploadedFileHash;
            if (existingHashRecord.isPresent()) {
                // Update existing record (re-upload attempt)
                uploadedFileHash = existingHashRecord.get();
                uploadedFileHash.setOriginalFilename(originalFilename);
                uploadedFileHash.setStoredFilename(storedFileName);
                uploadedFileHash.setUploadedAt(LocalDateTime.now());
                uploadedFileHash.setStatus(validationStatus);
                uploadedFileHash.setUploadCount(
                        (uploadedFileHash.getUploadCount() != null ? uploadedFileHash.getUploadCount() : 1) + 1);
                uploadedFileHash.setValidationRemark(validationRemark);
                uploadedFileHash.setCreatedBy(createdBy);
            } else {
                // First upload — insert new record
                uploadedFileHash = new RtaUploadedFileHash();
                uploadedFileHash.setMerchantId(merchantId);
                uploadedFileHash.setOriginalFilename(originalFilename);
                uploadedFileHash.setStoredFilename(storedFileName);
                uploadedFileHash.setFileHash(fileHash);
                uploadedFileHash.setUploadedAt(LocalDateTime.now());
                uploadedFileHash.setStatus(validationStatus);
                uploadedFileHash.setUploadCount(1);
                uploadedFileHash.setValidationRemark(validationRemark);
                uploadedFileHash.setCreatedBy(createdBy);
                uploadedFileHash.setSizeBytes(file.getSize());
            }
            uploadedFileHashRepository.save(uploadedFileHash);

            // Determine if validation completely failed — no valid records to process
            boolean isCompleteFailure = "NO_FILE_PROFILE".equals(validationStatus)
                    || "WRONG_FILE_FORMAT".equals(validationStatus)
                    || "NO_FIELD_MAPPING".equals(validationStatus)
                    || "MISSING_HEADER".equals(validationStatus)
                    || "VALIDATION_FAILED".equals(validationStatus)
                    || "VALIDATION_ERROR".equals(validationStatus)
                    || "INVALID_FILE_CONTENT".equals(validationStatus)
                    || successCount == 0;

            // Only save incoming batch file + transactions if validation passed (at least some records valid)
            Long savedBatchFileId = null;
            int duplicateCount = 0;
            List<String> duplicateTransactions = new ArrayList<>();

            if (!isCompleteFailure) {
                // 2. Create RtaIncomingBatchFile record (NO batch_id assigned at upload time)
                RtaIncomingBatchFile incomingFile = new RtaIncomingBatchFile();
                incomingFile.setMerchantId(merchantId);
                // batchId is left NULL — will be assigned when "run batch" executes
                incomingFile.setOriginalFilename(originalFilename);
                incomingFile.setStoredFilename(storedFileName);
                incomingFile.setStorageUri(storageUri);
                incomingFile.setSizeBytes(file.getSize());
                incomingFile.setTotalRecordCount(totalRecordCount);
                incomingFile.setSuccessCount(successCount);
                incomingFile.setFailCount(failCount);
                incomingFile.setFileStatus(validationStatus);
                incomingFile.setBatchStatus("PENDING");
                incomingFile.setCreateBy(createdBy);
                incomingFile.setCreatedAt(LocalDateTime.now());
                incomingFile.setTransactionRecordRemark(validationRemark);
                incomingFile.setInsertionStatus("INSERTING");
                RtaIncomingBatchFile savedFile = incomingFileRepository.save(incomingFile);
                savedBatchFileId = savedFile.getBatchFileId();

                // 3. Save transaction records (NO batch assigned, linked to batchFileId only)
                // Handle duplicate transaction constraint violations
                for (RtaTransaction txn : transactionsToSave) {
                    txn.setBatchFileId(savedFile.getBatchFileId());
                    // batch is left NULL — will be assigned when "run batch" executes
                    try {
                        transactionRepository.save(txn);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // Duplicate transaction detected
                        duplicateCount++;
                        duplicateTransactions.add("Row " + txn.getBatchSeq() + ": " + txn.getMerchantCustomer() + " / "
                                + (txn.getAmount() != null ? txn.getAmount() / 100.0 : "N/A") + " / " + txn.getActualBillingDate());
                        // Update counts
                        if ("PENDING".equals(txn.getStatus())) {
                            successCount--;
                            failCount++;
                        }
                    }
                }

                // Update incoming file if there were duplicates
                if (duplicateCount > 0) {
                    savedFile.setSuccessCount(successCount);
                    savedFile.setFailCount(failCount);
                    if (successCount == 0 && totalRecordCount > 0) {
                        validationStatus = "VALIDATION_FAILED";
                        // All records failed after dedup — remove incoming file record
                        incomingFileRepository.delete(savedFile);
                        savedBatchFileId = null;
                        // Update hash record status
                        uploadedFileHash.setStatus(validationStatus);
                        uploadedFileHashRepository.save(uploadedFileHash);
                    } else if (failCount > 0) {
                        validationStatus = "PARTIAL";
                        savedFile.setFileStatus(validationStatus);
                        String dupRemark = duplicateCount + " duplicate transaction(s) detected and skipped";
                        savedFile.setTransactionRecordRemark(
                                validationRemark != null ? validationRemark + "; " + dupRemark : dupRemark);
                        incomingFileRepository.save(savedFile);
                    }
                }

                // Mark insertion as COMPLETED — file is now eligible for batch scheduler
                if (savedBatchFileId != null) {
                    savedFile.setInsertionStatus("COMPLETED");
                    incomingFileRepository.save(savedFile);
                }
            }

            // Response
            Map<String, Object> response = new HashMap<>();
            if (isCompleteFailure && duplicateCount == 0) {
                response.put("message", "File uploaded but validation failed: " + validationRemark);
            } else if (failCount == 0) {
                response.put("message", "File received and validated successfully");
            } else if (failCount == totalRecordCount) {
                response.put("message", "File received but all records failed validation");
            } else {
                response.put("message", "File received with " + failCount + " failed records out of " + totalRecordCount);
            }
            if (savedBatchFileId != null) {
                response.put("batchFileId", savedBatchFileId);
            }
            response.put("hashId", uploadedFileHash.getId());
            response.put("fileName", renamedFilename);
            response.put("originalFileName", originalFilename);
            response.put("fileHash", fileHash);
            response.put("sizeBytes", file.getSize());
            response.put("status", validationStatus);
            response.put("totalRecords", totalRecordCount);
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            response.put("totalAmount", totalAmountCents / 100.0);
            if (duplicateCount > 0) {
                response.put("duplicateTransactionCount", duplicateCount);
                response.put("duplicateTransactions", duplicateTransactions);
            }
            if (!validationErrors.isEmpty()) {
                response.put("validationErrors", validationErrors);
            }

            // --- Audit logging ---
            // Log user upload activity
            auditLogService.logUserActivity("UPLOAD_FILE", createdBy, renamedFilename,
                    "User '" + createdBy + "' uploaded file '" + originalFilename + "' for merchant '" + merchantId + "' — Status: " + validationStatus,
                    validationStatus, null);
            // Log system incoming-batch activity
            auditLogService.logSystemActivity("INCOMING_BATCH", merchantId,
                    "Received batch file '" + originalFilename + "' from merchant '" + merchantId + "' — "
                    + totalRecordCount + " records, status: " + validationStatus,
                    validationStatus);
            // Log decrypt status if encrypted
            if (encrypted) {
                auditLogService.logSystemActivity("DECRYPT_FILE", merchantId,
                        "Decrypted file '" + originalFilename + "' for merchant '" + merchantId + "'",
                        "SUCCESS");
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
     * filtered by merchantId). The "batchId" in the response refers to the
     * rta_batch ID, assigned when the batch scheduler runs.
     */
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> getIncomingFiles(
            @RequestParam(value = "merchantId", required = false) String merchantId) {
        List<RtaIncomingBatchFile> files;
        if (merchantId != null && !merchantId.isBlank()) {
            files = incomingFileRepository.findByMerchantId(merchantId);
        } else {
            files = incomingFileRepository.findAll();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (RtaIncomingBatchFile f : files) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("batchFileId", f.getBatchFileId());
            dto.put("merchantId", f.getMerchantId());
            // batch_id references rta_batch (assigned when batch job executes)
            dto.put("batchId", f.getBatchId());
            dto.put("originalFilename", f.getOriginalFilename());
            dto.put("storedFilename", f.getStoredFilename());
            dto.put("storageUri", f.getStorageUri());
            dto.put("sizeBytes", f.getSizeBytes());
            dto.put("totalRecordCount", f.getTotalRecordCount());
            dto.put("successCount", f.getSuccessCount());
            dto.put("failCount", f.getFailCount());
            dto.put("fileStatus", f.getFileStatus());
            dto.put("batchStatus", f.getBatchStatus());
            dto.put("createBy", f.getCreateBy());
            dto.put("createdAt", f.getCreatedAt());
            dto.put("lastModifiedAt", f.getLastModifiedAt());
            dto.put("lastModifiedBy", f.getLastModifiedBy());

            // Find authorization batch ID via transactions (the Batch Maintenance ID)
            List<Long> authBatchIds = transactionRepository.findDistinctAuthBatchIdsByBatchFileId(f.getBatchFileId());
            if (!authBatchIds.isEmpty()) {
                dto.put("authBatchId", authBatchIds.get(0));
                authBatchRepository.findById(authBatchIds.get(0)).ifPresent(ab -> {
                    dto.put("authBatchStatus", ab.getBatchStatus());
                    dto.put("authBatchReference", ab.getBatchReference());
                });
            }

            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/incoming/upload-history - List all upload attempts from
     * rta_uploaded_file_hash table. This includes ALL uploads (passed and
     * failed validation). Used by the Upload Batch File page to show history.
     */
    @GetMapping("/upload-history")
    public ResponseEntity<List<Map<String, Object>>> getUploadHistory() {
        List<RtaUploadedFileHash> hashes = uploadedFileHashRepository.findAllByOrderByUploadedAtDesc();

        List<Map<String, Object>> result = new ArrayList<>();
        for (RtaUploadedFileHash h : hashes) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", h.getId());
            dto.put("merchantId", h.getMerchantId());
            dto.put("originalFilename", h.getOriginalFilename());
            dto.put("storedFilename", h.getStoredFilename());
            dto.put("fileHash", h.getFileHash());
            dto.put("uploadedAt", h.getUploadedAt());
            dto.put("status", h.getStatus());
            dto.put("uploadCount", h.getUploadCount());
            dto.put("validationRemark", h.getValidationRemark());
            dto.put("createdBy", h.getCreatedBy());
            dto.put("sizeBytes", h.getSizeBytes());
            result.add(dto);
        }
        return ResponseEntity.ok(result);
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
        int fail = transactionRepository.countByBatchBatchIdAndStatus(batchId, "FAILED");
        int success = total - fail;
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
     * GET /api/incoming/file-summary/{batchFileId} - Get file summary with
     * counts and total amount (works whether or not a batch has been assigned).
     */
    @GetMapping("/file-summary/{batchFileId}")
    public ResponseEntity<?> getFileSummary(@PathVariable Long batchFileId) {
        Optional<RtaIncomingBatchFile> fileOpt = incomingFileRepository.findById(batchFileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RtaIncomingBatchFile incomingFile = fileOpt.get();
        int total = transactionRepository.countByBatchFileId(batchFileId);
        int fail = transactionRepository.countByBatchFileIdAndStatus(batchFileId, "FAILED");
        int success = total - fail;
        long totalAmountCents = 0;
        try {
            totalAmountCents = transactionRepository.sumAmountByBatchFileIdAndStatusSuccess(batchFileId);
        } catch (Exception ignored) {
        }

        // Use totalRecordCount from incoming file if no transactions created
        if (total == 0 && incomingFile.getTotalRecordCount() != null) {
            total = incomingFile.getTotalRecordCount();
        }

        // batch_id references rta_batch (assigned when batch job executes)
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batchFileId", batchFileId);
        summary.put("batchId", incomingFile.getBatchId());
        summary.put("fileName", incomingFile.getOriginalFilename());
        summary.put("merchantId", incomingFile.getMerchantId());
        summary.put("status", incomingFile.getFileStatus());
        summary.put("totalRecords", total);
        summary.put("successCount", success);
        summary.put("failCount", fail);
        summary.put("totalAmount", totalAmountCents / 100.0);
        summary.put("createdAt", incomingFile.getCreatedAt());
        summary.put("createdBy", incomingFile.getCreateBy());
        summary.put("validationRemark", incomingFile.getTransactionRecordRemark());

        // Find authorization batch ID via transactions (the Batch Maintenance ID)
        List<Long> authBatchIds = transactionRepository.findDistinctAuthBatchIdsByBatchFileId(batchFileId);
        if (!authBatchIds.isEmpty()) {
            summary.put("authBatchId", authBatchIds.get(0));
            authBatchRepository.findById(authBatchIds.get(0)).ifPresent(ab -> {
                summary.put("authBatchStatus", ab.getBatchStatus());
                summary.put("authBatchReference", ab.getBatchReference());
            });
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/incoming/file-transactions/{batchFileId} - Get transactions for
     * a batch file. Optional query param: status (e.g., FAILED to get only
     * failed records).
     */
    @GetMapping("/file-transactions/{batchFileId}")
    public ResponseEntity<?> getTransactionsByFile(
            @PathVariable Long batchFileId,
            @RequestParam(value = "status", required = false) String status) {

        List<RtaTransaction> transactions;
        if (status != null && !status.isBlank()) {
            transactions = transactionRepository.findByBatchFileIdAndStatus(batchFileId, status.toUpperCase());
        } else {
            transactions = transactionRepository.findByBatchFileId(batchFileId);
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

    /**
     * POST /api/incoming/retry-validation/{batchFileId} - Retry validation for
     * a batch file that is still in RECEIVED status.
     */
    @PostMapping("/retry-validation/{batchFileId}")
    public ResponseEntity<?> retryValidation(@PathVariable Long batchFileId) {
        try {
            // Find the incoming batch file
            Optional<RtaIncomingBatchFile> fileOpt = incomingFileRepository.findById(batchFileId);
            if (fileOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            RtaIncomingBatchFile incomingFile = fileOpt.get();

            // Only allow retry for files with RECEIVED status
            if (!"RECEIVED".equalsIgnoreCase(incomingFile.getFileStatus())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Retry not allowed",
                        "detail", "File has already been processed. Current status: " + incomingFile.getFileStatus()));
            }

            // Find the associated batch (if any — may be null for files uploaded after V8)
            String merchantId = incomingFile.getMerchantId();
            String storagePath = incomingFile.getStorageUri();

            // Check if file exists in MinIO
            String objectName = minioStorageService.extractObjectName(storagePath);
            if (!minioStorageService.fileExists(objectName)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "File not found",
                        "detail", "The batch file no longer exists in storage: " + storagePath));
            }

            // Download file content from MinIO for validation
            byte[] fileContent = minioStorageService.downloadFileAsBytes(objectName);

            // Re-run validation
            List<String> validationErrors = new ArrayList<>();
            String validationStatus = "RECEIVED";
            String validationRemark = null;
            int totalRecordCount = 0;
            int successCount = 0;
            int failCount = 0;
            long totalAmountCents = 0;
            List<RtaTransaction> transactionsToSave = new ArrayList<>();

            String lowerName = incomingFile.getOriginalFilename().toLowerCase();

            if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt")) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(fileContent)))) {
                    List<String> allLines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allLines.add(line);
                    }

                    if (!allLines.isEmpty()) {
                        // Determine delimiter from merchant's file profile
                        String delimiter = ",";
                        var profileOpt = fileProfileService.getActiveProfile(merchantId);
                        RtaFileProfile profile = null;
                        List<RtaFieldMapping> mappings = new ArrayList<>();

                        if (profileOpt.isPresent()) {
                            profile = profileOpt.get();
                            if (profile.getFieldDelimiter() != null) {
                                delimiter = profile.getFieldDelimiter();
                            }
                            mappings = fileProfileService.getFieldMappings(profile.getProfileId());
                        } else {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", "No file profile",
                                    "detail", "No active file profile found for merchant: " + merchantId));
                        }

                        if (mappings.isEmpty()) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", "No field mappings",
                                    "detail", "No field mappings configured for merchant: " + merchantId));
                        }

                        String[] headerRow = allLines.get(0).split(
                                java.util.regex.Pattern.quote(delimiter), -1);

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

                        validationErrors = fileProfileService.validateFile(merchantId, headerRow, dataRows);
                        totalRecordCount = dataRows.size();

                        boolean headerValid = validationErrors.stream()
                                .noneMatch(e -> e.startsWith("Missing required column"));

                        if (!headerValid || !validationErrors.isEmpty() && validationErrors.stream()
                                .anyMatch(e -> e.contains("No active file profile") || e.contains("No field mappings"))) {
                            validationStatus = "VALIDATION_FAILED";
                            validationRemark = String.join("\n",
                                    validationErrors.subList(0, Math.min(validationErrors.size(), 50)));
                        } else {
                            validationStatus = "VALIDATED";
                            ObjectMapper objectMapper = new ObjectMapper();

                            for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                                String[] row = dataRows.get(rowIdx);
                                List<String> rowErrors = new ArrayList<>();
                                String txnStatus = "PENDING";

                                String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                                String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                                String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                                String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                                String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                                String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                                String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");
                                String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                                String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                                String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                                String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                                Map<String, String> additionalData = new LinkedHashMap<>();
                                for (RtaFieldMapping mapping : mappings) {
                                    String fieldName = mapping.getCanonicalField();
                                    if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                        String value = getFieldValue(row, headerMap, mappings, fieldName);
                                        if (value != null && !value.trim().isEmpty()) {
                                            additionalData.put(fieldName, value.trim());
                                        }
                                    }
                                }

                                for (RtaFieldMapping mapping : mappings) {
                                    if (Boolean.TRUE.equals(mapping.getRequired())) {
                                        String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                        if (val == null || val.trim().isEmpty()) {
                                            rowErrors.add("Missing required field: " + mapping.getCanonicalField());
                                            txnStatus = "FAILED";
                                        } else {
                                            String dataType = mapping.getDataType();
                                            if (dataType != null) {
                                                switch (dataType.toUpperCase()) {
                                                    case "INTEGER":
                                                        try {
                                                            Integer.parseInt(val.trim());
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
                                                    case "BOOLEAN":
                                                        String boolVal = val.trim().toLowerCase();
                                                        if (!boolVal.equals("true") && !boolVal.equals("false")
                                                                && !boolVal.equals("1") && !boolVal.equals("0")
                                                                && !boolVal.equals("yes") && !boolVal.equals("no")
                                                                && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                            rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                            txnStatus = "FAILED";
                                                        }
                                                        break;
                                                }
                                            }
                                        }
                                    }
                                }

                                Long amountCents = null;
                                if (amountStr != null && !amountStr.trim().isEmpty()) {
                                    try {
                                        double amt = Double.parseDouble(amountStr.trim());
                                        amountCents = Math.round(amt * 100);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }

                                LocalDate txnDate = null;
                                if (txnDateStr != null && !txnDateStr.trim().isEmpty() && profile != null) {
                                    try {
                                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(profile.getDateFormat());
                                        txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                    } catch (Exception ignored) {
                                    }
                                }

                                Boolean isRecurring = null;
                                if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                    String val = isRecurringStr.trim().toLowerCase();
                                    isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                                }

                                Integer freqValue = null;
                                if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                    try {
                                        freqValue = Integer.parseInt(freqValueStr.trim());
                                    } catch (NumberFormatException ignored) {
                                    }
                                }

                                RtaTransaction txn = new RtaTransaction();
                                txn.setMerchantId(merchantId);
                                txn.setBatchSeq(rowIdx + 1);
                                txn.setMerchantCustomer(customerRef);
                                txn.setMaskedPan(accountNum);
                                txn.setMerchantBillingRef(bankCode);
                                txn.setAmount(amountCents);
                                txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                                txn.setActualBillingDate(txnDate);
                                txn.setIsRecurring(isRecurring);
                                txn.setRecurringIndicator(recurringType);
                                txn.setFrequencyValue(freqValue);
                                txn.setRecurringReference(recurringRef);
                                txn.setTransactionDescription("start=" + (startDateStr != null ? startDateStr.trim() : ""));
                                txn.setStatus(txnStatus);
                                txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                                txn.setCreatedAt(LocalDateTime.now());

                                if (!additionalData.isEmpty()) {
                                    try {
                                        txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                    } catch (Exception ignored) {
                                    }
                                }

                                transactionsToSave.add(txn);

                                if ("PENDING".equals(txnStatus)) {
                                    successCount++;
                                    if (amountCents != null) {
                                        totalAmountCents += amountCents;
                                    }
                                } else {
                                    failCount++;
                                }
                            }

                            if (failCount > 0) {
                                if (successCount == 0) {
                                    validationStatus = "INVALID_FILE_CONTENT";
                                    validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                                } else {
                                    validationStatus = "PARTIAL";
                                    validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Validation error",
                            "detail", "Error during validation: " + e.getMessage()));
                }
            } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                // Process Excel files (xlsx/xls)
                try (InputStream is = new ByteArrayInputStream(fileContent); Workbook workbook = lowerName.endsWith(".xlsx") ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {

                    Sheet sheet = workbook.getSheetAt(0);
                    if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Empty file",
                                "detail", "Excel file is empty or has no sheets"));
                    }

                    // Get file profile and mappings
                    var profileOpt = fileProfileService.getActiveProfile(merchantId);
                    RtaFileProfile profile = null;
                    List<RtaFieldMapping> mappings = new ArrayList<>();

                    if (profileOpt.isPresent()) {
                        profile = profileOpt.get();
                        mappings = fileProfileService.getFieldMappings(profile.getProfileId());
                    } else {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "No file profile",
                                "detail", "No active file profile found for merchant: " + merchantId));
                    }

                    if (mappings.isEmpty()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "No field mappings",
                                "detail", "No field mappings configured for merchant: " + merchantId));
                    }

                    // Read header row
                    Row headerRow = sheet.getRow(0);
                    if (headerRow == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Invalid file",
                                "detail", "Excel file has no header row"));
                    }

                    // Build header array and map
                    int headerCellCount = headerRow.getLastCellNum();
                    String[] headerArr = new String[headerCellCount];
                    Map<String, Integer> headerMap = new HashMap<>();
                    DataFormatter formatter = new DataFormatter();

                    for (int i = 0; i < headerCellCount; i++) {
                        Cell cell = headerRow.getCell(i);
                        String val = (cell != null) ? formatter.formatCellValue(cell).trim() : "";
                        headerArr[i] = val;
                        headerMap.put(val.toLowerCase(), i);
                    }

                    // Build data rows
                    List<String[]> dataRows = new ArrayList<>();
                    for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                        Row row = sheet.getRow(rowIdx);
                        if (row != null) {
                            String[] rowData = new String[headerCellCount];
                            boolean hasData = false;
                            for (int colIdx = 0; colIdx < headerCellCount; colIdx++) {
                                Cell cell = row.getCell(colIdx);
                                String val = (cell != null) ? formatter.formatCellValue(cell).trim() : "";
                                rowData[colIdx] = val;
                                if (!val.isEmpty()) {
                                    hasData = true;
                                }
                            }
                            if (hasData) {
                                dataRows.add(rowData);
                            }
                        }
                    }

                    // Validate headers
                    validationErrors = fileProfileService.validateFile(merchantId, headerArr, dataRows);
                    totalRecordCount = dataRows.size();

                    boolean headerValid = validationErrors.stream()
                            .noneMatch(e -> e.startsWith("Missing required column"));

                    if (!headerValid || !validationErrors.isEmpty() && validationErrors.stream()
                            .anyMatch(e -> e.contains("No active file profile") || e.contains("No field mappings"))) {
                        validationStatus = "VALIDATION_FAILED";
                        validationRemark = String.join("\n",
                                validationErrors.subList(0, Math.min(validationErrors.size(), 50)));
                    } else {
                        // Process each data row
                        validationStatus = "VALIDATED";
                        ObjectMapper objectMapper = new ObjectMapper();
                        final RtaFileProfile finalProfile = profile;

                        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
                            String[] row = dataRows.get(rowIdx);
                            List<String> rowErrors = new ArrayList<>();
                            String txnStatus = "PENDING";

                            // Extract field values
                            String customerRef = getFieldValue(row, headerMap, mappings, "customer_reference");
                            String accountNum = getFieldValue(row, headerMap, mappings, "account_num");
                            String bankCode = getFieldValue(row, headerMap, mappings, "bank_code");
                            String amountStr = getFieldValue(row, headerMap, mappings, "amount");
                            String currencyVal = getFieldValue(row, headerMap, mappings, "currency");
                            String txnDateStr = getFieldValue(row, headerMap, mappings, "transaction_date");
                            String startDateStr = getFieldValue(row, headerMap, mappings, "start_date");
                            String isRecurringStr = getFieldValue(row, headerMap, mappings, "is_recurring");
                            String recurringType = getFieldValue(row, headerMap, mappings, "recurring_type");
                            String freqValueStr = getFieldValue(row, headerMap, mappings, "frequency_value");
                            String recurringRef = getFieldValue(row, headerMap, mappings, "recurring_reference");

                            // Extract additional/custom fields
                            Map<String, String> additionalData = new LinkedHashMap<>();
                            for (RtaFieldMapping mapping : mappings) {
                                String fieldName = mapping.getCanonicalField();
                                if (!FileProfileService.REQUIRED_CANONICAL_FIELDS.contains(fieldName)) {
                                    String value = getFieldValue(row, headerMap, mappings, fieldName);
                                    if (value != null && !value.trim().isEmpty()) {
                                        additionalData.put(fieldName, value.trim());
                                    }
                                }
                            }

                            // Validate required fields
                            for (RtaFieldMapping mapping : mappings) {
                                if (Boolean.TRUE.equals(mapping.getRequired())) {
                                    String val = getFieldValue(row, headerMap, mappings, mapping.getCanonicalField());
                                    if (val == null || val.trim().isEmpty()) {
                                        rowErrors.add("Missing required field: " + mapping.getCanonicalField());
                                        txnStatus = "FAILED";
                                    } else {
                                        String dataType = mapping.getDataType();
                                        if (dataType != null) {
                                            switch (dataType.toUpperCase()) {
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
                                                    if (finalProfile != null && finalProfile.getDateFormat() != null) {
                                                        try {
                                                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(finalProfile.getDateFormat());
                                                            LocalDate.parse(val.trim(), fmt);
                                                        } catch (Exception e) {
                                                            rowErrors.add("Invalid date for '" + mapping.getCanonicalField() + "': " + val);
                                                            txnStatus = "FAILED";
                                                        }
                                                    }
                                                    break;
                                                case "BOOLEAN":
                                                    String boolVal = val.trim().toLowerCase();
                                                    if (!boolVal.equals("true") && !boolVal.equals("false")
                                                            && !boolVal.equals("1") && !boolVal.equals("0")
                                                            && !boolVal.equals("yes") && !boolVal.equals("no")
                                                            && !boolVal.equals("y") && !boolVal.equals("n")) {
                                                        rowErrors.add("Invalid boolean for '" + mapping.getCanonicalField() + "': " + val);
                                                        txnStatus = "FAILED";
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
                                } catch (NumberFormatException ignored) {
                                }
                            }

                            // Parse transaction date
                            LocalDate txnDate = null;
                            if (txnDateStr != null && !txnDateStr.trim().isEmpty() && finalProfile != null) {
                                try {
                                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern(finalProfile.getDateFormat());
                                    txnDate = LocalDate.parse(txnDateStr.trim(), fmt);
                                } catch (Exception ignored) {
                                }
                            }

                            // Parse is_recurring boolean
                            Boolean isRecurring = null;
                            if (isRecurringStr != null && !isRecurringStr.trim().isEmpty()) {
                                String val = isRecurringStr.trim().toLowerCase();
                                isRecurring = "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
                            }

                            // Parse frequency_value integer
                            Integer freqValue = null;
                            if (freqValueStr != null && !freqValueStr.trim().isEmpty()) {
                                try {
                                    freqValue = Integer.parseInt(freqValueStr.trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }

                            // Build transaction entity
                            RtaTransaction txn = new RtaTransaction();
                            txn.setMerchantId(merchantId);
                            txn.setBatchSeq(rowIdx + 1);
                            txn.setMerchantCustomer(customerRef);
                            txn.setMaskedPan(accountNum);
                            txn.setMerchantBillingRef(bankCode);
                            txn.setAmount(amountCents);
                            txn.setCurrency(currencyVal != null ? currencyVal.trim() : "");
                            txn.setActualBillingDate(txnDate);
                            txn.setIsRecurring(isRecurring);
                            txn.setRecurringIndicator(recurringType);
                            txn.setFrequencyValue(freqValue);
                            txn.setRecurringReference(recurringRef);
                            txn.setTransactionDescription("start=" + (startDateStr != null ? startDateStr.trim() : ""));
                            txn.setStatus(txnStatus);
                            txn.setRemark(rowErrors.isEmpty() ? null : String.join("; ", rowErrors));
                            txn.setCreatedAt(LocalDateTime.now());

                            if (!additionalData.isEmpty()) {
                                try {
                                    txn.setAdditionalData(objectMapper.writeValueAsString(additionalData));
                                } catch (Exception ignored) {
                                }
                            }

                            transactionsToSave.add(txn);

                            if ("PENDING".equals(txnStatus)) {
                                successCount++;
                                if (amountCents != null) {
                                    totalAmountCents += amountCents;
                                }
                            } else {
                                failCount++;
                            }
                        }

                        if (failCount > 0) {
                            if (successCount == 0) {
                                validationStatus = "INVALID_FILE_CONTENT";
                                validationRemark = "All " + totalRecordCount + " record(s) failed validation — no valid transactions";
                            } else {
                                validationStatus = "PARTIAL";
                                validationRemark = failCount + " out of " + totalRecordCount + " records failed validation";
                            }
                        }
                    }
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Validation error",
                            "detail", "Error during Excel validation: " + e.getMessage()));
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unsupported file type",
                        "detail", "Retry validation is only supported for CSV/TXT/XLSX/XLS files"));
            }

            // Update incoming file record
            incomingFile.setFileStatus(validationStatus);
            incomingFile.setTotalRecordCount(totalRecordCount);
            incomingFile.setSuccessCount(successCount);
            incomingFile.setFailCount(failCount);
            incomingFile.setTransactionRecordRemark(validationRemark);
            incomingFile.setLastModifiedAt(LocalDateTime.now());
            incomingFileRepository.save(incomingFile);

            // Mark insertion in progress, save transactions, then mark completed
            incomingFile.setInsertionStatus("INSERTING");
            incomingFileRepository.save(incomingFile);

            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatchFileId(batchFileId);
            }
            transactionRepository.saveAll(transactionsToSave);

            incomingFile.setInsertionStatus("COMPLETED");
            incomingFileRepository.save(incomingFile);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Validation retry completed");
            response.put("batchFileId", batchFileId);
            response.put("status", validationStatus);
            response.put("totalRecords", totalRecordCount);
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            if (validationRemark != null) {
                response.put("validationRemark", validationRemark);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error", "detail", e.getMessage()));
        }
    }

    /**
     * Generate SHA-256 hash of file content. Used for duplicate file detection.
     */
    private String generateSHA256Hash(byte[] fileContent) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(fileContent);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
