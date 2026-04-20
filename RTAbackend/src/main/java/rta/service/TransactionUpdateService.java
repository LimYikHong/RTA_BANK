package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rta.entity.RtaBatch;
import rta.entity.RtaTransaction;
import rta.event.BatchResponseEvent;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaTransactionRepository;

import java.time.LocalDateTime;
import java.util.Optional;

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

        // Update each transaction individually
        for (BatchResponseEvent.TransactionResult result : event.getResults()) {
            Optional<RtaTransaction> optTxn = transactionRepository.findById(result.getTransactionId());
            if (optTxn.isEmpty()) {
                log.warn("[TxnUpdateService] Transaction {} not found, skipping", result.getTransactionId());
                continue;
            }

            RtaTransaction txn = optTxn.get();
            txn.setStatus(result.getStatus());
            txn.setRemark(result.getRemark());
            txn.setAuthorizationDatetime(LocalDateTime.now());
            transactionRepository.save(txn);

            if ("APPROVED".equals(result.getStatus())) {
                approved++;
            } else {
                failed++;
            }
        }

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
