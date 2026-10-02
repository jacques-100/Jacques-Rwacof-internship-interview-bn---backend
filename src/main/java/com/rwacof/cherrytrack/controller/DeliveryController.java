package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.AuditDtos.AuditLogDto;
import com.rwacof.cherrytrack.dto.DeliveryDtos.CorrectWeightRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.CreateDeliveryRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.DeliveryDto;
import com.rwacof.cherrytrack.dto.DeliveryDtos.GradeRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.RejectRequest;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.security.CurrentStation;
import com.rwacof.cherrytrack.repository.DeliverySpecs.DeliveryFilter;
import com.rwacof.cherrytrack.service.AuditService;
import com.rwacof.cherrytrack.service.DeliveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final AuditService auditService;

    public DeliveryController(DeliveryService deliveryService, AuditService auditService) {
        this.deliveryService = deliveryService;
        this.auditService = auditService;
    }

    @GetMapping
    public PageResponse<DeliveryDto> list(
            @CurrentStation Station station,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long farmerId,
            @RequestParam(required = false) BigDecimal minWeight,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String dir) {
        var filter = new DeliveryFilter(station.getId(), date, from, to, status, grade, farmerId, minWeight, maxWeight, q);
        return deliveryService.search(filter, page, size, sortBy, !"asc".equalsIgnoreCase(dir));
    }

    @GetMapping("/{id}")
    public DeliveryDto get(@PathVariable Long id) {
        return deliveryService.get(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogDto> audit(@PathVariable Long id) {
        deliveryService.get(id); // 404 for unknown ids
        return auditService.forEntity("DELIVERY", id);
    }

    @PreAuthorize("hasAuthority('DELIVERY_CREATE')")
    @PostMapping
    public ResponseEntity<DeliveryDto> create(@CurrentStation Station station, @Valid @RequestBody CreateDeliveryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deliveryService.create(station, request));
    }

    @PreAuthorize("hasAuthority('DELIVERY_CORRECT_WEIGHT')")
    @PatchMapping("/{id}/weight")
    public DeliveryDto correctWeight(@PathVariable Long id, @Valid @RequestBody CorrectWeightRequest request) {
        return deliveryService.correctWeight(id, request);
    }

    @PreAuthorize("hasAuthority('DELIVERY_GRADE')")
    @PostMapping("/{id}/grade")
    public DeliveryDto grade(@PathVariable Long id, @Valid @RequestBody GradeRequest request) {
        return deliveryService.grade(id, request);
    }

    @PreAuthorize("hasAuthority('DELIVERY_REJECT')")
    @PostMapping("/{id}/reject")
    public DeliveryDto reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request) {
        return deliveryService.reject(id, request.reason());
    }

    /** Payment is a supervisor/admin duty (separation of duties from grading and intake). */
    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('DELIVERY_PAY')")
    public DeliveryDto pay(@PathVariable Long id) {
        return deliveryService.pay(id);
    }
}
