package rta.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import rta.entity.RtaTransaction;

@Repository
public interface RtaTransactionRepository extends JpaRepository<RtaTransaction, Long> {

    List<RtaTransaction> findByBatchBatchId(Long batchId);

    List<RtaTransaction> findByBatchBatchIdAndStatus(Long batchId, String status);

    int countByBatchBatchId(Long batchId);

    int countByBatchBatchIdAndStatus(Long batchId, String status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM RtaTransaction t WHERE t.batch.batchId = :batchId AND t.status = 'SUCCESS'")
    long sumAmountByBatchIdAndStatusSuccess(Long batchId);

    // --- Queries by batchFileId (for viewing file details before batch assignment) ---
    List<RtaTransaction> findByBatchFileId(Long batchFileId);

    List<RtaTransaction> findByBatchFileIdAndStatus(Long batchFileId, String status);

    int countByBatchFileId(Long batchFileId);

    int countByBatchFileIdAndStatus(Long batchFileId, String status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM RtaTransaction t WHERE t.batchFileId = :batchFileId AND t.status = 'SUCCESS'")
    long sumAmountByBatchFileIdAndStatusSuccess(@Param("batchFileId") Long batchFileId);

    /**
     * Find transactions belonging to a batch file that have no batch assigned
     * yet.
     */
    @Query("SELECT t FROM RtaTransaction t WHERE t.batchFileId = :batchFileId AND t.batch IS NULL")
    List<RtaTransaction> findUnbatchedByBatchFileId(@Param("batchFileId") Long batchFileId);

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

    // Get distinct merchant IDs that have recurring transactions (is_recurring = true)
    @Query("SELECT DISTINCT t.merchantId FROM RtaTransaction t WHERE t.isRecurring = true ORDER BY t.merchantId")
    List<String> findDistinctMerchantIdsWithRecurring();

    // Get distinct merchant IDs that have non-recurring transactions (is_recurring = false or null)
    @Query("SELECT DISTINCT t.merchantId FROM RtaTransaction t WHERE t.isRecurring = false OR t.isRecurring IS NULL ORDER BY t.merchantId")
    List<String> findDistinctMerchantIdsNonRecurring();

    // Get ALL distinct merchant IDs
    @Query("SELECT DISTINCT t.merchantId FROM RtaTransaction t ORDER BY t.merchantId")
    List<String> findDistinctMerchantIdsAll();

    // =============================================
    // Authorization batch queries
    // =============================================
    /**
     * Find all validated (SUCCESS) transactions that have been assigned to a
     * batch (batch IS NOT NULL) but NOT yet assigned to an authorization batch
     * (authBatchId IS NULL).
     */
    @Query("SELECT t FROM RtaTransaction t WHERE t.status = 'SUCCESS' AND t.batch IS NOT NULL AND t.authBatchId IS NULL")
    List<RtaTransaction> findUnbatchedValidTransactions();

    /**
     * Find transactions by authorization batch ID
     */
    List<RtaTransaction> findByAuthBatchId(Long authBatchId);

    /**
     * Count transactions by authorization batch ID
     */
    int countByAuthBatchId(Long authBatchId);

    /**
     * Find the distinct auth_batch_id(s) assigned to transactions of a given
     * batch file. Returns empty list if no transactions have been assigned yet.
     */
    @Query("SELECT DISTINCT t.authBatchId FROM RtaTransaction t WHERE t.batchFileId = :batchFileId AND t.authBatchId IS NOT NULL")
    List<Long> findDistinctAuthBatchIdsByBatchFileId(@Param("batchFileId") Long batchFileId);

    /**
     * Find the distinct batch file IDs whose transactions belong to a given
     * authorization batch.
     */
    @Query("SELECT DISTINCT t.batchFileId FROM RtaTransaction t WHERE t.authBatchId = :authBatchId")
    List<Long> findDistinctBatchFileIdsByAuthBatchId(@Param("authBatchId") Long authBatchId);
}
