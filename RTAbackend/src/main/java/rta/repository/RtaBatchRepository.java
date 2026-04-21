package rta.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import rta.entity.RtaBatch;

public interface RtaBatchRepository extends JpaRepository<RtaBatch, Long> {

    List<RtaBatch> findByStatus(String status);

    Optional<RtaBatch> findByFileName(String fileName);

    @Query("SELECT b FROM RtaBatch b WHERE b.deletedAt IS NULL")
    List<RtaBatch> findAllActive();
}
