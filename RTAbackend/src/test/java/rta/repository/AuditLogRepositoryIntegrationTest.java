package rta.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import rta.entity.AuditLog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for AuditLogRepository.
 * Uses @DataJpaTest which auto-configures an H2 in-memory database,
 * scans JPA entities, and rolls back each test automatically.
 */
@DataJpaTest
@ActiveProfiles("test")
class AuditLogRepositoryIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();

        // Seed 3 USER logs and 2 SYSTEM logs with different timestamps
        auditLogRepository.save(AuditLog.builder()
                .logType("USER").action("LOGIN").userId("admin01").targetId("admin01")
                .description("Admin logged in").status("SUCCESS").ipAddress("192.168.1.1")
                .createdAt(LocalDateTime.of(2026, 4, 26, 10, 0)).build());

        auditLogRepository.save(AuditLog.builder()
                .logType("USER").action("EDIT_USER").userId("admin01").targetId("user02")
                .description("Edited user02 profile").status("SUCCESS").ipAddress("192.168.1.1")
                .createdAt(LocalDateTime.of(2026, 4, 26, 11, 0)).build());

        auditLogRepository.save(AuditLog.builder()
                .logType("USER").action("LOGOUT").userId("admin01").targetId("admin01")
                .description("Admin logged out").status("SUCCESS").ipAddress("192.168.1.1")
                .createdAt(LocalDateTime.of(2026, 4, 26, 12, 0)).build());

        auditLogRepository.save(AuditLog.builder()
                .logType("SYSTEM").action("BATCH_PROCESS").targetId("BATCH-001")
                .description("Batch processed successfully").status("SUCCESS")
                .createdAt(LocalDateTime.of(2026, 4, 26, 9, 0)).build());

        auditLogRepository.save(AuditLog.builder()
                .logType("SYSTEM").action("REPORT_GENERATED").targetId("RPT-001")
                .description("Monthly report generated").status("SUCCESS")
                .createdAt(LocalDateTime.of(2026, 4, 26, 13, 0)).build());
    }

    @Test
    @DisplayName("findByLogTypeOrderByCreatedAtDesc returns only USER logs, newest first")
    void findUserLogs() {
        List<AuditLog> userLogs = auditLogRepository.findByLogTypeOrderByCreatedAtDesc("USER");

        assertThat(userLogs).hasSize(3);
        assertThat(userLogs.get(0).getAction()).isEqualTo("LOGOUT");   // 12:00
        assertThat(userLogs.get(1).getAction()).isEqualTo("EDIT_USER"); // 11:00
        assertThat(userLogs.get(2).getAction()).isEqualTo("LOGIN");     // 10:00
    }

    @Test
    @DisplayName("findByLogTypeOrderByCreatedAtDesc returns only SYSTEM logs, newest first")
    void findSystemLogs() {
        List<AuditLog> systemLogs = auditLogRepository.findByLogTypeOrderByCreatedAtDesc("SYSTEM");

        assertThat(systemLogs).hasSize(2);
        assertThat(systemLogs.get(0).getAction()).isEqualTo("REPORT_GENERATED"); // 13:00
        assertThat(systemLogs.get(1).getAction()).isEqualTo("BATCH_PROCESS");    // 09:00
    }

    @Test
    @DisplayName("findAllByOrderByCreatedAtDesc returns all 5 logs, newest first")
    void findAllLogs() {
        List<AuditLog> all = auditLogRepository.findAllByOrderByCreatedAtDesc();

        assertThat(all).hasSize(5);
        assertThat(all.get(0).getCreatedAt()).isAfter(all.get(4).getCreatedAt());
    }

    @Test
    @DisplayName("findByUserIdOrderByCreatedAtDesc returns logs for a specific user")
    void findByUserId() {
        List<AuditLog> admin01Logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc("admin01");

        assertThat(admin01Logs).hasSize(3);
        assertThat(admin01Logs).allMatch(l -> "admin01".equals(l.getUserId()));
    }

    @Test
    @DisplayName("Saving an AuditLog persists it and assigns an ID")
    void saveLog() {
        AuditLog newLog = AuditLog.builder()
                .logType("USER").action("CREATE_MERCHANT").userId("admin01")
                .targetId("M-100").description("Created merchant M-100")
                .status("SUCCESS").ipAddress("10.0.0.1")
                .build();

        AuditLog saved = auditLogRepository.save(newLog);

        assertThat(saved.getLogId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull(); // @PrePersist fills it
        assertThat(auditLogRepository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("Empty result when querying non-existent log type")
    void findByNonExistentType() {
        List<AuditLog> result = auditLogRepository.findByLogTypeOrderByCreatedAtDesc("UNKNOWN");
        assertThat(result).isEmpty();
    }
}
