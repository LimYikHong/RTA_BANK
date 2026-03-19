package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import rta.entity.RtaIncomingBatchFile;

import java.util.List;

public interface RtaIncomingBatchFileRepository extends JpaRepository<RtaIncomingBatchFile, Long> {

    List<RtaIncomingBatchFile> findByMerchantId(String merchantId);

    List<RtaIncomingBatchFile> findByBatchId(Long batchId);

    /**
     * Find incoming files eligible for batch assignment:
     * - batchStatus is NULL or PENDING (not yet processed by scheduler)
     * - successCount > 0 (at least one valid record)
     * - deletedAt is NULL (not soft-deleted)
     */
    @Query("SELECT f FROM RtaIncomingBatchFile f WHERE (f.batchStatus IS NULL OR f.batchStatus = 'PENDING') AND f.successCount > 0 AND f.deletedAt IS NULL")
    List<RtaIncomingBatchFile> findEligibleForBatch();
}
