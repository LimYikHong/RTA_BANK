package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    /**
     * USER or SYSTEM
     */
    @Column(name = "log_type", nullable = false, length = 20)
    private String logType;

    /**
     * e.g. LOGIN, LOGOUT, UPLOAD_FILE, CREATE_USER, CREATE_MERCHANT …
     */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /**
     * The userId who performed the action (null for system actions)
     */
    @Column(name = "user_id", length = 50)
    private String userId;

    /**
     * Target entity id – created userId, merchantId, file name, etc.
     */
    @Column(name = "target_id", length = 255)
    private String targetId;

    /**
     * Human-readable description
     */
    @Column(name = "description", length = 1000)
    private String description;

    /**
     * SUCCESS, FAILED, PENDING …
     */
    @Column(name = "status", length = 50)
    private String status;

    /**
     * Client IP address
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
