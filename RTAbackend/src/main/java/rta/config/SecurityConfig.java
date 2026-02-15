package rta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity

/**
 * SecurityFilterChain - Disables CSRF (API-style, stateless) and form/basic
 * login. - Enables CORS (configured below). - Uses STATELESS sessions for
 * token/JWT-friendly APIs. - Currently permits ALL endpoints (including
 * /api/profile/** and /api/batches/**).
 */
public class SecurityConfig {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/profile/**", "/api/batches/**", "/api/incoming/**", "/api/merchants/**", "/uploads/**").permitAll()
                .anyRequest().permitAll())
                .httpBasic(h -> h.disable())
                .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * CORS configuration - Allows the Angular dev origin
     * (http://localhost:4200). - Permits common HTTP methods and headers
     * (including Authorization for future JWT use). - Credentials enabled for
     * cases like cookies or auth headers (if needed).
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
