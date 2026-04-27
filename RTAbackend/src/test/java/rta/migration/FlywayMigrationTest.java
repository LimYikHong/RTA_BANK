package rta.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import rta.entity.AuditLog;
import rta.entity.MerchantInfo;
import rta.model.UserProfile;
import rta.repository.AuditLogRepository;
import rta.repository.MerchantInfoRepository;
import rta.repository.ProfileRepository;

import static org.assertj.core.api.Assertions.*;

/**
 * Flyway Migration Test — verifies that all JPA entities map correctly to the
 * auto-generated H2 schema (simulating what Flyway migrations produce in MySQL).
 *
 * <p>Uses {@code @DataJpaTest} with the test profile to boot an H2 in-memory
 * database with {@code spring.jpa.hibernate.ddl-auto=create-drop}. This
 * validates that all entity annotations, column mappings, and constraints are
 * compatible and that basic CRUD operations work against the schema.</p>
 *
 * <p>In production, Flyway runs 16 migration scripts (V1–V16) against MySQL.
 * This test ensures the JPA entities stay in sync with the schema design.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private MerchantInfoRepository merchantInfoRepository;
    @Autowired private ProfileRepository profileRepository;

    /* ── Table existence checks ──────────────────────────────── */

    @Test
    @DisplayName("All core tables are created by Hibernate ddl-auto")
    void allCoreTablesExist() {
        // Query H2's INFORMATION_SCHEMA for tables
        var tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
                String.class);

        assertThat(tables).containsAll(java.util.List.of(
                "AUDIT_LOG",
                "MERCHANT_INFO",
                "MERCHANT_BANK_ACC",
                "MERCHANT_KEY",
                "RTA_BANK_USER",
                "RTA_BATCH",
                "RTA_BATCH_ENCRYPTION_KEY",
                "RTA_INCOMING_BATCH_FILE",
                "RTA_TRANSACTION",
                "RTA_REPORT",
                "RTA_ROLE",
                "RTA_PERMISSION",
                "RTA_ROLE_PERMISSION",
                "RTA_USER_ROLE",
                "RTA_FILE_PROFILE",
                "RTA_FIELD_MAPPING",
                "RTA_UPLOADED_FILE_HASH",
                "SYSTEM_RSA_KEY_REQUEST",
                "RTA_AUTHORIZATION_BATCH"
        ));
    }

    /* ── Column mapping checks ───────────────────────────────── */

    @Test
    @DisplayName("audit_log table has expected columns")
    void auditLogColumns() {
        var columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AUDIT_LOG'",
                String.class);

        assertThat(columns).contains("LOG_ID", "LOG_TYPE", "ACTION", "USER_ID",
                "TARGET_ID", "DESCRIPTION", "STATUS", "IP_ADDRESS", "CREATED_AT");
    }

    @Test
    @DisplayName("rta_bank_user table has expected columns")
    void userColumns() {
        var columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'RTA_BANK_USER'",
                String.class);

        assertThat(columns).contains("ID", "USERNAME", "PASSWORD", "EMAIL",
                "USER_ID", "STATUS", "TWO_FACTOR_SECRET", "IS_TWO_FACTOR_ENABLED");
    }

    /* ── Entity CRUD validation ──────────────────────────────── */

    @Test
    @DisplayName("AuditLog entity can be saved and read back")
    void auditLogCrud() {
        AuditLog log = AuditLog.builder()
                .logType("USER").action("TEST").userId("U001")
                .description("Migration test").status("SUCCESS")
                .build();
        AuditLog saved = auditLogRepository.save(log);

        assertThat(saved.getLogId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull(); // @PrePersist
    }

    @Test
    @DisplayName("MerchantInfo entity can be saved and read back")
    void merchantInfoCrud() {
        MerchantInfo m = new MerchantInfo();
        m.setMerchantId("M-TEST");
        m.setUsername("testmerchant");
        m.setPassword("pass");
        m.setCompany("TestCorp");
        m.setContact("012-345");
        m.setAccountId(0L);

        MerchantInfo saved = merchantInfoRepository.save(m);

        assertThat(merchantInfoRepository.findByMerchantId("M-TEST")).isPresent();
    }

    @Test
    @DisplayName("UserProfile entity can be saved and read back")
    void userProfileCrud() {
        UserProfile u = new UserProfile();
        u.setUsername("testuser");
        u.setPassword("pass");
        u.setEmail("test@test.com");
        u.setUserId("USR-TEST");

        UserProfile saved = profileRepository.save(u);

        assertThat(saved.getId()).isNotNull();
        assertThat(profileRepository.findByUsername("testuser")).isPresent();
    }

    /* ── Constraint validation ───────────────────────────────── */

    @Test
    @DisplayName("Duplicate username in rta_bank_user is rejected")
    void duplicateUsernameRejected() {
        UserProfile u1 = new UserProfile();
        u1.setUsername("dupuser");
        u1.setPassword("pass");
        u1.setEmail("dup1@test.com");
        profileRepository.save(u1);

        UserProfile u2 = new UserProfile();
        u2.setUsername("dupuser");
        u2.setPassword("pass");
        u2.setEmail("dup2@test.com");

        assertThatThrownBy(() -> {
            profileRepository.saveAndFlush(u2);
        }).isInstanceOf(Exception.class); // ConstraintViolationException or DataIntegrityViolationException
    }
}
