package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rta.entity.RtaTransaction;

import java.util.List;

@Repository
public interface RtaTransactionRepository extends JpaRepository<RtaTransaction, Long> {

    List<RtaTransaction> findByBatchBatchId(Long batchId);
}
