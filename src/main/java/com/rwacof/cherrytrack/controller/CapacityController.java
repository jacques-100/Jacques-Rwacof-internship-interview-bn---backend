package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.CapacityDtos.AdjustCapacityRequest;
import com.rwacof.cherrytrack.dto.CapacityDtos.CapacityDto;
import com.rwacof.cherrytrack.dto.CapacityDtos.CapacityPreviewDto;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.security.CurrentStation;
import com.rwacof.cherrytrack.service.CapacityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/capacity")
@Tag(name = "Capacity")
public class CapacityController {

    private final CapacityService capacityService;

    public CapacityController(CapacityService capacityService) {
        this.capacityService = capacityService;
    }

    /** Full daily-intake summary for the current station. Defaults to the station's today. */
    @GetMapping
    public CapacityDto day(@CurrentStation Station station,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return capacityService.getDay(station, date == null ? capacityService.today(station) : date);
    }

    /** Lightweight figures for the new-delivery form. Advisory: the backend re-validates on submit. */
    @GetMapping("/preview")
    public CapacityPreviewDto preview(@CurrentStation Station station,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return capacityService.preview(station, date == null ? capacityService.today(station) : date);
    }

    /** Raises or lowers the limit for one date. Supervisors manage their assigned stations; admins manage all. */
    @PutMapping("/limit")
    @PreAuthorize("hasAuthority('CAPACITY_ADJUST')")
    public CapacityDto adjust(@CurrentStation Station station,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @Valid @RequestBody AdjustCapacityRequest request) {
        return capacityService.adjustLimit(station, date, request.limitKg(), request.reason());
    }
}
