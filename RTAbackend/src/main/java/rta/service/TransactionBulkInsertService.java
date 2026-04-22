package rta.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rta.entity.RtaTransaction;

/**
 * High-performance bulk insert for RtaTransaction using native JDBC batching.
 *
 * Why not JPA saveAll()? → RtaTransaction uses GenerationType.IDENTITY, which
 * forces Hibernate to execute each INSERT immediately (to retrieve the
 * auto-generated ID). So saveAll() is still row-by-row under the hood.
 *
 * This service uses JDBC addBatch/executeBatch with
 * rewriteBatchedStatements=true for 10–20x faster inserts.
 */
@Service
public class TransactionBulkInsertService {

    private static final Logger log = LoggerFactory.getLogger(TransactionBulkInsertService.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    public TransactionBulkInsertService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
            INSERT INTO rta_transaction (
                batch_file_id, batch_seq, merchant_batch_seq,
                merchant_id, merchant_customer, merchant_billing_ref,
                bxn_ref, masked_pan, expiry_date,
                transaction_description, recurring_indicator,
                is_recurring, recurring_reference, frequency_value,
                additional_data, amount_cents, currency,
                actual_billing_date, status, remark, created_at,
                auth_batch_id, authorization_datetime
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Result holder for bulk insert — reports inserted count and duplicate
     * details.
     */
    @lombok.Data
    public static class BulkInsertResult {

        private int insertedCount;
        private int duplicateCount;
        private final List<String> duplicateDetails = new ArrayList<>();
    }

    /**
     * Bulk-insert transactions using JDBC batch. Duplicate constraint
     * violations are caught per-row and reported in the result.
     *
     * @param transactions list of RtaTransaction to insert
     * @param batchFileId the batch file ID to assign to each transaction
     * @return result with counts and duplicate details
     */
    @Transactional
    public BulkInsertResult bulkInsert(List<RtaTransaction> transactions, Long batchFileId) {
        BulkInsertResult result = new BulkInsertResult();
        if (transactions == null || transactions.isEmpty()) {
            return result;
        }

        long startMs = System.currentTimeMillis();

        // Split into chunks and batch-insert each chunk
        // For duplicates: we try batch first, and if it fails, fall back to row-by-row for that chunk
        List<List<RtaTransaction>> chunks = partition(transactions, BATCH_SIZE);

        for (List<RtaTransaction> chunk : chunks) {
            try {
                // Try batch insert for the entire chunk
                insertChunk(chunk, batchFileId);
                result.setInsertedCount(result.getInsertedCount() + chunk.size());
            } catch (Exception e) {
                // Batch failed (likely duplicate) — fall back to row-by-row for this chunk
                log.debug("[BulkInsert] Chunk batch failed, falling back to row-by-row: {}", e.getMessage());
                for (RtaTransaction txn : chunk) {
                    try {
                        insertChunk(List.of(txn), batchFileId);
                        result.setInsertedCount(result.getInsertedCount() + 1);
                    } catch (Exception ex) {
                        // Duplicate detected
                        result.setDuplicateCount(result.getDuplicateCount() + 1);
                        result.getDuplicateDetails().add(
                                "Row " + txn.getBatchSeq() + ": " + txn.getMerchantCustomer() + " / "
                                + (txn.getAmount() != null ? txn.getAmount() / 100.0 : "N/A")
                                + " / " + txn.getActualBillingDate());
                    }
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[BulkInsert] Inserted {} transactions ({} duplicates) in {}ms ({}s) for batchFileId {}",
                result.getInsertedCount(), result.getDuplicateCount(),
                elapsedMs, String.format("%.3f", elapsedMs / 1000.0), batchFileId);

        return result;
    }

    private void insertChunk(List<RtaTransaction> chunk, Long batchFileId) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RtaTransaction txn = chunk.get(i);
                ps.setLong(1, batchFileId);
                setNullableInt(ps, 2, txn.getBatchSeq());
                setNullableInt(ps, 3, txn.getMerchantBatchSeq());
                ps.setString(4, txn.getMerchantId());
                ps.setString(5, txn.getMerchantCustomer());
                ps.setString(6, txn.getMerchantBillingRef());
                ps.setString(7, txn.getBxnRef());
                ps.setString(8, txn.getMaskedPan());
                ps.setString(9, txn.getExpiryDate());
                ps.setString(10, txn.getTransactionDescription());
                ps.setString(11, txn.getRecurringIndicator());
                setNullableBoolean(ps, 12, txn.getIsRecurring());
                ps.setString(13, txn.getRecurringReference());
                setNullableInt(ps, 14, txn.getFrequencyValue());
                ps.setString(15, txn.getAdditionalData());
                setNullableLong(ps, 16, txn.getAmount());
                ps.setString(17, txn.getCurrency());
                if (txn.getActualBillingDate() != null) {
                    ps.setDate(18, java.sql.Date.valueOf(txn.getActualBillingDate()));
                } else {
                    ps.setNull(18, Types.DATE);
                }
                ps.setString(19, txn.getStatus());
                ps.setString(20, txn.getRemark());
                ps.setTimestamp(21, txn.getCreatedAt() != null
                        ? Timestamp.valueOf(txn.getCreatedAt())
                        : Timestamp.valueOf(java.time.LocalDateTime.now()));
                setNullableLong(ps, 22, txn.getAuthBatchId());
                if (txn.getAuthorizationDatetime() != null) {
                    ps.setTimestamp(23, Timestamp.valueOf(txn.getAuthorizationDatetime()));
                } else {
                    ps.setNull(23, Types.TIMESTAMP);
                }
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        });
    }

    // ── Null-safe setters ──────────────────────────────────────────────
    private void setNullableInt(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val != null) {
            ps.setInt(idx, val);
        } else {
            ps.setNull(idx, Types.INTEGER);
        }
    }

    private void setNullableLong(PreparedStatement ps, int idx, Long val) throws SQLException {
        if (val != null) {
            ps.setLong(idx, val);
        } else {
            ps.setNull(idx, Types.BIGINT);
        }
    }

    private void setNullableBoolean(PreparedStatement ps, int idx, Boolean val) throws SQLException {
        if (val != null) {
            ps.setBoolean(idx, val);
        } else {
            ps.setNull(idx, Types.BOOLEAN);
        }
    }

    // ── Bulk update auth statuses ────────────────────────────────────
    /**
     * Bulk-update transaction auth statuses using JDBC batch. Each entry in the
     * list must have: transactionId, status, remark. Sets
     * authorization_datetime = NOW() for all.
     *
     * @param txnResults list of maps with transactionId/status/remark
     * @return number of rows updated
     */
    @Transactional
    public int bulkUpdateAuthStatus(List<java.util.Map<String, Object>> txnResults) {
        if (txnResults == null || txnResults.isEmpty()) {
            return 0;
        }

        long startMs = System.currentTimeMillis();
        String sql = "UPDATE rta_transaction SET status = ?, remark = ?, authorization_datetime = ? WHERE transaction_id = ?";

        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now());
        List<List<java.util.Map<String, Object>>> chunks = partition(txnResults, BATCH_SIZE);
        int totalUpdated = 0;

        for (List<java.util.Map<String, Object>> chunk : chunks) {
            int[] results = jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    java.util.Map<String, Object> row = chunk.get(i);
                    ps.setString(1, (String) row.get("status"));
                    ps.setString(2, (String) row.get("remark"));
                    ps.setTimestamp(3, now);
                    long txnId = row.get("transactionId") instanceof Number n
                            ? n.longValue()
                            : Long.parseLong(row.get("transactionId").toString());
                    ps.setLong(4, txnId);
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
            for (int r : results) {
                totalUpdated += (r > 0 ? r : 0);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[BulkUpdate] Updated {} transaction auth statuses in {}ms ({}s)",
                totalUpdated, elapsedMs, String.format("%.3f", elapsedMs / 1000.0));
        return totalUpdated;
    }

    // ── Utility ────────────────────────────────────────────────────────
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
