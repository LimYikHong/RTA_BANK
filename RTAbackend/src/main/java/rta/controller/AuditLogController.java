package rta.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import rta.entity.AuditLog;
import rta.service.AuditLogService;

/**
 * AuditLogController – REST endpoints for reading audit log entries. Writing is
 * handled internally by other controllers / services via AuditLogService.
 */
@RestController
@RequestMapping("/api/audit-logs")
@CrossOrigin(origins = {"http://localhost:4200", "https://localhost:4200"})
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * GET /api/audit-logs/user – all user-activity logs
     */
    @GetMapping("/user")
    public ResponseEntity<List<AuditLog>> getUserActivityLogs() {
        return ResponseEntity.ok(auditLogService.getUserActivityLogs());
    }

    /**
     * GET /api/audit-logs/system – all system-activity logs
     */
    @GetMapping("/system")
    public ResponseEntity<List<AuditLog>> getSystemActivityLogs() {
        return ResponseEntity.ok(auditLogService.getSystemActivityLogs());
    }

    /**
     * GET /api/audit-logs – all logs (both types)
     */
    @GetMapping
    public ResponseEntity<List<AuditLog>> getAllLogs() {
        return ResponseEntity.ok(auditLogService.getAllLogs());
    }
}
