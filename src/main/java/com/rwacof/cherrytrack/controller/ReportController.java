package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.ReportDtos.ReportResponse;
import com.rwacof.cherrytrack.dto.ReportDtos.ReportType;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.security.CurrentStation;
import com.rwacof.cherrytrack.service.ReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAuthority('REPORT_VIEW')")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ReportResponse report(@CurrentStation Station station, @RequestParam ReportType type,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.run(station, type, from, to);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@CurrentStation Station station, @RequestParam ReportType type,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportResponse report = reportService.run(station, type, from, to);
        String filename = "cherrytrack-" + station.getCode().toLowerCase() + "-" + type.name().toLowerCase().replace('_', '-') + "-" + report.from() + "_" + report.to() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(reportService.toCsv(report));
    }
}
