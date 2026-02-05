package rta.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rta.model.MerchantProfile;
import rta.service.ProfileService;

import java.util.Map;
import java.util.HashMap;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private final ProfileService profileService;

    public AuthController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * POST /api/auth/login
     * - Step 1: Username/Password check.
     * - If valid, checks 2FA status.
     * - Returns:
     * - 200 + { status: "2FA_REQUIRED" } (if 2FA active)
     * - 200 + { status: "SETUP_2FA", secret: "..." } (if not set up)
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody MerchantProfile credentials) {
        try {
            MerchantProfile user = profileService.login(credentials.getUsername(), credentials.getPassword());

            // Generate secret if missing (force setup) or retrieve existing status
            // For this flow, we'll force setup if it's not enabled/present

            Map<String, Object> response = new HashMap<>();

            if (!user.isTwoFactorEnabled()) {

                String secret = profileService.generate2FASecret(user.getUsername());

                response.put("status", "SETUP_2FA");

                response.put("secret", secret);

                // Correctly encode the OTP Auth URL for the QR code
                String otpAuthUrl = "otpauth://totp/RTA_Example:" + user.getUsername()
                        + "?secret=" + secret
                        + "&issuer=RTA_Example";
                
                // Return the raw URI for client-side QR generation
                response.put("otpAuthUri", otpAuthUrl);

                String encodedOtpAuthUrl = URLEncoder.encode(otpAuthUrl, StandardCharsets.UTF_8);

                response.put("qrCodeUrl",
                        "https://chart.googleapis.com/chart?chs=200x200&chld=M%7C0&cht=qr&chl=" + encodedOtpAuthUrl);

            } else {
                response.put("status", "2FA_REQUIRED");
            }

            // Don't return the full user profile yet! Wait for 2FA verify.
            response.put("username", user.getUsername());

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body("Invalid username or password");
        }
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<?> verify2fa(@RequestBody Map<String, Object> payload) {
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

        boolean isValid = profileService.verify2FA(username, code);

        if (isValid) {
            MerchantProfile user = profileService.getProfileByUsername(username);
            return ResponseEntity.ok(user);
        } else {
            return ResponseEntity.status(401).body("Invalid 2FA Code");
        }
    }
}
