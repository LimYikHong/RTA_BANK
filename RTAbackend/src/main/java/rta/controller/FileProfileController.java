package rta.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.service.FileProfileService;

import java.util.*;

/**
 * FileProfileController - REST API for managing merchant file profiles and
 * field mappings.
 */
@RestController
@RequestMapping("/api/file-profiles")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:4200", "https://localhost:4200"})
public class FileProfileController {

    private final FileProfileService fileProfileService;

    /**
     * GET /api/file-profiles/required-fields Returns the list of required
     * canonical fields.
     */
    @GetMapping("/required-fields")
    public ResponseEntity<List<String>> getRequiredFields() {
        return ResponseEntity.ok(fileProfileService.getRequiredCanonicalFields());
    }

    /**
     * GET /api/file-profiles/merchant/{merchantId} Returns the active file
     * profile + field mappings for a merchant.
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<?> getProfileByMerchant(@PathVariable String merchantId) {
        Optional<RtaFileProfile> profileOpt = fileProfileService.getActiveProfile(merchantId);
        if (profileOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("hasProfile", false));
        }

        RtaFileProfile profile = profileOpt.get();
        List<RtaFieldMapping> mappings = fileProfileService.getFieldMappings(profile.getProfileId());

        Map<String, Object> result = new HashMap<>();
        result.put("hasProfile", true);
        result.put("profile", profile);
        result.put("fieldMappings", mappings);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/file-profiles/{profileId}/mappings Returns the field mappings
     * for a specific profile.
     */
    @GetMapping("/{profileId}/mappings")
    public ResponseEntity<List<RtaFieldMapping>> getFieldMappings(@PathVariable Long profileId) {
        return ResponseEntity.ok(fileProfileService.getFieldMappings(profileId));
    }

    /**
     * PUT /api/file-profiles/{profileId}/mappings Updates the field mappings
     * for a specific profile.
     */
    @SuppressWarnings("unchecked")
    @PutMapping("/{profileId}/mappings")
    public ResponseEntity<?> updateFieldMappings(
            @PathVariable Long profileId,
            @RequestBody Map<String, Object> payload) {
        try {
            List<Map<String, Object>> fieldMappings = (List<Map<String, Object>>) payload.get("fieldMappings");
            String modifiedBy = (String) payload.getOrDefault("modifiedBy", "system");

            if (fieldMappings == null || fieldMappings.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Field mappings are required"));
            }

            // Validate all required canonical fields are present
            List<String> requiredFields = fileProfileService.getRequiredCanonicalFields();
            Set<String> providedFields = new HashSet<>();
            for (Map<String, Object> fm : fieldMappings) {
                providedFields.add((String) fm.get("canonicalField"));
            }

            List<String> missingFields = new ArrayList<>();
            for (String required : requiredFields) {
                if (!providedFields.contains(required)) {
                    missingFields.add(required);
                }
            }

            if (!missingFields.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Missing required canonical fields",
                        "missingFields", missingFields
                ));
            }

            fileProfileService.updateFieldMappings(profileId, fieldMappings, modifiedBy);
            return ResponseEntity.ok(Map.of("message", "Field mappings updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
