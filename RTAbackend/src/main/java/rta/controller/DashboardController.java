package rta.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.SystemRsaKeyRequest;
import rta.repository.AuditLogRepository;
import rta.repository.MerchantInfoRepository;
import rta.repository.MerchantKeyRepository;
import rta.repository.ProfileRepository;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaTransactionRepository;
import rta.repository.SystemRsaKeyRequestRepository;
import rta.service.AuditLogService;
import rta.service.ConsumerKeyService;

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
    private final SystemRsaKeyRequestRepository rsaKeyRequestRepository;
    private final ConsumerKeyService consumerKeyService;
    private final AuditLogService auditLogService;
    private final MerchantKeyRepository merchantKeyRepository;

    public DashboardController(
            RtaBatchRepository batchRepository,
            RtaIncomingBatchFileRepository incomingBatchFileRepository,
            RtaTransactionRepository transactionRepository,
            MerchantInfoRepository merchantInfoRepository,
            ProfileRepository profileRepository,
            AuditLogRepository auditLogRepository,
            SystemRsaKeyRequestRepository rsaKeyRequestRepository,
            ConsumerKeyService consumerKeyService,
            AuditLogService auditLogService,
            MerchantKeyRepository merchantKeyRepository) {
        this.batchRepository = batchRepository;
        this.incomingBatchFileRepository = incomingBatchFileRepository;
        this.transactionRepository = transactionRepository;
        this.merchantInfoRepository = merchantInfoRepository;
        this.profileRepository = profileRepository;
        this.auditLogRepository = auditLogRepository;
        this.rsaKeyRequestRepository = rsaKeyRequestRepository;
        this.consumerKeyService = consumerKeyService;
        this.auditLogService = auditLogService;
        this.merchantKeyRepository = merchantKeyRepository;
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
                        && !"FAILED".equalsIgnoreCase(t.getStatus()))
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

    // ── RSA Key Request ─────────────────────────────────────────────────────
    /**
     * GET /api/dashboard/rsa-key-status Returns the current RSA key status for
     * the 30-day lifecycle. Only SUPER_ADMIN can access.
     */
    @GetMapping("/rsa-key-status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> getRsaKeyStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<SystemRsaKeyRequest> latest = rsaKeyRequestRepository
                .findTopByStatusOrderByRequestedAtDesc("ACTIVE");

        if (latest.isEmpty()) {
            // No key ever requested
            result.put("hasKey", false);
            result.put("daysRemaining", 0);
            result.put("canRequest", true);
            result.put("needsRenewal", false);
            result.put("expired", false);
            return ResponseEntity.ok(result);
        }

        SystemRsaKeyRequest key = latest.get();
        long daysElapsed = ChronoUnit.DAYS.between(key.getRequestedAt(), LocalDateTime.now());
        long daysRemaining = 30 - daysElapsed;

        boolean expired = daysRemaining <= 0;
        boolean needsRenewal = daysElapsed >= 25 && !expired;
        // Can request: before day 25 = disabled, day 25-29 = enabled, after day 30 = disabled (must contact admin)
        boolean canRequest = needsRenewal;

        if (expired) {
            key.setStatus("EXPIRED");
            rsaKeyRequestRepository.save(key);
        }

        result.put("hasKey", true);
        result.put("requestedAt", key.getRequestedAt().toString());
        result.put("expiresAt", key.getExpiresAt().toString());
        result.put("daysRemaining", Math.max(0, daysRemaining));
        result.put("daysElapsed", daysElapsed);
        result.put("canRequest", canRequest);
        result.put("needsRenewal", needsRenewal);
        result.put("expired", expired);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/dashboard/request-rsa-key Requests a new RSA key from the
     * consumer/auth third party. Only SUPER_ADMIN can access.
     */
    @PostMapping("/request-rsa-key")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> requestRsaKey(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        String userId = request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "SUPER_ADMIN";
        String ipAddress = request.getRemoteAddr();

        try {
            // Check if renewal is allowed
            Optional<SystemRsaKeyRequest> latest = rsaKeyRequestRepository
                    .findTopByStatusOrderByRequestedAtDesc("ACTIVE");

            if (latest.isPresent()) {
                long daysElapsed = ChronoUnit.DAYS.between(latest.get().getRequestedAt(), LocalDateTime.now());
                if (daysElapsed < 25) {
                    result.put("success", false);
                    result.put("message", "RSA key is still valid. Renewal is available from day 25.");
                    auditLogService.logUserActivity("REQUEST_RSA_KEY", userId, null,
                            "RSA key request rejected - key still valid (day " + daysElapsed + ")", "REJECTED", ipAddress);
                    return ResponseEntity.badRequest().body(result);
                }
                if (daysElapsed >= 30) {
                    result.put("success", false);
                    result.put("message", "RSA key has expired. Please contact system administrator.");
                    auditLogService.logUserActivity("REQUEST_RSA_KEY", userId, null,
                            "RSA key request rejected - key expired", "REJECTED", ipAddress);
                    return ResponseEntity.badRequest().body(result);
                }
                // Mark old key as expired
                latest.get().setStatus("EXPIRED");
                rsaKeyRequestRepository.save(latest.get());
            }

            // Fetch the RSA public key from the sendAuth system
            // (sendAuth generates its key pair on startup, we just fetch it)
            String pem = consumerKeyService.fetchRsaPublicKeyPem();

            // Save new key request
            LocalDateTime now = LocalDateTime.now();
            SystemRsaKeyRequest newRequest = SystemRsaKeyRequest.builder()
                    .requestedBy(userId)
                    .publicKeyPem(pem)
                    .status("ACTIVE")
                    .requestedAt(now)
                    .expiresAt(now.plusDays(30))
                    .ipAddress(ipAddress)
                    .build();
            rsaKeyRequestRepository.save(newRequest);

            // Audit log
            auditLogService.logUserActivity("REQUEST_RSA_KEY", userId, String.valueOf(newRequest.getId()),
                    "RSA key successfully requested from consumer system", "SUCCESS", ipAddress);

            result.put("success", true);
            result.put("message", "RSA key successfully obtained from consumer system.");
            result.put("expiresAt", newRequest.getExpiresAt().toString());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            auditLogService.logUserActivity("REQUEST_RSA_KEY", userId, null,
                    "RSA key request failed: " + e.getMessage(), "FAILED", ipAddress);
            result.put("success", false);
            result.put("message", "Failed to obtain RSA key: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    // ── Merchant Key Overview ───────────────────────────────────────────────
    /**
     * GET /api/dashboard/merchant-key-overview Returns a list of all active
     * merchants with their RSA key status. Used by the dashboard to show which
     * merchants need key rotation or new keys.
     */
    @GetMapping("/merchant-key-overview")
    public ResponseEntity<List<Map<String, Object>>> getMerchantKeyOverview() {
        List<Map<String, Object>> overview = new ArrayList<>();

        // Get all active merchants
        var allMerchants = merchantInfoRepository.findAll()
                .stream().filter(m -> m.getDeletedAt() == null).toList();

        for (var merchant : allMerchants) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("merchantId", merchant.getMerchantId());
            entry.put("merchantName", merchant.getName());

            // Find latest active key for this merchant
            Optional<rta.entity.MerchantKey> activeKey = merchantKeyRepository
                    .findFirstByMerchantIdAndStatusOrderByVersionNoDesc(merchant.getMerchantId(), "ACTIVE");

            if (activeKey.isPresent()) {
                rta.entity.MerchantKey key = activeKey.get();
                long daysElapsed = ChronoUnit.DAYS.between(key.getActivatedAt(), LocalDateTime.now());
                long daysRemaining = key.getExpiresAt() != null
                        ? ChronoUnit.DAYS.between(LocalDateTime.now(), key.getExpiresAt())
                        : 730 - daysElapsed; // default 2 years

                boolean expired = daysRemaining <= 0;
                boolean needsRotation = daysElapsed >= 25 && !expired;
                boolean canRotate = daysElapsed >= 25 && daysElapsed < 30;

                entry.put("hasKey", true);
                entry.put("keyVersion", key.getVersionNo());
                entry.put("keyStatus", expired ? "EXPIRED" : key.getStatus());
                entry.put("activatedAt", key.getActivatedAt() != null ? key.getActivatedAt().toString() : null);
                entry.put("expiresAt", key.getExpiresAt() != null ? key.getExpiresAt().toString() : null);
                entry.put("daysElapsed", daysElapsed);
                entry.put("daysRemaining", Math.max(0, daysRemaining));
                entry.put("needsRotation", needsRotation);
                entry.put("canRotate", canRotate);
                entry.put("expired", expired);
            } else {
                entry.put("hasKey", false);
                entry.put("keyVersion", 0);
                entry.put("keyStatus", "NO_KEY");
                entry.put("activatedAt", null);
                entry.put("expiresAt", null);
                entry.put("daysElapsed", 0);
                entry.put("daysRemaining", 0);
                entry.put("needsRotation", false);
                entry.put("canRotate", false);
                entry.put("expired", false);
            }

            overview.add(entry);
        }

        return ResponseEntity.ok(overview);
    }
}
