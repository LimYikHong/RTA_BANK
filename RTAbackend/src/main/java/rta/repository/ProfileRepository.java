package rta.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import rta.model.UserProfile;

@Repository
public interface ProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(String userId);

    Optional<UserProfile> findByUserIdAndDeletedAtIsNull(String userId);

    Optional<UserProfile> findByUsername(String username);

    Optional<UserProfile> findByUsernameAndDeletedAtIsNull(String username);

    @Query("SELECT u FROM UserProfile u WHERE "
            + "LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.emailAddress) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.userId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.company) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<UserProfile> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT u FROM UserProfile u WHERE u.deletedAt IS NULL")
    List<UserProfile> findAllActive();

    @Query("SELECT u FROM UserProfile u WHERE u.deletedAt IS NULL AND ("
            + "LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.emailAddress) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.userId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(u.company) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<UserProfile> searchByKeywordActive(@Param("keyword") String keyword);

    @Query("SELECT u.userId FROM UserProfile u WHERE u.userId LIKE 'A%' ORDER BY u.userId DESC")
    List<String> findAllAdminIdsWithPrefix();
}
