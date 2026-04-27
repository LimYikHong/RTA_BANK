package rta.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rta.entity.AuditLog;
import rta.repository.AuditLogRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuditLogService.
 * Verifies that user and system audit log entries are persisted correctly
 * and that repository failures do not propagate to the caller.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @InjectMocks
    private AuditLogService auditLogService;

    @Mock
    private AuditLogRepository auditLogRepository;

    // ──────────────── logUserActivity ────────────────

    @Test
    void logUserActivity_shouldPersistWithCorrectFields() {
        auditLogService.logUserActivity(
                "LOGIN", "A001", "A001",
                "User logged in successfully", "SUCCESS", "127.0.0.1");

        verify(auditLogRepository).save(argThat(log ->
                "USER".equals(log.getLogType()) &&
                "LOGIN".equals(log.getAction()) &&
                "A001".equals(log.getUserId()) &&
                "A001".equals(log.getTargetId()) &&
                "SUCCESS".equals(log.getStatus()) &&
                "127.0.0.1".equals(log.getIpAddress()) &&
                log.getCreatedAt() != null
        ));
    }

    @Test
    void logUserActivity_createUser_shouldRecordTargetId() {
        auditLogService.logUserActivity(
                "CREATE_USER", "A001", "A005",
                "Created user A005 with role ADMIN", "SUCCESS", "192.168.1.10");

        verify(auditLogRepository).save(argThat(log ->
                "CREATE_USER".equals(log.getAction()) &&
                "A001".equals(log.getUserId()) &&
                "A005".equals(log.getTargetId())
        ));
    }

    @Test
    void logUserActivity_repositoryFailure_shouldNotThrowException() {
        doThrow(new RuntimeException("DB connection lost"))
                .when(auditLogRepository).save(any());

        // Should not throw — audit logging must never break the caller
        assertDoesNotThrow(() ->
                auditLogService.logUserActivity(
                        "LOGIN", "A001", null,
                        "Test", "SUCCESS", "127.0.0.1")
        );
    }

    // ──────────────── logSystemActivity ────────────────

    @Test
    void logSystemActivity_shouldPersistWithNullUserIdAndIp() {
        auditLogService.logSystemActivity(
                "RUN_BATCH", "100",
                "Batch #100 created with 3 files", "SUCCESS");

        verify(auditLogRepository).save(argThat(log ->
                "SYSTEM".equals(log.getLogType()) &&
                "RUN_BATCH".equals(log.getAction()) &&
                log.getUserId() == null &&
                "100".equals(log.getTargetId()) &&
                log.getIpAddress() == null &&
                "SUCCESS".equals(log.getStatus())
        ));
    }

    @Test
    void logSystemActivity_sendAuth_shouldRecordDescription() {
        auditLogService.logSystemActivity(
                "SEND_AUTH", "200",
                "Batch #200 sent — 50 approved, 5 rejected", "SUCCESS");

        verify(auditLogRepository).save(argThat(log ->
                "SEND_AUTH".equals(log.getAction()) &&
                log.getDescription().contains("50 approved")
        ));
    }

    @Test
    void logSystemActivity_repositoryFailure_shouldNotThrow() {
        doThrow(new RuntimeException("DB error"))
                .when(auditLogRepository).save(any());

        assertDoesNotThrow(() ->
                auditLogService.logSystemActivity(
                        "RUN_BATCH", "100", "Test", "SUCCESS")
        );
    }

    // ──────────────── Reader methods ────────────────

    @Test
    void getUserActivityLogs_shouldDelegateToRepository() {
        AuditLog log1 = AuditLog.builder().logType("USER").action("LOGIN").build();
        AuditLog log2 = AuditLog.builder().logType("USER").action("LOGOUT").build();
        when(auditLogRepository.findByLogTypeOrderByCreatedAtDesc("USER"))
                .thenReturn(List.of(log1, log2));

        List<AuditLog> result = auditLogService.getUserActivityLogs();

        assertEquals(2, result.size());
        assertEquals("LOGIN", result.get(0).getAction());
    }

    @Test
    void getSystemActivityLogs_shouldReturnOnlySystemLogs() {
        AuditLog sysLog = AuditLog.builder().logType("SYSTEM").action("RUN_BATCH").build();
        when(auditLogRepository.findByLogTypeOrderByCreatedAtDesc("SYSTEM"))
                .thenReturn(List.of(sysLog));

        List<AuditLog> result = auditLogService.getSystemActivityLogs();

        assertEquals(1, result.size());
        assertEquals("SYSTEM", result.get(0).getLogType());
    }

    @Test
    void getAllLogs_shouldReturnBothTypes() {
        AuditLog userLog = AuditLog.builder().logType("USER").build();
        AuditLog sysLog = AuditLog.builder().logType("SYSTEM").build();
        when(auditLogRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(userLog, sysLog));

        List<AuditLog> result = auditLogService.getAllLogs();

        assertEquals(2, result.size());
    }

    @Test
    void getUserActivityLogsByUserId_shouldFilterByUser() {
        AuditLog log = AuditLog.builder().logType("USER").userId("A001").build();
        when(auditLogRepository.findByUserIdOrderByCreatedAtDesc("A001"))
                .thenReturn(List.of(log));

        List<AuditLog> result = auditLogService.getUserActivityLogsByUserId("A001");

        assertEquals(1, result.size());
        assertEquals("A001", result.get(0).getUserId());
    }
}
