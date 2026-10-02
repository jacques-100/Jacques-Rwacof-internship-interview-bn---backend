package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.DirectoryDtos.DepartmentDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.DepartmentRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.EmploymentDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.EmploymentRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.JobRoleDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.JobRoleRequest;
import com.rwacof.cherrytrack.dto.DirectoryDtos.PermissionDto;
import com.rwacof.cherrytrack.dto.DirectoryDtos.UpdatePermissionsRequest;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.model.EmploymentType;
import com.rwacof.cherrytrack.service.DirectoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Staff directory: job roles, departments and employment records. Needs the USER_MANAGE permission. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('USER_MANAGE')")
@Tag(name = "Directory")
public class DirectoryController {

    private final DirectoryService directory;

    public DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    // ------------------------------------------------------------------ roles

    @GetMapping("/roles")
    public List<JobRoleDto> roles() {
        return directory.roles();
    }

    @PostMapping("/roles")
    public ResponseEntity<JobRoleDto> createRole(@Valid @RequestBody JobRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.createRole(request));
    }

    @PutMapping("/roles/{id}")
    public JobRoleDto updateRole(@PathVariable Long id, @Valid @RequestBody JobRoleRequest request) {
        return directory.updateRole(id, request);
    }

    // ------------------------------------------------------------------ permissions

    /** Everything a role can be allowed to do, for building the permission matrix. */
    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE','PERMISSION_MANAGE')")
    public List<PermissionDto> permissions() {
        return directory.permissionCatalogue();
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public JobRoleDto updatePermissions(@PathVariable Long id, @Valid @RequestBody UpdatePermissionsRequest request) {
        return directory.updatePermissions(id, request.permissions());
    }

    // ------------------------------------------------------------------ departments

    @GetMapping("/departments")
    public List<DepartmentDto> departments() {
        return directory.departments();
    }

    @PostMapping("/departments")
    public ResponseEntity<DepartmentDto> createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.createDepartment(request));
    }

    @PutMapping("/departments/{id}")
    public DepartmentDto updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return directory.updateDepartment(id, request);
    }

    // ------------------------------------------------------------------ employments

    @GetMapping("/employments")
    public PageResponse<EmploymentDto> employments(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Long userId,
                                                   @RequestParam(required = false) Long departmentId,
                                                   @RequestParam(required = false) Long stationId,
                                                   @RequestParam(required = false) EmploymentType type,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return directory.employments(q, userId, departmentId, stationId, type, status, page, size);
    }

    @PostMapping("/employments")
    public ResponseEntity<EmploymentDto> createEmployment(@Valid @RequestBody EmploymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.createEmployment(request));
    }

    @PutMapping("/employments/{id}")
    public EmploymentDto updateEmployment(@PathVariable Long id, @Valid @RequestBody EmploymentRequest request) {
        return directory.updateEmployment(id, request);
    }
}
