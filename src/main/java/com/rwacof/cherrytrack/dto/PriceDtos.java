package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.GradePrice;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PriceDtos {

    private PriceDtos() {}

    public record PriceRequest(
            @NotBlank String grade,
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Price must be greater than 0")
            @DecimalMax(value = "1000000.00") @Digits(integer = 7, fraction = 2) BigDecimal pricePerKg,
            Instant effectiveFrom) {}

    public record PriceDto(Long id, String grade, BigDecimal pricePerKg, Instant effectiveFrom,
                           String createdBy, Instant createdAt, boolean current) {
        public static PriceDto of(GradePrice p, boolean current) {
            return new PriceDto(p.getId(), p.getGrade(), p.getPricePerKg(), p.getEffectiveFrom(),
                    p.getCreatedBy() == null ? null : p.getCreatedBy().getUsername(), p.getCreatedAt(), current);
        }
    }

    /** {@code current} holds the price in effect now per grade; {@code history} every record, newest first. */
    public record PricesResponse(List<PriceDto> current, List<PriceDto> history) {}
}
