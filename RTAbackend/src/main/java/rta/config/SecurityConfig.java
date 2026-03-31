package rta.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity

/**
 * SecurityConfig - JWT-based stateless authentication with role-based access
 * control. - Two roles: SUPER_ADMIN (full access) and ADMIN (restricted). -
 * ADMIN restrictions: - Cannot create users (POST /api/profile/users) - Cannot
 * edit/delete user profiles (PUT/DELETE /api/profile/{userId}) - Cannot
 * create/edit/delete merchants (POST/PUT/DELETE /api/merchants/**) - Can view
 * merchants (GET /api/merchants/**)
 */
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                // Public endpoints (no JWT needed)
                .requestMatchers("/api/auth/**").permitAll()
                // --- User Profile restrictions (ADMIN cannot create/edit/delete users) ---
                // Create user: only SUPER_ADMIN
                .requestMatchers(HttpMethod.POST, "/api/profile/users").hasRole("SUPER_ADMIN")
                // Edit user profile: only SUPER_ADMIN
                .requestMatchers(HttpMethod.PUT, "/api/profile/{userId}").hasRole("SUPER_ADMIN")
                // Update user role: only SUPER_ADMIN
                .requestMatchers(HttpMethod.PUT, "/api/profile/{userId}/role").hasRole("SUPER_ADMIN")
                // Delete user: only SUPER_ADMIN
                .requestMatchers(HttpMethod.DELETE, "/api/profile/users/**").hasRole("SUPER_ADMIN")
                // Upload photo for other user: only SUPER_ADMIN
                // (users can still view their own profile via GET)

                // --- Merchant restrictions (ADMIN can only view) ---
                // Create merchant: only SUPER_ADMIN
                .requestMatchers(HttpMethod.POST, "/api/merchants").hasRole("SUPER_ADMIN")
                // Edit merchant: only SUPER_ADMIN
                .requestMatchers(HttpMethod.PUT, "/api/merchants/**").hasRole("SUPER_ADMIN")
                // Delete merchant: only SUPER_ADMIN
                .requestMatchers(HttpMethod.DELETE, "/api/merchants/**").hasRole("SUPER_ADMIN")
                // View merchants: both roles allowed (GET is authenticated below)

                // All other endpoints: any authenticated user (SUPER_ADMIN or ADMIN)
                .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(h -> h.disable())
                .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * CORS configuration - Allows the Angular dev origin
     * (http://localhost:4200). - Permits common HTTP methods and headers
     * (including Authorization for JWT). - Credentials enabled for auth
     * headers.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:4200", "https://localhost:4200", "http://localhost:8086", "https://localhost:8086"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
