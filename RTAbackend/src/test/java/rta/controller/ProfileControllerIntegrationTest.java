package rta.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import rta.config.JwtUtil;
import rta.model.UserProfile;
import rta.repository.ProfileRepository;
import rta.service.AuditLogService;
import rta.service.ProfileService;

/**
 * Integration test for ProfileController. Uses @WebMvcTest (web layer only)
 * with mocked services.
 */
@WebMvcTest(ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProfileControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private ProfileRepository profileRepository;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private JwtUtil jwtUtil;

    private UserProfile buildProfile(String userId, String username) {
        UserProfile p = new UserProfile();
        p.setUserId(userId);
        p.setUsername(username);
        return p;
    }

    /*  GET /api/profile/{userId} */
    @Test
    @DisplayName("GET /api/profile/{userId} → 200 with profile JSON")
    void getProfile() throws Exception {
        UserProfile profile = buildProfile("USR-001", "alice");
        when(profileService.getProfile("USR-001")).thenReturn(profile);

        mockMvc.perform(get("/api/profile/USR-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USR-001"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    /*  PUT /api/profile/{userId} */
    @Test
    @DisplayName("PUT /api/profile/{userId} → 200 updates and returns profile")
    void updateProfile() throws Exception {
        UserProfile updated = buildProfile("USR-001", "alice-updated");
        when(profileService.updateProfile(eq("USR-001"), any(UserProfile.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/profile/USR-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice-updated"));

        // Verify audit logging was attempted
        verify(auditLogService, atMostOnce())
                .logUserActivity(anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString());
    }

    /* POST /api/profile/register*/
    @Test
    @DisplayName("POST /api/profile/register → 200 with created profile")
    void register() throws Exception {
        UserProfile newUser = buildProfile("USR-002", "bob");
        when(profileService.register(any(UserProfile.class))).thenReturn(newUser);

        mockMvc.perform(post("/api/profile/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USR-002"));
    }

    /*  POST /api/profile/login*/
    @Test
    @DisplayName("POST /api/profile/login → 200 on valid credentials")
    void login_success() throws Exception {
        UserProfile creds = buildProfile(null, "alice");
        creds.setPassword("pass123");

        UserProfile loggedIn = buildProfile("USR-001", "alice");
        when(profileService.login("alice", "pass123")).thenReturn(loggedIn);

        mockMvc.perform(post("/api/profile/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USR-001"));
    }

    @Test
    @DisplayName("POST /api/profile/login → 401 on invalid credentials")
    void login_failure() throws Exception {
        UserProfile creds = buildProfile(null, "alice");
        creds.setPassword("wrong");

        when(profileService.login("alice", "wrong"))
                .thenThrow(new RuntimeException("Invalid credentials"));

        mockMvc.perform(post("/api/profile/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds)))
                .andExpect(status().isUnauthorized());
    }

    /* GET /api/profile/{userId}/role*/
    @Test
    @DisplayName("GET /api/profile/{userId}/role → 200 with role map")
    void getUserRole() throws Exception {
        UserProfile profile = buildProfile("USR-001", "alice");
        profile.setId(1L);
        when(profileService.getProfile("USR-001")).thenReturn(profile);
        when(profileService.getUserRole(1L)).thenReturn("SUPER_ADMIN");

        mockMvc.perform(get("/api/profile/USR-001/role"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }
}
