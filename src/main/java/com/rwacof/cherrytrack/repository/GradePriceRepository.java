package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.GradePrice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GradePriceRepository extends JpaRepository<GradePrice, Long> {

    /** The price in effect at the given instant: latest effective_from not after it. */
    Optional<GradePrice> findFirstByGradeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String grade, Instant at);

    @EntityGraph(attributePaths = "createdBy")
    List<GradePrice> findAllByOrderByEffectiveFromDescIdDesc();
}
