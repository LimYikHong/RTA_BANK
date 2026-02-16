package rta.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import rta.model.UserProfile;
import rta.repository.ProfileRepository;
import rta.service.ProfileService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:8088"})
public class ProfileController {

    private final ProfileService profileService;
    private final ProfileRepository profileRepository;

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
            @RequestBody UserProfile updatedProfile) {
        UserProfile updated = profileService.updateProfile(userId, updatedProfile);
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
            boolean exists = profileRepository.findByUsername(username).isPresent();
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
            boolean exists = profileRepository.findByUserId(userId).isPresent();
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
    public ResponseEntity<?> createUser(@RequestBody UserProfile user, @RequestParam String role) {
        try {
            UserProfile created = profileService.createUser(user, role);
            return ResponseEntity.ok(created);
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

}
