package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.DailyCapacity;
import com.rwacof.cherrytrack.model.DailyCapacityId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyCapacityRepository extends JpaRepository<DailyCapacity, DailyCapacityId> {

    /**
     * Creates the day's row if missing, copying the station's current default limit. ON DUPLICATE KEY
     * UPDATE takes an exclusive lock on the row immediately (a plain INSERT IGNORE would take a shared
     * lock first and two concurrent transactions could then deadlock while upgrading it).
     */
    @Modifying
    @Query(value = """
            INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at)
            VALUES (:stationId, :date, 0, :limit, 0, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE delivery_date = delivery_date
            """, nativeQuery = true)
    void ensureRow(@Param("stationId") Long stationId, @Param("date") LocalDate date, @Param("limit") BigDecimal limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DailyCapacity c where c.stationId = :stationId and c.deliveryDate = :date")
    Optional<DailyCapacity> findForUpdate(@Param("stationId") Long stationId, @Param("date") LocalDate date);

    Optional<DailyCapacity> findByStationIdAndDeliveryDate(Long stationId, LocalDate date);

    List<DailyCapacity> findByStationIdAndDeliveryDateBetweenOrderByDeliveryDateAsc(Long stationId, LocalDate from, LocalDate to);
}
