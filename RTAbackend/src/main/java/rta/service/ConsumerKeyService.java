package rta.service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import rta.entity.SystemRsaKeyRequest;
import rta.repository.SystemRsaKeyRequestRepository;

/**
 * Fetches and caches the consumer system's RSA public key over HTTPS. Trusts
 * the consumer's self-signed certificate.
 */
@Service
@Slf4j
public class ConsumerKeyService {

    @Value("${rta.consumer.base-url}")
    private String consumerBaseUrl;

    @Value("${rta.consumer.public-key-path}")
    private String publicKeyPath;

    @Value("${rta.consumer.api-key}")
    private String apiKey;

    private final AtomicReference<PublicKey> cachedPublicKey = new AtomicReference<>();
    private final SystemRsaKeyRequestRepository rsaKeyRequestRepository;
    private RestTemplate restTemplate;

    public ConsumerKeyService(SystemRsaKeyRequestRepository rsaKeyRequestRepository) {
        this.rsaKeyRequestRepository = rsaKeyRequestRepository;
    }

    @PostConstruct
    public void init() {
        this.restTemplate = buildTrustAllRestTemplate();
    }

    // Removed auto-fetch on startup — key will be fetched lazily on first use
    // @EventListener(ApplicationReadyEvent.class)
    // public void onApplicationReady() {
    //     fetchAndCachePublicKey();
    // }
    /**
     * Returns the cached consumer RSA public key. Re-fetches if not yet cached.
     */
    public PublicKey getConsumerPublicKey() {
        PublicKey key = cachedPublicKey.get();
        if (key == null) {
            fetchAndCachePublicKey();
            key = cachedPublicKey.get();
        }
        if (key == null) {
            throw new IllegalStateException("Consumer RSA public key is not available");
        }
        return key;
    }

    /**
     * Force refresh the cached key (e.g. after key rotation).
     */
    public void refreshKey() {
        fetchAndCachePublicKey();
    }

    /**
     * Fetches the RSA public key from the sendAuth system via GET
     * /api/internal/public-key. The sendAuth system generates its RSA key pair
     * on startup — we just need to fetch the public key.
     *
     * @return the RSA public key PEM string from the sendAuth system
     * @throws RuntimeException if the request fails
     */
    public String fetchRsaPublicKeyPem() {
        try {
            String url = consumerBaseUrl + publicKeyPath;
            log.info("[ConsumerKey] Fetching RSA public key from sendAuth: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", apiKey);
            headers.setAccept(java.util.List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String pem = response.getBody().trim();
                PublicKey publicKey = parsePemPublicKey(pem);
                cachedPublicKey.set(publicKey);
                log.info("[ConsumerKey] RSA public key fetched and cached from sendAuth system");
                return pem;
            } else {
                throw new RuntimeException("SendAuth returned HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[ConsumerKey] Failed to fetch RSA public key from sendAuth: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch RSA public key from sendAuth system: " + e.getMessage(), e);
        }
    }

    private void fetchAndCachePublicKey() {
        try {
            String url = consumerBaseUrl + publicKeyPath;
            log.info("[ConsumerKey] Fetching public key from {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", apiKey);
            headers.setAccept(java.util.List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String pem = response.getBody().trim();
                PublicKey publicKey = parsePemPublicKey(pem);
                cachedPublicKey.set(publicKey);
                log.info("[ConsumerKey] Successfully cached consumer public key (algorithm={}, format={})",
                        publicKey.getAlgorithm(), publicKey.getFormat());
                trackConsumerKeyFetch(pem);
            } else {
                log.error("[ConsumerKey] Failed to fetch public key: HTTP {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[ConsumerKey] Error fetching consumer public key: {}", e.getMessage(), e);
        }
    }

    private void trackConsumerKeyFetch(String pem) {
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            SystemRsaKeyRequest record = SystemRsaKeyRequest.builder()
                    .requestedBy("SYSTEM")
                    .publicKeyPem(pem)
                    .status("ACTIVE")
                    .requestedAt(now)
                    .expiresAt(now.plusDays(30))
                    .ipAddress("localhost")
                    .keyType("CONSUMER_KEY_FETCH")
                    .build();
            rsaKeyRequestRepository.save(record);
            log.info("[ConsumerKey] Consumer key fetch tracked in system_rsa_key_request");
        } catch (Exception e) {
            log.warn("[ConsumerKey] Failed to track consumer key fetch: {}", e.getMessage());
        }
    }

    private PublicKey parsePemPublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * Build a RestTemplate that trusts all certificates (for self-signed
     * consumer cert).
     */
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
            factory.setConnectTimeout(5000);

            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all RestTemplate", e);
        }
    }
}
