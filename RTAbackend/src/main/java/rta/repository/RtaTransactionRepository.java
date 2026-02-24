package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import rta.entity.RtaTransaction;

import java.util.List;

@Repository
public interface RtaTransactionRepository extends JpaRepository<RtaTransaction, Long> {

    List<RtaTransaction> findByBatchBatchId(Long batchId);

    List<RtaTransaction> findByBatchBatchIdAndStatus(Long batchId, String status);

    int countByBatchBatchId(Long batchId);

    int countByBatchBatchIdAndStatus(Long batchId, String status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM RtaTransaction t WHERE t.batch.batchId = :batchId AND t.status = 'SUCCESS'")
    long sumAmountByBatchIdAndStatusSuccess(Long batchId);

    // Recurring transaction queries
    @Query("SELECT DISTINCT t.recurringReference, t.merchantId FROM RtaTransaction t WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> ''")
    List<Object[]> findDistinctRecurringReferences();

    List<RtaTransaction> findByRecurringReferenceOrderByCreatedAtDesc(String recurringReference);

    @Query("SELECT COUNT(t) FROM RtaTransaction t WHERE t.recurringReference = :recurringReference")
    int countByRecurringReference(String recurringReference);

    @Query("SELECT COUNT(t) FROM RtaTransaction t WHERE t.recurringReference = :recurringReference AND t.status = :status")
    int countByRecurringReferenceAndStatus(String recurringReference, String status);
}
