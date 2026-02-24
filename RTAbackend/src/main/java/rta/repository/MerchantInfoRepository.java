package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import rta.entity.MerchantInfo;

import java.util.List;
import java.util.Optional;

public interface MerchantInfoRepository extends JpaRepository<MerchantInfo, String> {

    Optional<MerchantInfo> findByMerchantId(String merchantId);

    Optional<MerchantInfo> findByUsername(String username);

    Optional<MerchantInfo> findByEmail(String email);

    @Query("SELECT m.merchantId FROM MerchantInfo m WHERE m.merchantId LIKE 'M%' ORDER BY m.merchantId DESC")
    List<String> findAllMerchantIdsWithPrefix();
}
