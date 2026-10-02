package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerDetailDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerRequest;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerSummaryDto;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.security.CurrentStation;
import com.rwacof.cherrytrack.service.FarmerService;
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

@RestController
@RequestMapping("/api/v1/farmers")
@Tag(name = "Farmers")
public class FarmerController {

    private final FarmerService farmerService;

    public FarmerController(FarmerService farmerService) {
        this.farmerService = farmerService;
    }

    @GetMapping
    public PageResponse<FarmerDto> list(@CurrentStation Station station,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Boolean active,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(defaultValue = "fullName") String sortBy,
                                        @RequestParam(defaultValue = "asc") String dir) {
        return farmerService.search(station, q, active, page, size, sortBy, "desc".equalsIgnoreCase(dir));
    }

    @GetMapping("/summary")
    public FarmerSummaryDto summary(@CurrentStation Station station) {
        return farmerService.summary(station);
    }

    @GetMapping("/{id}")
    public FarmerDetailDto get(@CurrentStation Station station, @PathVariable Long id) {
        return farmerService.get(station, id);
    }

    @PreAuthorize("hasAuthority('FARMER_MANAGE')")
    @PostMapping
    public ResponseEntity<FarmerDto> create(@Valid @RequestBody FarmerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(farmerService.create(request));
    }

    @PreAuthorize("hasAuthority('FARMER_MANAGE')")
    @PutMapping("/{id}")
    public FarmerDto update(@CurrentStation Station station, @PathVariable Long id, @Valid @RequestBody FarmerRequest request) {
        return farmerService.update(station, id, request);
    }
}
