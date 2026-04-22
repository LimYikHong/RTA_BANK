package rta.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import rta.entity.RtaIncomingBatchFile;

public interface RtaIncomingBatchFileRepository extends JpaRepository<RtaIncomingBatchFile, Long> {

    List<RtaIncomingBatchFile> findByMerchantId(String merchantId);

    List<RtaIncomingBatchFile> findByBatchId(Long batchId);

    /**
     * Find incoming files eligible for batch assignment: - batchStatus is NULL
     * or PENDING (not yet processed by scheduler) - insertionStatus =
     * 'COMPLETED' (all transaction records have been saved) - successCount > 0
     * (at least one valid record) - deletedAt is NULL (not soft-deleted)
     */
    @Query("SELECT f FROM RtaIncomingBatchFile f WHERE f.batchId IS NULL AND (f.batchStatus IS NULL OR f.batchStatus = 'PENDING') AND f.insertionStatus = 'COMPLETED' AND f.successCount > 0 AND f.deletedAt IS NULL")
    List<RtaIncomingBatchFile> findEligibleForBatch();

    /**
     * Find batch files by batchStatus (e.g. 'PROCESSED') that have not been
     * soft-deleted.
     */
    @Query("SELECT f FROM RtaIncomingBatchFile f WHERE f.batchStatus = :batchStatus AND f.deletedAt IS NULL")
    List<RtaIncomingBatchFile> findByBatchStatus(@org.springframework.data.repository.query.Param("batchStatus") String batchStatus);
}
