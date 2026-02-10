package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantInfo {

    @Id
    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Column(name = "merchant_bank")
    private String merchantBank;

    @Column(name = "merchant_code")
    private String merchantCode;

    @Column(name = "merchant_phone_num")
    private String merchantPhoneNum;

    @Column(name = "merchant_address")
    private String merchantAddress;

    @Column(name = "merchant_contact_person")
    private String merchantContactPerson;

    @Column(name = "merchant_status")
    private String merchantStatus;

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
