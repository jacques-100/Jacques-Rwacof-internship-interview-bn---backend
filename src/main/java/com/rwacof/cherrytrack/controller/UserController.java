package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.dto.UserDtos.AssignStationsRequest;
import com.rwacof.cherrytrack.dto.UserDtos.CreateUserRequest;
import com.rwacof.cherrytrack.dto.UserDtos.ResetPasswordRequest;
import com.rwacof.cherrytrack.dto.UserDtos.SetActiveRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UpdateUserRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
@Tag(name = "Users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserDto> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Role role,
                                      @RequestParam(required = false) Boolean active,
                                      @RequestParam(required = false) Long jobRoleId,
                                      @RequestParam(required = false) Long stationId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "fullName") String sortBy,
                                      @RequestParam(defaultValue = "asc") String dir) {
        return userService.search(q, role, active, jobRoleId, stationId, page, size, sortBy, "desc".equalsIgnoreCase(dir));
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PutMapping("/{id}/stations")
    public UserDto assignStations(@PathVariable Long id, @Valid @RequestBody AssignStationsRequest request) {
        return userService.assignStations(id, request.stationIds());
    }

    @PatchMapping("/{id}/active")
    public UserDto setActive(@PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return userService.setActive(id, request.active());
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
