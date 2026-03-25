package rta.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.RtaAuthorizationBatch;

import java.util.Optional;

@Repository
public interface RtaAuthorizationBatchRepository extends JpaRepository<RtaAuthorizationBatch, Long> {

    Optional<RtaAuthorizationBatch> findByBatchReference(String batchReference);

    Page<RtaAuthorizationBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
