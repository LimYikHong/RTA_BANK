package rta.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.RtaAuthorizationBatch;
import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.event.BatchResponseEvent;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;

/**
 * Listens on the {@code batch-response} Kafka topic for authorization results
 * from the auth service. Performs all post-authorization processing:
 * <ul>
 * <li>Step 4: Updates each transaction's status → APPROVED or FAILED</li>
 * <li>Step 5: Updates RtaAuthorizationBatch, RtaBatch and incoming files →
 * PROCESSED</li>
 * <li>Step 6: Sends the return batch file back to the merchant</li>
 * <li>Step 7: Sends the report summary to the merchant</li>
 * <li>Step 8: Auto-generates batch file results</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionUpdateService {

    private final RtaTransactionRepository transactionRepository;
    private final RtaBatchRepository batchRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final AuditLogService auditLogService;
    private final TransactionBulkInsertService bulkInsertService;
    private final ReturnBatchSendService returnBatchSendService;
    private final ReportGenerationService reportGenerationService;

    @KafkaListener(topics = "${rta.kafka.topic.batch-response}",
            groupId = "transaction-update-service",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onBatchResponse(BatchResponseEvent event) {
        log.info("[TxnUpdateService] Received batch-response: batchId={}, merchant={}, resultCount={}",
                event.getBatchId(), event.getMerchantId(),
                event.getResults() != null ? event.getResults().size() : 0);

        long startMs = System.currentTimeMillis();
        int approved = 0;
        int failed = 0;

        // ── Step 4: Update transaction statuses ───────────────────────────
        if (event.getResults() != null && !event.getResults().isEmpty()) {
            List<Map<String, Object>> txnUpdateList = new ArrayList<>();
            for (BatchResponseEvent.TransactionResult result : event.getResults()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("transactionId", result.getTransactionId());
                row.put("status", result.getStatus());
                row.put("remark", result.getRemark());
                txnUpdateList.add(row);

                if ("APPROVED".equals(result.getStatus())) {
                    approved++;
                } else {
                    failed++;
                }
            }
            bulkInsertService.bulkUpdateAuthStatus(txnUpdateList);
            log.info("[TxnUpdateService] Batch {} transactions updated: {} approved, {} failed",
                    event.getBatchId(), approved, failed);
        } else {
            // No per-transaction detail — bulk-update all PENDING txns to APPROVED
            log.info("[TxnUpdateService] No per-txn results for batch {}. Bulk-updating all PENDING txns to APPROVED.",
                    event.getBatchId());
            transactionRepository.bulkUpdateAuthStatusByBatchId(
                    event.getBatchId(), "APPROVED",
                    "Authorized by auth service (batch result)",
                    LocalDateTime.now());
            approved = transactionRepository.countByBatchBatchIdAndStatus(event.getBatchId(), "APPROVED");
        }

        auditLogService.logSystemActivity("UPDATE_AUTH_STATUS",
                String.valueOf(event.getBatchId()),
                "Updated transaction auth statuses for batch #" + event.getBatchId()
                + " — " + approved + " approved, " + failed + " failed",
                "SUCCESS");

        // Load RtaBatch for subsequent steps
        RtaBatch batch = batchRepository.findById(event.getBatchId()).orElse(null);
        if (batch == null) {
            log.error("[TxnUpdateService] RtaBatch not found for batchId={}", event.getBatchId());
            return;
        }

        // Pre-cache transactions for steps 6-8
        List<RtaTransaction> cachedTxns = transactionRepository.findByBatchBatchId(event.getBatchId());
        String primaryMerchant = cachedTxns.isEmpty() ? event.getMerchantId()
                : cachedTxns.get(0).getMerchantId();

        // ── Step 5: Update RtaAuthorizationBatch + batch status + incoming files ──
        long totalAmountCents = transactionRepository.sumAmountByBatchIdAndStatusSuccess(event.getBatchId());
        RtaAuthorizationBatch authBatch = authBatchRepository
                .findByBatchReference(batch.getFileName()).orElse(null);
        if (authBatch != null) {
            authBatch.setSuccessCount(approved);
            authBatch.setFailCount(failed);
            authBatch.setTotalAmountCents(totalAmountCents);
            authBatch.setBatchStatus("PROCESSED");
            authBatch.setLastModifiedAt(LocalDateTime.now());
            authBatch.setRemark(approved + " approved, " + failed + " rejected");
            authBatchRepository.save(authBatch);
            log.info("[TxnUpdateService] AuthorizationBatch #{} updated to PROCESSED for batch {}",
                    authBatch.getAuthBatchId(), event.getBatchId());
        } else {
            log.warn("[TxnUpdateService] AuthorizationBatch not found for batch ref '{}'", batch.getFileName());
        }

        batch.setStatus("PROCESSED");
        batch.setLastModifiedAt(LocalDateTime.now());
        batch.setLastModifiedBy("SYSTEM");
        batchRepository.save(batch);

        List<RtaIncomingBatchFile> batchFiles = incomingFileRepository.findByBatchId(event.getBatchId());
        for (RtaIncomingBatchFile bf : batchFiles) {
            bf.setBatchStatus("PROCESSED");
            bf.setLastModifiedAt(LocalDateTime.now());
            bf.setLastModifiedBy("SYSTEM");
            incomingFileRepository.save(bf);
        }

        auditLogService.logSystemActivity("BATCH_RESPONSE",
                String.valueOf(event.getBatchId()),
                "Authorization response received for batch #" + event.getBatchId()
                + ": " + approved + " approved, " + failed + " failed",
                "SUCCESS");

        // ── Step 6: Send return batch file back to merchant (encrypted) ───
        try {
            ReturnBatchSendService.ReturnBatchResponse returnResponse
                    = returnBatchSendService.sendReturnBatch(
                            batch, primaryMerchant, cachedTxns,
                            batch.getOriginalFileName(),
                            approved + " approved, " + failed + " rejected");
            if (returnResponse.isSuccess()) {
                log.info("[TxnUpdateService] Return batch sent for batch {}", event.getBatchId());
                auditLogService.logSystemActivity("SEND_RETURN_BATCH",
                        String.valueOf(event.getBatchId()),
                        "Return batch file sent to consumer for batch #" + event.getBatchId(),
                        "SUCCESS");
            } else {
                log.warn("[TxnUpdateService] Failed to send return batch for batch {}: {}",
                        event.getBatchId(), returnResponse.getErrorMessage());
                auditLogService.logSystemActivity("SEND_RETURN_BATCH",
                        String.valueOf(event.getBatchId()),
                        "Failed to send return batch: " + returnResponse.getErrorMessage(),
                        "FAILED");
            }
        } catch (Exception returnEx) {
            log.error("[TxnUpdateService] Error sending return batch for batch {}: {}",
                    event.getBatchId(), returnEx.getMessage(), returnEx);
        }

        // ── Step 7: Send report summary to merchant (encrypted) ───────────
        try {
            int failedCount = cachedTxns.size() - approved - failed;
            ReturnBatchSendService.ReportResponse reportResponse
                    = returnBatchSendService.sendReportSummary(
                            batch, primaryMerchant,
                            approved, failed,
                            Math.max(failedCount, 0), cachedTxns);
            if (reportResponse.isSuccess()) {
                log.info("[TxnUpdateService] Report summary sent for batch {}", event.getBatchId());
                auditLogService.logSystemActivity("SEND_REPORT",
                        String.valueOf(event.getBatchId()),
                        "Report summary sent to consumer for batch #" + event.getBatchId(),
                        "SUCCESS");
            } else {
                log.warn("[TxnUpdateService] Failed to send report for batch {}: {}",
                        event.getBatchId(), reportResponse.getErrorMessage());
                auditLogService.logSystemActivity("SEND_REPORT",
                        String.valueOf(event.getBatchId()),
                        "Failed to send report: " + reportResponse.getErrorMessage(),
                        "FAILED");
            }
        } catch (Exception reportEx) {
            log.error("[TxnUpdateService] Error sending report for batch {}: {}",
                    event.getBatchId(), reportEx.getMessage(), reportEx);
        }

        // ── Step 8: Auto-generate batch file results ──────────────────────
        try {
            var results = reportGenerationService.generateReportsForProcessedBatches();
            log.info("[TxnUpdateService] Auto-generated {} batch file result(s) for batch {}",
                    results.size(), event.getBatchId());
        } catch (Exception resultEx) {
            log.error("[TxnUpdateService] Error auto-generating batch file results for batch {}: {}",
                    event.getBatchId(), resultEx.getMessage(), resultEx);
        }

        long totalElapsedMs = System.currentTimeMillis() - startMs;
        log.info("[TxnUpdateService] Batch {} fully processed in {}ms", event.getBatchId(), totalElapsedMs);
    }
}
