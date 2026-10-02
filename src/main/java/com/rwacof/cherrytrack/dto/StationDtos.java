package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.Station;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class StationDtos {

    private StationDtos() {}

    public record StationRef(Long id, String code, String name) {
        public static StationRef of(Station s) {
            return new StationRef(s.getId(), s.getCode(), s.getName());
        }
    }

    public record StationDto(Long id, String code, String name, String location, String timezone,
                             BigDecimal dailyCapacityKg, BigDecimal maxDeliveryKg, BigDecimal lowThresholdKg,
                             boolean active, long assignedUsers, Instant createdAt) {
        public static StationDto of(Station s, long assignedUsers) {
            return new StationDto(s.getId(), s.getCode(), s.getName(), s.getLocation(), s.getTimezone(),
                    s.getDailyCapacityKg(), s.getMaxDeliveryKg(), s.getLowThresholdKg(), s.isActive(), assignedUsers, s.getCreatedAt());
        }
    }

    /** Used for both create and update. {@code code} is fixed once the station exists. */
    public record StationRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "Code must be 2-10 letters or digits") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 160) String location,
            @NotBlank @Size(max = 40) String timezone,
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Daily capacity must be greater than 0")
            @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal dailyCapacityKg,
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Largest delivery must be greater than 0")
            @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2) BigDecimal maxDeliveryKg,
            @NotNull @DecimalMin("0.00") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal lowThresholdKg,
            Boolean active) {}

    public record AssignUsersRequest(@NotNull List<Long> userIds) {}

    public record StationDetailDto(StationDto station, List<UserDtos.UserDto> users) {}
}
