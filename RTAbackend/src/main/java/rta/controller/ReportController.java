package rta.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import rta.entity.RtaReport;
import rta.repository.RtaReportRepository;
import rta.service.ReportGenerationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        response.put("content", reportPage.getContent());
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
                .<ResponseEntity<?>>map(ResponseEntity::ok)
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
}
