package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RtaReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "batch_file_id")
    private Long batchFileId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "auth_batch_id")
    private Long authBatchId;

    @Column(name = "report_name", nullable = false)
    private String reportName;

    @Column(name = "report_type", nullable = false)
    private String reportType;

    @Column(name = "file_format", nullable = false)
    private String fileFormat;

    @Column(name = "storage_uri")
    private String storageUri;

    @Column(name = "output_file_uri")
    private String outputFileUri;

    @Column(name = "raw_output_file_uri")
    private String rawOutputFileUri;

    @Column(name = "total_records")
    private Integer totalRecords;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "approved_count")
    private Integer approvedCount;

    @Column(name = "declined_count")
    private Integer declinedCount;

    @Column(name = "total_amount")
    private Long totalAmount;

    @Column(name = "digital_signature", columnDefinition = "TEXT")
    private String digitalSignature;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "send_status")
    private String sendStatus;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;
}
