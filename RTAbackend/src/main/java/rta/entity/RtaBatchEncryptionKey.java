package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Stores the AES-256 encryption key (and its RSA-encrypted form) used to
 * encrypt a batch CSV file before sending it to the authorization service.
 */
@Entity
@Table(name = "rta_batch_encryption_key")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RtaBatchEncryptionKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /**
     * Base64-encoded raw AES-256 key (32 bytes). Used by mock auth service to
     * decrypt.
     */
    @Column(name = "aes_key_base64", nullable = false, columnDefinition = "TEXT")
    private String aesKeyBase64;

    /**
     * Base64-encoded AES-GCM initialisation vector (12 bytes).
     */
    @Column(name = "iv_base64", nullable = false, length = 50)
    private String ivBase64;

    /**
     * Base64-encoded RSA-encrypted AES key. This is what would be sent to a
     * real third party.
     */
    @Column(name = "encrypted_aes_key_base64", nullable = false, columnDefinition = "TEXT")
    private String encryptedAesKeyBase64;

    @Column(name = "merchant_id", nullable = false, length = 50)
    private String merchantId;

    /**
     * Original CSV filename (e.g. "42_20260415143000.csv").
     */
    @Column(name = "csv_filename", nullable = false)
    private String csvFilename;

    /**
     * Path or URI where the encrypted file is stored.
     */
    @Column(name = "encrypted_file_path")
    private String encryptedFilePath;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
