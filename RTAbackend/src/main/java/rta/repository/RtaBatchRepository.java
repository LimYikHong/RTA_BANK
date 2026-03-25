package rta.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import rta.entity.RtaBatch;

public interface RtaBatchRepository extends JpaRepository<RtaBatch, Long> {

    List<RtaBatch> findByStatus(String status);

    List<RtaBatch> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
