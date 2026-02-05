package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.MerchantActivityLog;

@Repository
public interface MerchantActivityLogRepository extends JpaRepository<MerchantActivityLog, Long> {
}
