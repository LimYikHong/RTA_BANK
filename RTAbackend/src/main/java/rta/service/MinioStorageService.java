package rta.service;

import io.minio.*;
import io.minio.errors.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * MinIO Storage Service - Handles file upload, download, and delete operations
 * using MinIO object storage instead of local filesystem.
 */
@Service
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Initialize the bucket on startup if it doesn't exist.
     */
    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MinIO bucket: " + bucketName, e);
        }
    }

    /**
     * Upload a file to MinIO.
     *
     * @param objectName The object key/path in the bucket (e.g.,
     * "incoming-uploads/file.csv")
     * @param file The MultipartFile to upload
     * @return The full MinIO URI (minio://bucket/objectName)
     */
    public String uploadFile(String objectName, MultipartFile file) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());
            return getStorageUri(objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + objectName, e);
        }
    }

    /**
     * Upload file content (byte array) to MinIO.
     *
     * @param objectName The object key/path in the bucket
     * @param content The file content as byte array
     * @param contentType The MIME type of the file
     * @return The full MinIO URI
     */
    public String uploadFile(String objectName, byte[] content, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build());
            return getStorageUri(objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + objectName, e);
        }
    }

    /**
     * Upload file from InputStream to MinIO.
     *
     * @param objectName The object key/path in the bucket
     * @param inputStream The input stream of the file
     * @param size The size of the file (-1 if unknown)
     * @param contentType The MIME type of the file
     * @return The full MinIO URI
     */
    public String uploadFile(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build());
            return getStorageUri(objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + objectName, e);
        }
    }

    /**
     * Download a file from MinIO as InputStream.
     *
     * @param objectName The object key/path in the bucket
     * @return InputStream of the file content
     */
    public InputStream downloadFile(String objectName) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + objectName, e);
        }
    }

    /**
     * Download file content as byte array.
     *
     * @param objectName The object key/path in the bucket
     * @return The file content as byte array
     */
    public byte[] downloadFileAsBytes(String objectName) {
        try (InputStream is = downloadFile(objectName)) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + objectName, e);
        }
    }

    /**
     * Delete a file from MinIO.
     *
     * @param objectName The object key/path in the bucket
     */
    public void deleteFile(String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from MinIO: " + objectName, e);
        }
    }

    /**
     * Check if a file exists in MinIO.
     *
     * @param objectName The object key/path in the bucket
     * @return true if the file exists, false otherwise
     */
    public boolean fileExists(String objectName) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
            return true;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return false;
            }
            throw new RuntimeException("Failed to check file existence in MinIO: " + objectName, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to check file existence in MinIO: " + objectName, e);
        }
    }

    /**
     * Get the public URL for a file (if bucket is public) or a presigned URL.
     *
     * @param objectName The object key/path in the bucket
     * @return The public URL to access the file
     */
    public String getPublicUrl(String objectName) {
        return endpoint + "/" + bucketName + "/" + objectName;
    }

    /**
     * Get a presigned URL for temporary access to a file.
     *
     * @param objectName The object key/path in the bucket
     * @param expirySeconds The number of seconds until the URL expires
     * @return The presigned URL
     */
    public String getPresignedUrl(String objectName, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .method(io.minio.http.Method.GET)
                            .expiry(expirySeconds)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL: " + objectName, e);
        }
    }

    /**
     * Get the storage URI format used for database storage. Format:
     * minio://bucket/objectName
     *
     * @param objectName The object key/path in the bucket
     * @return The storage URI
     */
    public String getStorageUri(String objectName) {
        return "minio://" + bucketName + "/" + objectName;
    }

    /**
     * Extract the object name from a storage URI.
     *
     * @param storageUri The storage URI (minio://bucket/objectName or just
     * objectName)
     * @return The object name
     */
    public String extractObjectName(String storageUri) {
        if (storageUri == null) {
            return null;
        }
        // Handle minio:// prefix
        if (storageUri.startsWith("minio://")) {
            String withoutPrefix = storageUri.substring(8); // Remove "minio://"
            int slashIndex = withoutPrefix.indexOf('/');
            if (slashIndex >= 0) {
                return withoutPrefix.substring(slashIndex + 1);
            }
        }
        // Handle local path format (for backward compatibility)
        if (storageUri.startsWith("incoming-uploads/") || storageUri.startsWith("uploads/")) {
            return storageUri;
        }
        return storageUri;
    }

    /**
     * Get the bucket name.
     *
     * @return The bucket name
     */
    public String getBucketName() {
        return bucketName;
    }
}
