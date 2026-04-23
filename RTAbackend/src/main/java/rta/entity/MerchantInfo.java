package rta.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "merchant_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantInfo {

    @Id
    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "name")
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "company", nullable = false)
    private String company;

    @Column(name = "contact", nullable = false)
    private String contact;

    @Column(name = "joined_on")
    private LocalDateTime joinedOn;

    @Column(name = "profile_photo_url")
    private String profilePhotoUrl;

    @Column(name = "two_factor_secret")
    private String twoFactorSecret;

    @Column(name = "is_two_factor_enabled")
    private Boolean isTwoFactorEnabled;

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

    // ── Merchant API endpoint configuration ──
    @Column(name = "api_base_url")
    private String apiBaseUrl;

    @Column(name = "batch_return_path")
    private String batchReturnPath;

    @Column(name = "report_path")
    private String reportPath;

    @Column(name = "api_key")
    private String apiKey;
}
