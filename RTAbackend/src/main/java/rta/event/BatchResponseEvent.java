package rta.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.io.Serializable;
import java.util.List;

/**
 * Kafka message payload received on the {@code batch-response} topic from the
 * auth service ({@code com.worldline.mock.dto.BatchResponseMessage}). Contains
 * per-transaction authorization results so the update service can mark each
 * transaction as APPROVED or FAILED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchResponseEvent implements Serializable {

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
     * Overall batch outcome: "PROCESSED" or "FAILED"
     */
    private String batchStatus;

    /**
     * Timestamp when the auth service processed this batch
     */
    private String processedAt;

    /**
     * Error message if batchStatus is "FAILED"
     */
    private String errorMessage;

    /**
     * Per-transaction results (null if FAILED)
     */
    private List<TransactionResult> results;

    /**
     * RSA-encrypted AES key for the encrypted result content (optional)
     */
    private String encryptedAesKey;

    /**
     * AES-encrypted result CSV content, Base64-encoded (optional)
     */
    private String encryptedContent;

    /**
     * AES IV for decrypting encryptedContent, Base64-encoded (optional)
     */
    private String iv;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionResult implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * The transaction identifier (may be String like "TXN-0001" or numeric)
         */
        private String transactionId;

        /**
         * "APPROVED" or "FAILED"
         */
        private String status;

        /**
         * Optional reason for rejection
         */
        private String remark;

        /**
         * Merchant ID (from auth service)
         */
        private String merchantId;

        /**
         * Amount in cents (from auth service)
         */
        private String amountCents;
    }
}
