package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaFileProfile;

import java.util.List;
import java.util.Optional;

public interface RtaFileProfileRepository extends JpaRepository<RtaFileProfile, Long> {

    List<RtaFileProfile> findByMerchantId(String merchantId);

    Optional<RtaFileProfile> findByMerchantIdAndStatus(String merchantId, String status);

    List<RtaFileProfile> findByMerchantIdOrderByVersionNoDesc(String merchantId);
}
