package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import rta.entity.RtaBatchEncryptionKey;
import rta.entity.RtaTransaction;
import rta.repository.RtaBatchEncryptionKeyRepository;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Generates a CSV file from validated (PENDING) transactions, encrypts it with
 * AES-256-GCM, encrypts the AES key with the merchant's RSA public key, and
 * persists the encryption metadata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchFileGenerationService {

    private final ConsumerKeyService consumerKeyService;
    private final RtaBatchEncryptionKeyRepository encryptionKeyRepository;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_BITS = 256;
    private static final int AES_CBC_IV_BYTES = 16;
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Result holder for the generated and encrypted batch file.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class EncryptedBatchFile {

        private String csvFilename;
        private byte[] encryptedFileBytes;
        private String encryptedAesKeyBase64;
        private String ivBase64;
        private String aesKeyBase64;
    }

    /**
     * Generate a CSV from the given transactions, encrypt it, persist the
     * encryption key record, and return the encrypted payload.
     *
     * @param batchId the batch ID
     * @param merchantId merchant owning the transactions
     * @param transactions the PENDING transactions to include
     * @return encrypted batch file metadata
     */
    public EncryptedBatchFile generateAndEncrypt(Long batchId, String merchantId,
            List<RtaTransaction> transactions) {
        // 1. Generate CSV content
        String csvFilename = batchId + "_" + LocalDateTime.now().format(DT_FORMAT) + ".csv";
        byte[] csvBytes = generateCsv(transactions);
        log.info("Generated CSV '{}' for batch {}: {} bytes, {} transactions",
                csvFilename, batchId, csvBytes.length, transactions.size());

        try {
            // 2. Get consumer's RSA public key (fetched from internal channel)
            PublicKey rsaPublicKey = consumerKeyService.getConsumerPublicKey();

            // 3. Generate AES-256 session key
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_BITS);
            SecretKey aesKey = keyGen.generateKey();

            // 4. Generate random IV (16 bytes for CBC)
            byte[] iv = new byte[AES_CBC_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            // 5. Encrypt CSV with AES-256-CBC
            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] encryptedFile = aesCipher.doFinal(csvBytes);

            // 6. Encrypt AES key with consumer's RSA public key
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            // 7. Base64-encode everything for Kafka transport
            String aesKeyBase64 = Base64.getEncoder().encodeToString(aesKey.getEncoded());
            String encryptedAesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);

            // 8. Persist encryption key record
            RtaBatchEncryptionKey keyRecord = RtaBatchEncryptionKey.builder()
                    .batchId(batchId)
                    .merchantId(merchantId)
                    .aesKeyBase64(aesKeyBase64)
                    .ivBase64(ivBase64)
                    .encryptedAesKeyBase64(encryptedAesKeyBase64)
                    .csvFilename(csvFilename)
                    .createdAt(LocalDateTime.now())
                    .build();
            encryptionKeyRepository.save(keyRecord);

            log.info("Batch {} encrypted: encFile={} bytes, encKey={} bytes",
                    batchId, encryptedFile.length, encryptedAesKey.length);

            return new EncryptedBatchFile(csvFilename, encryptedFile,
                    encryptedAesKeyBase64, ivBase64, aesKeyBase64);

        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt batch " + batchId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Build CSV content from transaction records. Header: transaction_id,
     * merchant_id, merchant_customer, masked_pan, amount_cents, currency,
     * actual_billing_date, recurring_reference
     */
    private byte[] generateCsv(List<RtaTransaction> transactions) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        // Header
        pw.println("transaction_id,merchant_id,merchant_customer,masked_pan,amount_cents,currency,actual_billing_date,recurring_reference");

        // Data rows
        for (RtaTransaction txn : transactions) {
            pw.printf("%d,%s,%s,%s,%d,%s,%s,%s%n",
                    txn.getId(),
                    csvSafe(txn.getMerchantId()),
                    csvSafe(txn.getMerchantCustomer()),
                    csvSafe(txn.getMaskedPan()),
                    txn.getAmount() != null ? txn.getAmount() : 0L,
                    csvSafe(txn.getCurrency()),
                    txn.getActualBillingDate() != null ? txn.getActualBillingDate().toString() : "",
                    csvSafe(txn.getRecurringReference()));
        }

        pw.flush();
        return baos.toByteArray();
    }

    private String csvSafe(String value) {
        if (value == null) {
            return "";
        }
        // Escape commas and quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
