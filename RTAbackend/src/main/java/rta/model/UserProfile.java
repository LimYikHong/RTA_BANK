package rta.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_bank_user")
@Data
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "full_name")
    private String name;

    @Column(name = "phone_number")
    private String phone;

    @Column(name = "office_number")
    private String officeNumber;

    @Column(name = "profile_photo")
    private String profilePhotoUrl;

    @Column(name = "status")
    private String status = "ACTIVE";

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "failed_attempts")
    private Integer failedAttempts = 0;

    @Column(name = "is_enabled")
    @JsonProperty("isEnabled")
    private Boolean isEnabled = true;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "company")
    private String company;

    @Column(name = "address")
    private String address;

    @Column(name = "contact")
    private String contact;

    @Column(name = "two_factor_secret")
    private String twoFactorSecret;

    @Column(name = "is_two_factor_enabled")
    @JsonProperty("isTwoFactorEnabled")
    private boolean isTwoFactorEnabled;

    @Column(name = "created_at")
    private LocalDateTime joinedOn;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
