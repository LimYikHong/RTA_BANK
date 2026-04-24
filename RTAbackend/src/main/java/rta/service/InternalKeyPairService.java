package rta.service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import rta.entity.SystemRsaKeyRequest;
import rta.repository.SystemRsaKeyRequestRepository;

/**
 * Manages the system-level RSA key pair used for the internal channel.
 * Generates a 2048-bit RSA key pair on startup and holds it in memory. The
 * public key is served to producer systems; the private key is used to decrypt
 * incoming AES session keys.
 */
@Service
@Slf4j
public class InternalKeyPairService {

    private static final int RSA_KEY_SIZE = 2048;

    private KeyPair keyPair;
    private final SystemRsaKeyRequestRepository rsaKeyRequestRepository;

    public InternalKeyPairService(SystemRsaKeyRequestRepository rsaKeyRequestRepository) {
        this.rsaKeyRequestRepository = rsaKeyRequestRepository;
    }

    @PostConstruct
    public void init() {
        generateKeyPair();
    }

    private void generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE, new SecureRandom());
            this.keyPair = generator.generateKeyPair();
            log.info("[InternalKeyPair] RSA-{} key pair generated successfully", RSA_KEY_SIZE);
            trackKeyGeneration();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    private void trackKeyGeneration() {
        try {
            LocalDateTime now = LocalDateTime.now();
            SystemRsaKeyRequest record = SystemRsaKeyRequest.builder()
                    .requestedBy("SYSTEM")
                    .publicKeyPem(getPublicKeyPem())
                    .status("ACTIVE")
                    .requestedAt(now)
                    .expiresAt(now.plusDays(30))
                    .ipAddress("localhost")
                    .keyType("INTERNAL_KEY_PAIR")
                    .build();
            rsaKeyRequestRepository.save(record);
            log.info("[InternalKeyPair] Key generation tracked in system_rsa_key_request");
        } catch (Exception e) {
            log.warn("[InternalKeyPair] Failed to track key generation: {}", e.getMessage());
        }
    }

    /**
     * Returns the public key in PEM format.
     */
    public String getPublicKeyPem() {
        byte[] encoded = keyPair.getPublic().getEncoded();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    /**
     * Returns the raw PublicKey object.
     */
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    /**
     * Returns the raw PrivateKey object (used for decrypting incoming AES
     * keys).
     */
    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    /**
     * Regenerate the key pair (e.g. for key rotation).
     */
    public void rotateKeyPair() {
        generateKeyPair();
        log.info("[InternalKeyPair] Key pair rotated (tracked in DB)");
    }
}
