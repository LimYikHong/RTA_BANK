package rta.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rta.entity.RtaTransaction;
import rta.repository.RtaTransactionRepository;

import java.util.*;

@RestController
@RequestMapping("/api/recurring")
public class RecurringTransactionController {

    private final RtaTransactionRepository transactionRepository;

    public RecurringTransactionController(RtaTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * GET /api/recurring/list?page=0&size=10&search=&merchantId= Returns
     * paginated list of unique recurring references with aggregated counts.
     * Uses a single GROUP BY query instead of N+1 queries for much better
     * performance.
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getRecurringList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String merchantId) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Object[]> resultPage;

        boolean hasSearch = search != null && !search.trim().isEmpty();
        boolean hasMerchant = merchantId != null && !merchantId.trim().isEmpty();

        if (hasSearch && hasMerchant) {
            resultPage = transactionRepository.findRecurringListPagedByMerchantAndSearch(
                    merchantId.trim(), search.trim(), pageable);
        } else if (hasMerchant) {
            resultPage = transactionRepository.findRecurringListPagedByMerchant(
                    merchantId.trim(), pageable);
        } else if (hasSearch) {
            resultPage = transactionRepository.findRecurringListPagedBySearch(
                    search.trim(), pageable);
        } else {
            resultPage = transactionRepository.findRecurringListPaged(pageable);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (Object[] row : resultPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recurringReference", row[0]);
            item.put("merchantId", row[1]);
            item.put("totalTransactions", row[2]);
            item.put("successCount", row[3]);
            item.put("failedCount", row[4]);
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
     * GET /api/recurring/merchant-ids Returns distinct merchant IDs that have
     * recurring transactions (for filter dropdown).
     */
    @GetMapping("/merchant-ids")
    public ResponseEntity<List<String>> getRecurringMerchantIds() {
        List<String> merchantIds = transactionRepository.findDistinctMerchantIdsWithRecurring();
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
