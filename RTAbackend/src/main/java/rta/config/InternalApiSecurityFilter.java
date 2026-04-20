package rta.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Security filter for the internal API channel ({@code /api/internal/**}).
 * Validates requests using:
 * <ul>
 * <li><b>API Key</b> — must match the configured {@code X-API-Key} header</li>
 * <li><b>IP Whitelist</b> — client IP must be in the allowed list</li>
 * </ul>
 */
@Component
@Order(1)
@Slf4j
public class InternalApiSecurityFilter implements Filter {

    @Value("${rta.internal.api-key}")
    private String expectedApiKey;

    @Value("${rta.internal.allowed-ips}")
    private String allowedIpsConfig;

    private Set<String> allowedIps;

    @Override
    public void init(FilterConfig filterConfig) {
        allowedIps = Arrays.stream(allowedIpsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        log.info("[InternalApiSecurity] Initialized — API key configured, allowed IPs: {}", allowedIps);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();

        // Only apply to /api/internal/** endpoints
        if (!path.startsWith("/api/internal")) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpRes = (HttpServletResponse) response;
        String clientIp = getClientIp(httpReq);
        String apiKey = httpReq.getHeader("X-API-Key");

        // 1. Validate API Key
        if (apiKey == null || !apiKey.equals(expectedApiKey)) {
            log.warn("[InternalApiSecurity] Rejected request to {} — invalid API key from IP: {}", path, clientIp);
            httpRes.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpRes.setContentType("application/json");
            httpRes.getWriter().write("{\"error\":\"Invalid or missing API key\"}");
            return;
        }

        // 2. Validate IP whitelist
        if (!allowedIps.isEmpty() && !allowedIps.contains(clientIp)) {
            log.warn("[InternalApiSecurity] Rejected request to {} — IP not whitelisted: {}", path, clientIp);
            httpRes.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpRes.setContentType("application/json");
            httpRes.getWriter().write("{\"error\":\"IP address not allowed: " + clientIp + "\"}");
            return;
        }

        log.info("[InternalApiSecurity] Accepted request to {} from IP: {}", path, clientIp);
        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        // Check proxy headers first
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
