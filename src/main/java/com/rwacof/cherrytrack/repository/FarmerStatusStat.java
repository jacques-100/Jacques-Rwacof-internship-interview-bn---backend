package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.DeliveryStatus;

import java.math.BigDecimal;

public record FarmerStatusStat(Long farmerId, DeliveryStatus status, long count, BigDecimal weightKg, BigDecimal amount) {

    public FarmerStatusStat {
        weightKg = weightKg == null ? BigDecimal.ZERO : weightKg;
        amount = amount == null ? BigDecimal.ZERO : amount;
    }
}
