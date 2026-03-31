package rta.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rta.entity.RtaAuthorizationBatch;
import rta.entity.RtaTransaction;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;
import rta.service.BatchMaintenanceScheduler;

/**
 * REST controller for the Batch Maintenance module. Provides endpoints to list
 * authorization batches, view their transactions, and manually trigger a batch
 * run.
 */
@RestController
@RequestMapping("/api/batch-maintenance")
public class BatchMaintenanceController {

    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final RtaTransactionRepository transactionRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final BatchMaintenanceScheduler batchScheduler;

    public BatchMaintenanceController(RtaAuthorizationBatchRepository authBatchRepository,
            RtaTransactionRepository transactionRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            BatchMaintenanceScheduler batchScheduler) {
        this.authBatchRepository = authBatchRepository;
        this.transactionRepository = transactionRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.batchScheduler = batchScheduler;
    }

    /**
     * GET /api/batch-maintenance/list?page=0&size=10 Returns paginated list of
     * authorization batches (newest first).
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<RtaAuthorizationBatch> resultPage = authBatchRepository.findAllByOrderByCreatedAtDesc(pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (RtaAuthorizationBatch batch : resultPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("authBatchId", batch.getAuthBatchId());
            item.put("batchReference", batch.getBatchReference());
            item.put("totalCount", batch.getTotalCount());
            item.put("successCount", batch.getSuccessCount());
            item.put("failCount", batch.getFailCount());
            item.put("totalAmountCents", batch.getTotalAmountCents());
            item.put("batchStatus", batch.getBatchStatus());
            item.put("createdAt", batch.getCreatedAt());
            item.put("lastModifiedAt", batch.getLastModifiedAt());
            item.put("remark", batch.getRemark());
            // Count how many files contributed to this batch (find via transactions)
            List<Long> fileIds = transactionRepository.findDistinctBatchFileIdsByAuthBatchId(batch.getAuthBatchId());
            item.put("fileCount", fileIds.size());
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
     * GET /api/batch-maintenance/detail/{authBatchId} Returns the authorization
     * batch info plus its transactions.
     */
    @GetMapping("/detail/{authBatchId}")
    public ResponseEntity<Map<String, Object>> getBatchDetail(@PathVariable Long authBatchId) {
        return authBatchRepository.findById(authBatchId).map(batch -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("authBatchId", batch.getAuthBatchId());
            result.put("batchReference", batch.getBatchReference());
            result.put("totalCount", batch.getTotalCount());
            result.put("successCount", batch.getSuccessCount());
            result.put("failCount", batch.getFailCount());
            result.put("totalAmountCents", batch.getTotalAmountCents());
            result.put("batchStatus", batch.getBatchStatus());
            result.put("createdAt", batch.getCreatedAt());
            result.put("lastModifiedAt", batch.getLastModifiedAt());
            result.put("remark", batch.getRemark());

            // Get files that contributed to this batch (found via transactions)
            List<Long> fileIds = transactionRepository.findDistinctBatchFileIdsByAuthBatchId(authBatchId);
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (Long fid : fileIds) {
                incomingFileRepository.findById(fid).ifPresent(f -> {
                    Map<String, Object> fMap = new LinkedHashMap<>();
                    fMap.put("batchFileId", f.getBatchFileId());
                    fMap.put("originalFilename", f.getOriginalFilename());
                    fMap.put("merchantId", f.getMerchantId());
                    fMap.put("totalRecordCount", f.getTotalRecordCount());
                    fMap.put("successCount", f.getSuccessCount());
                    fMap.put("failCount", f.getFailCount());
                    fMap.put("fileStatus", f.getFileStatus());
                    fMap.put("createdAt", f.getCreatedAt());
                    fileList.add(fMap);
                });
            }
            result.put("files", fileList);

            // Get transactions in this batch
            List<RtaTransaction> transactions = transactionRepository.findByAuthBatchId(authBatchId);
            List<Map<String, Object>> txnList = new ArrayList<>();
            for (RtaTransaction txn : transactions) {
                Map<String, Object> txnMap = new LinkedHashMap<>();
                txnMap.put("transactionId", txn.getId());
                txnMap.put("batchFileId", txn.getBatchFileId());
                txnMap.put("merchantId", txn.getMerchantId());
                txnMap.put("merchantCustomer", txn.getMerchantCustomer());
                txnMap.put("maskedPan", txn.getMaskedPan());
                txnMap.put("amount", txn.getAmount());
                txnMap.put("currency", txn.getCurrency());
                txnMap.put("actualBillingDate", txn.getActualBillingDate());
                txnMap.put("status", txn.getStatus());
                txnMap.put("createdAt", txn.getCreatedAt());
                txnList.add(txnMap);
            }
            result.put("transactions", txnList);

            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/batch-maintenance/next-run Returns the epoch millis of the next
     * scheduled batch run and the interval.
     */
    @GetMapping("/next-run")
    public ResponseEntity<Map<String, Object>> getNextRun() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("nextRunTimeMs", batchScheduler.getNextRunTimeMs());
        response.put("intervalMs", BatchMaintenanceScheduler.INTERVAL_MS);
        response.put("serverTimeMs", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/batch-maintenance/run Manually trigger the batch grouping (same
     * logic as the 5-min scheduler).
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerBatchRun() {
        try {
            batchScheduler.runBatchGrouping();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Batch grouping triggered successfully");
            response.put("nextRunTimeMs", batchScheduler.getNextRunTimeMs());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Batch grouping failed: " + e.getMessage()));
        }
    }
}
