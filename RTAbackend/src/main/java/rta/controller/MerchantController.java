package rta.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rta.entity.MerchantInfo;
import rta.service.MerchantService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MerchantController - REST API for merchant management. - POST /api/merchants
 * : Create a new merchant (+ Kafka event) - GET /api/merchants : List all
 * merchants - GET /api/merchants/check-id : Check if merchantId exists
 */
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:4200", "https://localhost:4200"})
public class MerchantController {

    private final MerchantService merchantService;

    /**
     * POST /api/merchants Creates a new merchant with bank account info.
     * Request body example: { "merchantId": "M999", "merchantName": "New
     * Merchant", "merchantBank": "RTA Bank", "merchantCode": "M999",
     * "merchantPhoneNum": "012-345-6789", "merchantAddress": "123 Street",
     * "merchantContactPerson": "John", "merchantAccNum": "ACC-M999-001",
     * "merchantAccName": "New Merchant", "transactionCurrency": "MYR",
     * "settlementCurrency": "MYR", "createdBy": "bank_admin" }
     */
    @PostMapping
    public ResponseEntity<?> createMerchant(@RequestBody Map<String, String> payload) {
        try {
            MerchantInfo merchantInfo = new MerchantInfo();
            merchantInfo.setMerchantId(payload.get("merchantId"));
            merchantInfo.setName(payload.get("name"));
            merchantInfo.setEmail(payload.get("email"));
            merchantInfo.setUsername(payload.get("username"));
            merchantInfo.setPassword(payload.get("password"));
            merchantInfo.setCompany(payload.get("company"));
            merchantInfo.setContact(payload.get("contact"));
            merchantInfo.setPhone(payload.get("phone"));
            merchantInfo.setAddress(payload.get("address"));

            String merchantAccNum = payload.getOrDefault("merchantAccNum", "ACC-" + payload.get("merchantId") + "-001");
            String merchantAccName = payload.getOrDefault("merchantAccName", payload.get("name"));
            String txnCurrency = payload.getOrDefault("transactionCurrency", "MYR");
            String settleCurrency = payload.getOrDefault("settlementCurrency", "MYR");
            String createdBy = payload.getOrDefault("createdBy", "system");

            MerchantInfo created = merchantService.createMerchant(
                    merchantInfo, merchantAccNum, merchantAccName,
                    txnCurrency, settleCurrency, createdBy);

            return ResponseEntity.ok(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * GET /api/merchants Lists all merchants.
     */
    @GetMapping
    public ResponseEntity<List<MerchantInfo>> getAllMerchants() {
        return ResponseEntity.ok(merchantService.getAllMerchants());
    }

    /**
     * GET /api/merchants/check-id?merchantId= Checks if a merchantId already
     * exists.
     */
    @GetMapping("/check-id")
    public ResponseEntity<Map<String, Boolean>> checkMerchantId(@RequestParam String merchantId) {
        Map<String, Boolean> result = new HashMap<>();
        result.put("exists", merchantService.merchantIdExists(merchantId));
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/merchants/next-id Returns the next auto-generated merchant ID
     * (M001, M002, ...).
     */
    @GetMapping("/next-id")
    public ResponseEntity<Map<String, String>> getNextMerchantId() {
        Map<String, String> result = new HashMap<>();
        result.put("nextId", merchantService.generateNextMerchantId());
        return ResponseEntity.ok(result);
    }
}
