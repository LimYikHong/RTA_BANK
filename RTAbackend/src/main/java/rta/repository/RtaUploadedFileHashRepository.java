package rta.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import rta.entity.RtaUploadedFileHash;

@Repository
public interface RtaUploadedFileHashRepository extends JpaRepository<RtaUploadedFileHash, Long> {

    /**
     * Find an uploaded file hash by merchant ID and file hash. Used to detect
     * duplicate file uploads.
     */
    Optional<RtaUploadedFileHash> findByMerchantIdAndFileHash(String merchantId, String fileHash);

    /**
     * Check if a file with the given hash already exists for a merchant.
     */
    boolean existsByMerchantIdAndFileHash(String merchantId, String fileHash);

    /**
     * Find all uploaded file hashes for a merchant.
     */
    List<RtaUploadedFileHash> findByMerchantId(String merchantId);

    /**
     * Find uploaded file hash by original filename and merchant.
     */
    Optional<RtaUploadedFileHash> findByMerchantIdAndOriginalFilename(String merchantId, String originalFilename);

    /**
     * List all upload hash records ordered by upload time descending. Used by
     * the Upload Batch File page to show full upload history.
     */
    List<RtaUploadedFileHash> findAllByOrderByUploadedAtDesc();

    /**
     * List upload hash records excluding files received from external systems
     * via internal API (where createdBy='merchant').
     */
    List<RtaUploadedFileHash> findByCreatedByNotOrderByUploadedAtDesc(String createdBy);

    /**
     * Find all upload hash records with a given file hash, across all
     * merchants. Used to detect cross-merchant duplicate file uploads.
     */
    List<RtaUploadedFileHash> findByFileHash(String fileHash);
}
