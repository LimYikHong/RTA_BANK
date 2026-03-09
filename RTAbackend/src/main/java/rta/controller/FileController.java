package rta.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import rta.service.MinioStorageService;

import java.io.InputStream;

/**
 * FileController - Serves files stored in MinIO. Provides endpoints to download
 * files from MinIO storage.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final MinioStorageService minioStorageService;

    public FileController(MinioStorageService minioStorageService) {
        this.minioStorageService = minioStorageService;
    }

    /**
     * GET /api/files/download/{*path} - Downloads a file from MinIO. Example:
     * GET /api/files/download/uploads/profile-photos/abc.jpg
     */
    @GetMapping("/download/**")
    public ResponseEntity<InputStreamResource> downloadFile(
            @RequestParam(value = "objectName", required = false) String objectName,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            // Extract the path after /api/files/download/
            String fullPath = request.getRequestURI();
            String basePath = "/api/files/download/";
            String path = objectName;

            if (path == null || path.isEmpty()) {
                if (fullPath.contains(basePath)) {
                    path = fullPath.substring(fullPath.indexOf(basePath) + basePath.length());
                }
            }

            if (path == null || path.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            // Check if file exists
            if (!minioStorageService.fileExists(path)) {
                return ResponseEntity.notFound().build();
            }

            InputStream inputStream = minioStorageService.downloadFile(path);

            // Determine content type based on file extension
            String contentType = determineContentType(path);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + getFileName(path) + "\"")
                    .body(new InputStreamResource(inputStream));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/files/presigned - Gets a presigned URL for temporary access.
     */
    @GetMapping("/presigned")
    public ResponseEntity<?> getPresignedUrl(
            @RequestParam("objectName") String objectName,
            @RequestParam(value = "expiry", defaultValue = "3600") int expirySeconds) {
        try {
            if (!minioStorageService.fileExists(objectName)) {
                return ResponseEntity.notFound().build();
            }

            String presignedUrl = minioStorageService.getPresignedUrl(objectName, expirySeconds);
            return ResponseEntity.ok(java.util.Map.of("url", presignedUrl, "expiresIn", expirySeconds));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to generate presigned URL"));
        }
    }

    private String determineContentType(String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerPath.endsWith(".png")) {
            return "image/png";
        } else if (lowerPath.endsWith(".gif")) {
            return "image/gif";
        } else if (lowerPath.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lowerPath.endsWith(".csv")) {
            return "text/csv";
        } else if (lowerPath.endsWith(".txt")) {
            return "text/plain";
        } else if (lowerPath.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else if (lowerPath.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        } else {
            return "application/octet-stream";
        }
    }

    private String getFileName(String path) {
        if (path == null) {
            return "file";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }
}
