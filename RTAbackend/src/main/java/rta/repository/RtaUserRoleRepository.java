package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaUserRole;

import java.util.List;

public interface RtaUserRoleRepository extends JpaRepository<RtaUserRole, Long> {

    List<RtaUserRole> findByUser_Id(Long userId);
}
