package rta.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import rta.model.UserProfile;
import rta.repository.ProfileRepository;
import rta.entity.RtaRole;
import rta.entity.RtaUserRole;
import rta.repository.RtaRoleRepository;
import rta.repository.RtaUserRoleRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

@Service

/**
 * ProfileService - Handles user authentication and profile CRUD. - Stores
 * profile photos on local disk and saves public URL path in DB.
 */
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final RtaRoleRepository roleRepository;
    private final RtaUserRoleRepository userRoleRepository;
    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    public ProfileService(ProfileRepository profileRepository, RtaRoleRepository roleRepository, RtaUserRoleRepository userRoleRepository) {
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * Authenticate by username/password. - Throws RuntimeException on user not
     * found or invalid password.
     */
    public UserProfile login(String username, String password) {
        UserProfile profile = profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!profile.getPassword().equals(password)) {
            throw new RuntimeException("Invalid password");
        }

        // Don't log success here strictly if we are moving to 2FA
        // But for now, we leave it, the controller will decide what to return.
        return profile;
    }

    public String generate2FASecret(String username) {
        UserProfile profile = profileRepository.findByUsername(username)
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
        UserProfile profile = profileRepository.findByUsername(username)
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
        }
        return isCodeValid;
    }

    public UserProfile getProfileByUsername(String username) {
        return profileRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Register a new user profile. - Rejects duplicate usernames.
     */
    public UserProfile register(UserProfile profile) {
        if (profileRepository.findByUsername(profile.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        UserProfile saved = profileRepository.save(profile);
        return saved;
    }

    /**
     * Fetch profile by merchantId. - Throws if not found.
     */
    public UserProfile getProfile(String merchantId) {
        return profileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("User profile not found: " + merchantId));
    }

    /**
     * Update mutable profile fields. - Copies selected fields from newProfile
     * to existing record.
     */
    public UserProfile updateProfile(String merchantId, UserProfile newProfile) {
        UserProfile existing = profileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new RuntimeException("User profile not found: " + merchantId));

        existing.setName(newProfile.getName());
        existing.setEmail(newProfile.getEmail());
        existing.setCompany(newProfile.getCompany());
        existing.setContact(newProfile.getContact());
        existing.setAddress(newProfile.getAddress());
        existing.setJoinedOn(newProfile.getJoinedOn());

        UserProfile updated = profileRepository.save(existing);
        return updated;
    }

    /**
     * Save profile photo to disk and update profile with a public URL. - Writes
     * under "uploads/profile-photos" (relative to app working dir). - Stores
     * "/uploads/profile-photos/{uuid.ext}" as profilePhotoUrl.
     */
    public UserProfile uploadProfilePhoto(String merchantId, MultipartFile file) {
        UserProfile profile = getProfile(merchantId);

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

            UserProfile saved = profileRepository.save(profile);
            return saved;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file.", e);
        }
    }

    /**
     * Create a new user with a specific role.
     */
    public UserProfile createUser(UserProfile user, String roleName) {
        if (profileRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        if (profileRepository.findByMerchantId(user.getMerchantId()).isPresent()) {
            throw new RuntimeException("User ID already exists");
        }

        user.setJoinedOn(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        // Default values if missing
        if (user.getStatus() == null) {
            user.setStatus("ACTIVE");
        }
        if (!user.isTwoFactorEnabled()) {
            user.setTwoFactorEnabled(false);
        }
        user.setIsEnabled(true);

        // createdBy is set from frontend (current logged-in user's username)
        String creator = user.getCreatedBy() != null ? user.getCreatedBy() : "unknown";
        user.setLastModifiedBy(creator);

        UserProfile savedUser = profileRepository.save(user);

        // Assign Role
        RtaRole role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));

        RtaUserRole userRole = new RtaUserRole();
        userRole.setUser(savedUser);
        userRole.setRole(role);
        userRole.setAssignedBy(creator);
        userRoleRepository.save(userRole);

        return savedUser;
    }

    /**
     * Get all users.
     */
    public List<UserProfile> getAllUsers() {
        return profileRepository.findAll();
    }

    /**
     * Search users by keyword (matches username, name, email, merchantId,
     * company).
     */
    public List<UserProfile> searchUsers(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return profileRepository.findAll();
        }
        return profileRepository.searchByKeyword(keyword.trim());
    }

    /**
     * Get the role name for a given user.
     */
    public String getUserRole(Long userId) {
        List<RtaUserRole> userRoles = userRoleRepository.findByUser_Id(userId);
        if (userRoles.isEmpty()) {
            return "N/A";
        }
        return userRoles.get(0).getRole().getRoleName();
    }
}
