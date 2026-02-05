package rta.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import rta.model.MerchantProfile;
import rta.repository.ProfileRepository;
import rta.service.ProfileService;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@CrossOrigin(origins = { "http://localhost:4200", "http://localhost:8088" })
public class ProfileController {

    private final ProfileService profileService;
    private final ProfileRepository profileRepository;

    /**
     * POST /api/profile/register
     * - Creates a new merchant profile (demo registration endpoint).
     */
    @PostMapping("/register")
    public ResponseEntity<MerchantProfile> register(@RequestBody MerchantProfile profile) {
        return ResponseEntity.ok(profileService.register(profile));
    }

    /**
     * POST /api/profile/login
     * - Simple login that delegates to ProfileService.
     * - Returns 200 with profile on success; 401 with message on failure.
     */

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody MerchantProfile credentials) {
        try {
            MerchantProfile profile = profileService.login(
                    credentials.getUsername(),
                    credentials.getPassword());
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }

    /**
     * GET /api/profile/{merchantId}
     * - Fetches a merchant profile by merchantId.
     */
    @GetMapping("/{merchantId}")
    public ResponseEntity<MerchantProfile> getProfile(@PathVariable String merchantId) {
        MerchantProfile profile = profileService.getProfile(merchantId);
        return ResponseEntity.ok(profile);
    }

    /**
     * PUT /api/profile/{merchantId}
     * - Updates profile fields (company/contact/address/etc).
     */
    @PutMapping("/{merchantId}")
    public ResponseEntity<MerchantProfile> updateProfile(
            @PathVariable String merchantId,
            @RequestBody MerchantProfile updatedProfile) {
        MerchantProfile updated = profileService.updateProfile(merchantId, updatedProfile);
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/profile/{merchantId}/photo
     * - Uploads a profile photo (multipart/form-data).
     * - Returns the updated profile including new photo URL/path.
     */
    @PostMapping("/{merchantId}/photo")
    public ResponseEntity<MerchantProfile> uploadProfilePhoto(
            @PathVariable String merchantId,
            @RequestParam("profilePhoto") MultipartFile file) {
        MerchantProfile updatedProfile = profileService.uploadProfilePhoto(merchantId, file);
        return ResponseEntity.ok(updatedProfile);
    }

}
