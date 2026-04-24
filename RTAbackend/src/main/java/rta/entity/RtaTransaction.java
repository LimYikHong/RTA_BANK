package rta.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rta_transaction", uniqueConstraints = {
    @UniqueConstraint(name = "uk_transaction_unique",
            columnNames = {"merchant_id", "merchant_customer", "amount_cents", "actual_billing_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private RtaBatch batch;

    @Column(name = "auth_batch_id")
    private Long authBatchId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "batch_file_id", nullable = false)
    private Long batchFileId;

    @Column(name = "batch_seq")
    private Integer batchSeq;

    @Column(name = "merchant_batch_seq")
    private Integer merchantBatchSeq;

    @Column(name = "bxn_ref")
    private String bxnRef;

    @Column(name = "masked_pan")
    private String maskedPan;

    @Column(name = "expiry_date")
    private String expiryDate;

    @Column(name = "merchant_customer")
    private String merchantCustomer;

    @Column(name = "merchant_billing_ref")
    private String merchantBillingRef;

    @Column(name = "transaction_description")
    private String transactionDescription;

    @Column(name = "recurring_indicator")
    private String recurringIndicator;

    @Column(name = "is_recurring")
    private Boolean isRecurring;

    @Column(name = "recurring_reference")
    private String recurringReference;

    @Column(name = "frequency_value")
    private Integer frequencyValue;

    @Column(name = "additional_data", columnDefinition = "JSON")
    private String additionalData;

    @Column(name = "amount_cents")
    private Long amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "authorization_datetime")
    private LocalDateTime authorizationDatetime;

    @Column(name = "actual_billing_date")
    private LocalDate actualBillingDate;

    @Column(name = "validation_status")
    private String validationStatus;

    @Column(name = "record_hash", length = 64)
    private String recordHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
