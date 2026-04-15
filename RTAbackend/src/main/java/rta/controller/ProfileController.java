package rta.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import rta.model.UserProfile;
import rta.repository.ProfileRepository;
import rta.service.AuditLogService;
import rta.service.ProfileService;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:8088"})
public class ProfileController {

    private final ProfileService profileService;
    private final ProfileRepository profileRepository;
    private final AuditLogService auditLogService;

    public ProfileController(ProfileService profileService, ProfileRepository profileRepository, AuditLogService auditLogService) {
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * POST /api/profile/register - Creates a new user profile (demo
     * registration endpoint).
     */
    @PostMapping("/register")
    public ResponseEntity<UserProfile> register(@RequestBody UserProfile profile) {
        return ResponseEntity.ok(profileService.register(profile));
    }

    /**
     * POST /api/profile/login - Simple login that delegates to ProfileService.
     * - Returns 200 with profile on success; 401 with message on failure.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserProfile credentials) {
        try {
            UserProfile profile = profileService.login(
                    credentials.getUsername(),
                    credentials.getPassword());
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }

    /**
     * GET /api/profile/{userId} - Fetches a user profile by userId.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> getProfile(@PathVariable String userId) {
        UserProfile profile = profileService.getProfile(userId);
        return ResponseEntity.ok(profile);
    }

    /**
     * PUT /api/profile/{userId} - Updates profile fields
     * (company/contact/address/etc).
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserProfile> updateProfile(
            @PathVariable String userId,
            @RequestBody UserProfile updatedProfile,
            HttpServletRequest request) {
        UserProfile updated = profileService.updateProfile(userId, updatedProfile);
        try {
            String editorUserId = getAuthenticatedUserId();
            auditLogService.logUserActivity("EDIT_USER", editorUserId, userId,
                    "Edited profile of user '" + userId + "'", "SUCCESS",
                    request.getRemoteAddr());
        } catch (Exception ignored) {
            // audit logging must never break the main flow
        }
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/profile/{userId}/photo - Uploads a profile photo
     * (multipart/form-data). - Returns the updated profile including new photo
     * URL/path.
     */
    @PostMapping("/{userId}/photo")
    public ResponseEntity<UserProfile> uploadProfilePhoto(
            @PathVariable String userId,
            @RequestParam("profilePhoto") MultipartFile file) {
        UserProfile updatedProfile = profileService.uploadProfilePhoto(userId, file);
        return ResponseEntity.ok(updatedProfile);
    }

    /**
     * GET /api/profile/{userId}/role - Returns the role name for a given user.
     */
    @GetMapping("/{userId}/role")
    public ResponseEntity<Map<String, String>> getUserRole(@PathVariable String userId) {
        UserProfile user = profileService.getProfile(userId);
        String role = profileService.getUserRole(user.getId());
        Map<String, String> result = new HashMap<>();
        result.put("role", role);
        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/profile/{userId}/role - Updates the role for a given user.
     */
    @PutMapping("/{userId}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        try {
            String newRole = body.get("role");
            if (newRole == null || newRole.isBlank()) {
                return ResponseEntity.badRequest().body("Missing role");
            }
            profileService.updateUserRole(userId, newRole);
            return ResponseEntity.ok(Map.of("message", "Role updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * GET /api/profile/next-admin-id Returns the next auto-generated admin user
     * ID (A001, A002, ...).
     */
    @GetMapping("/next-admin-id")
    public ResponseEntity<Map<String, String>> getNextAdminId() {
        Map<String, String> result = new HashMap<>();
        result.put("nextId", profileService.generateNextAdminId());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/profile/check-username?username= - Check if username is taken.
     */
    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Boolean>> checkUsername(@RequestParam String username) {
        try {
            boolean exists = profileRepository.findByUsernameAndDeletedAtIsNull(username).isPresent();
            Map<String, Boolean> result = new HashMap<>();
            result.put("exists", exists);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Boolean> result = new HashMap<>();
            result.put("exists", false);
            return ResponseEntity.ok(result);
        }
    }

    /**
     * GET /api/profile/check-userid?userId= - Check if user ID is taken.
     */
    @GetMapping("/check-userid")
    public ResponseEntity<Map<String, Boolean>> checkUserId(@RequestParam String userId) {
        try {
            boolean exists = profileRepository.findByUserIdAndDeletedAtIsNull(userId).isPresent();
            Map<String, Boolean> result = new HashMap<>();
            result.put("exists", exists);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Boolean> result = new HashMap<>();
            result.put("exists", false);
            return ResponseEntity.ok(result);
        }
    }

    /**
     * POST /api/profile/users - Creates a new user with a role.
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserProfile user, @RequestParam String role,
            HttpServletRequest request) {
        try {
            UserProfile created = profileService.createUser(user, role);
            try {
                String creatorUserId = getAuthenticatedUserId();
                auditLogService.logUserActivity("CREATE_USER", creatorUserId, created.getUserId(),
                        "Created user '" + created.getUserId() + "' with role '" + role + "'",
                        "SUCCESS", request.getRemoteAddr());
            } catch (Exception ignored) {
                // audit logging must never break the main flow
            }
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * DELETE /api/profile/users/{userId} - Deletes a user and their role
     * assignments.
     */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId, HttpServletRequest request) {
        try {
            // Block superadmin from deleting themselves
            String deleterUserId = getAuthenticatedUserId();
            if (deleterUserId.equals(userId)) {
                String role = profileService.getUserRole(
                        profileService.getProfile(userId).getId());
                if ("SUPER_ADMIN".equals(role)) {
                    return ResponseEntity.badRequest().body(
                            "Super Admin cannot delete their own account");
                }
            }
            profileService.deleteUser(userId);
            try {
                auditLogService.logUserActivity("DELETE_USER", deleterUserId, userId,
                        "Deleted user '" + userId + "'", "SUCCESS",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
                // audit logging must never break the main flow
            }
            return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * PUT /api/profile/users/{userId}/disable - Disables a user account.
     */
    @PutMapping("/users/{userId}/disable")
    public ResponseEntity<?> disableUser(@PathVariable String userId, HttpServletRequest request) {
        try {
            profileService.disableUser(userId);
            try {
                String disablerUserId = getAuthenticatedUserId();
                auditLogService.logUserActivity("DISABLE_USER", disablerUserId, userId,
                        "Disabled user '" + userId + "'", "SUCCESS",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
            }
            return ResponseEntity.ok(Map.of("message", "User disabled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * PUT /api/profile/users/{userId}/enable - Re-enables a disabled user
     * account.
     */
    @PutMapping("/users/{userId}/enable")
    public ResponseEntity<?> enableUser(@PathVariable String userId, HttpServletRequest request) {
        try {
            profileService.enableUser(userId);
            try {
                String enablerUserId = getAuthenticatedUserId();
                auditLogService.logUserActivity("ENABLE_USER", enablerUserId, userId,
                        "Enabled user '" + userId + "'", "SUCCESS",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
            }
            return ResponseEntity.ok(Map.of("message", "User enabled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * GET /api/profile/users - List all admin users with their roles.
     */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<UserProfile> users = profileService.getAllUsers();
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("name", u.getName());
            map.put("email", u.getEmail());
            map.put("userId", u.getUserId());
            map.put("company", u.getCompany());
            map.put("phone", u.getPhone());
            map.put("status", u.getStatus());
            map.put("joinedOn", u.getJoinedOn());
            map.put("role", profileService.getUserRole(u.getId()));
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/profile/users/search?keyword= - Search admin users by keyword.
     */
    @GetMapping("/users/search")
    public ResponseEntity<List<Map<String, Object>>> searchUsers(@RequestParam String keyword) {
        List<UserProfile> users = profileService.searchUsers(keyword);
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("name", u.getName());
            map.put("email", u.getEmail());
            map.put("userId", u.getUserId());
            map.put("company", u.getCompany());
            map.put("phone", u.getPhone());
            map.put("status", u.getStatus());
            map.put("joinedOn", u.getJoinedOn());
            map.put("role", profileService.getUserRole(u.getId()));
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Helper: extract authenticated userId from the Security context principal.
     */
    private String getAuthenticatedUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? String.valueOf(auth.getPrincipal()) : "unknown";
    }

}
