package rta.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rta.entity.MerchantInfo;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.repository.MerchantInfoRepository;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;

/**
 * REST controller for the Batch File Maintenance module. Provides endpoints to
 * list all incoming batch files (with joined info) and view individual batch
 * file details with their transaction records.
 */
@RestController
@RequestMapping("/api/batch-file-maintenance")
public class BatchFileMaintenanceController {

    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaTransactionRepository transactionRepository;
    private final MerchantInfoRepository merchantInfoRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;

    public BatchFileMaintenanceController(
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            RtaAuthorizationBatchRepository authBatchRepository) {
        this.incomingFileRepository = incomingFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.authBatchRepository = authBatchRepository;
    }

    /**
     * GET /api/batch-file-maintenance/list?page=0&size=10 Returns paginated
     * list of all incoming batch files with joined merchant name and
     * authorization batch info.
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listBatchFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RtaIncomingBatchFile> resultPage = incomingFileRepository.findAll(pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (RtaIncomingBatchFile f : resultPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("batchFileId", f.getBatchFileId());
            item.put("batchId", f.getBatchId());
            item.put("originalFilename", f.getOriginalFilename());
            item.put("merchantId", f.getMerchantId());

            // Join merchant name
            String merchantName = "";
            Optional<MerchantInfo> merchantOpt = merchantInfoRepository.findByMerchantId(f.getMerchantId());
            if (merchantOpt.isPresent()) {
                merchantName = merchantOpt.get().getName();
            }
            item.put("merchantName", merchantName);

            item.put("fileStatus", f.getFileStatus());
            item.put("batchStatus", f.getBatchStatus());
            item.put("totalRecordCount", f.getTotalRecordCount());
            item.put("successCount", f.getSuccessCount());
            item.put("failCount", f.getFailCount());
            item.put("createdAt", f.getCreatedAt());
            item.put("createdBy", f.getCreateBy());

            // Find authorization batch ID via transactions if any
            List<Long> authBatchIds = transactionRepository.findDistinctAuthBatchIdsByBatchFileId(f.getBatchFileId());
            if (!authBatchIds.isEmpty()) {
                item.put("authBatchId", authBatchIds.get(0));
                // Look up auth batch status
                authBatchRepository.findById(authBatchIds.get(0)).ifPresent(ab -> {
                    item.put("authBatchStatus", ab.getBatchStatus());
                    item.put("authBatchReference", ab.getBatchReference());
                });
            }

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
     * GET /api/batch-file-maintenance/detail/{batchFileId} Returns the batch
     * file info, merchant info, and all transaction records.
     */
    @GetMapping("/detail/{batchFileId}")
    public ResponseEntity<Map<String, Object>> getBatchFileDetail(@PathVariable Long batchFileId) {
        Optional<RtaIncomingBatchFile> fileOpt = incomingFileRepository.findById(batchFileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RtaIncomingBatchFile f = fileOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();

        // File info
        result.put("batchFileId", f.getBatchFileId());
        result.put("batchId", f.getBatchId());
        result.put("originalFilename", f.getOriginalFilename());
        result.put("storedFilename", f.getStoredFilename());
        result.put("merchantId", f.getMerchantId());

        // Merchant name
        String merchantName = "";
        Optional<MerchantInfo> merchantOpt = merchantInfoRepository.findByMerchantId(f.getMerchantId());
        if (merchantOpt.isPresent()) {
            merchantName = merchantOpt.get().getName();
        }
        result.put("merchantName", merchantName);

        result.put("fileStatus", f.getFileStatus());
        result.put("batchStatus", f.getBatchStatus());
        result.put("sizeBytes", f.getSizeBytes());
        result.put("totalRecordCount", f.getTotalRecordCount());
        result.put("successCount", f.getSuccessCount());
        result.put("failCount", f.getFailCount());
        result.put("createdAt", f.getCreatedAt());
        result.put("createdBy", f.getCreateBy());
        result.put("lastModifiedAt", f.getLastModifiedAt());
        result.put("lastModifiedBy", f.getLastModifiedBy());
        result.put("transactionRecordRemark", f.getTransactionRecordRemark());

        // Authorization batch info (linked via transactions)
        List<Long> authBatchIds = transactionRepository.findDistinctAuthBatchIdsByBatchFileId(batchFileId);
        if (!authBatchIds.isEmpty()) {
            // Top-level authBatchId for convenience (first auth batch)
            result.put("authBatchId", authBatchIds.get(0));
            List<Map<String, Object>> authBatches = new ArrayList<>();
            for (Long abId : authBatchIds) {
                authBatchRepository.findById(abId).ifPresent(ab -> {
                    Map<String, Object> abMap = new LinkedHashMap<>();
                    abMap.put("authBatchId", ab.getAuthBatchId());
                    abMap.put("batchReference", ab.getBatchReference());
                    abMap.put("batchStatus", ab.getBatchStatus());
                    abMap.put("totalCount", ab.getTotalCount());
                    abMap.put("totalAmountCents", ab.getTotalAmountCents());
                    abMap.put("createdAt", ab.getCreatedAt());
                    authBatches.add(abMap);
                });
            }
            result.put("authorizationBatches", authBatches);
        }

        // All transactions for this batch file
        List<RtaTransaction> transactions = transactionRepository.findByBatchFileId(batchFileId);
        List<Map<String, Object>> txnList = new ArrayList<>();
        for (RtaTransaction txn : transactions) {
            Map<String, Object> txnMap = new LinkedHashMap<>();
            txnMap.put("transactionId", txn.getId());
            txnMap.put("batchSeq", txn.getBatchSeq());
            txnMap.put("merchantId", txn.getMerchantId());
            txnMap.put("customerReference", txn.getMerchantCustomer());
            txnMap.put("maskedPan", txn.getMaskedPan());
            txnMap.put("billingRef", txn.getMerchantBillingRef());
            txnMap.put("description", txn.getTransactionDescription());
            txnMap.put("amount", txn.getAmount() != null ? txn.getAmount() / 100.0 : null);
            txnMap.put("currency", txn.getCurrency());
            txnMap.put("actualBillingDate", txn.getActualBillingDate());
            txnMap.put("recurringIndicator", txn.getRecurringIndicator());
            txnMap.put("isRecurring", txn.getIsRecurring());
            txnMap.put("recurringReference", txn.getRecurringReference());
            txnMap.put("status", txn.getStatus());
            txnMap.put("remark", txn.getRemark());
            txnMap.put("createdAt", txn.getCreatedAt());
            txnList.add(txnMap);
        }
        result.put("transactions", txnList);
        result.put("transactionCount", txnList.size());

        return ResponseEntity.ok(result);
    }
}
