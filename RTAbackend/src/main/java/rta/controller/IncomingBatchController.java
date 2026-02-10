package rta.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import rta.entity.RtaBatch;
import rta.entity.RtaIncomingBatchFile;
import rta.entity.MerchantInfo;
import rta.repository.RtaBatchRepository;
import rta.repository.RtaIncomingBatchFileRepository;
import rta.repository.MerchantInfoRepository;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * IncomingBatchController - HTTPS endpoint for merchant-side applications to
 * upload batch files. - Receives multipart file uploads over TLS. - Creates
 * rta_batch + rta_incoming_batch_file records. - Stores the file under
 * /incoming-uploads/.
 */
@RestController
@RequestMapping("/api/incoming")
public class IncomingBatchController {

    private final RtaBatchRepository batchRepository;
    private final RtaIncomingBatchFileRepository incomingFileRepository;
    private final MerchantInfoRepository merchantInfoRepository;

    private static final String UPLOAD_DIR = "incoming-uploads";

    public IncomingBatchController(RtaBatchRepository batchRepository,
            RtaIncomingBatchFileRepository incomingFileRepository,
            MerchantInfoRepository merchantInfoRepository) {
        this.batchRepository = batchRepository;
        this.incomingFileRepository = incomingFileRepository;
        this.merchantInfoRepository = merchantInfoRepository;
    }

    /**
     * POST /api/incoming/upload - Merchant-side app uploads a batch file over
     * HTTPS. - Params: file (multipart), merchantId, createdBy (optional) -
     * Creates batch record + incoming file record, stores file on disk.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> receiveIncomingFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "createdBy", required = false, defaultValue = "merchant") String createdBy) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
            }

            // Validate file extension
            String lowerName = originalFilename.toLowerCase();
            if (!lowerName.endsWith(".csv") && !lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xls") && !lowerName.endsWith(".txt")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unsupported file type. Allowed: csv, xlsx, xls, txt"));
            }

            // Validate merchant exists in merchant_info table
            if (merchantInfoRepository.findByMerchantId(merchantId).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Merchant not found",
                        "detail", "Merchant ID '" + merchantId + "' does not exist in the system. Please register the merchant first."
                ));
            }

            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String storedFileName = System.currentTimeMillis() + "_" + originalFilename;
            Path filePath = uploadPath.resolve(storedFileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // 1. Create RtaBatch record
            RtaBatch batch = new RtaBatch();
            batch.setFileName(storedFileName);
            batch.setOriginalFileName(originalFilename);
            batch.setMerchantId(merchantId);
            batch.setStatus("RECEIVED");
            batch.setCreatedBy(createdBy);
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalCount(0);
            batch.setTotalSuccessCount(0);
            batch.setTotalFailCount(0);
            RtaBatch savedBatch = batchRepository.save(batch);

            // 2. Create RtaIncomingBatchFile record
            RtaIncomingBatchFile incomingFile = new RtaIncomingBatchFile();
            incomingFile.setMerchantId(merchantId);
            incomingFile.setBatchId(savedBatch.getBatchId());
            incomingFile.setOriginalFilename(originalFilename);
            incomingFile.setStorageUri(filePath.toString());
            incomingFile.setSizeBytes(file.getSize());
            incomingFile.setFileStatus("RECEIVED");
            incomingFile.setCreateBy(createdBy);
            incomingFile.setCreatedAt(LocalDateTime.now());
            RtaIncomingBatchFile savedFile = incomingFileRepository.save(incomingFile);

            // Response
            Map<String, Object> response = new HashMap<>();
            response.put("message", "File received successfully");
            response.put("batchId", savedBatch.getBatchId());
            response.put("batchFileId", savedFile.getBatchFileId());
            response.put("fileName", originalFilename);
            response.put("sizeBytes", file.getSize());
            response.put("status", "RECEIVED");

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to store file: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * GET /api/incoming/files - List all incoming batch files (optionally
     * filtered by merchantId).
     */
    @GetMapping("/files")
    public ResponseEntity<List<RtaIncomingBatchFile>> getIncomingFiles(
            @RequestParam(value = "merchantId", required = false) String merchantId) {
        List<RtaIncomingBatchFile> files;
        if (merchantId != null && !merchantId.isBlank()) {
            files = incomingFileRepository.findByMerchantId(merchantId);
        } else {
            files = incomingFileRepository.findAll();
        }
        return ResponseEntity.ok(files);
    }

    /**
     * GET /api/incoming/files/{id} - Get a specific incoming batch file by ID.
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<?> getIncomingFileById(@PathVariable Long id) {
        return incomingFileRepository.findById(id)
                .map(f -> ResponseEntity.ok((Object) f))
                .orElse(ResponseEntity.notFound().build());
    }
}
