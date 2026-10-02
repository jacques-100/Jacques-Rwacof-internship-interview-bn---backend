package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StationRepository extends JpaRepository<Station, Long> {

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);

    Optional<Station> findByCodeIgnoreCase(String code);

    List<Station> findAllByOrderByNameAsc();

    List<Station> findByActiveTrueOrderByNameAsc();

    @Query("select s.id, count(u) from User u join u.stations s group by s.id")
    List<Object[]> countAssignedUsers();

    @Query("select s from User u join u.stations s where u.id = :userId order by s.name")
    List<Station> findAssignedTo(@Param("userId") Long userId);
}
