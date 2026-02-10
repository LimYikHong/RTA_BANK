package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.MerchantInfo;

import java.util.Optional;

public interface MerchantInfoRepository extends JpaRepository<MerchantInfo, String> {

    Optional<MerchantInfo> findByMerchantId(String merchantId);
}
