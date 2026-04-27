package rta.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import rta.config.JwtUtil;
import rta.model.UserProfile;
import rta.service.AuditLogService;
import rta.service.ProfileService;

/**
 * Integration tests for AuthController — login, 2FA, and logout flows. Uses
 *
 * @WebMvcTest (web layer only) with mocked services.
 */
//AuthControllerIntegrationTest.java
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProfileService profileService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private AuditLogService auditLogService;

    /* ── helpers ──────────────────────────────────────────────── */
    private UserProfile buildUser(String userId, String username, boolean twoFaEnabled) {
        UserProfile u = new UserProfile();
        u.setId(1L);
        u.setUserId(userId);
        u.setUsername(username);
        u.setPassword("pass123");
        u.setEmail("test@example.com");
        u.setStatus("ACTIVE");
        u.setTwoFactorEnabled(twoFaEnabled);
        u.setTwoFactorSecret(twoFaEnabled ? "SECRET123" : null);
        return u;
    }

    /* ── POST /api/auth/login ────────────────────────────────── */
    @Test
    @DisplayName("Login → 200 + 2FA_REQUIRED when 2FA is enabled")
    void login_2faRequired() throws Exception {
        UserProfile user = buildUser("USR-001", "alice", true);
        when(profileService.login("alice", "pass123")).thenReturn(user);

        Map<String, String> creds = Map.of("username", "alice", "password", "pass123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("2FA_REQUIRED"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @DisplayName("Login → 200 + SETUP_2FA when 2FA not yet enabled")
    void login_setup2fa() throws Exception {
        UserProfile user = buildUser("USR-001", "alice", false);
        when(profileService.login("alice", "pass123")).thenReturn(user);
        when(profileService.generate2FASecret("alice")).thenReturn("NEWSECRET");

        Map<String, String> creds = Map.of("username", "alice", "password", "pass123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETUP_2FA"))
                .andExpect(jsonPath("$.secret").value("NEWSECRET"));
    }

    @Test
    @DisplayName("Login → 401 on invalid credentials")
    void login_invalidCredentials() throws Exception {
        when(profileService.login("alice", "wrong"))
                .thenThrow(new RuntimeException("Invalid password"));

        Map<String, String> creds = Map.of("username", "alice", "password", "wrong");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login → 403 when account is disabled")
    void login_accountDisabled() throws Exception {
        when(profileService.login("alice", "pass123"))
                .thenThrow(new RuntimeException("User account has been disabled. Please contact your administrator."));

        Map<String, String> creds = Map.of("username", "alice", "password", "pass123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isForbidden());
    }

    /* ── POST /api/auth/verify-2fa ───────────────────────────── */
    @Test
    @DisplayName("Verify 2FA → 200 with token on valid code")
    void verify2fa_success() throws Exception {
        UserProfile user = buildUser("USR-001", "alice", true);
        when(profileService.getProfileByUsername("alice")).thenReturn(user);
        when(profileService.verify2FA("alice", 123456)).thenReturn(true);
        when(profileService.getUserRole(1L)).thenReturn("SUPER_ADMIN");
        when(profileService.getUserPermissions(1L))
                .thenReturn(List.of("USER_CREATE", "USER_EDIT"));
        when(jwtUtil.generateToken(eq("alice"), eq("USR-001"), eq("SUPER_ADMIN"), anyList()))
                .thenReturn("jwt-token-123");

        Map<String, Object> payload = new HashMap<>();
        payload.put("username", "alice");
        payload.put("code", 123456);

        mockMvc.perform(post("/api/auth/verify-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token-123"))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.userId").value("USR-001"));
    }

    @Test
    @DisplayName("Verify 2FA → 401 on invalid code")
    void verify2fa_invalidCode() throws Exception {
        UserProfile user = buildUser("USR-001", "alice", true);
        when(profileService.getProfileByUsername("alice")).thenReturn(user);
        when(profileService.verify2FA("alice", 000000)).thenReturn(false);

        Map<String, Object> payload = new HashMap<>();
        payload.put("username", "alice");
        payload.put("code", 0);

        mockMvc.perform(post("/api/auth/verify-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Verify 2FA → 400 when missing fields")
    void verify2fa_missingFields() throws Exception {
        mockMvc.perform(post("/api/auth/verify-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /* ── POST /api/auth/logout ───────────────────────────────── */
    @Test
    @DisplayName("Logout → 200 with success message")
    void logout() throws Exception {
        Map<String, Object> payload = Map.of("userId", "USR-001");

        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        verify(auditLogService).logUserActivity(
                eq("LOGOUT"), eq("USR-001"), isNull(),
                eq("Logged out"), eq("SUCCESS"), anyString());
    }
}
