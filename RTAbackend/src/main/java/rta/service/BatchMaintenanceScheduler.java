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

    /**
     * Epoch millis of the last completed batch run
     */
    private volatile long lastRunTimeMs = System.currentTimeMillis();

    public BatchMaintenanceScheduler(RtaTransactionRepository transactionRepository,
            RtaAuthorizationBatchRepository authBatchRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            RtaBatchRepository batchRepository) {
        this.transactionRepository = transactionRepository;
        this.authBatchRepository = authBatchRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.batchRepository = batchRepository;
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
     * Finds incoming files with successCount > 0 and no batchId, creates an
     * RtaBatch for each, and links the file and its transactions to the batch.
     * Files with 0 successful records are excluded — no batch ID is wasted.
     */
    private void assignBatchIds() {
        List<RtaIncomingBatchFile> eligibleFiles = incomingFileRepository.findEligibleForBatch();

        if (eligibleFiles.isEmpty()) {
            log.info("[BatchMaintenance] No eligible files for batch assignment. Skipping.");
            return;
        }

        log.info("[BatchMaintenance] Found {} eligible file(s) for batch assignment.", eligibleFiles.size());

        for (RtaIncomingBatchFile file : eligibleFiles) {
            // Create RtaBatch record
            RtaBatch batch = new RtaBatch();
            batch.setFileName(file.getStoredFilename());
            batch.setOriginalFileName(file.getOriginalFilename());
            batch.setMerchantId(file.getMerchantId());
            batch.setStatus(file.getFileStatus());
            batch.setCreatedBy(file.getCreateBy() != null ? file.getCreateBy() : "SYSTEM");
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalCount(file.getTotalRecordCount());
            batch.setTotalSuccessCount(file.getSuccessCount());
            batch.setTotalFailCount(file.getFailCount());
            RtaBatch savedBatch = batchRepository.save(batch);

            // Assign the generated batch_id from rta_batch to the incoming file
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

            log.info("[BatchMaintenance] Batch {} created for file '{}' (merchant: {}, {} records)",
                    savedBatch.getBatchId(), file.getOriginalFilename(),
                    file.getMerchantId(), file.getSuccessCount());
        }
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
