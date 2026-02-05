package rta.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_merchant_activity_log")
@Data
public class MerchantActivityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "activity_type")
    private String activityType;

    private String description;

    private LocalDateTime timestamp;
}
