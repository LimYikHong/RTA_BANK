package rta.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import rta.model.MerchantProfile;
import rta.repository.ProfileRepository;
import rta.repository.MerchantActivityLogRepository;
import rta.entity.MerchantActivityLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

@Service

/**
 * ProfileService
 * - Handles merchant authentication and profile CRUD.
 * - Stores profile photos on local disk and saves public URL path in DB.
 */
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final MerchantActivityLogRepository activityLogRepository;
    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    public ProfileService(ProfileRepository profileRepository, MerchantActivityLogRepository activityLogRepository) {
        this.profileRepository = profileRepository;
        this.activityLogRepository = activityLogRepository;
    }

    private void logActivity(String merchantId, String type, String description) {
        MerchantActivityLog log = new MerchantActivityLog();
        log.setMerchantId(merchantId);
        log.setActivityType(type);
        log.setDescription(description);
        log.setTimestamp(LocalDateTime.now());
        activityLogRepository.save(log);
    }

    /**
     * Authenticate by username/password.
     * - Throws RuntimeException on user not found or invalid password.
     */
    public MerchantProfile login(String username, String password) {
        MerchantProfile profile = profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!profile.getPassword().equals(password)) {
            logActivity(profile.getMerchantId(), "LOGIN_FAILED", "Invalid password for user: " + username);
            throw new RuntimeException("Invalid password");
        }

        // Don't log success here strictly if we are moving to 2FA
        // But for now, we leave it, the controller will decide what to return.
        return profile;
    }

    public String generate2FASecret(String username) {
        MerchantProfile profile = profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (profile.getTwoFactorSecret() != null && !profile.getTwoFactorSecret().isEmpty()) {
            return profile.getTwoFactorSecret();
        }

        final GoogleAuthenticatorKey key = gAuth.createCredentials();
        String secret = key.getKey();
        profile.setTwoFactorSecret(secret);
        // Do NOT enable it yet. User must verify first.
        profileRepository.save(profile);
        return secret;
    }

    public boolean verify2FA(String username, int code) {
        MerchantProfile profile = profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (profile.getTwoFactorSecret() == null) {
            return false;
        }

        boolean isCodeValid = gAuth.authorize(profile.getTwoFactorSecret(), code);

        if (isCodeValid) {
            // If not enabled yet, enable it now (first successful verification)
            if (!profile.isTwoFactorEnabled()) {
                profile.setTwoFactorEnabled(true);
                profileRepository.save(profile);
            }
            logActivity(profile.getMerchantId(), "LOGIN_2FA_SUCCESS", "User logged in with 2FA successfully");
        } else {
            logActivity(profile.getMerchantId(), "LOGIN_2FA_FAILED", "Invalid 2FA code for user: " + username);
        }
        return isCodeValid;
    }

    public MerchantProfile getProfileByUsername(String username) {
        return profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Register a new merchant profile.
     * - Rejects duplicate usernames.
     */
    public MerchantProfile register(MerchantProfile profile) {
        if (profileRepository.findByUsername(profile.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        MerchantProfile saved = profileRepository.save(profile);
        logActivity(saved.getMerchantId(), "REGISTER", "New merchant registered: " + saved.getUsername());
        return saved;
    }

    /**
     * Fetch profile by merchantId.
     * - Throws if not found.
     */
    public MerchantProfile getProfile(String merchantId) {
        return profileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("Merchant profile not found: " + merchantId));
    }

    /**
     * Update mutable profile fields.
     * - Copies selected fields from newProfile to existing record.
     */
    public MerchantProfile updateProfile(String merchantId, MerchantProfile newProfile) {
        MerchantProfile existing = profileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("Merchant profile not found: " + merchantId));

        existing.setName(newProfile.getName());
        existing.setEmail(newProfile.getEmail());
        existing.setCompany(newProfile.getCompany());
        existing.setContact(newProfile.getContact());
        existing.setAddress(newProfile.getAddress());
        existing.setJoinedOn(newProfile.getJoinedOn());

        MerchantProfile updated = profileRepository.save(existing);
        logActivity(merchantId, "UPDATE_PROFILE", "Profile updated");
        return updated;
    }

    /**
     * Save profile photo to disk and update profile with a public URL.
     * - Writes under "uploads/profile-photos" (relative to app working dir).
     * - Stores "/uploads/profile-photos/{uuid.ext}" as profilePhotoUrl.
     */
    public MerchantProfile uploadProfilePhoto(String merchantId, MultipartFile file) {
        MerchantProfile profile = getProfile(merchantId);

        if (file.isEmpty()) {
            throw new RuntimeException("Failed to store empty file.");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString() + fileExtension;

            Path uploadPath = Paths.get("uploads/profile-photos");
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(newFilename);
            Files.copy(file.getInputStream(), filePath);

            String fileUrl = "/uploads/profile-photos/" + newFilename;
            profile.setProfilePhotoUrl(fileUrl);

            MerchantProfile saved = profileRepository.save(profile);
            logActivity(merchantId, "UPLOAD_PHOTO", "Profile photo uploaded: " + newFilename);
            return saved;
        } catch (IOException e) {
            logActivity(merchantId, "UPLOAD_PHOTO_FAILED", "Failed to upload photo: " + e.getMessage());
            throw new RuntimeException("Failed to store file.", e);
        }
    }
}
