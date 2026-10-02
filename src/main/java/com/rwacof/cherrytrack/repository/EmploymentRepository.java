package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Employment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmploymentRepository extends JpaRepository<Employment, Long>, JpaSpecificationExecutor<Employment> {

    @Query("select count(e) from Employment e where e.department.id = :departmentId and e.startDate <= current_date and (e.endDate is null or e.endDate >= current_date)")
    long countCurrentInDepartment(@Param("departmentId") Long departmentId);
}
