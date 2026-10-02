package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.StationDtos.AssignUsersRequest;
import com.rwacof.cherrytrack.dto.StationDtos.StationDetailDto;
import com.rwacof.cherrytrack.dto.StationDtos.StationDto;
import com.rwacof.cherrytrack.dto.StationDtos.StationRequest;
import com.rwacof.cherrytrack.service.StationService;
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

@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "Stations")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /** The stations the caller can work in. Administrators can include inactive ones. */
    @GetMapping
    public List<StationDto> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return stationService.list(includeInactive);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    public StationDetailDto get(@PathVariable Long id) {
        return stationService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    public ResponseEntity<StationDto> create(@Valid @RequestBody StationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stationService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    public StationDto update(@PathVariable Long id, @Valid @RequestBody StationRequest request) {
        return stationService.update(id, request);
    }

    /** Replaces the users assigned to the station. Assigned users manage it. */
    @PutMapping("/{id}/users")
    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    public StationDetailDto assignUsers(@PathVariable Long id, @Valid @RequestBody AssignUsersRequest request) {
        return stationService.assignUsers(id, request.userIds());
    }
}
