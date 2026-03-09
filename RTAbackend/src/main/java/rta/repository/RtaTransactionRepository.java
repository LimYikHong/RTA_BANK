package rta.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // =============================================
    // Server-side paginated recurring list (single aggregation query)
    // =============================================
    @Query(value = "SELECT t.recurringReference AS recurringReference, t.merchantId AS merchantId, "
            + "COUNT(t) AS totalTransactions, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, "
            + "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount "
            + "FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "GROUP BY t.recurringReference, t.merchantId "
            + "ORDER BY t.recurringReference ASC",
            countQuery = "SELECT COUNT(DISTINCT t.recurringReference) FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> ''")
    Page<Object[]> findRecurringListPaged(Pageable pageable);

    @Query(value = "SELECT t.recurringReference AS recurringReference, t.merchantId AS merchantId, "
            + "COUNT(t) AS totalTransactions, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, "
            + "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount "
            + "FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND t.merchantId = :merchantId "
            + "GROUP BY t.recurringReference, t.merchantId "
            + "ORDER BY t.recurringReference ASC",
            countQuery = "SELECT COUNT(DISTINCT t.recurringReference) FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND t.merchantId = :merchantId")
    Page<Object[]> findRecurringListPagedByMerchant(@Param("merchantId") String merchantId, Pageable pageable);

    @Query(value = "SELECT t.recurringReference AS recurringReference, t.merchantId AS merchantId, "
            + "COUNT(t) AS totalTransactions, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, "
            + "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount "
            + "FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND (LOWER(t.recurringReference) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "     OR LOWER(t.merchantId) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "GROUP BY t.recurringReference, t.merchantId "
            + "ORDER BY t.recurringReference ASC",
            countQuery = "SELECT COUNT(DISTINCT t.recurringReference) FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND (LOWER(t.recurringReference) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "     OR LOWER(t.merchantId) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Object[]> findRecurringListPagedBySearch(@Param("search") String search, Pageable pageable);

    @Query(value = "SELECT t.recurringReference AS recurringReference, t.merchantId AS merchantId, "
            + "COUNT(t) AS totalTransactions, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount, "
            + "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount "
            + "FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND t.merchantId = :merchantId "
            + "AND (LOWER(t.recurringReference) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "     OR LOWER(t.merchantId) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "GROUP BY t.recurringReference, t.merchantId "
            + "ORDER BY t.recurringReference ASC",
            countQuery = "SELECT COUNT(DISTINCT t.recurringReference) FROM RtaTransaction t "
            + "WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' "
            + "AND t.merchantId = :merchantId "
            + "AND (LOWER(t.recurringReference) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "     OR LOWER(t.merchantId) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Object[]> findRecurringListPagedByMerchantAndSearch(
            @Param("merchantId") String merchantId, @Param("search") String search, Pageable pageable);

    // Get distinct merchant IDs that have recurring references (for filter dropdown)
    @Query("SELECT DISTINCT t.merchantId FROM RtaTransaction t WHERE t.recurringReference IS NOT NULL AND t.recurringReference <> '' ORDER BY t.merchantId")
    List<String> findDistinctMerchantIdsWithRecurring();
}
