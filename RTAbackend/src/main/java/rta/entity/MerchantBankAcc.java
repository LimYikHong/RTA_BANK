package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_bank_acc")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantBankAcc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "merchant_acc_num", nullable = false)
    private String merchantAccNum;

    @Column(name = "merchant_acc_name", nullable = false)
    private String merchantAccName;

    @Column(name = "transaction_currency", nullable = false)
    private String transactionCurrency;

    @Column(name = "settlement_currency", nullable = false)
    private String settlementCurrency;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "create_by")
    private String createBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
