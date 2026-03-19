package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_uploaded_file_hash", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"merchant_id", "file_hash"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaUploadedFileHash {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "stored_filename")
    private String storedFilename;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "status", length = 50)
    private String status;

    /**
     * Number of times this same file content has been uploaded (max 5)
     */
    @Column(name = "upload_count")
    private Integer uploadCount;
}
