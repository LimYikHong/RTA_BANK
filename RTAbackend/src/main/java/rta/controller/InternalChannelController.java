package rta.controller;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.RtaBatch;
import rta.entity.RtaBatchEncryptionKey;
import rta.event.BatchResponseEvent;
import rta.repository.RtaBatchEncryptionKeyRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaTransactionRepository;
import rta.service.InternalKeyPairService;
import rta.service.TransactionBulkInsertService;

/**
 * Internal API channel — secured by API key + IP whitelist (via
 * InternalApiSecurityFilter).
 *
 * <p>
 * Endpoints:</p>
 * <ul>
 * <li>{@code GET /api/internal/public-key} — returns this system's RSA public
 * key in PEM format</li>
 * <li>{@code POST /api/internal/batch-upload} — receives an encrypted batch
 * file from the producer system, decrypts it, mock-authorizes transactions, and
 * updates statuses</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalChannelController {

    private final InternalKeyPairService keyPairService;
    private final RtaBatchEncryptionKeyRepository encryptionKeyRepository;
    private final RtaBatchRepository batchRepository;
    private final RtaTransactionRepository transactionRepository;
    private final TransactionBulkInsertService bulkInsertService;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final double APPROVAL_RATE = 0.9;

    // ────────────────────────────────────────────────────────────────────────
    // 1. Public Key Endpoint
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Returns the RSA public key in PEM format so the producer system can
     * encrypt AES session keys with it.
     */
    @GetMapping(value = "/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPublicKey() {
        String pem = keyPairService.getPublicKeyPem();
        log.info("[InternalChannel] Public key requested and served ({} bytes)", pem.length());
        return ResponseEntity.ok(pem);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Batch File Upload Endpoint
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Receives an encrypted batch file from the producer system.
     *
     * <p>
     * Expected multipart form fields:</p>
     * <ul>
     * <li>{@code file} — the AES-256-CBC encrypted CSV file</li>
     * <li>{@code batchId} — the batch primary key</li>
     * <li>{@code merchantId} — the merchant owning the batch</li>
     * <li>{@code encryptedAesKey} — Base64-encoded RSA-encrypted AES key</li>
     * <li>{@code iv} — Base64-encoded AES-CBC IV (16 bytes)</li>
     * <li>{@code csvFilename} — original CSV filename</li>
     * <li>{@code transactionCount} — number of transactions in the CSV</li>
     * </ul>
     *
     * <p>
     * Processing:</p>
     * <ol>
     * <li>Decrypt the AES key using this system's RSA private key</li>
     * <li>Decrypt the CSV file using the AES key + IV</li>
     * <li>Parse CSV rows and mock-authorize each transaction (~90%
     * approved)</li>
     * <li>Update transaction statuses and batch status → PROCESSED</li>
     * <li>Return the authorization results as JSON</li>
     * </ol>
     */
    @PostMapping("/batch-upload")
    public ResponseEntity<?> receiveBatchUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("batchId") Long batchId,
            @RequestParam("merchantId") String merchantId,
            @RequestParam("encryptedAesKey") String encryptedAesKeyBase64,
            @RequestParam("iv") String ivBase64,
            @RequestParam("csvFilename") String csvFilename,
            @RequestParam("transactionCount") int transactionCount) {

        log.info("[InternalChannel] Batch upload received: batchId={}, merchant={}, file={}, txnCount={}",
                batchId, merchantId, csvFilename, transactionCount);

        try {
            // 1. Decrypt AES key using our RSA private key
            byte[] encryptedAesKey = Base64.getDecoder().decode(encryptedAesKeyBase64);
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPairService.getPrivateKey());
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);

            // 2. Decrypt the CSV file
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            byte[] encryptedFileBytes = file.getBytes();
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] csvBytes = aesCipher.doFinal(encryptedFileBytes);

            log.info("[InternalChannel] Decrypted CSV for batch {}: {} bytes", batchId, csvBytes.length);

            // 3. Parse CSV and mock-authorize each transaction
            List<BatchResponseEvent.TransactionResult> results = new ArrayList<>();
            Random random = new Random();
            int approved = 0, rejected = 0;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8));
            reader.readLine(); // skip header
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", 2);
                Long transactionId;
                try {
                    transactionId = Long.parseLong(parts[0].trim());
                } catch (NumberFormatException e) {
                    log.warn("[InternalChannel] Skipping unparseable line: {}", line);
                    continue;
                }

                boolean isApproved = random.nextDouble() < APPROVAL_RATE;
                String status = isApproved ? "APPROVED" : "FAILED";
                String remark = isApproved
                        ? "Authorized by authorization service"
                        : "Declined: insufficient funds";

                if (isApproved) {
                    approved++;
                } else {
                    rejected++;
                }

                results.add(BatchResponseEvent.TransactionResult.builder()
                        .transactionId(transactionId)
                        .status(status)
                        .remark(remark)
                        .build());
            }

            log.info("[InternalChannel] Batch {} processed: {} approved, {} rejected out of {} total",
                    batchId, approved, rejected, results.size());

            // 4. Bulk-update transaction statuses using JDBC batch
            List<Map<String, Object>> txnUpdateList = new ArrayList<>();
            for (BatchResponseEvent.TransactionResult txnResult : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("transactionId", txnResult.getTransactionId());
                row.put("status", txnResult.getStatus());
                row.put("remark", txnResult.getRemark());
                txnUpdateList.add(row);
            }
            bulkInsertService.bulkUpdateAuthStatus(txnUpdateList);

            // 5. Update batch status → PROCESSED
            Optional<RtaBatch> batchOpt = batchRepository.findById(batchId);
            if (batchOpt.isPresent()) {
                RtaBatch batch = batchOpt.get();
                batch.setStatus("PROCESSED");
                batch.setLastModifiedAt(LocalDateTime.now());
                batchRepository.save(batch);
                log.info("[InternalChannel] Batch {} status updated to PROCESSED", batchId);
            }

            // 6. Persist encryption key record for audit
            encryptionKeyRepository.findByBatchId(batchId).ifPresentOrElse(
                    existing -> log.info("[InternalChannel] Encryption key record already exists for batch {}", batchId),
                    () -> {
                        RtaBatchEncryptionKey keyRecord = RtaBatchEncryptionKey.builder()
                                .batchId(batchId)
                                .merchantId(merchantId)
                                .encryptedAesKeyBase64(encryptedAesKeyBase64)
                                .ivBase64(ivBase64)
                                .csvFilename(csvFilename)
                                .createdAt(LocalDateTime.now())
                                .build();
                        encryptionKeyRepository.save(keyRecord);
                    }
            );

            // 7. Build response
            BatchResponseEvent responseEvent = BatchResponseEvent.builder()
                    .batchId(batchId)
                    .merchantId(merchantId)
                    .batchStatus("PROCESSED")
                    .results(results)
                    .build();

            Map<String, Object> responseBody = new LinkedHashMap<>();
            responseBody.put("batchId", batchId);
            responseBody.put("merchantId", merchantId);
            responseBody.put("batchStatus", "PROCESSED");
            responseBody.put("totalProcessed", results.size());
            responseBody.put("approved", approved);
            responseBody.put("rejected", rejected);
            responseBody.put("results", results);

            return ResponseEntity.ok(responseBody);

        } catch (Exception e) {
            log.error("[InternalChannel] Error processing batch {}: {}", batchId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to process batch: " + e.getMessage()));
        }
    }
}
