package rta.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Phase 1 — Batch assignment: finds incoming files with successCount > 0 that
 * have NOT yet been assigned a batch_id. For each eligible file, creates an
 * {@link RtaBatch} record, assigns the generated batch_id to the incoming file
 * (FK to rta_batch), and links the file's transactions to that batch. Files
 * with 0 successful records are skipped (no batch ID wasted).
 *
 * <p>
 * Phase 2 — Authorization grouping: groups all validated (SUCCESS) transactions
 * that have NOT been assigned to an authorization batch (auth_batch_id IS
 * NULL). Creates a new {@link RtaAuthorizationBatch} and assigns it to those
 * transactions with status "READY_TO_SEND".
 */
@Service
public class BatchMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchMaintenanceScheduler.class);
    private static final DateTimeFormatter REF_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final long INTERVAL_MS = 300_000; // 5 minutes

    private final RtaTransactionRepository transactionRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaBatchRepository batchRepository;
    private final AuditLogService auditLogService;

    /**
     * Epoch millis of the last completed batch run
     */
    private volatile long lastRunTimeMs = System.currentTimeMillis();

    public BatchMaintenanceScheduler(RtaTransactionRepository transactionRepository,
            RtaAuthorizationBatchRepository authBatchRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaBatchRepository batchRepository,
            AuditLogService auditLogService) {
        this.transactionRepository = transactionRepository;
        this.authBatchRepository = authBatchRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Returns epoch millis when the next scheduled batch will run.
     */
    public long getNextRunTimeMs() {
        return lastRunTimeMs + INTERVAL_MS;
    }

    /**
     * Runs every 5 minutes (300 000 ms). First assigns batch IDs to eligible
     * incoming files, then collects unbatched validated transactions and
     * creates an authorization batch.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void runBatchGrouping() {
        log.info("[BatchMaintenance] Scheduled batch grouping started...");
        this.lastRunTimeMs = System.currentTimeMillis();

        // -------------------------------------------------------
        // Phase 1: Assign batch IDs to eligible incoming files
        // -------------------------------------------------------
        assignBatchIds();

        // -------------------------------------------------------
        // Phase 2: Group unbatched validated transactions into
        //          authorization batches
        // -------------------------------------------------------
        createAuthorizationBatch();

        log.info("[BatchMaintenance] Scheduled batch grouping completed.");
    }

    /**
     * Finds all incoming files with successCount > 0 and no batchId, creates a
     * SINGLE RtaBatch for the entire run, and links all eligible files and
     * their transactions to that batch. All files uploaded within the time
     * period are grouped together in one batch run.
     */
    private void assignBatchIds() {
        List<RtaIncomingBatchFile> eligibleFiles = incomingFileRepository.findEligibleForBatch();

        if (eligibleFiles.isEmpty()) {
            log.info("[BatchMaintenance] No eligible files for batch assignment. Skipping.");
            return;
        }

        log.info("[BatchMaintenance] Found {} eligible file(s) for batch assignment.", eligibleFiles.size());

        // Aggregate totals across all eligible files
        int totalCount = 0;
        int totalSuccess = 0;
        int totalFail = 0;
        for (RtaIncomingBatchFile f : eligibleFiles) {
            totalCount += (f.getTotalRecordCount() != null ? f.getTotalRecordCount() : 0);
            totalSuccess += (f.getSuccessCount() != null ? f.getSuccessCount() : 0);
            totalFail += (f.getFailCount() != null ? f.getFailCount() : 0);
        }

        // Build a batch reference: BATCH-yyyyMMddHHmmss
        String batchRef = "BATCH-" + LocalDateTime.now().format(REF_FORMAT);

        // Create ONE RtaBatch record for all files in this run
        RtaBatch batch = new RtaBatch();
        batch.setFileName(batchRef);
        batch.setOriginalFileName(eligibleFiles.size() + " file(s)");
        batch.setMerchantId(eligibleFiles.size() == 1
                ? eligibleFiles.get(0).getMerchantId()
                : "MULTIPLE");
        batch.setStatus("BATCHED");
        batch.setCreatedBy("SYSTEM");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setTotalCount(totalCount);
        batch.setTotalSuccessCount(totalSuccess);
        batch.setTotalFailCount(totalFail);
        RtaBatch savedBatch = batchRepository.save(batch);

        // Assign the single batch to every eligible file and their transactions
        for (RtaIncomingBatchFile file : eligibleFiles) {
            file.setBatchId(savedBatch.getBatchId());
            file.setBatchStatus("BATCHED");
            incomingFileRepository.save(file);

            // Assign batch to all transactions belonging to this file
            List<RtaTransaction> transactions = transactionRepository
                    .findUnbatchedByBatchFileId(file.getBatchFileId());
            for (RtaTransaction txn : transactions) {
                txn.setBatch(savedBatch);
            }
            transactionRepository.saveAll(transactions);

            log.info("[BatchMaintenance] File '{}' (merchant: {}, {} records) assigned to batch {}",
                    file.getOriginalFilename(), file.getMerchantId(),
                    file.getSuccessCount(), savedBatch.getBatchId());
        }

        log.info("[BatchMaintenance] Batch {} created — {} file(s), {} total records ({} success, {} fail)",
                savedBatch.getBatchId(), eligibleFiles.size(), totalCount, totalSuccess, totalFail);

        // Audit log: system activity for run batch
        auditLogService.logSystemActivity("RUN_BATCH",
                String.valueOf(savedBatch.getBatchId()),
                "Batch scheduler created batch #" + savedBatch.getBatchId()
                + " with " + eligibleFiles.size() + " file(s), "
                + totalCount + " records (" + totalSuccess + " success, " + totalFail + " fail)",
                "SUCCESS");
    }

    /**
     * Groups all validated (SUCCESS) transactions that have NOT been assigned
     * to any authorization batch. Creates a new RtaAuthorizationBatch and
     * assigns it to those transactions.
     */
    private void createAuthorizationBatch() {
        List<RtaTransaction> unbatched = transactionRepository.findUnbatchedValidTransactions();

        if (unbatched.isEmpty()) {
            log.info("[BatchMaintenance] No unbatched validated transactions found. Skipping.");
            return;
        }

        log.info("[BatchMaintenance] Found {} unbatched validated transactions. Creating authorization batch...", unbatched.size());

        // Calculate totals
        int totalCount = unbatched.size();
        long totalAmountCents = unbatched.stream()
                .filter(t -> t.getAmount() != null)
                .mapToLong(RtaTransaction::getAmount)
                .sum();

        // Generate unique batch reference: AUTH-yyyyMMddHHmmss
        String batchRef = "AUTH-" + LocalDateTime.now().format(REF_FORMAT);

        // Create the authorization batch
        RtaAuthorizationBatch authBatch = new RtaAuthorizationBatch();
        authBatch.setBatchReference(batchRef);
        authBatch.setTotalCount(totalCount);
        authBatch.setSuccessCount(totalCount);  // All are validated SUCCESS
        authBatch.setFailCount(0);
        authBatch.setTotalAmountCents(totalAmountCents);
        authBatch.setBatchStatus("READY_TO_SEND");
        authBatch.setCreatedAt(LocalDateTime.now());
        authBatch.setRemark("Auto-grouped " + totalCount + " validated transactions");

        RtaAuthorizationBatch saved = authBatchRepository.save(authBatch);

        // Assign auth_batch_id to all unbatched transactions
        for (RtaTransaction txn : unbatched) {
            txn.setAuthBatchId(saved.getAuthBatchId());
        }
        transactionRepository.saveAll(unbatched);

        log.info("[BatchMaintenance] Authorization batch '{}' created with {} transactions, total amount: {} cents",
                batchRef, totalCount, totalAmountCents);
    }
}
