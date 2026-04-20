package rta.event;

import lombok.*;
import java.io.Serializable;
import java.util.List;

/**
 * Kafka message payload sent on the {@code batch-response} topic. Contains
 * per-transaction authorization results so the update service can mark each
 * transaction as APPROVED or FAILED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
     * Overall batch outcome: "PROCESSED"
     */
    private String batchStatus;

    /**
     * Per-transaction results
     */
    private List<TransactionResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionResult implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * The transaction_id (PK) from rta_transaction
         */
        private Long transactionId;

        /**
         * "APPROVED" or "FAILED"
         */
        private String status;

        /**
         * Optional reason for rejection
         */
        private String remark;
    }
}
