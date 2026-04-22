package rta.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import rta.config.JwtUtil;
import rta.model.UserProfile;
import rta.service.AuditLogService;
import rta.service.ProfileService;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = {"http://localhost:4200", "https://localhost:4200"})
public class AuthController {

    private final ProfileService profileService;
    private final JwtUtil jwtUtil;
    private final AuditLogService auditLogService;

    public AuthController(ProfileService profileService, JwtUtil jwtUtil, AuditLogService auditLogService) {
        this.profileService = profileService;
        this.jwtUtil = jwtUtil;
        this.auditLogService = auditLogService;
    }

    /**
     * POST /api/auth/login - Step 1: Username/Password check. - If valid,
     * checks 2FA status. - Returns: - 200 + { status: "2FA_REQUIRED" } (if 2FA
     * active) - 200 + { status: "SETUP_2FA", secret: "..." } (if not set up)
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserProfile credentials, HttpServletRequest request) {
        try {
            UserProfile user = profileService.login(credentials.getUsername(), credentials.getPassword());

            Map<String, Object> response = new HashMap<>();

            if (!user.isTwoFactorEnabled()) {

                String secret = profileService.generate2FASecret(user.getUsername());

                response.put("status", "SETUP_2FA");

                response.put("secret", secret);

                String otpAuthUrl = "otpauth://totp/RTA_Example:" + user.getUsername()
                        + "?secret=" + secret
                        + "&issuer=RTA_Example";

                response.put("otpAuthUri", otpAuthUrl);

                String encodedOtpAuthUrl = URLEncoder.encode(otpAuthUrl, StandardCharsets.UTF_8);

                response.put("qrCodeUrl",
                        "https://chart.googleapis.com/chart?chs=200x200&chld=M%7C0&cht=qr&chl=" + encodedOtpAuthUrl);

            } else {
                response.put("status", "2FA_REQUIRED");
            }

            response.put("username", user.getUsername());

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            String message = e.getMessage();
            boolean isDisabled = message != null && message.contains("disabled");

            try {
                auditLogService.logUserActivity("LOGIN", credentials.getUsername(), null,
                        isDisabled ? "Login blocked - account disabled" : "Login failed", "FAILED",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
            }

            if (isDisabled) {
                return ResponseEntity.status(403).body("User account has been disabled. Please contact your administrator.");
            }
            return ResponseEntity.status(401).body("Invalid username or password");
        }
    }

    /**
     * POST /api/auth/verify-2fa - Step 2: Verify 2FA code. - On success,
     * returns the user profile + JWT token + role.
     */
    @PostMapping("/verify-2fa")
    public ResponseEntity<?> verify2fa(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String username = (String) payload.get("username");
        Object codeObj = payload.get("code");

        if (username == null || codeObj == null) {
            return ResponseEntity.badRequest().body("Missing username or code");
        }

        Integer code;
        try {
            code = Integer.valueOf(codeObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("Invalid code format");
        }

        // Re-check if the user is disabled before issuing JWT
        UserProfile user = profileService.getProfileByUsername(username);
        if (user == null) {
            return ResponseEntity.status(401).body("User not found");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            return ResponseEntity.status(403).body("User account has been disabled. Please contact your administrator.");
        }

        boolean isValid = profileService.verify2FA(username, code);

        if (isValid) {
            String role = profileService.getUserRole(user.getId());
            List<String> permissions = profileService.getUserPermissions(user.getId());

            // Generate JWT token with role and permissions
            String token = jwtUtil.generateToken(username, user.getUserId(), role, permissions);

            // Build response with user profile, token, role, and permissions
            Map<String, Object> response = new HashMap<>();
            response.put("userId", user.getUserId());
            response.put("emailAddress", user.getEmailAddress());
            response.put("email", user.getEmail());
            response.put("company", user.getCompany());
            response.put("contact", user.getContact());
            response.put("address", user.getAddress());
            response.put("joinedOn", user.getJoinedOn());
            response.put("username", user.getUsername());
            response.put("profilePhotoUrl", user.getProfilePhotoUrl());
            response.put("phone", user.getPhone());
            response.put("firstName", user.getFirstName());
            response.put("lastName", user.getLastName());
            response.put("officeNumber", user.getOfficeNumber());
            response.put("status", user.getStatus());
            response.put("isTwoFactorEnabled", user.isTwoFactorEnabled());
            response.put("token", token);
            response.put("role", role);
            response.put("permissions", permissions);

            try {
                auditLogService.logUserActivity("LOGIN", user.getUserId(), null,
                        "Logged in successfully", "SUCCESS",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
            }

            return ResponseEntity.ok(response);
        } else {
            try {
                auditLogService.logUserActivity("LOGIN", username, null,
                        "2FA verification failed", "FAILED",
                        request.getRemoteAddr());
            } catch (Exception ignored) {
            }
            return ResponseEntity.status(401).body("Invalid 2FA Code");
        }
    }

    /**
     * POST /api/auth/logout - Logs the logout activity.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = (String) payload.get("userId");
        try {
            auditLogService.logUserActivity("LOGOUT", userId, null,
                    "Logged out", "SUCCESS",
                    request.getRemoteAddr());
        } catch (Exception ignored) {
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
