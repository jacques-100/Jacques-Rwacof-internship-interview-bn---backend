package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A coffee washing station. Deliveries, daily capacity and managers all belong to a station. */
@Entity
@Table(name = "stations")
@Getter
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Setter
    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Setter
    @Column(length = 160)
    private String location;

    @Setter
    @Column(nullable = false, length = 40)
    private String timezone;

    /** Default limit copied onto each new day. Individual days can be adjusted separately. */
    @Setter
    @Column(name = "daily_capacity_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyCapacityKg;

    @Setter
    @Column(name = "max_delivery_kg", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxDeliveryKg;

    @Setter
    @Column(name = "low_threshold_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal lowThresholdKg;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Station() {}

    public Station(String code, String name, String location, String timezone, BigDecimal dailyCapacityKg,
                   BigDecimal maxDeliveryKg, BigDecimal lowThresholdKg, Instant now) {
        this.code = code;
        this.name = name;
        this.location = location;
        this.timezone = timezone;
        this.dailyCapacityKg = dailyCapacityKg;
        this.maxDeliveryKg = maxDeliveryKg;
        this.lowThresholdKg = lowThresholdKg;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
