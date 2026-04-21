package rta.service;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends encrypted batch files to the sendAuth consumer system (port 8881) via
 * HTTPS internal API (API key + IP whitelist secured).
 *
 * <p>
 * The sendAuth system receives the encrypted CSV + RSA-encrypted AES key,
 * decrypts, authorizes transactions, and returns results in the HTTP
 * response.</p>
 */
@Service
@Slf4j
public class SendAuthService {

    @Value("${rta.consumer.base-url}")
    private String consumerBaseUrl;

    @Value("${rta.consumer.batch-upload-path}")
    private String batchUploadPath;

    @Value("${rta.consumer.api-key}")
    private String apiKey;

    private final InternalKeyPairService keyPairService;
    private RestTemplate restTemplate;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";

    public SendAuthService(InternalKeyPairService keyPairService) {
        this.keyPairService = keyPairService;
    }

    @PostConstruct
    public void init() {
        this.restTemplate = buildTrustAllRestTemplate();
    }

    /**
     * Send an encrypted batch file to the sendAuth system via HTTPS multipart
     * POST.
     *
     * @param batchId the batch primary key
     * @param merchantId merchant owning the batch
     * @param csvFilename original CSV filename
     * @param transactionCount number of transactions
     * @param encryptedFileBytes AES-encrypted CSV bytes
     * @param encryptedAesKeyBase64 RSA-encrypted AES key (Base64)
     * @param ivBase64 AES IV (Base64)
     * @return the authorization response from the sendAuth system
     */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    @SuppressWarnings("unchecked")
    public SendAuthResponse sendBatchToConsumer(Long batchId, String merchantId,
            String csvFilename, int transactionCount,
            byte[] encryptedFileBytes, String encryptedAesKeyBase64,
            String ivBase64) {

        String url = consumerBaseUrl + batchUploadPath;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.info("[SendAuth] Sending batch {} to consumer (attempt {}/{}): {}", batchId, attempt, MAX_RETRIES, url);

            SendAuthResponse result = doSendBatch(batchId, merchantId, csvFilename, transactionCount,
                    encryptedFileBytes, encryptedAesKeyBase64, ivBase64, url);

            if (result.isSuccess()) {
                return result;
            }

            log.warn("[SendAuth] Attempt {}/{} failed for batch {}: {}", attempt, MAX_RETRIES, batchId, result.getErrorMessage());

            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.error("[SendAuth] All {} attempts failed for batch {}", MAX_RETRIES, batchId);
        SendAuthResponse errorResult = new SendAuthResponse();
        errorResult.setSuccess(false);
        errorResult.setBatchId(batchId);
        errorResult.setErrorMessage("All " + MAX_RETRIES + " retry attempts failed");
        return errorResult;
    }

    @SuppressWarnings("unchecked")
    private SendAuthResponse doSendBatch(Long batchId, String merchantId,
            String csvFilename, int transactionCount,
            byte[] encryptedFileBytes, String encryptedAesKeyBase64,
            String ivBase64, String url) {

        try {
            // Build multipart form data (matching InternalChannelController expectations)
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("batchId", String.valueOf(batchId));
            body.add("merchantId", merchantId);
            body.add("csvFilename", csvFilename);
            body.add("transactionCount", String.valueOf(transactionCount));
            body.add("encryptedAesKey", encryptedAesKeyBase64);
            body.add("iv", ivBase64);
            // Send our public key so sendAuth can encrypt the response
            body.add("producerPublicKey", keyPairService.getPublicKeyPem());

            // Add encrypted file as multipart file
            ByteArrayResource fileResource = new ByteArrayResource(encryptedFileBytes) {
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
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                SendAuthResponse result = new SendAuthResponse();
                result.setBatchId(batchId);

                // Consumer returns BatchUploadResult with fields:
                // batchId, status, totalRecords, approvedCount, declinedCount,
                // encryptedAesKey, encryptedContent, iv, errorMessage
                String consumerStatus = (String) responseBody.get("status");

                // Check if consumer reported failure
                if ("FAILED".equalsIgnoreCase(consumerStatus)) {
                    result.setSuccess(false);
                    result.setErrorMessage((String) responseBody.get("errorMessage"));
                    return result;
                }

                result.setSuccess(true);
                result.setBatchStatus(consumerStatus);
                result.setTotalProcessed(toInt(responseBody.get("totalRecords")));
                result.setApproved(toInt(responseBody.get("approvedCount")));
                result.setRejected(toInt(responseBody.get("declinedCount")));

                // Try to decrypt the encrypted result CSV for detailed per-transaction results
                String encryptedContent = (String) responseBody.get("encryptedContent");
                String respEncAesKey = (String) responseBody.get("encryptedAesKey");
                String respIv = (String) responseBody.get("iv");

                if (encryptedContent != null && respEncAesKey != null && respIv != null) {
                    try {
                        String decryptedCsv = decryptResponseToCsv(encryptedContent, respEncAesKey, respIv);
                        List<Map<String, Object>> txnResults = parseResultCsv(decryptedCsv);
                        result.setResults(txnResults);
                        log.info("[SendAuth] Batch {} result CSV decrypted: {} rows, {} approved, {} rejected",
                                batchId, txnResults.size(), result.getApproved(), result.getRejected());
                    } catch (Exception e) {
                        log.warn("[SendAuth] Could not decrypt result CSV for batch {}: {}. Using counts from response.",
                                batchId, e.getMessage());
                        // Still successful — we have the counts from the outer response
                    }
                } else {
                    log.info("[SendAuth] Batch {} response has no encrypted result payload. Using counts: {} approved, {} rejected",
                            batchId, result.getApproved(), result.getRejected());
                }

                return result;
            } else {
                throw new RuntimeException("SendAuth returned HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[SendAuth] Attempt failed for batch {}: {}", batchId, e.getMessage(), e);
            SendAuthResponse errorResult = new SendAuthResponse();
            errorResult.setSuccess(false);
            errorResult.setBatchId(batchId);
            errorResult.setErrorMessage(e.getMessage());
            return errorResult;
        }
    }

    /**
     * Try multiple possible column names (snake_case, camelCase) from a parsed
     * CSV row.
     */
    private String getField(Map<String, Object> row, String... keysAndDefault) {
        String defaultVal = keysAndDefault[keysAndDefault.length - 1];
        for (int i = 0; i < keysAndDefault.length - 1; i++) {
            Object val = row.get(keysAndDefault[i]);
            if (val != null && !val.toString().isEmpty()) {
                return val.toString();
            }
        }
        return defaultVal;
    }

    private int toInt(Object val) {
        if (val == null) {
            return 0;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(val.toString());
    }

    /**
     * Decrypt the encrypted response CSV from the sendAuth system.
     */
    private String decryptResponseToCsv(String encryptedContentB64,
            String encryptedAesKeyB64, String ivB64) {
        try {
            byte[] encryptedAesKey = Base64.getDecoder().decode(encryptedAesKeyB64);
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPairService.getPrivateKey());
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);

            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] encryptedContent = Base64.getDecoder().decode(encryptedContentB64);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] decryptedBytes = aesCipher.doFinal(encryptedContent);

            String csv = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.info("[SendAuth] Decrypted response CSV: {} bytes", decryptedBytes.length);
            return csv;
        } catch (Exception e) {
            log.error("[SendAuth] Failed to decrypt response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decrypt sendAuth response: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the consumer's result CSV into a list of per-transaction results.
     * Expected CSV columns: transactionId,accountNumber,accountStatus,amount,
     * currency,merchantName,merchantCategory,authResult,decisionReason
     */
    private List<Map<String, Object>> parseResultCsv(String csv) {
        List<Map<String, Object>> results = new ArrayList<>();
        String[] lines = csv.split("\n");
        if (lines.length < 2) {
            return results; // header only or empty
        }
        String[] headers = lines[0].trim().split(",");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] values = line.split(",", -1);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                row.put(headers[j].trim(), values[j].trim());
            }
            // Map consumer fields — try both camelCase and snake_case column names
            String txnIdStr = getField(row, "transaction_id", "transactionId", "0");
            String authResult = getField(row, "auth_result", "authResult", "");
            String reason = getField(row, "decision_reason", "decisionReason", "");

            Map<String, Object> txnResult = new java.util.LinkedHashMap<>();
            txnResult.put("transactionId", txnIdStr);
            // Preserve consumer's auth result: APPROVED, DECLINED, or FAILED
            if ("APPROVED".equalsIgnoreCase(authResult)) {
                txnResult.put("status", "APPROVED");
            } else if ("DECLINED".equalsIgnoreCase(authResult)) {
                txnResult.put("status", "DECLINED");
            } else {
                txnResult.put("status", "FAILED");
            }
            txnResult.put("remark", reason);
            results.add(txnResult);
        }
        return results;
    }

    /**
     * Decrypt the encrypted response from the sendAuth system. The response
     * contains: - encryptedContent: Base64-encoded AES-encrypted result
     * CSV/JSON - encryptedAesKey: Base64-encoded RSA-encrypted AES key
     * (encrypted with our public key) - iv: Base64-encoded AES IV
     *
     * We decrypt the AES key with our private key, then decrypt the content.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptResponse(String encryptedContentB64,
            String encryptedAesKeyB64, String ivB64) {
        try {
            // 1. Decrypt AES key using our RSA private key
            byte[] encryptedAesKey = Base64.getDecoder().decode(encryptedAesKeyB64);
            Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPairService.getPrivateKey());
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);

            // 2. Decrypt content using AES key
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] encryptedContent = Base64.getDecoder().decode(encryptedContentB64);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] decryptedBytes = aesCipher.doFinal(encryptedContent);

            // 3. Parse JSON
            String json = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.info("[SendAuth] Decrypted response: {} bytes", decryptedBytes.length);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("[SendAuth] Failed to decrypt response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decrypt sendAuth response: " + e.getMessage(), e);
        }
    }

    /**
     * Response from the sendAuth consumer system.
     */
    @Data
    public static class SendAuthResponse {

        private boolean success;
        private Long batchId;
        private String batchStatus;
        private int totalProcessed;
        private int approved;
        private int rejected;
        private String errorMessage;
        private List<Map<String, Object>> results;
    }

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
            sslContext.init(null, trustAll, new java.security.SecureRandom());

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                    .setSslContext(sslContext)
                                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                    .build())
                            .build())
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(30_000);

            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all RestTemplate for SendAuth", e);
        }
    }
}
