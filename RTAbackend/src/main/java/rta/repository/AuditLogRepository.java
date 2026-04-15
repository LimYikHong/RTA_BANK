package rta.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import rta.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * All logs of a given type ordered newest first
     */
    List<AuditLog> findByLogTypeOrderByCreatedAtDesc(String logType);

    /**
     * All logs for a specific user ordered newest first
     */
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * All logs ordered newest first
     */
    List<AuditLog> findAllByOrderByCreatedAtDesc();
}
