package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "rta_transaction")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private RtaBatch batch;

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

    @Column(name = "amount_cents")
    private Long amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "authorization_datetime")
    private LocalDateTime authorizationDatetime;

    @Column(name = "actual_billing_date")
    private LocalDate actualBillingDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
