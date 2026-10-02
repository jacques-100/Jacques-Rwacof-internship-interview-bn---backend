package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.DeliveryStatus;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class CapacityDtos {

    private CapacityDtos() {}

    public enum AlertLevel { NONE, LOW, FULL }

    /** Everything the Daily Intake page and the dashboard header need for one day. */
    public record CapacityDto(
            Long stationId,
            LocalDate date,
            BigDecimal dailyLimitKg,
            BigDecimal acceptedKg,
            BigDecimal remainingKg,
            BigDecimal utilizationPercent,
            AlertLevel alert,
            long totalDeliveries,
            Map<DeliveryStatus, Long> statusCounts,
            BigDecimal rejectedKg,
            BigDecimal averageAcceptedWeightKg,
            BigDecimal totalAmountOwed,
            BigDecimal totalAmountPaid) {}

    /** Preview of what accepting a delivery would do; advisory only, the backend re-validates on submit. */
    public record CapacityPreviewDto(Long stationId, LocalDate date, BigDecimal acceptedKg, BigDecimal dailyLimitKg,
                                     BigDecimal remainingKg) {}

    /** Raises or lowers the limit for one date. It cannot go below what has already been accepted. */
    public record AdjustCapacityRequest(
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Limit must be greater than 0")
            @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal limitKg,
            @NotBlank @Size(max = 300) String reason) {}
}
