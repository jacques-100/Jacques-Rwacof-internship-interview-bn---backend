package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.DashboardDto;
import com.rwacof.cherrytrack.dto.SettingsDto;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.security.CurrentStation;
import com.rwacof.cherrytrack.service.StationAccessService;
import com.rwacof.cherrytrack.service.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final StationAccessService stationAccess;

    public DashboardController(DashboardService dashboardService, StationAccessService stationAccess) {
        this.dashboardService = dashboardService;
        this.stationAccess = stationAccess;
    }

    @GetMapping("/dashboard")
    public DashboardDto dashboard(@CurrentStation Station station, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.dashboard(station, date);
    }

    /** Configuration of the current station. It is edited on the Stations page, not here. */
    @GetMapping("/settings")
    public SettingsDto settings(@CurrentStation Station station) {
        return new SettingsDto(station.getId(), station.getCode(), station.getName(), station.getTimezone(), stationAccess.today(station),
                station.getDailyCapacityKg(), station.getMaxDeliveryKg(), station.getLowThresholdKg());
    }
}
