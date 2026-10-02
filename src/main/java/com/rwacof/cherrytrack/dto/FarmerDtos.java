package com.rwacof.cherrytrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FarmerDtos {

    private FarmerDtos() {}

    /** Create/update payload. {@code active} is only honoured on update. */
    public record FarmerRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^(\\+250|0)7\\d{8}$",
                    message = "Phone must be a Rwandan mobile number, e.g. 0788123456") String phone,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{3,30}$",
                    message = "Cooperative number must be 3-30 letters, digits or dashes") String cooperativeNumber,
            Boolean active) {}

    /** Light reference embedded in delivery responses. */
    public record FarmerRef(Long id, String fullName, String phone, String cooperativeNumber) {}

    public record FarmerDto(Long id, String fullName, String phone, String cooperativeNumber, boolean active,
                            long totalDeliveries, BigDecimal totalWeightKg, BigDecimal totalAmountPaid,
                            Instant createdAt) {}

    public record FarmerDetailDto(FarmerDto farmer, List<DeliveryDtos.DeliveryDto> recentDeliveries) {}

    public record FarmerSummaryDto(long total, long active, long inactive, long deliveriesToday) {}
}
