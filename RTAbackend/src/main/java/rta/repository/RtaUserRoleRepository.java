package rta.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import rta.entity.RtaUserRole;

public interface RtaUserRoleRepository extends JpaRepository<RtaUserRole, Long> {

    List<RtaUserRole> findByUser_Id(Long userId);

    void deleteByUser_Id(Long userId);
}
