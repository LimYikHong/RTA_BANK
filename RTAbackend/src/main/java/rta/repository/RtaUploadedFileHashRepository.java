package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.RtaUploadedFileHash;

import java.util.Optional;
import java.util.List;

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
}
