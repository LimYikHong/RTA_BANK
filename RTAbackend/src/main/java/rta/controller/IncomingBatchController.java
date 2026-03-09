package rta.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import rta.entity.RtaBatch;
import rta.entity.RtaBatchFile;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.entity.MerchantInfo;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaBatchFileRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;
import rta.repository.MerchantInfoRepository;
import rta.service.FileProfileService;
import rta.service.MinioStorageService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final RtaBatchFileRepository batchFileRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaTransactionRepository transactionRepository;
    private final MerchantInfoRepository merchantInfoRepository;
    private final FileProfileService fileProfileService;
    private final MinioStorageService minioStorageService;

    private static final String UPLOAD_DIR = "incoming-uploads";

    public IncomingBatchController(RtaBatchRepository batchRepository,
            RtaBatchFileRepository batchFileRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            FileProfileService fileProfileService,
            MinioStorageService minioStorageService) {
        this.batchRepository = batchRepository;
        this.batchFileRepository = batchFileRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.fileProfileService = fileProfileService;
        this.minioStorageService = minioStorageService;
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
            @RequestParam(value = "originalFileName", required = false) String originalFileNameParam) {
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

            // Validate file extension using renamed filename
            String lowerName = renamedFilename.toLowerCase();
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

            // Generate SHA-256 hash of file content to detect duplicates
            String fileHash = generateSHA256Hash(file.getBytes());

            // Check for duplicate file
            Optional<RtaBatchFile> existingFile = batchFileRepository.findByMerchantIdAndFileHash(merchantId, fileHash);
            if (existingFile.isPresent()) {
                RtaBatchFile duplicate = existingFile.get();
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Duplicate file detected",
                        "detail", "This file has already been uploaded.",
                        "duplicateFileInfo", Map.of(
                                "id", duplicate.getId(),
                                "originalFilename", duplicate.getOriginalFilename(),
                                "uploadedAt", duplicate.getUploadedAt() != null ? duplicate.getUploadedAt().toString() : "N/A",
                                "status", duplicate.getStatus() != null ? duplicate.getStatus() : "N/A"
                        )
                ));
            }

            // Generate stored filename: timestamp + renamed filename (merchantId_datetime format)
            String storedFileName = System.currentTimeMillis() + "_" + renamedFilename;
            String objectName = UPLOAD_DIR + "/" + storedFileName;

            // Upload file to MinIO
            String storageUri = minioStorageService.uploadFile(objectName, file);

            // Download file content for validation
            byte[] fileContent = minioStorageService.downloadFileAsBytes(objectName);

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
                                                String txnStatus = "SUCCESS";

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
                                            String txnStatus = "SUCCESS";

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
                                                    String txnStatus = "SUCCESS";

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
                                            String txnStatus = "SUCCESS";

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
                                validationRemark = "Error during Excel validation: " + e.getMessage();
                            }
                        }
                    }
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

            // 2. Save batch file record with hash for duplicate detection
            RtaBatchFile batchFile = new RtaBatchFile();
            batchFile.setMerchantId(merchantId);
            batchFile.setOriginalFilename(originalFilename);
            batchFile.setStoredFilename(storedFileName);
            batchFile.setFileHash(fileHash);
            batchFile.setUploadedAt(LocalDateTime.now());
            batchFile.setStatus(validationStatus);
            batchFileRepository.save(batchFile);

            // 3. Save transaction records linked to the batch
            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatch(savedBatch);
                txn.setBatchFileId(0L); // will update after incoming file is created
            }

            // 4. Create RtaIncomingBatchFile record
            RtaIncomingBatchFile incomingFile = new RtaIncomingBatchFile();
            incomingFile.setMerchantId(merchantId);
            incomingFile.setBatchId(savedBatch.getBatchId());
            incomingFile.setOriginalFilename(originalFilename);
            incomingFile.setStoredFilename(storedFileName);
            incomingFile.setStorageUri(storageUri);
            incomingFile.setSizeBytes(file.getSize());
            incomingFile.setTotalRecordCount(totalRecordCount);
            incomingFile.setSuccessCount(successCount);
            incomingFile.setFailCount(failCount);
            incomingFile.setFileStatus(validationStatus);
            incomingFile.setCreateBy(createdBy);
            incomingFile.setCreatedAt(LocalDateTime.now());
            incomingFile.setTransactionRecordRemark(validationRemark);
            RtaIncomingBatchFile savedFile = incomingFileRepository.save(incomingFile);

            // 5. Now update and save all transactions with the correct batchFileId
            // Handle duplicate transaction constraint violations
            int duplicateCount = 0;
            List<String> duplicateTransactions = new ArrayList<>();
            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatchFileId(savedFile.getBatchFileId());
                try {
                    transactionRepository.save(txn);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // Duplicate transaction detected
                    duplicateCount++;
                    duplicateTransactions.add("Row " + txn.getBatchSeq() + ": " + txn.getMerchantCustomer() + " / "
                            + (txn.getAmount() != null ? txn.getAmount() / 100.0 : "N/A") + " / " + txn.getActualBillingDate());
                    // Update counts
                    if ("SUCCESS".equals(txn.getStatus())) {
                        successCount--;
                        failCount++;
                    }
                }
            }

            // Update batch and incoming file if there were duplicates
            if (duplicateCount > 0) {
                savedBatch.setTotalSuccessCount(successCount);
                savedBatch.setTotalFailCount(failCount);
                if (successCount == 0 && totalRecordCount > 0) {
                    savedBatch.setStatus("VALIDATION_FAILED");
                    validationStatus = "VALIDATION_FAILED";
                } else if (failCount > 0) {
                    savedBatch.setStatus("PARTIAL");
                    validationStatus = "PARTIAL";
                }
                batchRepository.save(savedBatch);

                savedFile.setSuccessCount(successCount);
                savedFile.setFailCount(failCount);
                savedFile.setFileStatus(validationStatus);
                String dupRemark = duplicateCount + " duplicate transaction(s) detected and skipped";
                savedFile.setTransactionRecordRemark(
                        validationRemark != null ? validationRemark + "; " + dupRemark : dupRemark);
                incomingFileRepository.save(savedFile);
            }

            // Response
            Map<String, Object> response = new HashMap<>();
            response.put("message", failCount == 0
                    ? "File received and validated successfully"
                    : failCount == totalRecordCount
                            ? "File received but all records failed validation"
                            : "File received with " + failCount + " failed records out of " + totalRecordCount);
            response.put("batchId", savedBatch.getBatchId());
            response.put("batchFileId", savedFile.getBatchFileId());
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

            // Find the associated batch
            Optional<RtaBatch> batchOpt = batchRepository.findById(incomingFile.getBatchId());
            if (batchOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Batch not found",
                        "detail", "Associated batch record not found"));
            }

            RtaBatch batch = batchOpt.get();
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
                                String txnStatus = "SUCCESS";

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
                            String txnStatus = "SUCCESS";

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

            // Update batch record
            batch.setStatus(validationStatus);
            batch.setTotalCount(totalRecordCount);
            batch.setTotalSuccessCount(successCount);
            batch.setTotalFailCount(failCount);
            batchRepository.save(batch);

            // Update incoming file record
            incomingFile.setFileStatus(validationStatus);
            incomingFile.setTotalRecordCount(totalRecordCount);
            incomingFile.setSuccessCount(successCount);
            incomingFile.setFailCount(failCount);
            incomingFile.setTransactionRecordRemark(validationRemark);
            incomingFile.setLastModifiedAt(LocalDateTime.now());
            incomingFileRepository.save(incomingFile);

            // Save transactions
            for (RtaTransaction txn : transactionsToSave) {
                txn.setBatch(batch);
                txn.setBatchFileId(batchFileId);
            }
            transactionRepository.saveAll(transactionsToSave);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Validation retry completed");
            response.put("batchFileId", batchFileId);
            response.put("batchId", batch.getBatchId());
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
