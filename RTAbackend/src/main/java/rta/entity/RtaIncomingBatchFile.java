package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_incoming_batch_file")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaIncomingBatchFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_file_id")
    private Long batchFileId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "storage_uri")
    private String storageUri;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "total_record_count")
    private Integer totalRecordCount;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "create_by")
    private String createBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "file_status")
    private String fileStatus;

    @Column(name = "transaction_record_remark")
    private String transactionRecordRemark;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
