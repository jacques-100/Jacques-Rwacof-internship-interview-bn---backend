package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.PriceDtos.PriceDto;
import com.rwacof.cherrytrack.dto.PriceDtos.PriceRequest;
import com.rwacof.cherrytrack.dto.PriceDtos.PricesResponse;
import com.rwacof.cherrytrack.service.PricingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prices")
@Tag(name = "Prices")
public class PriceController {

    private final PricingService pricingService;

    public PriceController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /** Readable by every role: clerks need the current price to preview a grading. */
    @GetMapping
    public PricesResponse list() {
        return pricingService.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    public ResponseEntity<PriceDto> create(@Valid @RequestBody PriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pricingService.create(request));
    }
}
