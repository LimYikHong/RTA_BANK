package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.MerchantKey;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantKeyRepository extends JpaRepository<MerchantKey, Long> {

    /**
     * Find the active RSA key for a merchant.
     */
    Optional<MerchantKey> findByMerchantIdAndStatus(String merchantId, String status);

    /**
     * Find all keys for a merchant (any status), ordered by version descending.
     */
    List<MerchantKey> findByMerchantIdOrderByVersionNoDesc(String merchantId);

    /**
     * Find the latest active key for a merchant.
     */
    Optional<MerchantKey> findFirstByMerchantIdAndStatusOrderByVersionNoDesc(String merchantId, String status);
}
