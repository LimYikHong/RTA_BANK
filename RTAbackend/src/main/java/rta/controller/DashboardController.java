package rta.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.repository.AuditLogRepository;
import rta.repository.MerchantInfoRepository;
import rta.repository.ProfileRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;

/**
 * DashboardController – aggregates platform-wide stats for the Dashboard page.
 * GET /api/dashboard/stats
 */
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = {"http://localhost:4200", "https://localhost:4200"})
public class DashboardController {

    private final RtaBatchRepository batchRepository;
    private final RtaIncomingBatchFileRepository incomingBatchFileRepository;
    private final RtaTransactionRepository transactionRepository;
    private final MerchantInfoRepository merchantInfoRepository;
    private final ProfileRepository profileRepository;
    private final AuditLogRepository auditLogRepository;

    public DashboardController(
            RtaBatchRepository batchRepository,
            RtaIncomingBatchFileRepository incomingBatchFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            ProfileRepository profileRepository,
            AuditLogRepository auditLogRepository) {
        this.batchRepository = batchRepository;
        this.incomingBatchFileRepository = incomingBatchFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.profileRepository = profileRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * GET /api/dashboard/stats Returns all KPI counters, batch status
     * breakdown, transaction trend (last 7 days), incoming file status
     * distribution, and recent activity.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new LinkedHashMap<>();

            // ── KPI counters ────────────────────────────────────────────────────
            List<RtaBatch> allBatches = batchRepository.findAllActive();
            List<RtaIncomingBatchFile> allIncoming = incomingBatchFileRepository.findAll()
                    .stream().filter(f -> f.getDeletedAt() == null).collect(Collectors.toList());
            List<rta.entity.RtaTransaction> allTxns = transactionRepository.findAll();
            long totalTransactions = allTxns.size();
            long activeMerchants = merchantInfoRepository.findAll()
                    .stream().filter(m -> m.getDeletedAt() == null).count();
            long adminUsers = profileRepository.findAll()
                    .stream().filter(u -> u.getDeletedAt() == null).count();

            stats.put("totalBatches", allBatches.size());
            stats.put("totalIncomingFiles", allIncoming.size());
            stats.put("totalTransactions", totalTransactions);
            stats.put("activeMerchants", activeMerchants);
            stats.put("adminUsers", adminUsers);

            // ── Transaction status breakdown (replaces batch status) ─────────────
            Map<String, Long> txnStatusMap = allTxns.stream()
                    .collect(Collectors.groupingBy(
                            t -> t.getStatus() != null ? t.getStatus().toUpperCase() : "UNKNOWN",
                            Collectors.counting()));
            stats.put("transactionStatusBreakdown", txnStatusMap);

            // ── Incoming file status distribution ───────────────────────────────
            Map<String, Long> incomingStatusMap = allIncoming.stream()
                    .collect(Collectors.groupingBy(
                            f -> f.getFileStatus() != null ? f.getFileStatus() : "UNKNOWN",
                            Collectors.counting()));
            stats.put("incomingFileStatusBreakdown", incomingStatusMap);

            // ── Transaction trend: success vs fail per day (last 7 days) ────────
            DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("MM/dd");
            List<Map<String, Object>> trend = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                LocalDateTime from = day.atStartOfDay();
                LocalDateTime to = day.plusDays(1).atStartOfDay();
                long success = allTxns.stream()
                        .filter(t -> t.getCreatedAt() != null
                        && !t.getCreatedAt().isBefore(from)
                        && t.getCreatedAt().isBefore(to)
                        && "SUCCESS".equalsIgnoreCase(t.getStatus()))
                        .count();
                long failed = allTxns.stream()
                        .filter(t -> t.getCreatedAt() != null
                        && !t.getCreatedAt().isBefore(from)
                        && t.getCreatedAt().isBefore(to)
                        && "FAILED".equalsIgnoreCase(t.getStatus()))
                        .count();
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("day", day.format(dayFmt));
                point.put("success", success);
                point.put("failed", failed);
                trend.add(point);
            }
            stats.put("transactionTrend", trend);

            // ── Incoming files per merchant (top 8) ─────────────────────────────
            Map<String, Long> perMerchant = allIncoming.stream()
                    .filter(f -> f.getMerchantId() != null)
                    .collect(Collectors.groupingBy(
                            RtaIncomingBatchFile::getMerchantId,
                            Collectors.counting()));
            List<Map<String, Object>> topMerchants = perMerchant.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(8)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("merchantId", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            stats.put("incomingFilesPerMerchant", topMerchants);

            // ── Transactions per merchant (top 8) ───────────────────────────────
            Map<String, Long> txnPerMerchantMap = allTxns.stream()
                    .filter(t -> t.getMerchantId() != null)
                    .collect(Collectors.groupingBy(
                            rta.entity.RtaTransaction::getMerchantId,
                            Collectors.counting()));
            List<Map<String, Object>> txnTopMerchants = txnPerMerchantMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(8)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("merchantId", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            stats.put("txnPerMerchant", txnTopMerchants);

            // ── Recurring vs One-Time breakdown ─────────────────────────────────
            long recurringCount = allTxns.stream()
                    .filter(t -> Boolean.TRUE.equals(t.getIsRecurring())).count();
            long oneTimeCount = allTxns.size() - recurringCount;
            Map<String, Long> recurringMap = new LinkedHashMap<>();
            recurringMap.put("RECURRING", recurringCount);
            recurringMap.put("ONE-TIME", oneTimeCount);
            stats.put("recurringBreakdown", recurringMap);

            // ── Daily transaction amount trend (last 7 days) ────────────────────
            List<Map<String, Object>> amountTrend = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                LocalDateTime from = day.atStartOfDay();
                LocalDateTime to = day.plusDays(1).atStartOfDay();
                long totalCents = allTxns.stream()
                        .filter(t -> t.getCreatedAt() != null
                        && !t.getCreatedAt().isBefore(from)
                        && t.getCreatedAt().isBefore(to)
                        && t.getAmount() != null)
                        .mapToLong(rta.entity.RtaTransaction::getAmount)
                        .sum();
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("day", day.format(dayFmt));
                pt.put("amount", totalCents);
                amountTrend.add(pt);
            }
            stats.put("dailyAmountTrend", amountTrend);

            // ── Recent audit log entries (last 8) ───────────────────────────────
            List<Map<String, Object>> recentActivity;
            try {
                recentActivity = auditLogRepository
                        .findAllByOrderByCreatedAtDesc()
                        .stream()
                        .limit(8)
                        .map(log -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("action", log.getAction());
                            entry.put("description", log.getDescription());
                            entry.put("userId", log.getUserId());
                            entry.put("status", log.getStatus());
                            entry.put("createdAt", log.getCreatedAt() != null
                                    ? log.getCreatedAt().toString() : null);
                            return entry;
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                recentActivity = new ArrayList<>();
            }
            stats.put("recentActivity", recentActivity);

            return ResponseEntity.ok(stats);
        } catch (Exception ex) {
            ex.printStackTrace();
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", ex.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
