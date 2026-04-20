package rta.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;

/**
 * Scheduled service that runs every 5 minutes.
 *
 * <p>
 * Phase 1 — Batch assignment: finds incoming files with successCount > 0 that
 * have NOT yet been assigned a batch_id. Creates an {@link RtaBatch} with
 * status {@code CREATED}, assigns the batch_id to each file, and bulk-assigns
 * batch_id to the file's transactions.</p>
 *
 * <p>
 * Phase 2 — Encrypt & send: for each {@code CREATED} batch, generates a CSV of
 * all PENDING transactions, encrypts it with AES-256 (key encrypted with
 * merchant RSA public key), sends the encrypted file to the
 * {@code batch-request} Kafka topic, then bulk-updates transaction status to
 * {@code SENT} and batch status to {@code SENT}.</p>
 *
 * <p>
 * The authorization response arrives asynchronously on the
 * {@code batch-response} Kafka topic and is handled by
 * {@link TransactionUpdateService}, which sets each transaction to
 * {@code APPROVED} or {@code FAILED} and the batch to {@code PROCESSED}.</p>
 */
@Service
public class BatchMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchMaintenanceScheduler.class);
    private static final DateTimeFormatter REF_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final long INTERVAL_MS = 300_000; // 5 minutes

    private final RtaTransactionRepository transactionRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaBatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final BatchFileGenerationService batchFileGenerationService;
    private final BatchRequestProducer batchRequestProducer;

    /**
     * Epoch millis of the last completed batch run.
     */
    private volatile long lastRunTimeMs = System.currentTimeMillis();

    public BatchMaintenanceScheduler(RtaTransactionRepository transactionRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaBatchRepository batchRepository,
            AuditLogService auditLogService,
            BatchFileGenerationService batchFileGenerationService,
            BatchRequestProducer batchRequestProducer) {
        this.transactionRepository = transactionRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
        this.batchFileGenerationService = batchFileGenerationService;
        this.batchRequestProducer = batchRequestProducer;
    }

    /**
     * Returns epoch millis when the next scheduled batch will run.
     */
    public long getNextRunTimeMs() {
        return lastRunTimeMs + INTERVAL_MS;
    }

    /**
     * Runs every 5 minutes (300 000 ms).
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void runBatchGrouping() {
        log.info("[BatchMaintenance] Scheduled batch grouping started...");

        // Phase 1: Assign batch IDs to eligible incoming files → status CREATED
        assignBatchIds();

        // Phase 2: For each CREATED batch, generate encrypted CSV → send via Kafka → status SENT
        encryptAndSendCreatedBatches();

        this.lastRunTimeMs = System.currentTimeMillis();
        log.info("[BatchMaintenance] Scheduled batch grouping completed.");
    }

    // ─────────────────────────────────────────────────────────────
    // Phase 1: Assign batch IDs
    // ─────────────────────────────────────────────────────────────
    /**
     * Finds all eligible incoming files, creates a SINGLE RtaBatch with status
     * CREATED, and links files + transactions to that batch.
     */
    private void assignBatchIds() {
        List<RtaIncomingBatchFile> eligibleFiles = incomingFileRepository.findEligibleForBatch();

        if (eligibleFiles.isEmpty()) {
            log.info("[BatchMaintenance] No eligible files for batch assignment. Skipping.");
            return;
        }

        log.info("[BatchMaintenance] Found {} eligible file(s) for batch assignment.", eligibleFiles.size());

        int totalCount = 0;
        int totalSuccess = 0;
        int totalFail = 0;
        for (RtaIncomingBatchFile f : eligibleFiles) {
            totalCount += (f.getTotalRecordCount() != null ? f.getTotalRecordCount() : 0);
            totalSuccess += (f.getSuccessCount() != null ? f.getSuccessCount() : 0);
            totalFail += (f.getFailCount() != null ? f.getFailCount() : 0);
        }

        String batchRef = "BATCH-" + LocalDateTime.now().format(REF_FORMAT);

        RtaBatch batch = new RtaBatch();
        batch.setFileName(batchRef);
        batch.setOriginalFileName(eligibleFiles.size() + " file(s)");
        batch.setMerchantId(eligibleFiles.size() == 1
                ? eligibleFiles.get(0).getMerchantId()
                : "MULTIPLE");
        batch.setStatus("CREATED");
        batch.setCreatedBy("SYSTEM");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setTotalCount(totalCount);
        batch.setTotalSuccessCount(totalSuccess);
        batch.setTotalFailCount(totalFail);
        RtaBatch savedBatch = batchRepository.save(batch);

        for (RtaIncomingBatchFile file : eligibleFiles) {
            file.setBatchId(savedBatch.getBatchId());
            file.setBatchStatus("CREATED");
            incomingFileRepository.save(file);

            int updated = transactionRepository.bulkAssignBatchByFileId(
                    savedBatch.getBatchId(), file.getBatchFileId());

            log.info("[BatchMaintenance] File '{}' (merchant: {}) assigned to batch {} — {} txn(s) linked",
                    file.getOriginalFilename(), file.getMerchantId(),
                    savedBatch.getBatchId(), updated);
        }

        log.info("[BatchMaintenance] Batch {} CREATED — {} file(s), {} records ({} success, {} fail)",
                savedBatch.getBatchId(), eligibleFiles.size(), totalCount, totalSuccess, totalFail);

        auditLogService.logSystemActivity("RUN_BATCH",
                String.valueOf(savedBatch.getBatchId()),
                "Batch #" + savedBatch.getBatchId() + " created with "
                + eligibleFiles.size() + " file(s), "
                + totalCount + " records (" + totalSuccess + " success, " + totalFail + " fail)",
                "SUCCESS");
    }

    // ─────────────────────────────────────────────────────────────
    // Phase 2: Encrypt & send CREATED batches via Kafka
    // ─────────────────────────────────────────────────────────────
    /**
     * For every batch in CREATED status: collect PENDING transactions, generate
     * an encrypted CSV, send via Kafka, bulk-update transactions to SENT, and
     * mark batch as SENT.
     */
    private void encryptAndSendCreatedBatches() {
        List<RtaBatch> createdBatches = batchRepository.findByStatus("CREATED");

        if (createdBatches.isEmpty()) {
            log.info("[BatchMaintenance] No CREATED batches to send. Skipping.");
            return;
        }

        for (RtaBatch batch : createdBatches) {
            try {
                sendBatch(batch);
            } catch (Exception e) {
                log.error("[BatchMaintenance] Failed to send batch {}: {}",
                        batch.getBatchId(), e.getMessage(), e);
            }
        }
    }

    private void sendBatch(RtaBatch batch) {
        // Get all PENDING transactions for this batch
        List<RtaTransaction> pendingTxns = transactionRepository
                .findByBatchBatchIdAndStatus(batch.getBatchId(), "PENDING");

        if (pendingTxns.isEmpty()) {
            log.info("[BatchMaintenance] Batch {} has no PENDING transactions. Marking SENT with 0 records.",
                    batch.getBatchId());
            batch.setStatus("SENT");
            batch.setLastModifiedAt(LocalDateTime.now());
            batch.setLastModifiedBy("SYSTEM");
            batchRepository.save(batch);
            return;
        }

        log.info("[BatchMaintenance] Encrypting batch {} ({} PENDING transactions, merchant: {})...",
                batch.getBatchId(), pendingTxns.size(), batch.getMerchantId());

        // Generate CSV, encrypt with AES-256, encrypt key with RSA
        BatchFileGenerationService.EncryptedBatchFile encrypted
                = batchFileGenerationService.generateAndEncrypt(
                        batch.getBatchId(), batch.getMerchantId(), pendingTxns);

        // Send to Kafka topic: batch-request
        batchRequestProducer.send(
                batch.getBatchId(),
                batch.getMerchantId(),
                encrypted.getCsvFilename(),
                pendingTxns.size(),
                encrypted.getEncryptedFileBytes(),
                encrypted.getEncryptedAesKeyBase64(),
                encrypted.getIvBase64());

        // Bulk-update transaction status: PENDING → SENT (using batch_file_id from batch)
        List<Long> batchFileIds = transactionRepository
                .findDistinctBatchFileIdsByBatchId(batch.getBatchId());
        for (Long batchFileId : batchFileIds) {
            transactionRepository.bulkUpdateStatusByBatchFileId(batchFileId, "PENDING", "SENT");
        }

        // Update batch status: CREATED → SENT
        batch.setStatus("SENT");
        batch.setLastModifiedAt(LocalDateTime.now());
        batch.setLastModifiedBy("SYSTEM");
        batchRepository.save(batch);

        log.info("[BatchMaintenance] Batch {} SENT to Kafka — {} transactions, file: {}",
                batch.getBatchId(), pendingTxns.size(), encrypted.getCsvFilename());

        auditLogService.logSystemActivity("BATCH_SENT",
                String.valueOf(batch.getBatchId()),
                "Batch #" + batch.getBatchId() + " encrypted and sent to authorization — "
                + pendingTxns.size() + " transactions",
                "SUCCESS");
    }
}
