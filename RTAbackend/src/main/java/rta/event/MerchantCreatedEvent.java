package rta.event;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Kafka event payload published when a new merchant is created
 * on the main (bank) system. Sub-systems listen to this event
 * and replicate the merchant record in their own databases.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantCreatedEvent implements Serializable {

    private String merchantId;
    private String merchantName;
    private String merchantBank;
    private String merchantCode;
    private String merchantPhoneNum;
    private String merchantAddress;
    private String merchantContactPerson;
    private String merchantStatus;
    private String createdBy;
    private String createdAt;

    // Bank account info
    private String merchantAccNum;
    private String merchantAccName;
    private String transactionCurrency;
    private String settlementCurrency;
}
