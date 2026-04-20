package rta.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "system_rsa_key_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemRsaKeyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by", nullable = false, length = 50)
    private String requestedBy;

    @Column(name = "public_key_pem", nullable = false, columnDefinition = "TEXT")
    private String publicKeyPem;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;
}
