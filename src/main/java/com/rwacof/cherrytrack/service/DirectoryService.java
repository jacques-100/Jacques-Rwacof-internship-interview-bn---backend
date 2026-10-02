package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.DirectoryDtos.DepartmentDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.DepartmentRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.EmploymentDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.EmploymentRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.JobRoleDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.JobRoleRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.PermissionDto;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.Department;
import com.rwacof.cherrytrack.model.Employment;
import com.rwacof.cherrytrack.model.EmploymentType;
import com.rwacof.cherrytrack.model.JobRole;
import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.DepartmentRepository;
import com.rwacof.cherrytrack.repository.EmploymentRepository;
import com.rwacof.cherrytrack.repository.JobRoleRepository;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The staff directory: job roles, departments and employment records. */
@Service
public class DirectoryService {

    private final JobRoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final EmploymentRepository employmentRepository;
    private final UserRepository userRepository;
    private final StationRepository stationRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DirectoryService(JobRoleRepository roleRepository, DepartmentRepository departmentRepository,
                            EmploymentRepository employmentRepository, UserRepository userRepository,
                            StationRepository stationRepository, AuditService auditService, Clock clock) {
        this.roleRepository = roleRepository;
        this.departmentRepository = departmentRepository;
        this.employmentRepository = employmentRepository;
        this.userRepository = userRepository;
        this.stationRepository = stationRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ================================================================== job roles

    @Transactional(readOnly = true)
    public List<JobRoleDto> roles() {
        return roleRepository.findAllByOrderBySystemRoleDescNameAsc().stream()
                .map(r -> JobRoleDto.of(r, roleRepository.countUsers(r.getId())))
                .toList();
    }

    @Transactional
    public JobRoleDto createRole(JobRoleRequest request) {
        String name = request.name().trim();
        if (roleRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("DUPLICATE_ROLE", "A role named '" + name + "' already exists.");
        }
        JobRole role = new JobRole(name, blankToNull(request.description()), request.accessLevel(), clock.instant());
        if (request.permissions() != null) {
            role.replacePermissions(parsePermissions(request.permissions()));   // otherwise: the access level's template
        }
        Grants.require(role.getPermissions());
        JobRole saved = roleRepository.saveAndFlush(role);
        auditService.record(AuditAction.ROLE_CREATED, "ROLE", saved.getId(), "Role created: " + name + " (" + saved.getAccessLevel() + " access)",
                Map.of("name", name, "accessLevel", saved.getAccessLevel().name()));
        return JobRoleDto.of(saved, 0);
    }

    @Transactional
    public JobRoleDto updateRole(Long id, JobRoleRequest request) {
        JobRole role = roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role", id));
        long users = roleRepository.countUsers(id);
        String name = request.name().trim();

        if (role.isSystemRole()) {
            if (!role.getName().equals(name) || role.getAccessLevel() != request.accessLevel()) {
                throw new BusinessRuleException("SYSTEM_ROLE", "Built-in roles keep their name and access level; only the description can change.");
            }
            if (Boolean.FALSE.equals(request.active())) {
                throw new BusinessRuleException("SYSTEM_ROLE", "Built-in roles cannot be switched off.");
            }
        } else {
            roleRepository.findByNameIgnoreCase(name).filter(r -> !r.getId().equals(id)).ifPresent(r -> {
                throw new ConflictException("DUPLICATE_ROLE", "A role named '" + name + "' already exists.");
            });
            // A user's access level always equals their role's. Changing a role that people hold would
            // silently change what they can do, so it must be unassigned first.
            if (role.getAccessLevel() != request.accessLevel() && users > 0) {
                throw new ConflictException("ROLE_IN_USE", "This role is assigned to " + users + " user(s). Move them to another role before changing its access level.");
            }
            if (Boolean.FALSE.equals(request.active()) && users > 0) {
                throw new ConflictException("ROLE_IN_USE", "This role is assigned to " + users + " user(s). Move them to another role before switching it off.");
            }
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "name", role.getName(), name);
        track(changes, "description", role.getDescription(), blankToNull(request.description()));
        track(changes, "accessLevel", role.getAccessLevel(), request.accessLevel());
        if (request.active() != null) {
            track(changes, "active", role.isActive(), request.active());
            role.setActive(request.active());
        }
        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        role.setAccessLevel(request.accessLevel());
        role.touch(clock.instant());
        roleRepository.saveAndFlush(role);
        auditService.record(AuditAction.ROLE_UPDATED, "ROLE", id, "Role updated: " + name, changes);
        return JobRoleDto.of(role, users);
    }

    /** Every permission the system knows about, grouped for display. */
    public List<PermissionDto> permissionCatalogue() {
        return Arrays.stream(Permission.values()).map(PermissionDto::of).toList();
    }

    /**
     * Replaces the permissions a role holds. Takes effect on its holders' next request. The built-in
     * Administrator role is fixed so the system can never be locked out of its own administration.
     */
    @Transactional
    public JobRoleDto updatePermissions(Long id, List<String> codes) {
        JobRole role = roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role", id));
        if (role.isSystemRole() && role.getAccessLevel() == Role.ADMIN) {
            throw new BusinessRuleException("ADMIN_ROLE_FIXED", "The Administrator role always holds every permission and cannot be edited.");
        }
        Set<Permission> next = parsePermissions(codes);
        Set<Permission> before = EnumSet.noneOf(Permission.class);
        before.addAll(role.getPermissions());

        Grants.require(next.stream().filter(p -> !before.contains(p)).collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(Permission.class))));
        List<String> added = next.stream().filter(p -> !before.contains(p)).map(Permission::name).sorted().toList();
        List<String> removed = before.stream().filter(p -> !next.contains(p)).map(Permission::name).sorted().toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return JobRoleDto.of(role, roleRepository.countUsers(id));
        }
        role.replacePermissions(next);
        role.touch(clock.instant());
        roleRepository.saveAndFlush(role);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", added);
        details.put("removed", removed);
        auditService.record(AuditAction.ROLE_PERMISSIONS_CHANGED, "ROLE", id,
                "Permissions of " + role.getName() + " changed (+" + added.size() + ", -" + removed.size() + ")", details);
        return JobRoleDto.of(role, roleRepository.countUsers(id));
    }

    private static Set<Permission> parsePermissions(List<String> codes) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (String code : codes) {
            try {
                result.add(Permission.valueOf(code.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("UNKNOWN_PERMISSION", "'" + code + "' is not a permission.");
            }
        }
        return result;
    }

    // ================================================================== departments

    @Transactional(readOnly = true)
    public List<DepartmentDto> departments() {
        return departmentRepository.findAllByOrderByNameAsc().stream()
                .map(d -> DepartmentDto.of(d, employmentRepository.countCurrentInDepartment(d.getId())))
                .toList();
    }

    @Transactional
    public DepartmentDto createDepartment(DepartmentRequest request) {
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        if (departmentRepository.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("DUPLICATE_DEPARTMENT_CODE", "A department with code " + code + " already exists.");
        }
        if (departmentRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("DUPLICATE_DEPARTMENT_NAME", "A department named '" + name + "' already exists.");
        }
        Department saved = departmentRepository.saveAndFlush(
                new Department(code, name, blankToNull(request.description()), head(request.headUserId()), clock.instant()));
        auditService.record(AuditAction.DEPARTMENT_CREATED, "DEPARTMENT", saved.getId(), "Department created: " + name + " (" + code + ")",
                Map.of("code", code, "name", name));
        return DepartmentDto.of(saved, 0);
    }

    @Transactional
    public DepartmentDto updateDepartment(Long id, DepartmentRequest request) {
        Department dept = departmentRepository.findById(id).orElseThrow(() -> new NotFoundException("Department", id));
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        departmentRepository.findAll().forEach(other -> {
            if (other.getId().equals(id)) return;
            if (other.getCode().equalsIgnoreCase(code)) throw new ConflictException("DUPLICATE_DEPARTMENT_CODE", "A department with code " + code + " already exists.");
            if (other.getName().equalsIgnoreCase(name)) throw new ConflictException("DUPLICATE_DEPARTMENT_NAME", "A department named '" + name + "' already exists.");
        });
        User head = head(request.headUserId());

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "name", dept.getName(), name);
        track(changes, "description", dept.getDescription(), blankToNull(request.description()));
        track(changes, "head", dept.getHead() == null ? null : dept.getHead().getId(), head == null ? null : head.getId());
        if (request.active() != null) {
            track(changes, "active", dept.isActive(), request.active());
            dept.setActive(request.active());
        }
        dept.setName(name);
        dept.setDescription(blankToNull(request.description()));
        dept.setHead(head);
        dept.touch(clock.instant());
        departmentRepository.saveAndFlush(dept);
        auditService.record(AuditAction.DEPARTMENT_UPDATED, "DEPARTMENT", id, "Department updated: " + name, changes);
        return DepartmentDto.of(dept, employmentRepository.countCurrentInDepartment(id));
    }

    private User head(Long userId) {
        if (userId == null) return null;
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (!user.isActive()) {
            throw new BusinessRuleException("USER_INACTIVE", "A deactivated user cannot head a department.");
        }
        return user;
    }

    // ================================================================== employments

    /** {@code status} is ACTIVE, ENDED or UPCOMING (relative to today). */
    @Transactional(readOnly = true)
    public PageResponse<EmploymentDto> employments(String q, Long userId, Long departmentId, Long stationId,
                                                   EmploymentType type, String status, int page, int size) {
        LocalDate today = LocalDate.now(clock);
        Specification<Employment> spec = (root, query, cb) -> {
            var user = root.join("user", JoinType.INNER);
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("user", JoinType.INNER);
                root.fetch("department", JoinType.LEFT);
                root.fetch("station", JoinType.LEFT);
            }
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(
                        cb.like(cb.lower(user.get("fullName")), like, '\\'),
                        cb.like(cb.lower(user.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("jobTitle")), like, '\\')));
            }
            if (userId != null) p.add(cb.equal(user.get("id"), userId));
            if (departmentId != null) p.add(cb.equal(root.get("department").get("id"), departmentId));
            if (stationId != null) p.add(cb.equal(root.get("station").get("id"), stationId));
            if (type != null) p.add(cb.equal(root.get("employmentType"), type));
            if ("ACTIVE".equalsIgnoreCase(status)) {
                p.add(cb.lessThanOrEqualTo(root.get("startDate"), today));
                p.add(cb.or(cb.isNull(root.get("endDate")), cb.greaterThanOrEqualTo(root.get("endDate"), today)));
            } else if ("ENDED".equalsIgnoreCase(status)) {
                p.add(cb.lessThan(root.get("endDate"), today));
            } else if ("UPCOMING".equalsIgnoreCase(status)) {
                p.add(cb.greaterThan(root.get("startDate"), today));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(Sort.Direction.DESC, "startDate").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(employmentRepository.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort)),
                e -> EmploymentDto.of(e, today));
    }

    @Transactional
    public EmploymentDto createEmployment(EmploymentRequest request) {
        User user = userRepository.findById(request.userId()).orElseThrow(() -> new NotFoundException("User", request.userId()));
        validateDates(request.startDate(), request.endDate());
        Employment saved = employmentRepository.saveAndFlush(new Employment(user, department(request.departmentId()), station(request.stationId()),
                request.jobTitle().trim(), request.employmentType(), request.startDate(), request.endDate(), blankToNull(request.notes()), clock.instant()));
        auditService.record(AuditAction.EMPLOYMENT_CREATED, "EMPLOYMENT", saved.getId(),
                "Employment recorded for " + user.getFullName() + ": " + saved.getJobTitle(),
                Map.of("user", user.getUsername(), "jobTitle", saved.getJobTitle(), "type", saved.getEmploymentType().name()));
        return EmploymentDto.of(saved, LocalDate.now(clock));
    }

    @Transactional
    public EmploymentDto updateEmployment(Long id, EmploymentRequest request) {
        Employment e = employmentRepository.findById(id).orElseThrow(() -> new NotFoundException("Employment", id));
        if (!e.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("EMPLOYEE_FIXED", "An employment record stays with the same person. Add a new record for someone else.");
        }
        validateDates(request.startDate(), request.endDate());
        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "jobTitle", e.getJobTitle(), request.jobTitle().trim());
        track(changes, "type", e.getEmploymentType(), request.employmentType());
        track(changes, "startDate", e.getStartDate(), request.startDate());
        track(changes, "endDate", e.getEndDate(), request.endDate());
        e.setDepartment(department(request.departmentId()));
        e.setStation(station(request.stationId()));
        e.setJobTitle(request.jobTitle().trim());
        e.setEmploymentType(request.employmentType());
        e.setStartDate(request.startDate());
        e.setEndDate(request.endDate());
        e.setNotes(blankToNull(request.notes()));
        e.touch(clock.instant());
        employmentRepository.saveAndFlush(e);
        auditService.record(AuditAction.EMPLOYMENT_UPDATED, "EMPLOYMENT", id, "Employment updated for " + e.getUser().getFullName(), changes);
        return EmploymentDto.of(e, LocalDate.now(clock));
    }

    private Department department(Long id) {
        if (id == null) return null;
        Department d = departmentRepository.findById(id).orElseThrow(() -> new NotFoundException("Department", id));
        if (!d.isActive()) {
            throw new BusinessRuleException("DEPARTMENT_INACTIVE", "The department '" + d.getName() + "' is not active.");
        }
        return d;
    }

    private Station station(Long id) {
        if (id == null) return null;
        return stationRepository.findById(id).orElseThrow(() -> new NotFoundException("Station", id));
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw new BusinessRuleException("INVALID_DATES", "The end date cannot be before the start date.");
        }
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", before == null ? null : before.toString());
            change.put("to", after == null ? null : after.toString());
            changes.put(field, change);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
