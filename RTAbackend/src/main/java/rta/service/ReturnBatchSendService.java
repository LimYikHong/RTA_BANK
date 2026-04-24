package rta.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import rta.entity.MerchantKey;
import rta.entity.RtaBatch;
import rta.entity.RtaTransaction;
import rta.repository.MerchantKeyRepository;

/**
 * Sends encrypted return batch files and report summaries back to the
 * sendAuth/consumer system (port 8882) via HTTPS internal API.
 *
 * <p>
 * Flow:</p>
 * <ol>
 * <li>Request RSA public key from consumer system (via ConsumerKeyService)</li>
 * <li>Generate return CSV from processed transactions (with updated
 * statuses)</li>
 * <li>Encrypt the CSV using AES-256-CBC, encrypt the AES key with consumer's
 * RSA public key</li>
 * <li>Send the encrypted return batch file via POST
 * /api/internal/batch-return</li>
 * <li>Send the report summary JSON via POST /api/internal/report</li>
 * </ol>
 */
@Service

@Slf4j
public class ReturnBatchSendService {

    @Value("${rta.merchant.base-url}")
    private String merchantBaseUrl;

    @Value("${rta.merchant.batch-return-path}")
    private String batchReturnPath;

    @Value("${rta.merchant.report-path}")
    private String reportPath;

    @Value("${rta.merchant.api-key}")
    private String merchantApiKey;

    private final ConsumerKeyService consumerKeyService;
    private final InternalKeyPairService keyPairService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final MerchantKeyRepository merchantKeyRepository;
    private final rta.repository.MerchantInfoRepository merchantInfoRepository;

    private RestTemplate restTemplate;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_BITS = 256;
    private static final int AES_CBC_IV_BYTES = 16;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    public ReturnBatchSendService(ConsumerKeyService consumerKeyService,
            InternalKeyPairService keyPairService,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            MerchantKeyRepository merchantKeyRepository,
            rta.repository.MerchantInfoRepository merchantInfoRepository) {
        this.consumerKeyService = consumerKeyService;
        this.keyPairService = keyPairService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.merchantKeyRepository = merchantKeyRepository;
        this.merchantInfoRepository = merchantInfoRepository;
    }

    @PostConstruct
    public void init() {
        this.restTemplate = buildTrustAllRestTemplate();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. Send Return Batch File (encrypted)
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Generates a return CSV from processed transactions, encrypts it with
     * AES+RSA (using the consumer's RSA public key), and sends it to the
     * consumer system via POST /api/internal/batch-return.
     *
     * @param batch the processed batch
     * @param merchantId merchant ID
     * @param transactions transactions with updated statuses
     * (APPROVED/FAILED/DECLINED)
     * @param originalFileName original batch file name
     * @param remarks optional remarks
     * @return response from the consumer system
     */
    public ReturnBatchResponse sendReturnBatch(RtaBatch batch, String merchantId,
            List<RtaTransaction> transactions,
            String originalFileName, String remarks) {
        log.info("[ReturnBatch] Preparing return batch for batchId={}, merchant={}, txnCount={}",
                batch.getBatchId(), merchantId, transactions.size());

        try {
            // 1. Fetch merchant-specific OUTBOUND RSA public key for encryption
            PublicKey encryptionKey = null;
            String keySource = "consumer";
            try {
                java.util.Optional<MerchantKey> merchantKeyOpt = merchantKeyRepository
                        .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                                merchantId, "ACTIVE", "OUTBOUND");
                if (merchantKeyOpt.isPresent() && merchantKeyOpt.get().getPublicKeyPem() != null) {
                    String pem = merchantKeyOpt.get().getPublicKeyPem()
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s", "");
                    byte[] decoded = Base64.getDecoder().decode(pem);
                    encryptionKey = KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(decoded));
                    keySource = "merchant(" + merchantId + ")";
                    log.info("[ReturnBatch] Using merchant OUTBOUND RSA key for merchant={}", merchantId);
                }
            } catch (Exception keyEx) {
                log.warn("[ReturnBatch] Failed to load merchant OUTBOUND key for {}: {}, falling back to consumer key",
                        merchantId, keyEx.getMessage());
            }
            if (encryptionKey == null) {
                encryptionKey = consumerKeyService.getConsumerPublicKey();
                log.info("[ReturnBatch] Falling back to consumer RSA key for merchant={}", merchantId);
            }

            // 2. Generate return CSV with updated statuses
            byte[] csvBytes = generateReturnCsv(transactions);
            String csvFilename = "return_" + batch.getBatchId() + "_" + System.currentTimeMillis() + ".csv";
            log.info("[ReturnBatch] Generated return CSV: {} bytes, {} transactions", csvBytes.length, transactions.size());

            // 3. Encrypt CSV with AES-256-CBC
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_BITS);
            SecretKey aesKey = keyGen.generateKey();

            byte[] iv = new byte[AES_CBC_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] encryptedFile = aesCipher.doFinal(csvBytes);

            // 4. Encrypt AES key with merchant/consumer RSA public key
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            String encryptedAesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);

            log.info("[ReturnBatch] Encrypted return batch: encFile={} bytes, encKey={} bytes",
                    encryptedFile.length, encryptedAesKey.length);

            auditLogService.logSystemActivity("ENCRYPT_RETURN_BATCH",
                    merchantId,
                    String.format("Encrypted return batch for batchId=%d | File: %s | "
                            + "Original: %d bytes → Encrypted: %d bytes | AES key encrypted with %s RSA | "
                            + "Transaction count: %d",
                            batch.getBatchId(), csvFilename, csvBytes.length,
                            encryptedFile.length, keySource, transactions.size()),
                    "SUCCESS");

            // 5. Send to consumer system with retries
            return doSendReturnBatchWithRetry(batch.getBatchId(), merchantId, csvFilename,
                    encryptedFile, encryptedAesKeyBase64, ivBase64,
                    originalFileName, remarks, transactions.size());

        } catch (Exception e) {
            log.error("[ReturnBatch] Failed to send return batch for batchId={}: {}",
                    batch.getBatchId(), e.getMessage(), e);
            ReturnBatchResponse errorResult = new ReturnBatchResponse();
            errorResult.setSuccess(false);
            errorResult.setBatchId(batch.getBatchId());
            errorResult.setErrorMessage("Failed to send return batch: " + e.getMessage());
            return errorResult;
        }
    }

    private ReturnBatchResponse doSendReturnBatchWithRetry(Long batchId, String merchantId,
            String csvFilename, byte[] encryptedFile,
            String encryptedAesKeyBase64, String ivBase64,
            String originalFileName, String remarks,
            int transactionCount) {
        // Resolve merchant-specific URL from DB, fall back to rta.merchant.* config
        String baseUrl = merchantBaseUrl;
        String path = batchReturnPath;
        String key = merchantApiKey;
        try {
            var merchantOpt = merchantInfoRepository.findByMerchantId(merchantId);
            if (merchantOpt.isPresent()) {
                var merchant = merchantOpt.get();
                if (merchant.getApiBaseUrl() != null && !merchant.getApiBaseUrl().isBlank()) {
                    baseUrl = merchant.getApiBaseUrl();
                    path = merchant.getBatchReturnPath() != null ? merchant.getBatchReturnPath() : batchReturnPath;
                    key = merchant.getApiKey() != null ? merchant.getApiKey() : merchantApiKey;
                    log.info("[ReturnBatch] Using merchant DB URL for {}: {}{}", merchantId, baseUrl, path);
                }
            }
        } catch (Exception e) {
            log.warn("[ReturnBatch] Failed to resolve merchant URL for {}, using default: {}", merchantId, e.getMessage());
        }
        String url = baseUrl + path;
        String finalApiKey = key;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long attemptStartMs = System.currentTimeMillis();
            log.info("[ReturnBatch] Sending return batch {} to consumer (attempt {}/{}): {}",
                    batchId, attempt, MAX_RETRIES, url);

            try {
                // Build multipart form data
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("batchId", String.valueOf(batchId));
                body.add("merchantId", merchantId);
                body.add("originalFileName", originalFileName != null ? originalFileName : "");
                body.add("remarks", remarks != null ? remarks : "");
                body.add("encryptedAesKey", encryptedAesKeyBase64);
                body.add("iv", ivBase64);
                body.add("transactionCount", String.valueOf(transactionCount));
                // Send our public key so consumer can encrypt any response back
                body.add("producerPublicKey", keyPairService.getPublicKeyPem());

                ByteArrayResource fileResource = new ByteArrayResource(encryptedFile) {
                    @Override
                    public String getFilename() {
                        return csvFilename;
                    }
                };
                body.add("file", fileResource);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                headers.set("X-API-Key", finalApiKey);

                HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

                @SuppressWarnings("unchecked")
                ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> responseBody = response.getBody();
                    ReturnBatchResponse result = new ReturnBatchResponse();
                    result.setSuccess(true);
                    result.setBatchId(batchId);
                    result.setMessage((String) responseBody.getOrDefault("message", "Return batch received"));
                    log.info("[ReturnBatch] Return batch {} sent successfully on attempt {} in {}ms",
                            batchId, attempt, System.currentTimeMillis() - attemptStartMs);
                    auditLogService.logSystemActivity("SEND_RETURN_BATCH",
                            merchantId,
                            String.format("Sent encrypted return batch to consumer | BatchId: %d | "
                                    + "File: %s | Endpoint: %s | Transaction count: %d | "
                                    + "Response: %s",
                                    batchId, csvFilename, url, transactionCount,
                                    result.getMessage()),
                            "SUCCESS");
                    return result;
                } else {
                    throw new RuntimeException("Consumer returned HTTP " + response.getStatusCode());
                }
            } catch (Exception e) {
                long attemptElapsed = System.currentTimeMillis() - attemptStartMs;
                log.warn("[ReturnBatch] Attempt {}/{} failed for batch {} in {}ms: {}",
                        attempt, MAX_RETRIES, batchId, attemptElapsed, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    long sleepMs = RETRY_DELAY_MS * attempt;
                    log.info("[ReturnBatch] Sleeping {}ms before retry attempt {} for batch {}",
                            sleepMs, attempt + 1, batchId);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("[ReturnBatch] All {} attempts failed for batch {}", MAX_RETRIES, batchId);
        auditLogService.logSystemActivity("SEND_RETURN_BATCH",
                merchantId,
                String.format("Failed to send return batch after %d attempts | BatchId: %d | "
                        + "File: %s | Endpoint: %s | Transaction count: %d",
                        MAX_RETRIES, batchId, csvFilename, url, transactionCount),
                "FAILED");
        ReturnBatchResponse errorResult = new ReturnBatchResponse();
        errorResult.setSuccess(false);
        errorResult.setBatchId(batchId);
        errorResult.setErrorMessage("All " + MAX_RETRIES + " retry attempts failed");
        return errorResult;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Send Report Summary (encrypted JSON)
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Sends a batch processing report summary to the consumer system via POST
     * /api/internal/report. The report JSON is encrypted with AES+RSA.
     *
     * @param batch the processed batch
     * @param merchantId merchant ID
     * @param approved count of approved transactions
     * @param declined count of declined transactions
     * @param failed count of failed transactions
     * @param transactions all transactions in the batch (for detailed
     * breakdown)
     * @return response from the consumer system
     */
    public ReportResponse sendReportSummary(RtaBatch batch, String merchantId,
            int approved, int declined, int failed,
            List<RtaTransaction> transactions) {
        log.info("[ReturnBatch] Preparing report summary for batchId={}, merchant={}",
                batch.getBatchId(), merchantId);

        try {
            // 1. Build report JSON
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("batchId", batch.getBatchId());
            report.put("merchantId", merchantId);
            report.put("batchStatus", batch.getStatus());
            report.put("fileName", batch.getFileName());
            report.put("originalFileName", batch.getOriginalFileName());
            report.put("totalCount", batch.getTotalCount());
            report.put("approvedCount", approved);
            report.put("declinedCount", declined);
            report.put("failedCount", failed);
            report.put("processedAt", java.time.LocalDateTime.now().toString());

            // Transaction-level summary
            List<Map<String, String>> txnSummary = transactions.stream().map(txn -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("transactionId", String.valueOf(txn.getId()));
                row.put("merchantCustomer", txn.getMerchantCustomer());
                row.put("maskedPan", txn.getMaskedPan());
                row.put("amount", String.valueOf(txn.getAmount()));
                row.put("currency", txn.getCurrency());
                row.put("status", txn.getStatus());
                row.put("validationStatus", txn.getValidationStatus() != null ? txn.getValidationStatus() : "");
                row.put("remark", txn.getRemark() != null ? txn.getRemark() : "");
                return row;
            }).toList();
            report.put("transactions", txnSummary);

            String reportJson = objectMapper.writeValueAsString(report);
            byte[] reportBytes = reportJson.getBytes(StandardCharsets.UTF_8);

            // 2. Fetch merchant-specific OUTBOUND RSA public key (fallback to consumer key)
            PublicKey reportEncryptionKey = null;
            String reportKeySource = "consumer";
            try {
                java.util.Optional<MerchantKey> merchantKeyOpt = merchantKeyRepository
                        .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                                merchantId, "ACTIVE", "OUTBOUND");
                if (merchantKeyOpt.isPresent() && merchantKeyOpt.get().getPublicKeyPem() != null) {
                    String pem = merchantKeyOpt.get().getPublicKeyPem()
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s", "");
                    byte[] decoded = Base64.getDecoder().decode(pem);
                    reportEncryptionKey = KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(decoded));
                    reportKeySource = "merchant(" + merchantId + ")";
                    log.info("[ReturnBatch] Using merchant OUTBOUND RSA key for report, merchant={}", merchantId);
                }
            } catch (Exception keyEx) {
                log.warn("[ReturnBatch] Failed to load merchant OUTBOUND key for report {}: {}, falling back to consumer key",
                        merchantId, keyEx.getMessage());
            }
            if (reportEncryptionKey == null) {
                reportEncryptionKey = consumerKeyService.getConsumerPublicKey();
                log.info("[ReturnBatch] Falling back to consumer RSA key for report, merchant={}", merchantId);
            }

            // 3. Encrypt report JSON with AES-256-CBC
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_BITS);
            SecretKey aesKey = keyGen.generateKey();

            byte[] iv = new byte[AES_CBC_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] encryptedReport = aesCipher.doFinal(reportBytes);

            // 4. Encrypt AES key with merchant/consumer RSA public key
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.ENCRYPT_MODE, reportEncryptionKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            String encryptedContentBase64 = Base64.getEncoder().encodeToString(encryptedReport);
            String encryptedAesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);

            log.info("[ReturnBatch] Encrypted report: {} bytes -> {} bytes encrypted",
                    reportBytes.length, encryptedReport.length);

            auditLogService.logSystemActivity("ENCRYPT_REPORT_SUMMARY",
                    merchantId,
                    String.format("Encrypted report summary for batchId=%d | "
                            + "Original: %d bytes → Encrypted: %d bytes | AES key encrypted with %s RSA",
                            batch.getBatchId(), reportBytes.length, encryptedReport.length, reportKeySource),
                    "SUCCESS");

            // 5. Send encrypted report to consumer
            return doSendReportWithRetry(batch.getBatchId(), merchantId,
                    encryptedContentBase64, encryptedAesKeyBase64, ivBase64);

        } catch (Exception e) {
            log.error("[ReturnBatch] Failed to send report for batchId={}: {}",
                    batch.getBatchId(), e.getMessage(), e);
            ReportResponse errorResult = new ReportResponse();
            errorResult.setSuccess(false);
            errorResult.setBatchId(batch.getBatchId());
            errorResult.setErrorMessage("Failed to send report: " + e.getMessage());
            return errorResult;
        }
    }

    private ReportResponse doSendReportWithRetry(Long batchId, String merchantId,
            String encryptedContentBase64,
            String encryptedAesKeyBase64,
            String ivBase64) {
        // Resolve merchant-specific URL from DB, fall back to rta.merchant.* config
        String baseUrl = merchantBaseUrl;
        String path = reportPath;
        String key = merchantApiKey;
        try {
            var merchantOpt = merchantInfoRepository.findByMerchantId(merchantId);
            if (merchantOpt.isPresent()) {
                var merchant = merchantOpt.get();
                if (merchant.getApiBaseUrl() != null && !merchant.getApiBaseUrl().isBlank()) {
                    baseUrl = merchant.getApiBaseUrl();
                    path = merchant.getReportPath() != null ? merchant.getReportPath() : reportPath;
                    key = merchant.getApiKey() != null ? merchant.getApiKey() : merchantApiKey;
                    log.info("[ReturnBatch] Using merchant DB URL for report {}: {}{}", merchantId, baseUrl, path);
                }
            }
        } catch (Exception e) {
            log.warn("[ReturnBatch] Failed to resolve merchant URL for report {}, using default: {}", merchantId, e.getMessage());
        }
        String url = baseUrl + path;
        String finalApiKey = key;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long attemptStartMs = System.currentTimeMillis();
            log.info("[ReturnBatch] Sending report for batch {} to consumer (attempt {}/{}): {}",
                    batchId, attempt, MAX_RETRIES, url);

            try {
                // Build JSON body with encrypted payload
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("batchId", batchId);
                body.put("merchantId", merchantId);
                body.put("encryptedContent", encryptedContentBase64);
                body.put("encryptedAesKey", encryptedAesKeyBase64);
                body.put("iv", ivBase64);
                body.put("producerPublicKey", keyPairService.getPublicKeyPem());

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-API-Key", finalApiKey);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                @SuppressWarnings("unchecked")
                ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    ReportResponse result = new ReportResponse();
                    result.setSuccess(true);
                    result.setBatchId(batchId);
                    result.setMessage("Report sent successfully");
                    log.info("[ReturnBatch] Report for batch {} sent successfully on attempt {} in {}ms",
                            batchId, attempt, System.currentTimeMillis() - attemptStartMs);
                    auditLogService.logSystemActivity("SEND_REPORT_SUMMARY",
                            merchantId,
                            String.format("Sent encrypted report summary to consumer | BatchId: %d | Endpoint: %s",
                                    batchId, url),
                            "SUCCESS");
                    return result;
                } else {
                    throw new RuntimeException("Consumer returned HTTP " + response.getStatusCode());
                }
            } catch (Exception e) {
                long attemptElapsed = System.currentTimeMillis() - attemptStartMs;
                log.warn("[ReturnBatch] Report attempt {}/{} failed for batch {} in {}ms: {}",
                        attempt, MAX_RETRIES, batchId, attemptElapsed, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    long sleepMs = RETRY_DELAY_MS * attempt;
                    log.info("[ReturnBatch] Sleeping {}ms before report retry attempt {} for batch {}",
                            sleepMs, attempt + 1, batchId);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("[ReturnBatch] All {} report attempts failed for batch {}", MAX_RETRIES, batchId);
        ReportResponse errorResult = new ReportResponse();
        errorResult.setSuccess(false);
        errorResult.setBatchId(batchId);
        errorResult.setErrorMessage("All " + MAX_RETRIES + " retry attempts failed");
        return errorResult;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Return CSV generation
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Generate a return CSV with updated transaction statuses. Columns:
     * transaction_id, merchant_id, merchant_customer, masked_pan, amount_cents,
     * currency, actual_billing_date, status, remark
     */
    private byte[] generateReturnCsv(List<RtaTransaction> transactions) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        // Header
        pw.println("transaction_id,merchant_id,merchant_customer,masked_pan,amount_cents,currency,actual_billing_date,status,remark");

        for (RtaTransaction txn : transactions) {
            pw.printf("%d,%s,%s,%s,%d,%s,%s,%s,%s%n",
                    txn.getId(),
                    csvSafe(txn.getMerchantId()),
                    csvSafe(txn.getMerchantCustomer()),
                    csvSafe(txn.getMaskedPan()),
                    txn.getAmount() != null ? txn.getAmount() : 0L,
                    csvSafe(txn.getCurrency()),
                    txn.getActualBillingDate() != null ? txn.getActualBillingDate().toString() : "",
                    csvSafe(txn.getStatus()),
                    csvSafe(txn.getRemark()));
        }

        pw.flush();
        return baos.toByteArray();
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

    // ────────────────────────────────────────────────────────────────────────
    // Response DTOs
    // ────────────────────────────────────────────────────────────────────────
    @Data
    public static class ReturnBatchResponse {

        private boolean success;
        private Long batchId;
        private String message;
        private String errorMessage;
    }

    @Data
    public static class ReportResponse {

        private boolean success;
        private Long batchId;
        private String message;
        private String errorMessage;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Trust-all RestTemplate (for self-signed certificates)
    // ────────────────────────────────────────────────────────────────────────
    private RestTemplate buildTrustAllRestTemplate() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                    .setSslContext(sslContext)
                                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                    .build())
                            .build())
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(5000);

            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all RestTemplate", e);
        }
    }
}
