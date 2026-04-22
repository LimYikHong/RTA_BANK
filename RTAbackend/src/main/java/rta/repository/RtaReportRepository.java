package rta.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rta.entity.RtaReport;

import java.util.List;

@Repository
public interface RtaReportRepository extends JpaRepository<RtaReport, Long> {

    List<RtaReport> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    List<RtaReport> findByBatchFileIdOrderByCreatedAtDesc(Long batchFileId);

    List<RtaReport> findByBatchIdOrderByCreatedAtDesc(Long batchId);

    Page<RtaReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<RtaReport> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    @Query("SELECT r FROM RtaReport r WHERE "
            + "LOWER(r.reportName) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(r.merchantId) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "ORDER BY r.createdAt DESC")
    Page<RtaReport> searchReports(@Param("search") String search, Pageable pageable);

    @Query("SELECT r FROM RtaReport r WHERE r.merchantId = :merchantId AND "
            + "(LOWER(r.reportName) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(r.status) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "ORDER BY r.createdAt DESC")
    Page<RtaReport> searchReportsByMerchant(@Param("merchantId") String merchantId,
            @Param("search") String search, Pageable pageable);
}
