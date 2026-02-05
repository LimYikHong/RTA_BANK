package rta.repository;

import rta.entity.RtaBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RtaBatchRepository extends JpaRepository<RtaBatch, Long> {
    List<RtaBatch> findByStatus(String status);
}
