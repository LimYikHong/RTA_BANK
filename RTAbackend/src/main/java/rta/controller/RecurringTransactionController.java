package rta.controller;

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
     * GET /api/recurring/list Returns list of unique recurring references with
     * merchant info
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> getRecurringList() {
        List<Object[]> results = transactionRepository.findDistinctRecurringReferences();

        List<Map<String, Object>> recurringList = new ArrayList<>();

        for (Object[] row : results) {
            String recurringRef = (String) row[0];
            String merchantId = (String) row[1];

            // Get transaction counts for this recurring reference
            int totalCount = transactionRepository.countByRecurringReference(recurringRef);
            int successCount = transactionRepository.countByRecurringReferenceAndStatus(recurringRef, "SUCCESS");
            int failedCount = transactionRepository.countByRecurringReferenceAndStatus(recurringRef, "FAILED");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recurringReference", recurringRef);
            item.put("merchantId", merchantId);
            item.put("totalTransactions", totalCount);
            item.put("successCount", successCount);
            item.put("failedCount", failedCount);

            recurringList.add(item);
        }

        return ResponseEntity.ok(recurringList);
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
