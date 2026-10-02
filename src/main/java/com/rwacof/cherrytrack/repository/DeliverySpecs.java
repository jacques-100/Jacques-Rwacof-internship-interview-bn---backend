package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Delivery;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Farmer;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class DeliverySpecs {

    private DeliverySpecs() {}

    /** {@code stationId} is mandatory: deliveries are only ever listed within one station. */
    public record DeliveryFilter(Long stationId, LocalDate date, LocalDate from, LocalDate to, DeliveryStatus status,
                                 String grade, Long farmerId, BigDecimal minWeight, BigDecimal maxWeight, String q) {}

    public static Specification<Delivery> of(DeliveryFilter f) {
        return (root, query, cb) -> {
            // Fetch associations for the page query only (the count query must not fetch).
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("station", JoinType.INNER);
                root.fetch("farmer", JoinType.INNER);
                root.fetch("createdBy", JoinType.INNER);
                root.fetch("gradedBy", JoinType.LEFT);
                root.fetch("paidBy", JoinType.LEFT);
                root.fetch("rejectedBy", JoinType.LEFT);
            }
            Join<Delivery, Farmer> farmer = root.join("farmer", JoinType.INNER);
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("station").get("id"), f.stationId()));
            if (f.date() != null) {
                p.add(cb.equal(root.get("deliveryDate"), f.date()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("deliveryDate"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("deliveryDate"), f.to()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.grade() != null && !f.grade().isBlank()) {
                p.add(cb.equal(root.get("grade"), f.grade()));
            }
            if (f.farmerId() != null) {
                p.add(cb.equal(farmer.get("id"), f.farmerId()));
            }
            if (f.minWeight() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("weightKg"), f.minWeight()));
            }
            if (f.maxWeight() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("weightKg"), f.maxWeight()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + escape(f.q().trim().toLowerCase()) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), like, '\\'),
                        cb.like(cb.lower(farmer.get("fullName")), like, '\\'),
                        cb.like(cb.lower(farmer.get("phone")), like, '\\'),
                        cb.like(cb.lower(farmer.get("cooperativeNumber")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
