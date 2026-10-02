package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.Delivery;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.model.User;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DeliveryDtos {

    private DeliveryDtos() {}

    public enum Action { CORRECT_WEIGHT, GRADE, REJECT, PAY }

    // The client supplies only farmer, date and weight. Grade, price, amount and status are server-owned.
    public record CreateDeliveryRequest(
            @NotNull Long farmerId,
            @NotNull LocalDate deliveryDate,
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Weight must be greater than 0 kg")
            @DecimalMax(value = "999999.99", message = "Weight is unreasonably large")
            @Digits(integer = 6, fraction = 2, message = "Weight allows at most 2 decimal places") BigDecimal weightKg) {}

    public record CorrectWeightRequest(
            @NotNull @DecimalMin(value = "0.00", inclusive = false, message = "Weight must be greater than 0 kg")
            @DecimalMax(value = "999999.99", message = "Weight is unreasonably large")
            @Digits(integer = 6, fraction = 2, message = "Weight allows at most 2 decimal places") BigDecimal newWeightKg,
            @NotBlank @Size(max = 300) String reason) {}

    /** The grading form. Price and amount are never part of it: the server resolves and computes them. */
    public record GradeRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "Choose a grade") String grade,
            @DecimalMin(value = "0.0", message = "Moisture cannot be negative") @DecimalMax(value = "100.0", message = "Moisture cannot exceed 100%")
            @Digits(integer = 3, fraction = 1, message = "Moisture allows one decimal place") BigDecimal moisturePercent,
            @Size(max = 500) String notes) {}

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    public record DeliveryDto(
            Long id, String reference, Long stationId, String stationCode, LocalDate deliveryDate, FarmerDtos.FarmerRef farmer,
            BigDecimal weightKg, String grade, BigDecimal moisturePercent, String gradeNotes, BigDecimal pricePerKg, BigDecimal amountOwed,
            DeliveryStatus status, String rejectReason,
            String createdBy, String gradedBy, String paidBy, String rejectedBy,
            Instant createdAt, Instant gradedAt, Instant paidAt, Instant rejectedAt,
            long version, List<Action> allowedActions) {

        /** Must be called inside a transaction (lazy associations). */
        public static DeliveryDto of(Delivery d, Set<Permission> viewer) {
            var f = d.getFarmer();
            return new DeliveryDto(
                    d.getId(), d.getReference(), d.getStation().getId(), d.getStation().getCode(), d.getDeliveryDate(),
                    new FarmerDtos.FarmerRef(f.getId(), f.getFullName(), f.getPhone(), f.getCooperativeNumber()),
                    d.getWeightKg(), d.getGrade(), d.getMoisturePercent(), d.getGradeNotes(), d.getPricePerKg(), d.getAmountOwed(),
                    d.getStatus(), d.getRejectReason(),
                    name(d.getCreatedBy()), name(d.getGradedBy()), name(d.getPaidBy()), name(d.getRejectedBy()),
                    d.getCreatedAt(), d.getGradedAt(), d.getPaidAt(), d.getRejectedAt(),
                    d.getVersion(), actionsFor(d.getStatus(), viewer));
        }

        private static String name(User u) {
            return u == null ? null : u.getUsername();
        }
    }

    /** Which actions the UI may offer. The backend re-checks every one of them regardless. */
    public static List<Action> actionsFor(DeliveryStatus status, Set<Permission> held) {
        List<Action> actions = new ArrayList<>();
        switch (status) {
            case RECEIVED -> {
                if (held.contains(Permission.DELIVERY_CORRECT_WEIGHT)) actions.add(Action.CORRECT_WEIGHT);
                if (held.contains(Permission.DELIVERY_GRADE)) actions.add(Action.GRADE);
                if (held.contains(Permission.DELIVERY_REJECT)) actions.add(Action.REJECT);
            }
            case GRADED -> {
                if (held.contains(Permission.DELIVERY_PAY)) {
                    actions.add(Action.PAY);
                }
            }
            case PAID, REJECTED -> { }
        }
        return actions;
    }
}
