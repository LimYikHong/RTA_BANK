package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * JPA entity for the merchant_key table. Stores RSA key pairs (public +
 * private) per merchant for batch file encryption.
 */
@Entity
@Table(name = "merchant_key")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "key_id")
    private Long keyId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "key_provider")
    private String keyProvider;

    @Column(name = "keystore_alias")
    private String keystoreAlias;

    /**
     * INBOUND = merchant encrypts uploads → bank decrypts (bank keeps private
     * key) OUTBOUND = bank encrypts return files → merchant decrypts (bank
     * keeps public key)
     */
    @Column(name = "key_purpose")
    private String keyPurpose;

    @Column(name = "public_key_pem", columnDefinition = "TEXT")
    private String publicKeyPem;

    @Column(name = "private_key_pem", columnDefinition = "TEXT")
    private String privateKeyPem;

    @Column(name = "status")
    private String status;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
