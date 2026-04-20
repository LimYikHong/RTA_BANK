package rta.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import rta.entity.RtaBatchEncryptionKey;

@Repository
public interface RtaBatchEncryptionKeyRepository extends JpaRepository<RtaBatchEncryptionKey, Long> {

    Optional<RtaBatchEncryptionKey> findByBatchId(Long batchId);
}
