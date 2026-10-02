package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.GradeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GradeDefinitionRepository extends JpaRepository<GradeDefinition, Long> {

    Optional<GradeDefinition> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    List<GradeDefinition> findAllByOrderBySortOrderAscCodeAsc();

    List<GradeDefinition> findByActiveTrueOrderBySortOrderAscCodeAsc();
}
