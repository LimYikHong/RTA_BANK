package rta.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import rta.entity.AuditLog;
import rta.repository.AuditLogRepository;

/**
 * AuditLogService – centralised helper that any controller / service can call
 * to record an audit-log entry (user activity or system activity).
 *
 * Each write runs in its own transaction (REQUIRES_NEW) so that: 1. it is never
 * rolled back by an outer service transaction, and 2. a failure here never
 * breaks the caller's business logic.
 */
@Service
@Slf4j
public class AuditLogService {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_SYSTEM = "SYSTEM";

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /* ---- convenience writers ---- */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logUserActivity(String action, String userId, String targetId,
            String description, String status, String ipAddress) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logType(TYPE_USER)
                    .action(action)
                    .userId(userId)
                    .targetId(targetId)
                    .description(description)
                    .status(status)
                    .ipAddress(ipAddress)
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
            log.info("Audit log saved: action={}, userId={}, targetId={}", action, userId, targetId);
        } catch (Exception e) {
            log.error("Failed to save audit log: action={}, userId={}, targetId={}, error={}",
                    action, userId, targetId, e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSystemActivity(String action, String targetId,
            String description, String status) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logType(TYPE_SYSTEM)
                    .action(action)
                    .userId(null)
                    .targetId(targetId)
                    .description(description)
                    .status(status)
                    .ipAddress(null)
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
            log.info("Audit log saved: action={}, targetId={}", action, targetId);
        } catch (Exception e) {
            log.error("Failed to save system audit log: action={}, targetId={}, error={}",
                    action, targetId, e.getMessage(), e);
        }
    }

    /* ---- readers ---- */
    public List<AuditLog> getUserActivityLogs() {
        return auditLogRepository.findByLogTypeOrderByCreatedAtDesc(TYPE_USER);
    }

    public List<AuditLog> getSystemActivityLogs() {
        return auditLogRepository.findByLogTypeOrderByCreatedAtDesc(TYPE_SYSTEM);
    }

    public List<AuditLog> getUserActivityLogsByUserId(String userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<AuditLog> getAllLogs() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }
}
