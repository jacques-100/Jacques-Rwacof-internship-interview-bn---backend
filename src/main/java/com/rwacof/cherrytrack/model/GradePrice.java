package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An append-only price record. Changing a price inserts a new row with a later
 * effective_from; existing rows (and the price snapshots on graded deliveries) are never touched.
 */
@Entity
@Table(name = "grade_prices")
@Getter
public class GradePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Code of a configured grade. */
    @Column(nullable = false, length = 10, updatable = false)
    private String grade;

    @Column(name = "price_per_kg", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal pricePerKg;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GradePrice() {}

    public GradePrice(String grade, BigDecimal pricePerKg, Instant effectiveFrom, User createdBy, Instant now) {
        this.grade = grade;
        this.pricePerKg = pricePerKg;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
        this.createdAt = now;
    }
}
