package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaIncomingBatchFile;

import java.util.List;

public interface RtaIncomingBatchFileRepository extends JpaRepository<RtaIncomingBatchFile, Long> {

    List<RtaIncomingBatchFile> findByMerchantId(String merchantId);

    List<RtaIncomingBatchFile> findByBatchId(Long batchId);
}
