package rta.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import rta.config.JwtUtil;
import rta.model.UserProfile;
import rta.repository.ProfileRepository;
import rta.repository.RtaRolePermissionRepository;
import rta.repository.RtaRoleRepository;
import rta.repository.RtaUserRoleRepository;

/**
 * Unit tests for authentication logic:
 * <ul>
 * <li>{@link JwtUtil} — token generation, extraction, and validation</li>
 * <li>{@link ProfileService#login} — credential checking</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /* ── JwtUtil is a standalone component (no mocks needed) ── */
    private final JwtUtil jwtUtil = new JwtUtil();

    // The hard-coded secret inside JwtUtil
    private static final String SECRET = "RtaBankSecretKeyForJwtToken2025!MustBeAtLeast32Chars";

    /* ── Mocks for ProfileService ─────────────────────────── */
    @Mock
    private ProfileRepository profileRepository;
    @Mock
    private RtaRoleRepository roleRepository;
    @Mock
    private RtaUserRoleRepository userRoleRepository;
    @Mock
    private RtaRolePermissionRepository rolePermissionRepository;
    @Mock
    private MinioStorageService minioStorageService;

    // ================================================================
    //  JwtUtil — generateToken
    // ================================================================
    @Test
    @DisplayName("generateToken should contain username, userId, role and permissions")
    void generateToken_shouldContainAllClaims() {
        String token = jwtUtil.generateToken("admin01", "A001", "SUPER_ADMIN",
                List.of("USER_MANAGEMENT", "BATCH_VIEW"));

        assertThat(token).isNotBlank();

        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertThat(claims.getSubject()).isEqualTo("admin01");
        assertThat(claims.get("userId", String.class)).isEqualTo("A001");
        assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
        assertThat(claims.get("permissions", List.class))
                .containsExactly("USER_MANAGEMENT", "BATCH_VIEW");
    }

    // ================================================================
    //  JwtUtil — getUsername / getUserId / getRole / getPermissions
    // ================================================================
    @Test
    @DisplayName("getUsername returns the subject embedded in the token")
    void getUsername_shouldReturnSubject() {
        String token = jwtUtil.generateToken("banker", "B002", "ADMIN", List.of());
        assertThat(jwtUtil.getUsername(token)).isEqualTo("banker");
    }

    @Test
    @DisplayName("getUserId returns the userId claim")
    void getUserId_shouldReturnClaim() {
        String token = jwtUtil.generateToken("banker", "B002", "ADMIN", List.of());
        assertThat(jwtUtil.getUserId(token)).isEqualTo("B002");
    }

    @Test
    @DisplayName("getRole returns the role claim")
    void getRole_shouldReturnClaim() {
        String token = jwtUtil.generateToken("banker", "B002", "ADMIN", List.of());
        assertThat(jwtUtil.getRole(token)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("getPermissions returns the permissions list")
    void getPermissions_shouldReturnList() {
        String token = jwtUtil.generateToken("banker", "B002", "ADMIN",
                List.of("BATCH_VIEW", "REPORT_VIEW"));
        assertThat(jwtUtil.getPermissions(token))
                .containsExactly("BATCH_VIEW", "REPORT_VIEW");
    }

    // ================================================================
    //  JwtUtil — isTokenValid
    // ================================================================
    @Test
    @DisplayName("isTokenValid returns true for a freshly generated token")
    void isTokenValid_freshToken_shouldReturnTrue() {
        String token = jwtUtil.generateToken("admin01", "A001", "ADMIN", List.of());
        assertThat(jwtUtil.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("isTokenValid returns false for a tampered token")
    void isTokenValid_tamperedToken_shouldReturnFalse() {
        String token = jwtUtil.generateToken("admin01", "A001", "ADMIN", List.of());
        // flip one character in the signature
        String tampered = token.substring(0, token.length() - 2) + "XX";
        assertThat(jwtUtil.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for garbage input")
    void isTokenValid_garbage_shouldReturnFalse() {
        assertThat(jwtUtil.isTokenValid("not.a.jwt")).isFalse();
    }

    // ================================================================
    //  ProfileService — login
    // ================================================================
    @Test
    @DisplayName("login succeeds with valid credentials")
    void login_validCredentials_shouldReturnProfile() {
        UserProfile user = new UserProfile();
        user.setUsername("admin01");
        user.setPassword("pass123");
        user.setStatus("ACTIVE");

        when(profileRepository.findByUsername("admin01")).thenReturn(Optional.of(user));

        ProfileService profileService = new ProfileService(
                profileRepository, roleRepository, userRoleRepository,
                rolePermissionRepository, minioStorageService);

        UserProfile result = profileService.login("admin01", "pass123");

        assertThat(result.getUsername()).isEqualTo("admin01");
        verify(profileRepository).findByUsername("admin01");
    }

    @Test
    @DisplayName("login throws when user not found")
    void login_unknownUser_shouldThrow() {
        when(profileRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        ProfileService profileService = new ProfileService(
                profileRepository, roleRepository, userRoleRepository,
                rolePermissionRepository, minioStorageService);

        assertThatThrownBy(() -> profileService.login("unknown", "password"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("login throws when password is wrong")
    void login_wrongPassword_shouldThrow() {
        UserProfile user = new UserProfile();
        user.setUsername("admin01");
        user.setPassword("correct");
        user.setStatus("ACTIVE");

        when(profileRepository.findByUsername("admin01")).thenReturn(Optional.of(user));

        ProfileService profileService = new ProfileService(
                profileRepository, roleRepository, userRoleRepository,
                rolePermissionRepository, minioStorageService);

        assertThatThrownBy(() -> profileService.login("admin01", "wrong"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid password");
    }

    @Test
    @DisplayName("login throws when account is disabled")
    void login_disabledAccount_shouldThrow() {
        UserProfile user = new UserProfile();
        user.setUsername("admin01");
        user.setPassword("pass123");
        user.setStatus("DISABLED");

        when(profileRepository.findByUsername("admin01")).thenReturn(Optional.of(user));

        ProfileService profileService = new ProfileService(
                profileRepository, roleRepository, userRoleRepository,
                rolePermissionRepository, minioStorageService);

        assertThatThrownBy(() -> profileService.login("admin01", "pass123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("disabled");
    }
}
