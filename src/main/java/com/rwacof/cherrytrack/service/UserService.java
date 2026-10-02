package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.dto.UserDtos.CreateUserRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UpdateProfileRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UpdateUserRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.JobRole;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.JobRoleRepository;
import com.rwacof.cherrytrack.repository.RefreshTokenRepository;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.CurrentUser;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserService {

    private static final Set<String> SORTABLE = Set.of("username", "fullName", "role", "active", "createdAt");

    private final UserRepository userRepository;
    private final JobRoleRepository jobRoleRepository;
    private final StationRepository stationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    public UserService(UserRepository userRepository, JobRoleRepository jobRoleRepository, StationRepository stationRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.stationRepository = stationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDto> search(String q, Role role, Boolean active, Long jobRoleId, Long stationId,
                                        int page, int size, String sortBy, boolean desc) {
        Specification<User> spec = (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("jobRole", JoinType.INNER);
            }
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("fullName")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("email"), "")), like, '\\')));
            }
            if (role != null) p.add(cb.equal(root.get("role"), role));
            if (active != null) p.add(cb.equal(root.get("active"), active));
            if (jobRoleId != null) p.add(cb.equal(root.get("jobRole").get("id"), jobRoleId));
            if (stationId != null) p.add(cb.equal(root.join("stations", JoinType.INNER).get("id"), stationId));
            return cb.and(p.toArray(new Predicate[0]));
        };
        String property = SORTABLE.contains(sortBy) ? sortBy : "fullName";
        Sort sort = Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, property).and(Sort.by("id"));
        return PageResponse.of(userRepository.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort)), UserDto::of);
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        return UserDto.of(find(id));
    }

    @Transactional
    public UserDto create(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("DUPLICATE_USERNAME", "Username '" + request.username() + "' is already taken.");
        }
        String email = normalizeEmail(request.email());
        requireEmailFree(email, null);
        JobRole jobRole = activeJobRole(request.jobRoleId());
        Grants.require(jobRole.getPermissions());

        User user = new User(request.username(), passwordEncoder.encode(request.password()), request.fullName().trim(), jobRole, clock.instant());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));
        if (request.stationIds() != null) {
            user.getStations().addAll(stationsByIds(request.stationIds()));
        }
        User saved = userRepository.saveAndFlush(user);
        auditService.record(AuditAction.USER_CREATED, "USER", saved.getId(),
                "User created: " + saved.getUsername() + " (" + jobRole.getName() + ")",
                Map.of("username", saved.getUsername(), "jobRole", jobRole.getName(), "role", saved.getRole().name()));
        return UserDto.of(saved);
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest request) {
        User user = find(id);
        JobRole jobRole = jobRoleRepository.findById(request.jobRoleId()).orElseThrow(() -> new NotFoundException("Job role", request.jobRoleId()));
        boolean changingRole = !user.getJobRole().getId().equals(jobRole.getId());
        if (changingRole) {
            if (!jobRole.isActive()) {
                throw new BusinessRuleException("ROLE_INACTIVE", "The role '" + jobRole.getName() + "' is not active.");
            }
            Grants.require(jobRole.getPermissions());
            if (user.getRole() != jobRole.getAccessLevel()) {
                guardNotSelf(id, "change your own role");
            }
        }
        String email = normalizeEmail(request.email());
        requireEmailFree(email, id);

        Map<String, Object> details = new LinkedHashMap<>();
        if (changingRole) {
            details.put("jobRole", Map.of("from", user.getJobRole().getName(), "to", jobRole.getName()));
        }
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));
        if (changingRole) {
            user.assignJobRole(jobRole);
        }
        user.touch(clock.instant());
        auditService.record(AuditAction.USER_UPDATED, "USER", id, "User updated: " + user.getUsername(), details);
        return UserDto.of(user);
    }

    /** Replaces the stations a user is assigned to (assigned users manage those stations). */
    @Transactional
    public UserDto assignStations(Long id, List<Long> stationIds) {
        User user = find(id);
        Set<Station> wanted = stationsByIds(stationIds);
        List<String> before = user.getStations().stream().map(Station::getCode).sorted().toList();
        user.getStations().clear();
        user.getStations().addAll(wanted);
        user.touch(clock.instant());
        List<String> after = wanted.stream().map(Station::getCode).sorted().toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stations", Map.of("from", before, "to", after));
        auditService.record(AuditAction.USER_UPDATED, "USER", id, "Stations changed for " + user.getUsername() + ": " + String.join(", ", after), details);
        return UserDto.of(user);
    }

    @Transactional
    public UserDto setActive(Long id, boolean active) {
        User user = find(id);
        if (!active) {
            guardNotSelf(id, "deactivate your own account");
        }
        user.setActive(active);
        user.touch(clock.instant());
        if (!active) {
            refreshTokenRepository.revokeAllForUser(id);
        }
        auditService.record(AuditAction.USER_ACTIVATION_CHANGED, "USER", id,
                "User " + user.getUsername() + (active ? " activated" : " deactivated"), Map.of("active", active));
        return UserDto.of(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        User user = find(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.touch(clock.instant());
        refreshTokenRepository.revokeAllForUser(id);   // force re-login everywhere
        auditService.record(AuditAction.USER_PASSWORD_RESET, "USER", id,
                "Password reset for " + user.getUsername(), null);
    }

    // ------------------------------------------------------------------ own profile

    @Transactional(readOnly = true)
    public UserDto profile() {
        return UserDto.of(find(CurrentUser.require().id()));
    }

    /** Name, email and phone only: a user can never change their own role, stations or status. */
    @Transactional
    public UserDto updateProfile(UpdateProfileRequest request) {
        User user = find(CurrentUser.require().id());
        String email = normalizeEmail(request.email());
        requireEmailFree(email, user.getId());

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "fullName", user.getFullName(), request.fullName().trim());
        track(changes, "email", user.getEmail(), email);
        track(changes, "phone", user.getPhone(), blankToNull(request.phone()));
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));
        user.touch(clock.instant());
        userRepository.saveAndFlush(user);
        auditService.record(AuditAction.PROFILE_UPDATED, "USER", user.getId(),
                changes.isEmpty() ? user.getUsername() + " saved their profile" : user.getUsername() + " updated their profile: " + String.join(", ", changes.keySet()), changes);
        return UserDto.of(user);
    }

    // ------------------------------------------------------------------ helpers

    private User find(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    private JobRole activeJobRole(Long id) {
        JobRole role = jobRoleRepository.findById(id).orElseThrow(() -> new NotFoundException("Job role", id));
        if (!role.isActive()) {
            throw new BusinessRuleException("ROLE_INACTIVE", "The role '" + role.getName() + "' is not active.");
        }
        return role;
    }

    private Set<Station> stationsByIds(List<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        List<Station> found = stationRepository.findAllById(wanted);
        if (found.size() != wanted.size()) {
            throw new BusinessRuleException("UNKNOWN_STATION", "One or more selected stations do not exist.");
        }
        return new HashSet<>(found);
    }

    private void requireEmailFree(String email, Long selfId) {
        if (email == null) return;
        userRepository.findByEmailIgnoreCase(email).filter(u -> !u.getId().equals(selfId)).ifPresent(u -> {
            throw new ConflictException("DUPLICATE_EMAIL", "That email address is already used by another account.");
        });
    }

    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", before);
            change.put("to", after);
            changes.put(field, change);
        }
    }

    /** An admin can't lock themselves out or demote themselves, so the system always keeps an active admin. */
    private void guardNotSelf(Long id, String what) {
        if (CurrentUser.get().map(u -> u.id().equals(id)).orElse(false)) {
            throw new BusinessRuleException("SELF_MODIFICATION", "You cannot " + what + ".");
        }
    }
}
