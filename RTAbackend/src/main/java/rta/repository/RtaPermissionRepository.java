package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaPermission;

import java.util.Optional;

public interface RtaPermissionRepository extends JpaRepository<RtaPermission, Long> {

    Optional<RtaPermission> findByPermissionName(String permissionName);
}
