package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.DeliveryStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardDto(
        CapacityDtos.CapacityDto capacity,
        List<TrendPoint> intakeTrend,
        List<StatusSlice> statusDistribution,
        List<DeliveryDtos.DeliveryDto> recentDeliveries) {

    public record TrendPoint(LocalDate date, BigDecimal acceptedKg) {}

    public record StatusSlice(DeliveryStatus status, long count) {}
}
