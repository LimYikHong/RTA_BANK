package rta.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import rta.entity.SystemRsaKeyRequest;

public interface SystemRsaKeyRequestRepository extends JpaRepository<SystemRsaKeyRequest, Long> {

    Optional<SystemRsaKeyRequest> findTopByStatusOrderByRequestedAtDesc(String status);
}
