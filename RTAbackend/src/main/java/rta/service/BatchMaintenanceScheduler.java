package rta.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import rta.entity.RtaAuthorizationBatch;
import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.RtaTransaction;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;

/**
 * Scheduled service that runs every 5 minutes.
 *
 * <p>
 * Phase 1 — Batch assignment: finds incoming batch files that have no batch_id
 * assigned yet, where insertionStatus = COMPLETED and successCount &gt; 0.
 * Creates an {@link RtaBatch} with status {@code CREATED}, assigns the batch_id
 * to each eligible file and its transactions.</p>
 *
 * <p>
 * Phase 2 — Encrypt &amp; send to consumer: for each {@code CREATED} batch,
 * collects only PENDING (passed validation) transactions, generates a CSV,
 * encrypts it with AES-256 (AES key encrypted with consumer's RSA public key),
 * stores the encrypted CSV in MinIO, sends it to the sendAuth consumer system
 * via HTTPS internal API, receives the authorization result (encrypted),
 * decrypts it, and updates transaction statuses (APPROVED/FAILED).</p>
 */
@Service
public class BatchMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchMaintenanceScheduler.class);
    private static final DateTimeFormatter REF_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final long INTERVAL_MS = 300_000; // 5 minutes

    private final RtaTransactionRepository transactionRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaBatchRepository batchRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final AuditLogService auditLogService;
    private final BatchFileGenerationService batchFileGenerationService;
    private final BatchRequestProducer batchRequestProducer;
    private final MinioStorageService minioStorageService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Epoch millis of the last completed batch run.
     */
    private volatile long lastRunTimeMs = System.currentTimeMillis();

    public BatchMaintenanceScheduler(RtaTransactionRepository transactionRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaBatchRepository batchRepository,
            RtaAuthorizationBatchRepository authBatchRepository,
            AuditLogService auditLogService,
            BatchFileGenerationService batchFileGenerationService,
            BatchRequestProducer batchRequestProducer,
            MinioStorageService minioStorageService,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.batchRepository = batchRepository;
        this.authBatchRepository = authBatchRepository;
        this.auditLogService = auditLogService;
        this.batchFileGenerationService = batchFileGenerationService;
        this.batchRequestProducer = batchRequestProducer;
        this.minioStorageService = minioStorageService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Returns epoch millis when the next scheduled batch will run. Aligned to
     * 5-minute clock intervals (0, 5, 10, ... minutes past the hour).
     */
    public long getNextRunTimeMs() {
        long now = System.currentTimeMillis();
        long midnightMs = now - (now % 86_400_000L); // approximate midnight
        long elapsed = now - midnightMs;
        long slotsPassed = elapsed / INTERVAL_MS;
        return midnightMs + (slotsPassed + 1) * INTERVAL_MS;
    }

    /**
     * Runs every 5 minutes aligned to clock (0, 5, 10, ... minutes past the
     * hour). Synced with frontend timer which also counts from midnight in
     * 5-min slots. Phase 1 and Phase 2 run in separate transactions so a
     * failure in Phase 2 does not roll back Phase 1.
     */
    @Scheduled(cron = "0 0/5 * * * *")
    public synchronized void runBatchGrouping() {
        log.info("[BatchMaintenance] Scheduled batch grouping started...");

        try {
            // Phase 1: Assign batch IDs to eligible incoming files → status CREATED
            assignBatchIds();
        } catch (Exception e) {
            log.error("[BatchMaintenance] Phase 1 (batch assignment) failed: {}", e.getMessage(), e);
        }

        try {
            // Phase 2: For each CREATED batch, generate encrypted CSV → send to consumer → status PROCESSED
            encryptAndSendCreatedBatches();
        } catch (Exception e) {
            log.error("[BatchMaintenance] Phase 2 (encrypt & send) failed: {}", e.getMessage(), e);
        }

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
        transactionTemplate.executeWithoutResult(status -> {
            doAssignBatchIds();
        });
    }

    private void doAssignBatchIds() {
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

        // Collect distinct merchant IDs as JSON array, e.g. ["M001","M002"]
        String merchantIdsJson = eligibleFiles.stream()
                .map(RtaIncomingBatchFile::getMerchantId)
                .distinct()
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));

        RtaBatch batch = new RtaBatch();
        batch.setFileName(batchRef);
        batch.setOriginalFileName(eligibleFiles.size() + " file(s)");
        batch.setMerchantIds(merchantIdsJson);
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

        // ── Create RtaAuthorizationBatch so it appears in Batch Maintenance immediately ──
        long totalAmountCents = 0;
        for (RtaTransaction txn : transactionRepository.findByBatchBatchId(savedBatch.getBatchId())) {
            if (!"FAILED".equals(txn.getStatus())) {
                totalAmountCents += (txn.getAmount() != null ? txn.getAmount() : 0);
            }
        }

        RtaAuthorizationBatch authBatch = new RtaAuthorizationBatch();
        authBatch.setBatchReference(batchRef);
        authBatch.setTotalCount(totalSuccess); // only valid (PENDING) transactions
        authBatch.setSuccessCount(0);  // not yet authorized
        authBatch.setFailCount(0);     // not yet authorized
        authBatch.setTotalAmountCents(totalAmountCents);
        authBatch.setBatchStatus("CREATED");
        authBatch.setCreatedAt(LocalDateTime.now());
        authBatch.setRemark(eligibleFiles.size() + " file(s), awaiting authorization");
        RtaAuthorizationBatch savedAuthBatch = authBatchRepository.save(authBatch);

        // Link all transactions to the auth batch (single UPDATE instead of row-by-row)
        int linkedCount = transactionRepository.bulkAssignAuthBatchId(
                savedAuthBatch.getAuthBatchId(), savedBatch.getBatchId());
        log.info("[BatchMaintenance] Linked {} transactions to AuthBatch #{}",
                linkedCount, savedAuthBatch.getAuthBatchId());

        log.info("[BatchMaintenance] Batch {} CREATED — {} file(s), {} records ({} success, {} fail). AuthBatch #{}",
                savedBatch.getBatchId(), eligibleFiles.size(), totalCount, totalSuccess, totalFail,
                savedAuthBatch.getAuthBatchId());

        auditLogService.logSystemActivity("RUN_BATCH",
                String.valueOf(savedBatch.getBatchId()),
                "Batch #" + savedBatch.getBatchId() + " created with "
                + eligibleFiles.size() + " file(s), "
                + totalCount + " records (" + totalSuccess + " success, " + totalFail + " fail). "
                + "AuthBatch #" + savedAuthBatch.getAuthBatchId(),
                "SUCCESS");
    } // end doAssignBatchIds

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
        long batchStartMs = System.currentTimeMillis();

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

        log.info("[BatchMaintenance] Processing batch {} ({} PENDING transactions, merchants: {})...",
                batch.getBatchId(), pendingTxns.size(), batch.getMerchantIds());

        // ── Step 1: Generate CSV and encrypt ──────────────────────────────
        long csvStartMs = System.currentTimeMillis();
        // Use first transaction's merchantId for encryption key record
        String primaryMerchantId = pendingTxns.get(0).getMerchantId();

        BatchFileGenerationService.EncryptedBatchFile encrypted
                = batchFileGenerationService.generateAndEncrypt(
                        batch.getBatchId(), primaryMerchantId, pendingTxns);
        long csvElapsedMs = System.currentTimeMillis() - csvStartMs;

        log.info("[BatchMaintenance] CSV generated and encrypted for batch {} in {}ms: {}",
                batch.getBatchId(), csvElapsedMs, encrypted.getCsvFilename());

        double csvElapsedSec = csvElapsedMs / 1000.0;
        auditLogService.logSystemActivity("GENERATE_CSV",
                String.valueOf(batch.getBatchId()),
                "CSV '" + encrypted.getCsvFilename() + "' generated and encrypted for batch #"
                + batch.getBatchId() + " — " + pendingTxns.size() + " transactions. "
                + "Time: " + String.format("%.3f", csvElapsedSec) + "s",
                "SUCCESS");

        // ── Step 2: Store CSV in MinIO ────────────────────────────────────
        try {
            String minioPath = "batch-csv/" + encrypted.getCsvFilename();
            minioStorageService.uploadFile(minioPath, encrypted.getEncryptedFileBytes(),
                    "application/octet-stream");
            log.info("[BatchMaintenance] Encrypted CSV stored in MinIO: {}", minioPath);

            auditLogService.logSystemActivity("STORE_CSV_MINIO",
                    String.valueOf(batch.getBatchId()),
                    "Encrypted CSV '" + encrypted.getCsvFilename() + "' stored in MinIO at " + minioPath,
                    "SUCCESS");
        } catch (Exception e) {
            log.error("[BatchMaintenance] Failed to store CSV in MinIO for batch {}: {}",
                    batch.getBatchId(), e.getMessage(), e);
            auditLogService.logSystemActivity("STORE_CSV_MINIO",
                    String.valueOf(batch.getBatchId()),
                    "Failed to store CSV in MinIO: " + e.getMessage(),
                    "FAILED");
            // Continue — MinIO failure should not block the auth process
        }

        // ── Step 3: Publish to auth service via Kafka (async) ────────────
        // Authorization result will arrive asynchronously on the batch-response
        // topic and be processed by TransactionUpdateService (steps 4-8).
        long sendStartMs = System.currentTimeMillis();
        try {
            batchRequestProducer.send(
                    batch.getBatchId(),
                    batch.getMerchantIds(),
                    encrypted.getCsvFilename(),
                    pendingTxns.size(),
                    encrypted.getEncryptedFileBytes(),
                    encrypted.getEncryptedAesKeyBase64(),
                    encrypted.getIvBase64());

            long sendElapsedMs = System.currentTimeMillis() - sendStartMs;
            log.info("[BatchMaintenance] Batch {} published to Kafka auth topic in {}ms",
                    batch.getBatchId(), sendElapsedMs);

            // Mark batch as SENT — TransactionUpdateService will update to PROCESSED
            batch.setStatus("SENT");
            batch.setLastModifiedAt(LocalDateTime.now());
            batch.setLastModifiedBy("SYSTEM");
            batchRepository.save(batch);

            long totalElapsedMs = System.currentTimeMillis() - batchStartMs;
            double sendElapsedSec = sendElapsedMs / 1000.0;
            double totalElapsedSec = totalElapsedMs / 1000.0;
            auditLogService.logSystemActivity("SEND_AUTH",
                    String.valueOf(batch.getBatchId()),
                    "Batch #" + batch.getBatchId() + " published to Kafka for authorization. "
                    + "CSV: " + String.format("%.3f", csvElapsedSec) + "s, "
                    + "Send: " + String.format("%.3f", sendElapsedSec) + "s, "
                    + "Total: " + String.format("%.3f", totalElapsedSec) + "s",
                    "SUCCESS");

        } catch (Exception e) {
            log.error("[BatchMaintenance] Failed to publish batch {} to Kafka: {}",
                    batch.getBatchId(), e.getMessage(), e);

            // Keep transactions as PENDING so they can be retried
            batch.setStatus("SEND_FAILED");
            batch.setLastModifiedAt(LocalDateTime.now());
            batch.setLastModifiedBy("SYSTEM");
            batchRepository.save(batch);

            RtaAuthorizationBatch failedAuthBatch = authBatchRepository
                    .findByBatchReference(batch.getFileName()).orElse(null);
            if (failedAuthBatch != null) {
                failedAuthBatch.setBatchStatus("SEND_FAILED");
                failedAuthBatch.setLastModifiedAt(LocalDateTime.now());
                failedAuthBatch.setRemark("Send auth failed: " + e.getMessage());
                authBatchRepository.save(failedAuthBatch);
            }

            long totalElapsedMs = System.currentTimeMillis() - batchStartMs;
            auditLogService.logSystemActivity("SEND_AUTH",
                    String.valueOf(batch.getBatchId()),
                    "Batch #" + batch.getBatchId() + " failed to publish to Kafka: "
                    + e.getMessage() + ". Total: " + totalElapsedMs + "ms",
                    "FAILED");
        }
    }

    /**
     * Retry sending authorization for a failed batch. Called from the
     * controller when user clicks retry button.
     */
    public String retrySendAuth(Long authBatchId) {
        RtaAuthorizationBatch authBatch = authBatchRepository.findById(authBatchId).orElse(null);
        if (authBatch == null) {
            return "Authorization batch not found";
        }
        if (!"SEND_FAILED".equals(authBatch.getBatchStatus())) {
            return "Batch is not in SEND_FAILED status (current: " + authBatch.getBatchStatus() + ")";
        }

        // Find the corresponding RtaBatch by batch reference
        List<RtaBatch> matchingBatches = batchRepository.findByStatus("SEND_FAILED");
        RtaBatch rtaBatch = matchingBatches.stream()
                .filter(b -> b.getFileName().equals(authBatch.getBatchReference()))
                .findFirst().orElse(null);
        if (rtaBatch == null) {
            return "Corresponding RtaBatch not found for reference: " + authBatch.getBatchReference();
        }

        // Reset to CREATED so sendBatch can process it
        rtaBatch.setStatus("CREATED");
        rtaBatch.setLastModifiedAt(LocalDateTime.now());
        rtaBatch.setLastModifiedBy("SYSTEM");
        batchRepository.save(rtaBatch);

        authBatch.setBatchStatus("RETRYING");
        authBatch.setLastModifiedAt(LocalDateTime.now());
        authBatch.setRemark("Retrying send auth...");
        authBatchRepository.save(authBatch);

        // Now send
        try {
            sendBatch(rtaBatch);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("[BatchMaintenance] Retry failed for auth batch {}: {}", authBatchId, e.getMessage(), e);
            return "Retry failed: " + e.getMessage();
        }
    }

    private Long toLong(Object val) {
        if (val == null) {
            return 0L;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return Long.parseLong(val.toString());
    }
}
