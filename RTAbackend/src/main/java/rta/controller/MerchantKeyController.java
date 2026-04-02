package rta.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rta.entity.MerchantKey;
import rta.service.RsaKeyService;

import java.util.Map;

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

    /**
     * GET /api/merchant-keys/{merchantId}/public-key Returns the active RSA
     * public key in PEM format for the given merchant. Merchant upload systems
     * call this to get the key for encrypting files.
     */
    @GetMapping("/{merchantId}/public-key")
    public ResponseEntity<?> getPublicKey(@PathVariable String merchantId) {
        return rsaKeyService.getActivePublicKey(merchantId)
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
     * POST /api/merchant-keys/{merchantId}/rotate Generates a new RSA key pair
     * for the merchant, deactivating the old one.
     */
    @PostMapping("/{merchantId}/rotate")
    public ResponseEntity<?> rotateKey(
            @PathVariable String merchantId,
            @RequestParam(value = "rotatedBy", defaultValue = "system") String rotatedBy) {
        try {
            MerchantKey newKey = rsaKeyService.generateKeyPair(merchantId, rotatedBy);
            log.info("Key rotated for merchantId={}, newVersion={}", merchantId, newKey.getVersionNo());
            return ResponseEntity.ok(Map.of(
                    "merchantId", merchantId,
                    "keyId", newKey.getKeyId(),
                    "version", newKey.getVersionNo(),
                    "status", newKey.getStatus(),
                    "publicKeyPem", newKey.getPublicKeyPem(),
                    "activatedAt", newKey.getActivatedAt().toString(),
                    "expiresAt", newKey.getExpiresAt().toString()
            ));
        } catch (Exception e) {
            log.error("Key rotation failed for merchantId={}", merchantId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Key rotation failed: " + e.getMessage()));
        }
    }
}
