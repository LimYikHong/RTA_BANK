package rta.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import rta.entity.MerchantInfo;
import rta.repository.MerchantBankAccRepository;
import rta.repository.MerchantInfoRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MerchantService – CRUD and ID generation logic.
 */
@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock private MerchantInfoRepository merchantInfoRepository;
    @Mock private MerchantBankAccRepository merchantBankAccRepository;
    @Mock private MerchantKafkaProducer kafkaProducer;
    @Mock private FileProfileService fileProfileService;
    @Mock private RsaKeyService rsaKeyService;

    @InjectMocks
    private MerchantService merchantService;

    private MerchantInfo sampleMerchant;

    @BeforeEach
    void setUp() {
        sampleMerchant = new MerchantInfo();
        sampleMerchant.setMerchantId("M001");
        sampleMerchant.setName("Test Merchant");
        sampleMerchant.setUsername("merchant01");
        sampleMerchant.setEmail("m01@example.com");
        sampleMerchant.setCompany("Test Corp");
        sampleMerchant.setContact("012-3456789");
        sampleMerchant.setPassword("pass123");
    }

    /* ── getAllMerchants ──────────────────────────────────────── */

    @Test
    @DisplayName("getAllMerchants returns all active merchants")
    void getAllMerchants() {
        when(merchantInfoRepository.findAllActive())
                .thenReturn(List.of(sampleMerchant));

        List<MerchantInfo> result = merchantService.getAllMerchants();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMerchantId()).isEqualTo("M001");
    }

    /* ── getMerchantById ─────────────────────────────────────── */

    @Test
    @DisplayName("getMerchantById returns merchant when found")
    void getMerchantById_found() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M001"))
                .thenReturn(Optional.of(sampleMerchant));

        Optional<MerchantInfo> result = merchantService.getMerchantById("M001");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Test Merchant");
    }

    @Test
    @DisplayName("getMerchantById returns empty when not found")
    void getMerchantById_notFound() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M999"))
                .thenReturn(Optional.empty());

        assertThat(merchantService.getMerchantById("M999")).isEmpty();
    }

    /* ── merchantIdExists ────────────────────────────────────── */

    @Test
    @DisplayName("merchantIdExists returns true for existing ID")
    void merchantIdExists_true() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M001"))
                .thenReturn(Optional.of(sampleMerchant));

        assertThat(merchantService.merchantIdExists("M001")).isTrue();
    }

    @Test
    @DisplayName("merchantIdExists returns false for non-existing ID")
    void merchantIdExists_false() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M999"))
                .thenReturn(Optional.empty());

        assertThat(merchantService.merchantIdExists("M999")).isFalse();
    }

    /* ── updateMerchant ──────────────────────────────────────── */

    @Test
    @DisplayName("updateMerchant updates fields and saves")
    void updateMerchant() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M001"))
                .thenReturn(Optional.of(sampleMerchant));
        when(merchantInfoRepository.save(any(MerchantInfo.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MerchantInfo update = new MerchantInfo();
        update.setName("Updated Name");
        update.setEmail("updated@example.com");

        MerchantInfo result = merchantService.updateMerchant("M001", update);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getEmail()).isEqualTo("updated@example.com");
        verify(merchantInfoRepository).save(any(MerchantInfo.class));
    }

    @Test
    @DisplayName("updateMerchant throws when merchant not found")
    void updateMerchant_notFound() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> merchantService.updateMerchant("M999", new MerchantInfo()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Merchant not found");
    }

    /* ── deleteMerchant ──────────────────────────────────────── */

    @Test
    @DisplayName("deleteMerchant soft-deletes by setting deletedAt")
    void deleteMerchant() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M001"))
                .thenReturn(Optional.of(sampleMerchant));
        when(merchantInfoRepository.save(any(MerchantInfo.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        merchantService.deleteMerchant("M001");

        assertThat(sampleMerchant.getDeletedAt()).isNotNull();
        verify(merchantInfoRepository).save(sampleMerchant);
    }

    @Test
    @DisplayName("deleteMerchant throws when merchant not found")
    void deleteMerchant_notFound() {
        when(merchantInfoRepository.findByMerchantIdAndDeletedAtIsNull("M999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> merchantService.deleteMerchant("M999"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Merchant not found");
    }

    /* ── generateNextMerchantId ──────────────────────────────── */

    @Test
    @DisplayName("generateNextMerchantId returns M001 when no merchants exist")
    void generateNextMerchantId_empty() {
        when(merchantInfoRepository.findAllMerchantIdsWithPrefix())
                .thenReturn(List.of());

        assertThat(merchantService.generateNextMerchantId()).isEqualTo("M001");
    }

    @Test
    @DisplayName("generateNextMerchantId increments highest existing ID")
    void generateNextMerchantId_increments() {
        when(merchantInfoRepository.findAllMerchantIdsWithPrefix())
                .thenReturn(Arrays.asList("M001", "M003", "M002"));

        assertThat(merchantService.generateNextMerchantId()).isEqualTo("M004");
    }

    /* ── usernameExists ──────────────────────────────────────── */

    @Test
    @DisplayName("usernameExists returns true when username exists")
    void usernameExists_true() {
        when(merchantInfoRepository.findByUsernameAndDeletedAtIsNull("merchant01"))
                .thenReturn(Optional.of(sampleMerchant));

        assertThat(merchantService.usernameExists("merchant01")).isTrue();
    }
}
