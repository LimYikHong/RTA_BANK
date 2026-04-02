package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaRolePermission;

import java.util.List;

public interface RtaRolePermissionRepository extends JpaRepository<RtaRolePermission, Long> {

    List<RtaRolePermission> findByRole_Id(Long roleId);
}
