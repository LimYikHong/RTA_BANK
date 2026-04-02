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
import java.util.Map;
import java.util.Optional;

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
    private final FileProfileService fileProfileService;

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
            String createdBy,
            String fileType,
            String fieldDelimiter,
            Boolean hasHeader,
            String dateFormat,
            List<Map<String, Object>> fieldMappings) {

        // Validate merchantId uniqueness (only among active merchants)
        if (merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantInfo.getMerchantId()).isPresent()) {
            throw new RuntimeException("Merchant ID already exists: " + merchantInfo.getMerchantId());
        }

        // Validate username uniqueness (only among active merchants)
        if (merchantInfoRepository.findByUsernameAndDeletedAtIsNull(merchantInfo.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists: " + merchantInfo.getUsername());
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
        merchantBankAccRepository.save(bankAcc);

        // 2. Create merchant_info (link to the bank account)
        merchantInfo.setAccountId(bankAcc.getAccountId());
        merchantInfo.setJoinedOn(LocalDateTime.now());
        merchantInfo.setCreatedAt(LocalDateTime.now());
        merchantInfo.setCreateBy(createdBy);
        merchantInfo.setIsTwoFactorEnabled(false);
        MerchantInfo savedMerchant = merchantInfoRepository.save(merchantInfo);

        // 3. Publish Kafka event for sub-systems
        MerchantCreatedEvent event = MerchantCreatedEvent.builder()
                .merchantId(savedMerchant.getMerchantId())
                .name(savedMerchant.getName())
                .email(savedMerchant.getEmail())
                .username(savedMerchant.getUsername())
                .company(savedMerchant.getCompany())
                .contact(savedMerchant.getContact())
                .phone(savedMerchant.getPhone())
                .address(savedMerchant.getAddress())
                .createdBy(createdBy)
                .createdAt(savedMerchant.getCreatedAt().toString())
                .merchantAccNum(merchantAccNum)
                .merchantAccName(merchantAccName)
                .transactionCurrency(transactionCurrency)
                .settlementCurrency(settlementCurrency)
                .build();

        kafkaProducer.sendMerchantCreatedEvent(event);

        // 4. Create file profile with field mappings
        fileProfileService.createDefaultFileProfile(
                savedMerchant.getMerchantId(), createdBy,
                fileType, fieldDelimiter, hasHeader, dateFormat, fieldMappings);

        log.info("Merchant created successfully: merchantId={}", savedMerchant.getMerchantId());
        return savedMerchant;
    }

    /**
     * Get all active (non-deleted) merchants.
     */
    public List<MerchantInfo> getAllMerchants() {
        return merchantInfoRepository.findAllActive();
    }

    /**
     * Check if a merchantId already exists (active only).
     */
    public boolean merchantIdExists(String merchantId) {
        return merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantId).isPresent();
    }

    public boolean usernameExists(String username) {
        return merchantInfoRepository.findByUsernameAndDeletedAtIsNull(username).isPresent();
    }

    /**
     * Get a single active merchant by merchantId.
     */
    public Optional<MerchantInfo> getMerchantById(String merchantId) {
        return merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantId);
    }

    /**
     * Update an existing merchant's basic info.
     */
    @Transactional
    public MerchantInfo updateMerchant(String merchantId, MerchantInfo updated) {
        MerchantInfo existing = merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantId)
                .orElseThrow(() -> new RuntimeException("Merchant not found: " + merchantId));

        if (updated.getName() != null) {
            existing.setName(updated.getName());
        }
        if (updated.getEmail() != null) {
            existing.setEmail(updated.getEmail());
        }
        if (updated.getCompany() != null) {
            existing.setCompany(updated.getCompany());
        }
        if (updated.getContact() != null) {
            existing.setContact(updated.getContact());
        }
        if (updated.getPhone() != null) {
            existing.setPhone(updated.getPhone());
        }
        if (updated.getAddress() != null) {
            existing.setAddress(updated.getAddress());
        }
        if (updated.getPassword() != null && !updated.getPassword().isBlank()) {
            existing.setPassword(updated.getPassword());
        }
        existing.setLastModifiedAt(java.time.LocalDateTime.now());
        return merchantInfoRepository.save(existing);
    }

    /**
     * Soft-delete a merchant by merchantId. Sets deleted_at timestamp.
     */
    @Transactional
    public void deleteMerchant(String merchantId) {
        MerchantInfo existing = merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull(merchantId)
                .orElseThrow(() -> new RuntimeException("Merchant not found: " + merchantId));
        existing.setDeletedAt(java.time.LocalDateTime.now());
        merchantInfoRepository.save(existing);
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
