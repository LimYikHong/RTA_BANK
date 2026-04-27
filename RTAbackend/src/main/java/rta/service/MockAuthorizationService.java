package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import rta.entity.RtaBatchEncryptionKey;
import rta.event.BatchRequestEvent;
import rta.event.BatchResponseEvent;
import rta.repository.RtaBatchEncryptionKeyRepository;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * Mock Authorization Service — simulates a third-party card authorization
 * system.
 *
 * <p>
 * Listens on {@code batch-request}, decrypts the CSV using the raw AES key
 * stored in the encryption key table (since this is a mock running in the same
 * JVM), processes each transaction with a ~90% approval rate, and publishes
 * per-transaction results to {@code batch-response}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MockAuthorizationService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RtaBatchEncryptionKeyRepository encryptionKeyRepository;

    @Value("${rta.kafka.topic.batch-response}")
    private String batchResponseTopic;

    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final double APPROVAL_RATE = 0.9;

    @KafkaListener(topics = "${rta.kafka.topic.batch-request}",
            groupId = "mock-auth-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onBatchRequest(BatchRequestEvent event) {
        log.info("[MockAuth] Received batch-request: batchId={}, merchant={}, txnCount={}",
                event.getBatchId(), event.getMerchantId(), event.getTransactionCount());

        try {
            // 1. Look up the raw AES key from the encryption key table (mock shortcut)
            RtaBatchEncryptionKey keyRecord = encryptionKeyRepository.findByBatchId(event.getBatchId())
                    .orElseThrow(() -> new IllegalStateException(
                    "No encryption key record for batchId=" + event.getBatchId()));

            // 2. Decrypt the CSV using the raw AES key (mock: we use the stored key directly)
            byte[] aesKeyBytes = Base64.getDecoder().decode(keyRecord.getAesKeyBase64());
            byte[] iv = Base64.getDecoder().decode(event.getIvBase64());
            byte[] encryptedFile = Base64.getDecoder().decode(event.getEncryptedFileBase64());

            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] csvBytes = cipher.doFinal(encryptedFile);

            String csvContent = new String(csvBytes, StandardCharsets.UTF_8);
            log.info("[MockAuth] Decrypted CSV for batch {}: {} bytes", event.getBatchId(), csvBytes.length);

            // 3. Parse CSV and mock-authorize each transaction
            List<BatchResponseEvent.TransactionResult> results = new ArrayList<>();
            Random random = new Random();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8));
            String header = reader.readLine(); // skip header
            String line;
            int approved = 0;
            int rejected = 0;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Parse transaction_id from first column
                String[] parts = line.split(",", 2);
                String transactionId;
                try {
                    transactionId = parts[0].trim();
                    Long.parseLong(transactionId); // validate it's numeric
                } catch (NumberFormatException e) {
                    log.warn("[MockAuth] Skipping unparseable line: {}", line);
                    continue;
                }

                // Mock authorization: ~90% approved, ~10% failed
                boolean isApproved = random.nextDouble() < APPROVAL_RATE;
                String status = isApproved ? "APPROVED" : "FAILED";
                String remark = isApproved ? "Authorized by mock service"
                        : "Declined: insufficient funds (mock)";

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

            log.info("[MockAuth] Batch {} processed: {} approved, {} rejected out of {} total",
                    event.getBatchId(), approved, rejected, results.size());

            // 4. Publish results to batch-response topic
            BatchResponseEvent response = BatchResponseEvent.builder()
                    .batchId(event.getBatchId())
                    .merchantId(event.getMerchantId())
                    .batchStatus("PROCESSED")
                    .results(results)
                    .build();

            kafkaTemplate.send(batchResponseTopic, String.valueOf(event.getBatchId()), response)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[MockAuth] Failed to send batch-response for batch {}: {}",
                                    event.getBatchId(), ex.getMessage());
                        } else {
                            log.info("[MockAuth] batch-response sent for batch {}", event.getBatchId());
                        }
                    });

        } catch (Exception e) {
            log.error("[MockAuth] Error processing batch {}: {}", event.getBatchId(), e.getMessage(), e);
        }
    }
}
