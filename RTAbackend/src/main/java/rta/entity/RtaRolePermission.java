package rta.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_role_permission")
@Data
public class RtaRolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "role_id", nullable = false)
    private RtaRole role;

    @ManyToOne
    @JoinColumn(name = "permission_id", nullable = false)
    private RtaPermission permission;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt = LocalDateTime.now();
}
