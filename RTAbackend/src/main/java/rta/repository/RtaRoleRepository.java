package rta.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rta.entity.RtaRole;
import java.util.Optional;

public interface RtaRoleRepository extends JpaRepository<RtaRole, Long> {

    Optional<RtaRole> findByRoleName(String roleName);
}
