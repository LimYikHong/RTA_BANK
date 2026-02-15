package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rta.entity.MerchantBankAcc;
import rta.entity.MerchantInfo;
import rta.event.MerchantCreatedEvent;
import rta.repository.MerchantBankAccRepository;
import rta.repository.MerchantInfoRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MerchantService - Handles creation and management of merchant records. - On
 * creation, also creates a merchant_bank_acc and publishes a Kafka event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantService {

    private final MerchantInfoRepository merchantInfoRepository;
    private final MerchantBankAccRepository merchantBankAccRepository;
    private final MerchantKafkaProducer kafkaProducer;

    /**
     * Create a new merchant (merchant_bank_acc + merchant_info) and publish a
     * Kafka event so sub-systems can replicate.
     */
    @Transactional
    public MerchantInfo createMerchant(MerchantInfo merchantInfo,
            String merchantAccNum,
            String merchantAccName,
            String transactionCurrency,
            String settlementCurrency,
            String createdBy) {

        // Validate merchantId uniqueness
        if (merchantInfoRepository.findByMerchantId(merchantInfo.getMerchantId()).isPresent()) {
            throw new RuntimeException("Merchant ID already exists: " + merchantInfo.getMerchantId());
        }

        // 1. Create the bank account
        MerchantBankAcc bankAcc = new MerchantBankAcc();
        bankAcc.setMerchantAccNum(merchantAccNum);
        bankAcc.setMerchantAccName(merchantAccName);
        bankAcc.setTransactionCurrency(transactionCurrency);
        bankAcc.setSettlementCurrency(settlementCurrency);
        bankAcc.setIsDefault(true);
        bankAcc.setCreatedAt(LocalDateTime.now());
        bankAcc.setCreateBy(createdBy);
        MerchantBankAcc savedAcc = merchantBankAccRepository.save(bankAcc);

        // 2. Create merchant_info linked to the bank account
        merchantInfo.setAccountId(savedAcc.getAccountId());
        merchantInfo.setCreatedAt(LocalDateTime.now());
        merchantInfo.setCreateBy(createdBy);
        if (merchantInfo.getMerchantStatus() == null) {
            merchantInfo.setMerchantStatus("ACTIVE");
        }
        MerchantInfo savedMerchant = merchantInfoRepository.save(merchantInfo);

        // 3. Publish Kafka event for sub-systems
        MerchantCreatedEvent event = MerchantCreatedEvent.builder()
                .merchantId(savedMerchant.getMerchantId())
                .merchantName(savedMerchant.getMerchantName())
                .merchantBank(savedMerchant.getMerchantBank())
                .merchantCode(savedMerchant.getMerchantCode())
                .merchantPhoneNum(savedMerchant.getMerchantPhoneNum())
                .merchantAddress(savedMerchant.getMerchantAddress())
                .merchantContactPerson(savedMerchant.getMerchantContactPerson())
                .merchantStatus(savedMerchant.getMerchantStatus())
                .createdBy(createdBy)
                .createdAt(savedMerchant.getCreatedAt().toString())
                .merchantAccNum(merchantAccNum)
                .merchantAccName(merchantAccName)
                .transactionCurrency(transactionCurrency)
                .settlementCurrency(settlementCurrency)
                .build();

        kafkaProducer.sendMerchantCreatedEvent(event);

        log.info("Merchant created successfully: merchantId={}", savedMerchant.getMerchantId());
        return savedMerchant;
    }

    /**
     * Get all merchants.
     */
    public List<MerchantInfo> getAllMerchants() {
        return merchantInfoRepository.findAll();
    }

    /**
     * Check if a merchantId already exists.
     */
    public boolean merchantIdExists(String merchantId) {
        return merchantInfoRepository.findByMerchantId(merchantId).isPresent();
    }

    /**
     * Generate the next merchant ID in ascending order: M001, M002, ...
     */
    public String generateNextMerchantId() {
        List<String> existingIds = merchantInfoRepository.findAllMerchantIdsWithPrefix();
        int maxNum = 0;
        for (String id : existingIds) {
            try {
                int num = Integer.parseInt(id.substring(1));
                if (num > maxNum) {
                    maxNum = num;
                }
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                // skip non-numeric IDs
            }
        }
        return String.format("M%03d", maxNum + 1);
    }
}
