package rta.controller;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import rta.entity.RtaBatch;
import rta.repository.RtaBatchRepository;
import rta.service.BatchRequestProducer;
import rta.service.InternalKeyPairService;
import rta.service.MerchantKafkaProducer;
import rta.service.MinioStorageService;

/**
 * Full integration test for InternalChannelController.
 *
 * <p>
 * Uses {@code @SpringBootTest(RANDOM_PORT)} to boot the entire Spring context
 * with an embedded server, H2 in-memory database, and real beans. This tests
 * the full HTTP round-trip including the
 * {@link rta.config.InternalApiSecurityFilter} (API key + IP whitelist).</p>
 *
 * <p>
 * Test scenarios:</p>
 * <ol>
 * <li>GET /api/internal/public-key → valid PEM response</li>
 * <li>POST /api/internal/batch-upload without API key → 401 Unauthorized</li>
 * <li>POST /api/internal/batch-upload full round-trip → AES/RSA encrypt →
 * decrypt → authorize</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalChannelControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InternalKeyPairService keyPairService;

    @Autowired
    private RtaBatchRepository batchRepository;

    /**
     * Mock MinioStorageService to prevent @PostConstruct from trying to connect
     * to a real MinIO server during tests.
     */
    @MockitoBean
    private MinioStorageService minioStorageService;

    /**
     * Mock Kafka producers — KafkaTemplate is excluded in test profile.
     */
    @MockitoBean
    private BatchRequestProducer batchRequestProducer;

    @MockitoBean
    private MerchantKafkaProducer merchantKafkaProducer;

    /**
     * Seed a test batch so that the batch-upload endpoint can find it and
     * update its status to PROCESSED.
     */
    @BeforeEach
    void seedTestData() {
        if (batchRepository.findById(1L).isEmpty()) {
            RtaBatch batch = new RtaBatch();
            batch.setFileName("TEST-BATCH");
            batch.setOriginalFileName("test.csv");
            batch.setStatus("CREATED");
            batch.setCreatedBy("TEST");
            batch.setCreatedAt(LocalDateTime.now());
            batchRepository.save(batch);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 1. Public Key Endpoint
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("GET /api/internal/public-key → 200 with valid PEM")
    void publicKey_shouldReturnValidPem() {
        // The public-key endpoint has no API-key requirement in the filter
        // (GET requests are allowed through), but we add the key anyway.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "test-api-key");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/internal/public-key",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("-----BEGIN PUBLIC KEY-----"));
        assertTrue(response.getBody().contains("-----END PUBLIC KEY-----"));
    }

    // ────────────────────────────────────────────────────────────────
    // 2. Security — missing API key
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("POST /api/internal/batch-upload without API key → 401")
    void batchUpload_withoutApiKey_shouldReturn401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // No X-API-Key header

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("batchId", "1");
        body.add("merchantId", "M001");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/internal/batch-upload",
                new HttpEntity<>(body, headers),
                String.class);

        // InternalApiSecurityFilter returns 401 for missing/invalid API key
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // ────────────────────────────────────────────────────────────────
    // 3. Full round-trip: encrypt → upload → decrypt → authorize
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Full batch upload round-trip: AES-256/RSA encrypt → decrypt → mock-authorize")
    @SuppressWarnings("unchecked")
    void batchUpload_fullRoundTrip_shouldDecryptAndAuthorize() throws Exception {
        // 1. Fetch the system's RSA public key
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-API-Key", "test-api-key");
        ResponseEntity<String> keyResponse = restTemplate.exchange(
                "/api/internal/public-key",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class);
        String publicKeyPem = keyResponse.getBody();
        PublicKey publicKey = parsePublicKey(publicKeyPem);

        // 2. Generate test CSV (transactionId,amount — matches the controller's parser)
        String csv = "transactionId,amount\n1,1000\n2,2000\n3,3000\n";

        // 3. Encrypt CSV with AES-256-CBC
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] encryptedFile = aesCipher.doFinal(csv.getBytes());

        // 4. Encrypt AES key with RSA (OAEP SHA-256)
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 5. Build multipart request with valid API key
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-API-Key", "test-api-key");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(encryptedFile) {
            @Override
            public String getFilename() {
                return "test-batch.csv";
            }
        });
        body.add("batchId", "1");
        body.add("merchantId", "M001");
        body.add("encryptedAesKey", Base64.getEncoder().encodeToString(encryptedAesKey));
        body.add("iv", Base64.getEncoder().encodeToString(iv));
        body.add("csvFilename", "test-batch.csv");
        body.add("transactionCount", "3");

        // 6. Send the encrypted batch
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/internal/batch-upload",
                new HttpEntity<>(body, headers),
                Map.class);

        // 7. Verify response
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PROCESSED", response.getBody().get("batchStatus"));

        int approved = (int) response.getBody().get("approved");
        int rejected = (int) response.getBody().get("rejected");
        assertEquals(3, approved + rejected,
                "Total approved + rejected should equal the number of CSV rows");
    }

    // ────────────────────────────────────────────────────────────────
    // Helper: parse PEM public key → java.security.PublicKey
    // ────────────────────────────────────────────────────────────────
    private PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(keyBytes));
    }
}
