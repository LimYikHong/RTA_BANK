package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.RtaBatchFile;

import java.util.Optional;
import java.util.List;

@Repository
public interface RtaBatchFileRepository extends JpaRepository<RtaBatchFile, Long> {

    /**
     * Find a batch file by merchant ID and file hash. Used to detect duplicate
     * file uploads.
     */
    Optional<RtaBatchFile> findByMerchantIdAndFileHash(String merchantId, String fileHash);

    /**
     * Check if a file with the given hash already exists for a merchant.
     */
    boolean existsByMerchantIdAndFileHash(String merchantId, String fileHash);

    /**
     * Find all batch files for a merchant.
     */
    List<RtaBatchFile> findByMerchantId(String merchantId);

    /**
     * Find batch file by original filename and merchant.
     */
    Optional<RtaBatchFile> findByMerchantIdAndOriginalFilename(String merchantId, String originalFilename);
}
