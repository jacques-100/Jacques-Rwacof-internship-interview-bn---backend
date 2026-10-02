package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.Department;
import com.rwacof.cherrytrack.model.Employment;
import com.rwacof.cherrytrack.model.EmploymentType;
import com.rwacof.cherrytrack.model.JobRole;
import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Job roles, departments and employment records: the staff directory. */
public final class DirectoryDtos {

    private DirectoryDtos() {}

    // ------------------------------------------------------------------ roles

    public record JobRoleRef(Long id, String name) {
        public static JobRoleRef of(JobRole r) {
            return new JobRoleRef(r.getId(), r.getName());
        }
    }

    public record JobRoleDto(Long id, String name, String description, Role accessLevel, boolean systemRole,
                             boolean active, long users, List<String> permissions, Instant createdAt) {
        public static JobRoleDto of(JobRole r, long users) {
            List<String> permissions = r.getPermissions().stream().sorted(Comparator.comparing(Enum::ordinal)).map(Permission::name).toList();
            return new JobRoleDto(r.getId(), r.getName(), r.getDescription(), r.getAccessLevel(), r.isSystemRole(), r.isActive(), users, permissions, r.getCreatedAt());
        }
    }

    /** {@code permissions} is optional on create: omitted, the role starts from its access level's template. */
    public record JobRoleRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 255) String description,
            @NotNull Role accessLevel,
            Boolean active,
            List<String> permissions) {}

    public record PermissionDto(String code, String group, String label, String description) {
        public static PermissionDto of(Permission p) {
            return new PermissionDto(p.name(), p.group(), p.label(), p.description());
        }
    }

    public record UpdatePermissionsRequest(@NotNull List<String> permissions) {}

    // ------------------------------------------------------------------ departments

    public record DepartmentDto(Long id, String code, String name, String description, Long headUserId, String headName,
                                boolean active, long currentStaff) {
        public static DepartmentDto of(Department d, long currentStaff) {
            return new DepartmentDto(d.getId(), d.getCode(), d.getName(), d.getDescription(),
                    d.getHead() == null ? null : d.getHead().getId(), d.getHead() == null ? null : d.getHead().getFullName(),
                    d.isActive(), currentStaff);
        }
    }

    public record DepartmentRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,20}$", message = "Code must be 2-20 letters, digits or dashes") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            Long headUserId,
            Boolean active) {}

    // ------------------------------------------------------------------ employments

    public record EmploymentDto(Long id, Long userId, String userName, String username, Long departmentId, String departmentName,
                                Long stationId, String stationName, String jobTitle, EmploymentType employmentType,
                                LocalDate startDate, LocalDate endDate, String status, String notes) {
        public static EmploymentDto of(Employment e, LocalDate today) {
            return new EmploymentDto(e.getId(), e.getUser().getId(), e.getUser().getFullName(), e.getUser().getUsername(),
                    e.getDepartment() == null ? null : e.getDepartment().getId(),
                    e.getDepartment() == null ? null : e.getDepartment().getName(),
                    e.getStation() == null ? null : e.getStation().getId(),
                    e.getStation() == null ? null : e.getStation().getName(),
                    e.getJobTitle(), e.getEmploymentType(), e.getStartDate(), e.getEndDate(), e.statusOn(today), e.getNotes());
        }
    }

    public record EmploymentRequest(
            @NotNull Long userId,
            Long departmentId,
            Long stationId,
            @NotBlank @Size(max = 100) String jobTitle,
            @NotNull EmploymentType employmentType,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 500) String notes) {}
}
