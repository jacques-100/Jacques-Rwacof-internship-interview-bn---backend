package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Per-station, per-day capacity ledger. The row is locked (SELECT ... FOR UPDATE) by every
 * operation that can change accepted weight, which serialises them and makes the limit race-free.
 * The limit is stored on the row (copied from the station when the day starts) so one day can be
 * raised or lowered without touching any other day.
 */
@Entity
@Table(name = "daily_capacity")
@IdClass(DailyCapacityId.class)
@Getter
public class DailyCapacity {

    @Id
    @Column(name = "station_id")
    private Long stationId;

    @Id
    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "accepted_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal acceptedKg = BigDecimal.ZERO;

    @Column(name = "limit_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal limitKg;

    @Column(name = "delivery_seq", nullable = false)
    private int deliverySeq;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyCapacity() {}

    /** Adds (or, if negative, removes) weight and returns the new accepted total. */
    public BigDecimal addWeight(BigDecimal delta, Instant now) {
        this.acceptedKg = this.acceptedKg.add(delta);
        this.updatedAt = now;
        return this.acceptedKg;
    }

    public int nextSequence(Instant now) {
        this.deliverySeq++;
        this.updatedAt = now;
        return this.deliverySeq;
    }

    public void changeLimit(BigDecimal newLimit, Instant now) {
        this.limitKg = newLimit;
        this.updatedAt = now;
    }
}
