package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Delivery;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

    @Query("select new com.rwacof.cherrytrack.repository.DeliveryKey(d.station.id, d.deliveryDate) from Delivery d where d.id = :id")
    Optional<DeliveryKey> findKeyById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"station", "farmer", "createdBy", "gradedBy", "paidBy", "rejectedBy"})
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"station", "farmer", "createdBy"})
    List<Delivery> findTop10ByStationIdOrderByCreatedAtDescIdDesc(Long stationId);

    /** Authoritative accepted weight (non-rejected). Used to reconcile the daily_capacity counter. */
    @Query("select coalesce(sum(d.weightKg), 0) from Delivery d where d.station.id = :stationId and d.deliveryDate = :date and d.status <> :rejected")
    BigDecimal sumAcceptedWeight(@Param("stationId") Long stationId, @Param("date") LocalDate date, @Param("rejected") DeliveryStatus rejected);

    @Query("""
            select new com.rwacof.cherrytrack.repository.StatusStat(d.status, count(d), sum(d.weightKg), sum(d.amountOwed))
            from Delivery d where d.station.id = :stationId and d.deliveryDate = :date group by d.status
            """)
    List<StatusStat> statsByDate(@Param("stationId") Long stationId, @Param("date") LocalDate date);

    @Query("""
            select new com.rwacof.cherrytrack.repository.FarmerStatusStat(d.farmer.id, d.status, count(d), sum(d.weightKg), sum(d.amountOwed))
            from Delivery d where d.station.id = :stationId and d.farmer.id in :ids group by d.farmer.id, d.status
            """)
    List<FarmerStatusStat> statsByFarmerIds(@Param("stationId") Long stationId, @Param("ids") Collection<Long> ids);

    long countByStationIdAndDeliveryDate(Long stationId, LocalDate date);
}
