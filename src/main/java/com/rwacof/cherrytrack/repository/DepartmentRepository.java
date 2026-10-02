package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Department;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);

    @EntityGraph(attributePaths = "head")
    List<Department> findAllByOrderByNameAsc();
}
