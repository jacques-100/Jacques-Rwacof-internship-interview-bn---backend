package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.DeliveryStatus;

import java.math.BigDecimal;

/** Count, total weight and total amount for deliveries in one status (null sums become zero). */
public record StatusStat(DeliveryStatus status, long count, BigDecimal weightKg, BigDecimal amount) {

    public StatusStat {
        weightKg = weightKg == null ? BigDecimal.ZERO : weightKg;
        amount = amount == null ? BigDecimal.ZERO : amount;
    }
}
