package rta.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.RtaBatch;
import rta.event.BatchResponseEvent;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaTransactionRepository;

/**
 * Listens on the {@code batch-response} Kafka topic for authorization results
 * from the (mock) third-party service. Updates:
 * <ul>
 * <li>Each transaction's status individually → APPROVED or FAILED</li>
 * <li>The batch status → PROCESSED</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionUpdateService {

    private final RtaTransactionRepository transactionRepository;
    private final RtaBatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final TransactionBulkInsertService bulkInsertService;

    @KafkaListener(topics = "${rta.kafka.topic.batch-response}",
            groupId = "transaction-update-service",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onBatchResponse(BatchResponseEvent event) {
        log.info("[TxnUpdateService] Received batch-response: batchId={}, merchant={}, resultCount={}",
                event.getBatchId(), event.getMerchantId(),
                event.getResults() != null ? event.getResults().size() : 0);

        if (event.getResults() == null || event.getResults().isEmpty()) {
            log.warn("[TxnUpdateService] No transaction results in response for batch {}", event.getBatchId());
            return;
        }

        int approved = 0;
        int failed = 0;

        // Build bulk update list and count
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

        // JDBC batch update — all at once
        bulkInsertService.bulkUpdateAuthStatus(txnUpdateList);

        log.info("[TxnUpdateService] Batch {} transactions updated: {} approved, {} failed",
                event.getBatchId(), approved, failed);

        // Update batch status to PROCESSED
        Optional<RtaBatch> optBatch = batchRepository.findById(event.getBatchId());
        if (optBatch.isPresent()) {
            RtaBatch batch = optBatch.get();
            batch.setStatus("PROCESSED");
            batch.setLastModifiedAt(LocalDateTime.now());
            batch.setLastModifiedBy("SYSTEM");
            batchRepository.save(batch);
            log.info("[TxnUpdateService] Batch {} status updated to PROCESSED", event.getBatchId());
        }

        // Audit log
        auditLogService.logSystemActivity("BATCH_RESPONSE",
                String.valueOf(event.getBatchId()),
                "Authorization response received for batch #" + event.getBatchId()
                + ": " + approved + " approved, " + failed + " failed",
                "SUCCESS");
    }
}
