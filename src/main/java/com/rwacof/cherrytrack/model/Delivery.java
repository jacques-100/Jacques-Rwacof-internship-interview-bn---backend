package com.rwacof.cherrytrack.model;

import com.rwacof.cherrytrack.exception.InvalidStateTransitionException;
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
import jakarta.persistence.Version;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Delivery aggregate. All state changes go through the domain methods below, which enforce the
 * state machine; there is no public status setter. Deliveries are never deleted.
 */
@Entity
@Table(name = "deliveries")
@Getter
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, unique = true, length = 30)
    private String reference;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false, updatable = false)
    private Station station;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false, updatable = false)
    private Farmer farmer;

    @Column(name = "delivery_date", nullable = false, updatable = false)
    private LocalDate deliveryDate;

    @Column(name = "weight_kg", nullable = false, precision = 8, scale = 2)
    private BigDecimal weightKg;

    /** Code of a configured grade (see GradeDefinition). */
    @Column(length = 10)
    private String grade;

    @Column(name = "moisture_percent", precision = 4, scale = 1)
    private BigDecimal moisturePercent;

    @Column(name = "grade_notes", length = 500)
    private String gradeNotes;

    @Column(name = "price_per_kg", precision = 12, scale = 2)
    private BigDecimal pricePerKg;

    @Column(name = "amount_owed", precision = 14, scale = 2)
    private BigDecimal amountOwed;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private DeliveryStatus status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "graded_by")
    private User gradedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private User paidBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by")
    private User rejectedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "graded_at")
    private Instant gradedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Version
    private long version;

    protected Delivery() {}

    public static Delivery receive(String reference, Station station, Farmer farmer, LocalDate deliveryDate,
                                   BigDecimal weightKg, User createdBy, Instant now) {
        requirePositive(weightKg);
        Delivery d = new Delivery();
        d.reference = reference;
        d.station = station;
        d.farmer = farmer;
        d.deliveryDate = deliveryDate;
        d.weightKg = weightKg;
        d.status = DeliveryStatus.RECEIVED;
        d.createdBy = createdBy;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    /** Weight may only change while RECEIVED. */
    public void correctWeight(BigDecimal newWeightKg, Instant now) {
        if (status != DeliveryStatus.RECEIVED) {
            throw new InvalidStateTransitionException(
                    "Weight can only be corrected while the delivery is RECEIVED (current status: " + status + ").");
        }
        requirePositive(newWeightKg);
        this.weightKg = newWeightKg;
        this.updatedAt = now;
    }

    /**
     * Grades the delivery. The amount is computed here from the weight and the price snapshot;
     * callers can never supply it.
     */
    public void grade(String newGrade, BigDecimal priceSnapshot, BigDecimal moisture, String notes, User by, Instant now) {
        requireTransition(DeliveryStatus.GRADED);
        this.grade = newGrade;
        this.moisturePercent = moisture;
        this.gradeNotes = notes;
        this.pricePerKg = priceSnapshot;
        this.amountOwed = weightKg.multiply(priceSnapshot).setScale(2, RoundingMode.HALF_UP);
        this.gradedBy = by;
        this.gradedAt = now;
        this.status = DeliveryStatus.GRADED;
        this.updatedAt = now;
    }

    public void markPaid(User by, Instant now) {
        requireTransition(DeliveryStatus.PAID);
        this.paidBy = by;
        this.paidAt = now;
        this.status = DeliveryStatus.PAID;
        this.updatedAt = now;
    }

    public void reject(String reason, User by, Instant now) {
        requireTransition(DeliveryStatus.REJECTED);
        this.rejectReason = reason;
        this.rejectedBy = by;
        this.rejectedAt = now;
        this.status = DeliveryStatus.REJECTED;
        this.updatedAt = now;
    }

    private void requireTransition(DeliveryStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException(
                    "A " + status + " delivery cannot move to " + next + "."
                            + (status.isTerminal() ? " Paid and rejected deliveries are immutable." : ""));
        }
    }

    private static void requirePositive(BigDecimal weightKg) {
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("Weight must be greater than zero.");
        }
    }
}
