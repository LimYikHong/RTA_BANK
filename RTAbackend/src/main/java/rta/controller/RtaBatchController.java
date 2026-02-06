package rta.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import rta.entity.RtaBatch;
import rta.entity.RtaTransaction;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaTransactionRepository;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/batches")
public class RtaBatchController {

    private final RtaBatchRepository batchRepository;
    private final RtaTransactionRepository transactionRepository;

    public RtaBatchController(RtaBatchRepository batchRepository,
            RtaTransactionRepository transactionRepository) {
        this.batchRepository = batchRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * GET /api/batches - Returns all batches.
     */
    @GetMapping
    public List<RtaBatch> getAllBatches() {
        return batchRepository.findAll();
    }

    /**
     * POST /api/batches/upload - Validates file type + content type. - Saves
     * the uploaded file under /uploads. - Creates a batch record with
     * status=UPLOADED.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadBatch(@RequestParam("file") MultipartFile file,
            @RequestParam("merchantId") String merchantId,
            @RequestParam("originalFileName") String originalFileName) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("No file uploaded");
            }

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                return ResponseEntity.badRequest().body("Invalid file name");
            }

            String lowerName = fileName.toLowerCase();
            if (!(lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")
                    || lowerName.endsWith(".csv") || lowerName.endsWith(".txt"))) {
                return ResponseEntity.badRequest()
                        .body("Invalid file type. Only .xlsx, .xls, .csv, and .txt are allowed.");
            }

            String contentType = file.getContentType();
            if (contentType == null
                    || !(contentType.equals("application/vnd.ms-excel")
                    || contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    || contentType.equals("text/plain")
                    || contentType.equals("text/csv"))) {
                return ResponseEntity.badRequest()
                        .body("Invalid content type: " + contentType);
            }

            String uploadDir = "uploads/";
            Files.createDirectories(Paths.get(uploadDir));
            Path path = Paths.get(uploadDir + fileName);
            Files.copy(file.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);

            RtaBatch batch = new RtaBatch();
            batch.setOriginalFileName(originalFileName);
            batch.setFileName(fileName);
            batch.setMerchantId(merchantId);
            batch.setCreatedAt(LocalDateTime.now());
            batch.setCreatedBy("system");
            batch.setStatus("UPLOADED");
            RtaBatch savedBatch = batchRepository.save(batch);

            return ResponseEntity.ok(savedBatch);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error during upload: " + e.getMessage());
        }
    }

    /**
     * Helper: parse CSV into transactions, set batch to READY/FAILED. -
     * Expected columns: amount, currency
     */
    private void processCsvFile(RtaBatch batch, File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int success = 0;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 2) {
                    continue;
                }

                RtaTransaction tx = new RtaTransaction();
                tx.setBatch(batch);
                tx.setMerchantId(batch.getMerchantId());
                tx.setAmount(Long.parseLong(parts[0].trim()));
                tx.setCurrency(parts[1].trim());
                tx.setStatus("PENDING");
                tx.setCreatedAt(LocalDateTime.now());

                transactionRepository.save(tx);
                success++;
            }
            batch.setStatus("READY");
            batchRepository.save(batch);
        } catch (Exception e) {
            batch.setStatus("FAILED");
            batchRepository.save(batch);
        }
    }

    /**
     * Helper: parse first sheet of XLSX into transactions, set batch to
     * READY/FAILED. - Assumes first row is header; skips it. - Expected
     * columns: [0]=amount (numeric), [1]=currency (string).
     */
    private void processExcelFile(RtaBatch batch, File file) {
        try (InputStream fis = new FileInputStream(file); Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int success = 0;

            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }

                Cell amtCell = row.getCell(0);
                Cell curCell = row.getCell(1);

                if (amtCell == null || curCell == null) {
                    continue;
                }

                RtaTransaction tx = new RtaTransaction();
                tx.setBatch(batch);
                tx.setMerchantId(batch.getMerchantId());
                tx.setAmount((long) amtCell.getNumericCellValue());
                tx.setCurrency(curCell.getStringCellValue().trim());
                tx.setStatus("PENDING");
                tx.setCreatedAt(LocalDateTime.now());

                transactionRepository.save(tx);
                success++;
            }

            batch.setStatus("READY");
            batchRepository.save(batch);

        } catch (Exception e) {
            batch.setStatus("FAILED");
            batchRepository.save(batch);
            e.printStackTrace();
        }
    }

    /**
     * PUT /api/batches/{id} - Updates batch fields (currently
     * merchantId/status).
     */
    @PutMapping("/{id}")
    public ResponseEntity<RtaBatch> updateBatch(@PathVariable Long id, @RequestBody RtaBatch batchDetails) {
        return batchRepository.findById(id).map(batch -> {
            if (batchDetails.getMerchantId() != null) {
                batch.setMerchantId(batchDetails.getMerchantId());
            }
            if (batchDetails.getStatus() != null) {
                batch.setStatus(batchDetails.getStatus());
            }
            RtaBatch updated = batchRepository.save(batch);
            return ResponseEntity.ok(updated);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/batches/{id} - Deletes related transactions, the file on disk
     * (if present), and the batch record itself. - Returns success JSON or an
     * error message if file deletion fails after DB delete.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBatch(@PathVariable Long id) {
        return batchRepository.findById(id).map(batch -> {
            try {
                List<RtaTransaction> transactions = transactionRepository.findByBatchBatchId(id);
                if (!transactions.isEmpty()) {
                    transactionRepository.deleteAll(transactions);
                }

                Path filePath = Paths.get("uploads/" + batch.getFileName());
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }

                batchRepository.delete(batch);

                return ResponseEntity.ok(Map.of("message", "Batch and related records deleted successfully"));
            } catch (IOException e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Batch deleted from DB, but file removal failed"));
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
