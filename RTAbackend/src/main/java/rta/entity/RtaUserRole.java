package rta.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import rta.model.UserProfile;

@Entity
@Table(name = "rta_user_role")
@Data
public class RtaUserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private UserProfile user;

    @ManyToOne
    @JoinColumn(name = "role_id", nullable = false)
    private RtaRole role;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "assigned_by")
    private String assignedBy;
}
