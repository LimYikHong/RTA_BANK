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

    // Key purpose constants
    public static final String PURPOSE_INBOUND = "INBOUND";
    public static final String PURPOSE_OUTBOUND = "OUTBOUND";

    /**
     * Generate TWO RSA key pairs for a merchant:
     * <ul>
     * <li><b>INBOUND</b> — bank keeps private key, sends public key to
     * merchant. Merchant uses it to encrypt batch file uploads.</li>
     * <li><b>OUTBOUND</b> — bank keeps public key, sends private key to
     * merchant. Bank uses it to encrypt return batch files for the
     * merchant.</li>
     * </ul>
     *
     * @return array of [inboundKey, outboundKey]
     */
    @Transactional
    public MerchantKey[] generateBothKeyPairs(String merchantId, String createdBy) {
        MerchantKey inbound = generateKeyPair(merchantId, createdBy, PURPOSE_INBOUND);
        MerchantKey outbound = generateKeyPair(merchantId, createdBy, PURPOSE_OUTBOUND);
        log.info("Generated INBOUND (v{}) + OUTBOUND (v{}) RSA key pairs for merchantId={}",
                inbound.getVersionNo(), outbound.getVersionNo(), merchantId);
        return new MerchantKey[]{inbound, outbound};
    }

    /**
     * Generate a single RSA key pair for a specific purpose.
     *
     * @param merchantId the merchant to generate keys for
     * @param createdBy audit trail — who triggered the generation
     * @param purpose INBOUND or OUTBOUND
     * @return the persisted {@link MerchantKey}
     */
    @Transactional
    public MerchantKey generateKeyPair(String merchantId, String createdBy, String purpose) {
        log.info("Generating RSA-{} {} key pair for merchantId={}", RSA_KEY_SIZE, purpose, merchantId);

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

            // --- Deactivate any previous ACTIVE key with the same purpose ---
            merchantKeyRepository
                    .findByMerchantIdAndStatusAndKeyPurpose(merchantId, "ACTIVE", purpose)
                    .ifPresent(oldKey -> {
                        oldKey.setStatus("ROTATED");
                        merchantKeyRepository.save(oldKey);
                        log.info("Rotated previous {} key version={} for merchantId={}",
                                purpose, oldKey.getVersionNo(), merchantId);
                    });

            // --- Persist the new key pair ---
            MerchantKey merchantKey = MerchantKey.builder()
                    .merchantId(merchantId)
                    .versionNo(nextVersion)
                    .keyProvider("RTA_BANK")
                    .keystoreAlias(merchantId + "-" + purpose.toLowerCase() + "-v" + nextVersion)
                    .keyPurpose(purpose)
                    .publicKeyPem(publicKeyPem)
                    .privateKeyPem(privateKeyPem)
                    .status("ACTIVE")
                    .activatedAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusYears(2))
                    .createdAt(LocalDateTime.now())
                    .build();

            MerchantKey saved = merchantKeyRepository.save(merchantKey);
            log.info("RSA {} key pair persisted: keyId={}, merchantId={}, version={}",
                    purpose, saved.getKeyId(), merchantId, nextVersion);

            return saved;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA key generation failed — algorithm not available", e);
        }
    }

    /**
     * Backward-compatible: generates an INBOUND key pair (original behavior).
     */
    @Transactional
    public MerchantKey generateKeyPair(String merchantId, String createdBy) {
        return generateKeyPair(merchantId, createdBy, PURPOSE_INBOUND);
    }

    // ─── INBOUND key getters (merchant→bank encryption) ───────────────────
    /**
     * Get the INBOUND public key PEM — given to merchant so they can encrypt
     * uploads.
     */
    public Optional<String> getActiveInboundPublicKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                        merchantId, "ACTIVE", PURPOSE_INBOUND)
                .map(MerchantKey::getPublicKeyPem);
    }

    /**
     * Get the INBOUND key entity (includes private key) — used by bank to
     * decrypt uploads.
     */
    public Optional<MerchantKey> getActiveInboundKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                        merchantId, "ACTIVE", PURPOSE_INBOUND);
    }

    // ─── OUTBOUND key getters (bank→merchant encryption) ──────────────────
    /**
     * Get the OUTBOUND public key PEM — used by bank to encrypt return files.
     */
    public Optional<String> getActiveOutboundPublicKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                        merchantId, "ACTIVE", PURPOSE_OUTBOUND)
                .map(MerchantKey::getPublicKeyPem);
    }

    /**
     * Get the OUTBOUND key entity — the private key is sent to the merchant so
     * they can decrypt return files.
     */
    public Optional<MerchantKey> getActiveOutboundKey(String merchantId) {
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(
                        merchantId, "ACTIVE", PURPOSE_OUTBOUND);
    }

    // ─── Legacy/backward-compatible getters (fallback to any active key) ──
    /**
     * Get the active RSA public key PEM for a merchant (legacy — prefers
     * INBOUND).
     */
    public Optional<String> getActivePublicKey(String merchantId) {
        Optional<String> inbound = getActiveInboundPublicKey(merchantId);
        if (inbound.isPresent()) {
            return inbound;
        }
        // Fallback for old keys without purpose
        return merchantKeyRepository
                .findFirstByMerchantIdAndStatusOrderByVersionNoDesc(merchantId, "ACTIVE")
                .map(MerchantKey::getPublicKeyPem);
    }

    /**
     * Get the active MerchantKey entity (legacy — prefers INBOUND).
     */
    public Optional<MerchantKey> getActiveKey(String merchantId) {
        Optional<MerchantKey> inbound = getActiveInboundKey(merchantId);
        if (inbound.isPresent()) {
            return inbound;
        }
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
