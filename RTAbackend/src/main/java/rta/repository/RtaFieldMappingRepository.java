package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaFieldMapping;

import java.util.List;

public interface RtaFieldMappingRepository extends JpaRepository<RtaFieldMapping, Long> {

    List<RtaFieldMapping> findByProfileId(Long profileId);

    List<RtaFieldMapping> findByProfileIdOrderBySourceColumnIdxAsc(Long profileId);

    void deleteByProfileId(Long profileId);
}
