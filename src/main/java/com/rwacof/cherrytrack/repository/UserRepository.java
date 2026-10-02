package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /** The user with their job role and its permissions loaded, as needed to authorise a request. */
    @EntityGraph(attributePaths = {"jobRole", "jobRole.permissions"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithRoleById(@Param("id") Long id);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @Query("select u from User u join u.stations s where s.id = :stationId order by u.fullName")
    List<User> findAssignedToStation(@Param("stationId") Long stationId);
}
