package rta.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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
import rta.entity.RtaBatch;
import rta.entity.RtaTransaction;

/**
 * Sends encrypted return batch files and report summaries back to the
 * sendAuth/consumer system (port 8881) via HTTPS internal API.
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
@ Service 

    @Slf4j
    public class ReturnBatchSendService {

        @Value("${rta.consumer.base-url}")
        private String consumerBaseUrl;

        @Value("${rta.consumer.batch-return-path}")
        private String batchReturnPath;

        @Value("${rta.consumer.report-path}")
        private String reportPath;

        @Value("${rta.consumer.api-key}")
        private String apiKey;

        private final ConsumerKeyService consumerKeyService;
        private final InternalKeyPairService keyPairService;
        private final ObjectMapper objectMapper;

        private RestTemplate restTemplate;

        private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
        private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
        private static final int AES_KEY_BITS = 256;
        private static final int AES_CBC_IV_BYTES = 16;
        private static final int MAX_RETRIES = 3;
        private static final long RETRY_DELAY_MS = 2000;

        public ReturnBatchSendService(ConsumerKeyService consumerKeyService,
                InternalKeyPairService keyPairService,
                ObjectMapper objectMapper) {
            this.consumerKeyService = consumerKeyService;
            this.keyPairService = keyPairService;
            this.objectMapper = objectMapper;
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
                // 1. Fetch/use cached RSA public key from the consumer system
                PublicKey consumerPublicKey = consumerKeyService.getConsumerPublicKey();

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

                // 4. Encrypt AES key with consumer's RSA public key
                Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
                rsaCipher.init(Cipher.ENCRYPT_MODE, consumerPublicKey);
                byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

                String encryptedAesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
                String ivBase64 = Base64.getEncoder().encodeToString(iv);

                log.info("[ReturnBatch] Encrypted return batch: encFile={} bytes, encKey={} bytes",
                        encryptedFile.length, encryptedAesKey.length);

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
            String url = consumerBaseUrl + batchReturnPath;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
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
                    headers.set("X-API-Key", apiKey);

                    HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

                    @SuppressWarnings("unchecked")
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        Map<String, Object> responseBody = response.getBody();
                        ReturnBatchResponse result = new ReturnBatchResponse();
                        result.setSuccess(true);
                        result.setBatchId(batchId);
                        result.setMessage((String) responseBody.getOrDefault("message", "Return batch received"));
                        log.info("[ReturnBatch] Return batch {} sent successfully", batchId);
                        return result;
                    } else {
                        throw new RuntimeException("Consumer returned HTTP " + response.getStatusCode());
                    }
                } catch (Exception e) {
                    log.warn("[ReturnBatch] Attempt {}/{} failed for batch {}: {}",
                            attempt, MAX_RETRIES, batchId, e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            log.error("[ReturnBatch] All {} attempts failed for batch {}", MAX_RETRIES, batchId);
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
         * Sends a batch processing report summary to the consumer system via
         * POST /api/internal/report. The report JSON is encrypted with AES+RSA.
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
                    row.put("remark", txn.getRemark() != null ? txn.getRemark() : "");
                    return row;
                }).toList();
                report.put("transactions", txnSummary);

                String reportJson = objectMapper.writeValueAsString(report);
                byte[] reportBytes = reportJson.getBytes(StandardCharsets.UTF_8);

                // 2. Fetch consumer's RSA public key
                PublicKey consumerPublicKey = consumerKeyService.getConsumerPublicKey();

                // 3. Encrypt report JSON with AES-256-CBC
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(AES_KEY_BITS);
                SecretKey aesKey = keyGen.generateKey();

                byte[] iv = new byte[AES_CBC_IV_BYTES];
                new SecureRandom().nextBytes(iv);

                Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
                aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
                byte[] encryptedReport = aesCipher.doFinal(reportBytes);

                // 4. Encrypt AES key with consumer's RSA public key
                Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
                rsaCipher.init(Cipher.ENCRYPT_MODE, consumerPublicKey);
                byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

                String encryptedContentBase64 = Base64.getEncoder().encodeToString(encryptedReport);
                String encryptedAesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
                String ivBase64 = Base64.getEncoder().encodeToString(iv);

                log.info("[ReturnBatch] Encrypted report: {} bytes -> {} bytes encrypted",
                        reportBytes.length, encryptedReport.length);

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
            String url = consumerBaseUrl + reportPath;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
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
                    headers.set("X-API-Key", apiKey);

                    HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                    @SuppressWarnings("unchecked")
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

                    if (response.getStatusCode().is2xxSuccessful()) {
                        ReportResponse result = new ReportResponse();
                        result.setSuccess(true);
                        result.setBatchId(batchId);
                        result.setMessage("Report sent successfully");
                        log.info("[ReturnBatch] Report for batch {} sent successfully", batchId);
                        return result;
                    } else {
                        throw new RuntimeException("Consumer returned HTTP " + response.getStatusCode());
                    }
                } catch (Exception e) {
                    log.warn("[ReturnBatch] Report attempt {}/{} failed for batch {}: {}",
                            attempt, MAX_RETRIES, batchId, e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt);
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
         * transaction_id, merchant_id, merchant_customer, masked_pan,
         * amount_cents, currency, actual_billing_date, status, remark
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
                
        
        
                    
                    
                    
                    
                        
                    