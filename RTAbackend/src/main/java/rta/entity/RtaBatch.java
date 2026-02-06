package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_batch")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "total_success_count")
    private Integer totalSuccessCount;

    @Column(name = "total_fail_count")
    private Integer totalFailCount;

    @Column(name = "processed_by")
    private String processedBy;

    @Column(name = "batch_status", nullable = false)
    private String status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
