package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.model.MerchantProfile;
import java.util.Optional;

@Repository
public interface ProfileRepository extends JpaRepository<MerchantProfile, Long> {
    Optional<MerchantProfile> findByMerchantId(String merchantId);

    Optional<MerchantProfile> findByUsername(String username);
}
