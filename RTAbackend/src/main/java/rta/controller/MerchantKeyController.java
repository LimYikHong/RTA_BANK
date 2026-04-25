package rta.controller;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.MerchantKey;
import rta.repository.MerchantInfoRepository;
import rta.repository.MerchantKeyRepository;
import rta.service.RsaKeyService;

/**
 * MerchantKeyController — REST endpoints for managing merchant RSA keys.
 *
 * <ul>
 * <li>GET /api/merchant-keys/{merchantId}/public-key — Retrieve the active RSA
 * public key for a merchant (used by merchant upload systems).</li>
 * <li>POST /api/merchant-keys/{merchantId}/rotate — Rotate (re-generate) the
 * RSA key pair for a merchant.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/merchant-keys")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"https://localhost:4200", "https://localhost:8086"})
public class MerchantKeyController {

    private final RsaKeyService rsaKeyService;
    private final MerchantKeyRepository merchantKeyRepository;
    private final MerchantInfoRepository merchantInfoRepository;

    /**
     * GET /api/merchant-keys/{merchantId}/public-key Returns the active INBOUND
     * RSA public key in PEM format. Merchant uses this to encrypt batch file
     * uploads.
     */
    @GetMapping("/{merchantId}/public-key")
    public ResponseEntity<?> getPublicKey(@PathVariable String merchantId) {
        return rsaKeyService.getActiveInboundPublicKey(merchantId)
                .<ResponseEntity<?>>map(pem -> ResponseEntity.ok(Map.of(
                "merchantId", merchantId,
                "publicKeyPem", pem
        )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/merchant-keys/{merchantId}/status Returns summary info about the
     * merchant's active key.
     */
    @GetMapping("/{merchantId}/status")
    public ResponseEntity<?> getKeyStatus(@PathVariable String merchantId) {
        return rsaKeyService.getActiveKey(merchantId)
                .<ResponseEntity<?>>map(key -> ResponseEntity.ok(Map.of(
                "merchantId", merchantId,
                "keyId", key.getKeyId(),
                "version", key.getVersionNo(),
                "status", key.getStatus(),
                "activatedAt", key.getActivatedAt() != null ? key.getActivatedAt().toString() : "N/A",
                "expiresAt", key.getExpiresAt() != null ? key.getExpiresAt().toString() : "N/A"
        )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/merchant-keys/{merchantId}/detail Returns both INBOUND and
     * OUTBOUND key status for the merchant.
     */
    @GetMapping("/{merchantId}/detail")
    public ResponseEntity<?> getKeyDetail(@PathVariable String merchantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);

        // Look up merchant name
        String merchantName = merchantInfoRepository.findByMerchantId(merchantId)
                .map(m -> m.getName()).orElse("");
        result.put("merchantName", merchantName);

        // Inbound key (merchant encrypts uploads → bank decrypts)
        Optional<MerchantKey> inboundOpt = merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(merchantId, "ACTIVE", "INBOUND");
        result.put("inbound", buildKeyInfo(inboundOpt, "INBOUND"));

        // Outbound key (bank encrypts return files → merchant decrypts)
        Optional<MerchantKey> outboundOpt = merchantKeyRepository
                .findFirstByMerchantIdAndStatusAndKeyPurposeOrderByVersionNoDesc(merchantId, "ACTIVE", "OUTBOUND");
        result.put("outbound", buildKeyInfo(outboundOpt, "OUTBOUND"));

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildKeyInfo(Optional<MerchantKey> keyOpt, String purpose) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("purpose", purpose);
        if (keyOpt.isPresent()) {
            MerchantKey key = keyOpt.get();
            long daysElapsed = ChronoUnit.DAYS.between(key.getActivatedAt(), LocalDateTime.now());
            long daysRemaining = key.getExpiresAt() != null
                    ? ChronoUnit.DAYS.between(LocalDateTime.now(), key.getExpiresAt())
                    : 730 - daysElapsed;
            boolean expired = daysRemaining <= 0;
            boolean canRotate = daysElapsed >= 25 && daysElapsed < 30;

            info.put("hasKey", true);
            info.put("keyId", key.getKeyId());
            info.put("version", key.getVersionNo());
            info.put("status", expired ? "EXPIRED" : key.getStatus());
            info.put("activatedAt", key.getActivatedAt() != null ? key.getActivatedAt().toString() : null);
            info.put("expiresAt", key.getExpiresAt() != null ? key.getExpiresAt().toString() : null);
            info.put("daysElapsed", daysElapsed);
            info.put("daysRemaining", Math.max(0, daysRemaining));
            info.put("expired", expired);
            info.put("canRotate", canRotate);
        } else {
            info.put("hasKey", false);
            info.put("keyId", null);
            info.put("version", 0);
            info.put("status", "NO_KEY");
            info.put("activatedAt", null);
            info.put("expiresAt", null);
            info.put("daysElapsed", 0);
            info.put("daysRemaining", 0);
            info.put("expired", false);
            info.put("canRotate", false);
        }
        return info;
    }

    /**
     * POST /api/merchant-keys/{merchantId}/rotate Generates a new RSA key pair
     * for the merchant, deactivating the old one.
     */
    @PostMapping("/{merchantId}/rotate")
    public ResponseEntity<?> rotateKey(
            @PathVariable String merchantId,
            @RequestParam(value = "rotatedBy", defaultValue = "system") String rotatedBy) {
        try {
            MerchantKey[] keys = rsaKeyService.generateBothKeyPairs(merchantId, rotatedBy);
            MerchantKey inbound = keys[0];
            MerchantKey outbound = keys[1];
            log.info("Keys rotated for merchantId={}: INBOUND v{}, OUTBOUND v{}",
                    merchantId, inbound.getVersionNo(), outbound.getVersionNo());
            return ResponseEntity.ok(Map.of(
                    "merchantId", merchantId,
                    "inbound", Map.of(
                            "keyId", inbound.getKeyId(),
                            "version", inbound.getVersionNo(),
                            "purpose", "INBOUND",
                            "publicKeyPem", inbound.getPublicKeyPem(),
                            "activatedAt", inbound.getActivatedAt().toString(),
                            "expiresAt", inbound.getExpiresAt().toString()
                    ),
                    "outbound", Map.of(
                            "keyId", outbound.getKeyId(),
                            "version", outbound.getVersionNo(),
                            "purpose", "OUTBOUND",
                            "privateKeyPem", outbound.getPrivateKeyPem(),
                            "activatedAt", outbound.getActivatedAt().toString(),
                            "expiresAt", outbound.getExpiresAt().toString()
                    )
            ));
        } catch (Exception e) {
            log.error("Key rotation failed for merchantId={}", merchantId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Key rotation failed: " + e.getMessage()));
        }
    }
}
