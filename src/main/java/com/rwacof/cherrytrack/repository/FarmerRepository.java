package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Farmer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FarmerRepository extends JpaRepository<Farmer, Long>, JpaSpecificationExecutor<Farmer> {

    Optional<Farmer> findByCooperativeNumber(String cooperativeNumber);

    boolean existsByCooperativeNumber(String cooperativeNumber);

    long countByActive(boolean active);
}
