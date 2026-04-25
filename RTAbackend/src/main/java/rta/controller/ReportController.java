package rta.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rta.entity.RtaReport;
import rta.entity.RtaTransaction;
import rta.repository.RtaAuthorizationBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.RtaReportRepository;
import rta.repository.RtaTransactionRepository;
import rta.service.ReportGenerationService;

/**
 * ReportController — REST endpoints for the Report module.
 *
 * <ul>
 * <li>GET /api/reports — List all reports (paginated)</li>
 * <li>GET /api/reports/{reportId} — Get report detail</li>
 * <li>GET /api/reports/{reportId}/download — Download HTML/PDF report</li>
 * <li>GET /api/reports/{reportId}/output — Download output file (CSV/XLSX)</li>
 * <li>POST /api/reports/generate — Trigger report generation for all PROCESSED
 * batches</li>
 * <li>POST /api/reports/generate/{batchFileId} — Trigger report for a specific
 * batch file</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"https://localhost:4200", "https://localhost:8086"})
public class ReportController {

    private final RtaReportRepository reportRepository;
    private final RtaTransactionRepository transactionRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final RtaAuthorizationBatchRepository authBatchRepository;
    private final ReportGenerationService reportGenerationService;

    /**
     * GET /api/reports — List reports with pagination and optional search.
     */
    @GetMapping
    public ResponseEntity<?> listReports(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "merchantId", required = false) String merchantId,
            @RequestParam(value = "search", required = false) String search) {

        PageRequest pageRequest = PageRequest.of(page, size);
        Page<RtaReport> reportPage;

        if (merchantId != null && !merchantId.isBlank() && search != null && !search.isBlank()) {
            reportPage = reportRepository.searchReportsByMerchant(merchantId, search, pageRequest);
        } else if (merchantId != null && !merchantId.isBlank()) {
            reportPage = reportRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageRequest);
        } else if (search != null && !search.isBlank()) {
            reportPage = reportRepository.searchReports(search, pageRequest);
        } else {
            reportPage = reportRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }

        Map<String, Object> response = new LinkedHashMap<>();

        // Enrich each report with validationStatus and authStatus
        List<Map<String, Object>> enrichedReports = new java.util.ArrayList<>();
        for (RtaReport report : reportPage.getContent()) {
            Map<String, Object> rMap = new LinkedHashMap<>();
            rMap.put("reportId", report.getReportId());
            rMap.put("merchantId", report.getMerchantId());
            rMap.put("batchFileId", report.getBatchFileId());
            rMap.put("batchId", report.getBatchId());
            rMap.put("authBatchId", report.getAuthBatchId());
            rMap.put("reportName", report.getReportName());
            rMap.put("reportType", report.getReportType());
            rMap.put("fileFormat", report.getFileFormat());
            // Recompute counts from transactions if stored approvedCount is 0
            // (fixes stale counts for batches with multiple files)
            int approvedCount = report.getApprovedCount() != null ? report.getApprovedCount() : 0;
            int declinedCount = report.getDeclinedCount() != null ? report.getDeclinedCount() : 0;
            int failCount = report.getFailCount() != null ? report.getFailCount() : 0;
            int totalRecords = report.getTotalRecords() != null ? report.getTotalRecords() : 0;
            int successCount = report.getSuccessCount() != null ? report.getSuccessCount() : 0;

            if (approvedCount == 0 && report.getBatchFileId() != null) {
                List<RtaTransaction> txns = transactionRepository.findByBatchFileId(report.getBatchFileId());
                if (!txns.isEmpty()) {
                    int recomputedApproved = 0, recomputedDeclined = 0, recomputedFail = 0, recomputedSuccess = 0;
                    for (RtaTransaction txn : txns) {
                        String st = txn.getStatus() != null ? txn.getStatus().toUpperCase() : "";
                        switch (st) {
                            case "APPROVED" -> {
                                recomputedApproved++;
                                recomputedSuccess++;
                            }
                            case "DECLINED" -> {
                                recomputedDeclined++;
                                recomputedFail++;
                            }
                            case "FAILED" ->
                                recomputedFail++;
                            default ->
                                recomputedSuccess++;
                        }
                    }
                    if (recomputedApproved > 0) {
                        approvedCount = recomputedApproved;
                        declinedCount = recomputedDeclined;
                        failCount = recomputedFail;
                        successCount = recomputedSuccess;
                        totalRecords = txns.size();

                        // Also update the stored report so future queries are correct
                        report.setApprovedCount(approvedCount);
                        report.setDeclinedCount(declinedCount);
                        report.setFailCount(failCount);
                        report.setSuccessCount(successCount);
                        report.setTotalRecords(totalRecords);
                        reportRepository.save(report);
                    }
                }
            }

            rMap.put("totalRecords", totalRecords);
            rMap.put("successCount", successCount);
            rMap.put("failCount", failCount);
            rMap.put("approvedCount", approvedCount);
            rMap.put("declinedCount", declinedCount);
            rMap.put("totalAmount", report.getTotalAmount());
            rMap.put("status", report.getStatus());
            rMap.put("sendStatus", report.getSendStatus());
            rMap.put("sentAt", report.getSentAt());
            rMap.put("createdAt", report.getCreatedAt());
            rMap.put("createdBy", report.getCreatedBy());

            // Resolve validation status from incoming batch file
            String validationStatus = null;
            if (report.getBatchFileId() != null) {
                validationStatus = incomingFileRepository.findById(report.getBatchFileId())
                        .map(f -> f.getFileStatus()).orElse(null);
            }
            rMap.put("validationStatus", validationStatus);

            // Resolve auth status from auth batch
            String authStatus = null;
            Long authBatchId = report.getAuthBatchId();
            if (authBatchId == null && report.getBatchFileId() != null) {
                authBatchId = transactionRepository.findByBatchFileId(report.getBatchFileId()).stream()
                        .map(RtaTransaction::getAuthBatchId)
                        .filter(id -> id != null)
                        .findFirst().orElse(null);
            }
            if (validationStatus != null && validationStatus.equalsIgnoreCase("FAILED")) {
                authStatus = null; // validation failed, no auth
            } else if (authBatchId != null) {
                authStatus = authBatchRepository.findById(authBatchId)
                        .map(b -> b.getBatchStatus()).orElse(null);
            }
            rMap.put("authStatus", authStatus);

            enrichedReports.add(rMap);
        }

        response.put("content", enrichedReports);
        response.put("totalElements", reportPage.getTotalElements());
        response.put("totalPages", reportPage.getTotalPages());
        response.put("number", reportPage.getNumber());
        response.put("size", reportPage.getSize());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/{reportId} — Get a single report detail.
     */
    @GetMapping("/{reportId}")
    public ResponseEntity<?> getReport(@PathVariable Long reportId) {
        return reportRepository.findById(reportId)
                .<ResponseEntity<?>>map(report -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("reportId", report.getReportId());
                    result.put("merchantId", report.getMerchantId());
                    result.put("batchFileId", report.getBatchFileId());
                    result.put("batchId", report.getBatchId());
                    result.put("reportName", report.getReportName());
                    result.put("reportType", report.getReportType());
                    result.put("fileFormat", report.getFileFormat());
                    result.put("storageUri", report.getStorageUri());
                    result.put("outputFileUri", report.getOutputFileUri());
                    result.put("totalRecords", report.getTotalRecords());
                    result.put("successCount", report.getSuccessCount());
                    result.put("failCount", report.getFailCount());
                    result.put("approvedCount", report.getApprovedCount());
                    result.put("declinedCount", report.getDeclinedCount());
                    result.put("totalAmount", report.getTotalAmount());
                    result.put("digitalSignature", report.getDigitalSignature());
                    result.put("status", report.getStatus());
                    result.put("sendStatus", report.getSendStatus());
                    result.put("sentAt", report.getSentAt());
                    result.put("createdAt", report.getCreatedAt());
                    result.put("createdBy", report.getCreatedBy());

                    // Resolve authBatchId: use entity value, or look up from transactions
                    Long authBatchId = report.getAuthBatchId();
                    if (authBatchId == null && report.getBatchFileId() != null) {
                        List<RtaTransaction> txns = transactionRepository.findByBatchFileId(report.getBatchFileId());
                        authBatchId = txns.stream()
                                .map(RtaTransaction::getAuthBatchId)
                                .filter(id -> id != null)
                                .findFirst()
                                .orElse(null);
                    }
                    result.put("authBatchId", authBatchId);

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/reports/{reportId}/download — Download the HTML/PDF report
     * content.
     */
    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable Long reportId) {
        try {
            RtaReport report = reportRepository.findById(reportId)
                    .orElseThrow(() -> new IllegalArgumentException("Report not found"));

            byte[] content = reportGenerationService.downloadReport(reportId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_HTML);
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(report.getReportName() + "_report.html").build());

            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to download report {}: {}", reportId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/reports/{reportId}/output — Download the output file (CSV/XLSX).
     */
    @GetMapping("/{reportId}/output")
    public ResponseEntity<byte[]> downloadOutputFile(@PathVariable Long reportId) {
        try {
            RtaReport report = reportRepository.findById(reportId)
                    .orElseThrow(() -> new IllegalArgumentException("Report not found"));

            byte[] content = reportGenerationService.downloadOutputFile(reportId);

            String extension = "CSV".equalsIgnoreCase(report.getFileFormat()) ? ".csv" : ".xlsx";
            MediaType mediaType = "CSV".equalsIgnoreCase(report.getFileFormat())
                    ? MediaType.parseMediaType("text/csv")
                    : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(report.getReportName() + extension).build());

            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to download output file {}: {}", reportId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/reports/generate — Trigger report generation for all PROCESSED
     * batch files.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateReports() {
        try {
            List<RtaReport> reports = reportGenerationService.generateReportsForProcessedBatches();
            return ResponseEntity.ok(Map.of(
                    "message", "Report generation completed",
                    "reportsGenerated", reports.size(),
                    "reports", reports
            ));
        } catch (Exception e) {
            log.error("Report generation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Report generation failed",
                    "detail", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/reports/generate/{batchFileId} — Trigger report for a specific
     * batch file.
     */
    @PostMapping("/generate/{batchFileId}")
    public ResponseEntity<?> generateReportForBatchFile(
            @PathVariable Long batchFileId,
            @RequestParam(value = "triggeredBy", defaultValue = "SYSTEM") String triggeredBy) {
        try {
            RtaReport report = reportGenerationService.generateReportForBatchFileId(batchFileId, triggeredBy);
            if (report == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No transactions found for batch file " + batchFileId));
            }
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Report generation failed for batchFileId={}: {}", batchFileId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Report generation failed",
                    "detail", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/reports/{reportId}/resend — Retry sending a batch result to the
     * merchant.
     */
    @PostMapping("/{reportId}/resend")
    public ResponseEntity<?> resendReport(@PathVariable Long reportId) {
        try {
            reportGenerationService.retrySend(reportId);
            return ResponseEntity.ok(Map.of(
                    "message", "Batch result resent successfully",
                    "reportId", reportId
            ));
        } catch (Exception e) {
            log.error("Resend failed for reportId={}: {}", reportId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Resend failed",
                    "detail", e.getMessage()
            ));
        }
    }
}
