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
    @SuppressWarnings("unchecked")
    @PostMapping
    public ResponseEntity<?> createMerchant(@RequestBody Map<String, Object> payload) {
        try {
            MerchantInfo merchantInfo = new MerchantInfo();
            merchantInfo.setMerchantId((String) payload.get("merchantId"));
            merchantInfo.setName((String) payload.get("name"));
            merchantInfo.setEmail((String) payload.get("email"));
            merchantInfo.setUsername((String) payload.get("username"));
            merchantInfo.setPassword((String) payload.get("password"));
            merchantInfo.setCompany((String) payload.get("company"));
            merchantInfo.setContact((String) payload.get("contact"));
            merchantInfo.setPhone((String) payload.get("phone"));
            merchantInfo.setAddress((String) payload.get("address"));

            String merchantAccNum = payload.get("merchantAccNum") != null
                    ? (String) payload.get("merchantAccNum")
                    : "ACC-" + payload.get("merchantId") + "-001";
            String merchantAccName = payload.get("merchantAccName") != null
                    ? (String) payload.get("merchantAccName")
                    : (String) payload.get("name");
            String txnCurrency = payload.get("transactionCurrency") != null
                    ? (String) payload.get("transactionCurrency") : "MYR";
            String settleCurrency = payload.get("settlementCurrency") != null
                    ? (String) payload.get("settlementCurrency") : "MYR";
            String createdBy = payload.get("createdBy") != null
                    ? (String) payload.get("createdBy") : "system";

            // File profile parameters
            String fileType = (String) payload.get("fileType");
            String fieldDelimiter = (String) payload.get("fieldDelimiter");
            Boolean hasHeader = payload.get("hasHeader") != null
                    ? (Boolean) payload.get("hasHeader") : true;
            String dateFormat = (String) payload.get("dateFormat");
            List<Map<String, Object>> fieldMappings
                    = (List<Map<String, Object>>) payload.get("fieldMappings");

            MerchantInfo created = merchantService.createMerchant(
                    merchantInfo, merchantAccNum, merchantAccName,
                    txnCurrency, settleCurrency, createdBy,
                    fileType, fieldDelimiter, hasHeader, dateFormat, fieldMappings);

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
     * GET /api/merchants/check-username?username= Checks if a username already
     * exists in merchant_info.
     */
    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Boolean>> checkMerchantUsername(@RequestParam String username) {
        Map<String, Boolean> result = new HashMap<>();
        result.put("exists", merchantService.usernameExists(username));
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

    /**
     * GET /api/merchants/{merchantId} Returns a single merchant by ID.
     */
    @GetMapping("/{merchantId}")
    public ResponseEntity<?> getMerchantById(@PathVariable String merchantId) {
        return merchantService.getMerchantById(merchantId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /api/merchants/{merchantId} Updates an existing merchant's info.
     */
    @PutMapping("/{merchantId}")
    public ResponseEntity<?> updateMerchant(@PathVariable String merchantId,
            @RequestBody Map<String, Object> payload) {
        try {
            MerchantInfo updates = new MerchantInfo();
            updates.setName((String) payload.get("name"));
            updates.setEmail((String) payload.get("email"));
            updates.setCompany((String) payload.get("company"));
            updates.setContact((String) payload.get("contact"));
            updates.setPhone((String) payload.get("phone"));
            updates.setAddress((String) payload.get("address"));
            if (payload.get("password") != null) {
                updates.setPassword((String) payload.get("password"));
            }
            MerchantInfo updated = merchantService.updateMerchant(merchantId, updates);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * DELETE /api/merchants/{merchantId} Deletes a merchant.
     */
    @DeleteMapping("/{merchantId}")
    public ResponseEntity<?> deleteMerchant(@PathVariable String merchantId) {
        try {
            merchantService.deleteMerchant(merchantId);
            return ResponseEntity.ok(Map.of("message", "Merchant deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
