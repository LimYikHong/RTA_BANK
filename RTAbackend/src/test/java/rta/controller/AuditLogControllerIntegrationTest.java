package rta.controller;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rta.config.JwtUtil;
import rta.entity.AuditLog;
import rta.service.AuditLogService;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for AuditLogController. Uses @WebMvcTest to boot only the
 * web layer (controller + security filters). Service layer is mocked via
 * @MockBean.
 */
@WebMvcTest(AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)   // disable security filters for controller-only testing
class AuditLogControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private JwtUtil jwtUtil;  // required by JwtAuthenticationFilter

    /* ── helper ──────────────────────────────────────────────── */
    private AuditLog buildLog(Long id, String type, String action, String userId) {
        return AuditLog.builder()
                .logId(id).logType(type).action(action).userId(userId)
                .targetId("TGT-1").description("desc").status("SUCCESS")
                .ipAddress("127.0.0.1").createdAt(LocalDateTime.now())
                .build();
    }

    /* ── GET /api/audit-logs/user ────────────────────────────── */
    @Test
    @DisplayName("GET /api/audit-logs/user → 200 with user logs")
    void getUserActivityLogs() throws Exception {
        List<AuditLog> logs = Arrays.asList(
                buildLog(1L, "USER", "LOGIN", "admin01"),
                buildLog(2L, "USER", "LOGOUT", "admin01"));

        when(auditLogService.getUserActivityLogs()).thenReturn(logs);

        mockMvc.perform(get("/api/audit-logs/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].logType").value("USER"))
                .andExpect(jsonPath("$[0].action").value("LOGIN"));
    }

    /* ── GET /api/audit-logs/system ──────────────────────────── */
    @Test
    @DisplayName("GET /api/audit-logs/system → 200 with system logs")
    void getSystemActivityLogs() throws Exception {
        List<AuditLog> logs = List.of(
                buildLog(3L, "SYSTEM", "BATCH_PROCESS", null));

        when(auditLogService.getSystemActivityLogs()).thenReturn(logs);

        mockMvc.perform(get("/api/audit-logs/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].logType").value("SYSTEM"));
    }

    /* ── GET /api/audit-logs ─────────────────────────────────── */
    @Test
    @DisplayName("GET /api/audit-logs → 200 with all logs")
    void getAllLogs() throws Exception {
        List<AuditLog> logs = Arrays.asList(
                buildLog(1L, "USER", "LOGIN", "admin01"),
                buildLog(3L, "SYSTEM", "BATCH_PROCESS", null));

        when(auditLogService.getAllLogs()).thenReturn(logs);

        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("GET /api/audit-logs → 200 empty list when no logs exist")
    void getAllLogs_empty() throws Exception {
        when(auditLogService.getAllLogs()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
