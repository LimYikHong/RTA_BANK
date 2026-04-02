package rta.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import rta.entity.RtaTransaction;
import rta.repository.RtaTransactionRepository;

@RestController
@RequestMapping("/api/recurring")
public class RecurringTransactionController {

    private final RtaTransactionRepository transactionRepository;
    private final EntityManager entityManager;

    public RecurringTransactionController(RtaTransactionRepository transactionRepository,
            EntityManager entityManager) {
        this.transactionRepository = transactionRepository;
        this.entityManager = entityManager;
    }

    /**
     * GET /api/recurring/list?page=0&size=10&search=&merchantId=&recurringType=
     * recurringType: ALL (default), RECURRING, NON_RECURRING
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getRecurringList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String merchantId,
            @RequestParam(defaultValue = "ALL") String recurringType) {

        Pageable pageable = PageRequest.of(page, size);

        // Build dynamic WHERE clauses
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        // recurringType filter — based on is_recurring column (1=recurring, 0=non-recurring)
        if ("RECURRING".equalsIgnoreCase(recurringType)) {
            conditions.add("t.isRecurring = true");
        } else if ("NON_RECURRING".equalsIgnoreCase(recurringType)) {
            conditions.add("(t.isRecurring = false OR t.isRecurring IS NULL)");
        }
        // ALL => no filter

        if (merchantId != null && !merchantId.trim().isEmpty()) {
            conditions.add("t.merchantId = :merchantId");
            params.put("merchantId", merchantId.trim());
        }

        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(LOWER(t.recurringReference) LIKE LOWER(CONCAT('%', :search, '%')) "
                    + "OR LOWER(t.merchantId) LIKE LOWER(CONCAT('%', :search, '%')))");
            params.put("search", search.trim());
        }

        String whereClause = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

        // Grouping strategy depends on recurring type
        String groupByFields;
        String selectFields;
        String countDistinctField;

        if ("NON_RECURRING".equalsIgnoreCase(recurringType)) {
            // Non-recurring: group by merchantId (no meaningful recurringReference)
            selectFields = "COALESCE(t.merchantBillingRef, '') AS recurringRef, t.merchantId AS merchantId";
            groupByFields = "COALESCE(t.merchantBillingRef, ''), t.merchantId";
            countDistinctField = "CONCAT(COALESCE(t.merchantBillingRef, ''), '|', t.merchantId)";
        } else if ("RECURRING".equalsIgnoreCase(recurringType)) {
            // Recurring only: group by recurringReference + merchantId
            selectFields = "t.recurringReference AS recurringRef, t.merchantId AS merchantId";
            groupByFields = "t.recurringReference, t.merchantId";
            countDistinctField = "CONCAT(t.recurringReference, '|', t.merchantId)";
        } else {
            // ALL: use COALESCE so both recurring and non-recurring group properly
            selectFields = "COALESCE(t.recurringReference, t.merchantBillingRef, '') AS recurringRef, t.merchantId AS merchantId";
            groupByFields = "COALESCE(t.recurringReference, t.merchantBillingRef, ''), t.merchantId";
            countDistinctField = "CONCAT(COALESCE(t.recurringReference, t.merchantBillingRef, ''), '|', t.merchantId)";
        }

        // Data query — LEFT JOIN authorization batch to get auth status
        String joinClause = "LEFT JOIN RtaAuthorizationBatch ab ON ab.authBatchId = t.authBatchId";
        String dataJpql = "SELECT " + selectFields + ", COUNT(t), "
                + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END), "
                + "MAX(ab.batchStatus) "
                + "FROM RtaTransaction t " + joinClause + " " + whereClause + " "
                + "GROUP BY " + groupByFields + " ORDER BY recurringRef ASC";

        // Count query
        String countJpql = "SELECT COUNT(DISTINCT " + countDistinctField + ") "
                + "FROM RtaTransaction t " + whereClause;

        TypedQuery<Object[]> dataQuery = entityManager.createQuery(dataJpql, Object[].class);
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            dataQuery.setParameter(entry.getKey(), entry.getValue());
            countQuery.setParameter(entry.getKey(), entry.getValue());
        }

        long total = countQuery.getSingleResult();
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());
        List<Object[]> rows = dataQuery.getResultList();

        Page<Object[]> resultPage = new PageImpl<>(rows, pageable, total);

        List<Map<String, Object>> content = new ArrayList<>();
        for (Object[] row : resultPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recurringReference", row[0]);
            item.put("merchantId", row[1]);
            item.put("totalTransactions", row[2]);
            item.put("successCount", row[3]);
            item.put("failedCount", row[4]);
            item.put("authStatus", row.length > 5 ? row[5] : null);
            content.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("totalElements", resultPage.getTotalElements());
        response.put("totalPages", resultPage.getTotalPages());
        response.put("currentPage", resultPage.getNumber());
        response.put("pageSize", resultPage.getSize());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/recurring/merchant-ids?recurringType=ALL Returns distinct
     * merchant IDs for the filter dropdown.
     */
    @GetMapping("/merchant-ids")
    public ResponseEntity<List<String>> getRecurringMerchantIds(
            @RequestParam(defaultValue = "ALL") String recurringType) {
        List<String> merchantIds;
        if ("RECURRING".equalsIgnoreCase(recurringType)) {
            merchantIds = transactionRepository.findDistinctMerchantIdsWithRecurring();
        } else if ("NON_RECURRING".equalsIgnoreCase(recurringType)) {
            merchantIds = transactionRepository.findDistinctMerchantIdsNonRecurring();
        } else {
            merchantIds = transactionRepository.findDistinctMerchantIdsAll();
        }
        return ResponseEntity.ok(merchantIds);
    }

    /**
     * GET /api/recurring/detail/{recurringReference} Returns all transactions
     * for a specific recurring reference
     */
    @GetMapping("/detail/{recurringReference}")
    public ResponseEntity<Map<String, Object>> getRecurringDetail(
            @PathVariable String recurringReference) {

        List<RtaTransaction> transactions = transactionRepository
                .findByRecurringReferenceOrderByCreatedAtDesc(recurringReference);

        if (transactions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Get summary stats
        int totalCount = transactions.size();
        int successCount = (int) transactions.stream()
                .filter(t -> "SUCCESS".equals(t.getStatus()))
                .count();
        int failedCount = (int) transactions.stream()
                .filter(t -> "FAILED".equals(t.getStatus()))
                .count();
        long totalAmount = transactions.stream()
                .filter(t -> t.getAmount() != null)
                .mapToLong(RtaTransaction::getAmount)
                .sum();

        // Get first transaction for recurring info
        RtaTransaction first = transactions.get(transactions.size() - 1); // oldest

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recurringReference", recurringReference);
        result.put("merchantId", first.getMerchantId());
        result.put("merchantCustomer", first.getMerchantCustomer());
        result.put("isRecurring", first.getIsRecurring());
        result.put("recurringIndicator", first.getRecurringIndicator());
        result.put("frequencyValue", first.getFrequencyValue());
        result.put("totalTransactions", totalCount);
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("totalAmountCents", totalAmount);

        // Build transaction list
        List<Map<String, Object>> txnList = new ArrayList<>();
        for (RtaTransaction txn : transactions) {
            Map<String, Object> txnMap = new LinkedHashMap<>();
            txnMap.put("transactionId", txn.getId());
            txnMap.put("batchSeq", txn.getBatchSeq());
            txnMap.put("merchantCustomer", txn.getMerchantCustomer());
            txnMap.put("maskedPan", txn.getMaskedPan());
            txnMap.put("merchantBillingRef", txn.getMerchantBillingRef());
            txnMap.put("amount", txn.getAmount());
            txnMap.put("currency", txn.getCurrency());
            txnMap.put("actualBillingDate", txn.getActualBillingDate());
            txnMap.put("status", txn.getStatus());
            txnMap.put("remark", txn.getRemark());
            txnMap.put("createdAt", txn.getCreatedAt());
            txnMap.put("batchId", txn.getBatch() != null ? txn.getBatch().getBatchId() : null);
            txnList.add(txnMap);
        }
        result.put("transactions", txnList);

        return ResponseEntity.ok(result);
    }
}
