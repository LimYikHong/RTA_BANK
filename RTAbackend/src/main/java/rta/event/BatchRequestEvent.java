package rta.event;

import lombok.*;
import java.io.Serializable;

/**
 * Kafka message payload sent on the {@code batch-request} topic. Contains the
 * encrypted CSV file and the RSA-encrypted AES key so the authorization service
 * can decrypt and process the batch.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRequestEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The RtaBatch primary key
     */
    private Long batchId;

    /**
     * Merchant that owns this batch
     */
    private String merchantId;

    /**
     * Original CSV filename (e.g. "42_20260415143000.csv")
     */
    private String csvFilename;

    /**
     * Number of transaction rows in the CSV
     */
    private int transactionCount;

    /**
     * Base64-encoded encrypted CSV file bytes (AES-256-GCM)
     */
    private String encryptedFileBase64;

    /**
     * Base64-encoded RSA-encrypted AES key
     */
    private String encryptedAesKeyBase64;

    /**
     * Base64-encoded AES-GCM IV (12 bytes)
     */
    private String ivBase64;
}
