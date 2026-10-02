package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.JobRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRoleRepository extends JpaRepository<JobRole, Long> {

    boolean existsByNameIgnoreCase(String name);

    Optional<JobRole> findByNameIgnoreCase(String name);

    List<JobRole> findAllByOrderBySystemRoleDescNameAsc();

    @Query("select count(u) from User u where u.jobRole.id = :roleId")
    long countUsers(@Param("roleId") Long roleId);
}
