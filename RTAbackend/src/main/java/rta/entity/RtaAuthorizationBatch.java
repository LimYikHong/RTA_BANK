package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_authorization_batch")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaAuthorizationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_batch_id")
    private Long authBatchId;

    @Column(name = "batch_reference", nullable = false, unique = true)
    private String batchReference;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "total_amount_cents")
    private Long totalAmountCents;

    @Column(name = "batch_status", nullable = false)
    private String batchStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "remark")
    private String remark;
}
