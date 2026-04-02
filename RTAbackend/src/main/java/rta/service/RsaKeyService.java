package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rta.entity.MerchantKey;
import rta.repository.MerchantKeyRepository;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * RsaKeyService — Generates and manages RSA key pairs for merchants.
 *
 * <p>
 * Each merchant gets a 2048-bit RSA key pair on creation:
 * <ul>
 * <li><b>Public key</b> — distributed to the merchant's upload system so it can
 * encrypt the AES session key.</li>
 * <li><b>Private key</b> — stored in the bank DB and used by
 * {@link FileDecryptionService} to decrypt received files.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RsaKeyService {

    private final MerchantKeyRepository merchantKeyRepository;

    private static final int RSA_KEY_SIZE = 2048;

    /**
     * Generate a new RSA key pair for the given merchant and persist it.
     *
     * @param merchantId the merchant to generate keys for
     * @param createdBy audit trail — who triggered the generation
     * @return the persisted {@link MerchantKey} with PEM-encoded keys
     */
    @Transactional
    public MerchantKey generateKeyPair(String merchantId, String createdBy) {
        log.info("Generating RSA-{} key pair for merchantId={}", RSA_KEY_SIZE, merchantId);

        try {
            // --- Generate RSA key pair ---
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();

            // --- Encode to PEM format ---
            String publicKeyPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());
            String privateKeyPem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

            // --- Determine version number (increment from latest) ---
            int nextVersion = merchantKeyRepository
                    .findByMerchantIdOrderByVersionNoDesc(merchantId)
                    .stream()
                    .findFirst()
                    .map(k -> k.getVersionNo() + 1)
                    .orElse(1);

            // --- Deactivate any previous ACTIVE key for this merchant ---
            merchantKeyRepository
                    .findByMerchantIdAndStatus(merchantId, "ACTIVE")
                    .ifPresent(oldKey -> {
                        oldKey.setStatus("ROTATED");
                        merchantKeyRepository.save(oldKey);
                        log.info("Rotated previous key version={} for merchantId={}",
                                oldKey.getVersionNo(), merchantId);
                    });

            // --- Persist the new key pair ---
            MerchantKey merchantKey = MerchantKey.builder()
                    .merchantId(merchantId)
                    .versionNo(nextVersion)
                    .keyProvider("RTA_BANK")
                    .keystoreAlias(merchantId + "-v" + nextVersion)
                    .publicKeyPem(publicKeyPem)
                    .privateKeyPem(privateKeyPem)
                    .status("ACTIVE")
                    .activatedAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusYears(2))
                    .createdAt(LocalDateTime.now())
                    .build();

            MerchantKey saved = merchantKeyRepository.save(merchantKey);
            log.info("RSA key pair persisted: keyId={}, merchantId={}, version={}",
                    saved.getKeyId(), merchantId, nextVersion);

            return saved;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA key generation failed — algorithm not available", e);
        }
    }

    /**
     * Get the active RSA public key PEM for a merchant.
     *
     * @param merchantId the merchant ID
     * @return the PEM-encoded public key, or empty if no active key exists
     */
    public Optional<String> getActivePublicKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusOrderByVersionNoDesc(merchantId, "ACTIVE")
                .map(MerchantKey::getPublicKeyPem);
    }

    /**
     * Get the active MerchantKey entity (includes private key) for decryption.
     *
     * @param merchantId the merchant ID
     * @return the active key, or empty
     */
    public Optional<MerchantKey> getActiveKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusOrderByVersionNoDesc(merchantId, "ACTIVE");
    }

    // -----------------------------------------------------------------------
    // PEM Encoding Helpers
    // -----------------------------------------------------------------------
    /**
     * Wraps DER-encoded key bytes into PEM format.
     *
     * @param type "PUBLIC KEY" or "PRIVATE KEY"
     * @param der the raw key bytes
     * @return PEM string with BEGIN/END markers and Base64 body
     */
    private String toPem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n"
                + base64 + "\n"
                + "-----END " + type + "-----";
    }
}
