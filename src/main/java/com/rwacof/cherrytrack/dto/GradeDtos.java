package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.GradeDefinition;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class GradeDtos {

    private GradeDtos() {}

    public record GradeDto(Long id, String code, String name, String description, boolean active, int sortOrder) {
        public static GradeDto of(GradeDefinition g) {
            return new GradeDto(g.getId(), g.getCode(), g.getName(), g.getDescription(), g.isActive(), g.getSortOrder());
        }
    }

    /** {@code code} is used on create only; it cannot change afterwards because deliveries and prices store it. */
    public record GradeRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "Code must be 1-10 letters or digits") String code,
            @NotBlank @Size(max = 60) String name,
            @Size(max = 255) String description,
            @Min(0) @Max(9999) Integer sortOrder,
            Boolean active) {}
}
