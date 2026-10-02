package com.rwacof.cherrytrack.dto;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class UserDtos {

    private UserDtos() {}

    /** A Rwandan mobile number, or empty (to clear the field). */
    private static final String PHONE = "^$|^(\\+250|0)7\\d{8}$";

    /** {@code role} is the access level (what the user may do); {@code jobRole} is their named position. */
    public record UserDto(Long id, String username, String fullName, String email, String phone, Role role,
                          DirectoryDtos.JobRoleRef jobRole, boolean active, Instant createdAt,
                          List<StationDtos.StationRef> stations, List<String> permissions,
                          /** Changes whenever the profile photo does; null when the user has none. */
                          Long avatarVersion) {
        /** Must be called inside a transaction (lazy associations). */
        public static UserDto of(User u) {
            List<StationDtos.StationRef> stations = u.getStations().stream()
                    .sorted(Comparator.comparing(Station::getName))
                    .map(StationDtos.StationRef::of)
                    .toList();
            List<String> permissions = u.getJobRole().getPermissions().stream()
                    .sorted(Comparator.comparing(Enum::ordinal)).map(Enum::name).toList();
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(),
                    DirectoryDtos.JobRoleRef.of(u.getJobRole()), u.isActive(), u.getCreatedAt(), stations, permissions,
                    u.getAvatarUpdatedAt() == null ? null : u.getAvatarUpdatedAt().toEpochMilli());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9._-]{3,50}$",
                    message = "Username must be 3-50 characters: lowercase letters, digits, dot, dash, underscore") String username,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(min = 10, max = 100, message = "Password must be 10-100 characters") String password,
            @NotNull Long jobRoleId,
            @Email @Size(max = 160) String email,
            @Pattern(regexp = PHONE, message = "Phone must be a Rwandan mobile number, e.g. 0788123456") String phone,
            List<Long> stationIds) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Long jobRoleId,
            @Email @Size(max = 160) String email,
            @Pattern(regexp = PHONE, message = "Phone must be a Rwandan mobile number, e.g. 0788123456") String phone) {}

    public record SetActiveRequest(@NotNull Boolean active) {}

    public record AssignStationsRequest(@NotNull List<Long> stationIds) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 100, message = "Password must be 10-100 characters") String newPassword) {}

    /** What a user may change about themselves. Role, stations and status are admin-only. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Email @Size(max = 160) String email,
            @Pattern(regexp = PHONE, message = "Phone must be a Rwandan mobile number, e.g. 0788123456") String phone) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 100, message = "Password must be 10-100 characters") String newPassword) {}
}
