package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.DeliveryDtos.DeliveryDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerDetailDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerRequest;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerSummaryDto;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.repository.DeliveryRepository;
import com.rwacof.cherrytrack.repository.DeliverySpecs;
import com.rwacof.cherrytrack.repository.DeliverySpecs.DeliveryFilter;
import com.rwacof.cherrytrack.repository.FarmerRepository;
import com.rwacof.cherrytrack.repository.FarmerStatusStat;
import com.rwacof.cherrytrack.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FarmerService {

    private static final Set<String> SORTABLE = Set.of("fullName", "cooperativeNumber", "phone", "createdAt", "active");

    private final FarmerRepository farmerRepository;
    private final DeliveryRepository deliveryRepository;
    private final AuditService auditService;
    private final StationAccessService stationAccess;
    private final Clock clock;

    public FarmerService(FarmerRepository farmerRepository, DeliveryRepository deliveryRepository,
                         AuditService auditService, StationAccessService stationAccess, Clock clock) {
        this.stationAccess = stationAccess;
        this.farmerRepository = farmerRepository;
        this.deliveryRepository = deliveryRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<FarmerDto> search(Station station, String q, Boolean active, int page, int size, String sortBy, boolean desc) {
        Specification<Farmer> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("phone")), like, '\\'),
                        cb.like(cb.lower(root.get("cooperativeNumber")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        String property = SORTABLE.contains(sortBy) ? sortBy : "fullName";
        Sort sort = Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, property).and(Sort.by("id"));
        Page<Farmer> result = farmerRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort));

        Map<Long, List<FarmerStatusStat>> stats = statsFor(station, result.getContent().stream().map(Farmer::getId).toList());
        return PageResponse.of(result, f -> toDto(f, stats.getOrDefault(f.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public FarmerDetailDto get(Station station, Long id) {
        Farmer farmer = find(id);
        FarmerDto dto = toDto(farmer, statsFor(station, List.of(id)).getOrDefault(id, List.of()));
        var held = CurrentUser.require().permissions();
        List<DeliveryDto> recent = deliveryRepository
                .findAll(DeliverySpecs.of(new DeliveryFilter(station.getId(), null, null, null, null, null, id, null, null, null)),
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))))
                .map(d -> DeliveryDto.of(d, held)).getContent();
        return new FarmerDetailDto(dto, recent);
    }

    @Transactional(readOnly = true)
    public FarmerSummaryDto summary(Station station) {
        long active = farmerRepository.countByActive(true);
        long inactive = farmerRepository.countByActive(false);
        return new FarmerSummaryDto(active + inactive, active, inactive,
                deliveryRepository.countByStationIdAndDeliveryDate(station.getId(), stationAccess.today(station)));
    }

    @Transactional
    public FarmerDto create(FarmerRequest request) {
        String coop = normalizeCoop(request.cooperativeNumber());
        if (farmerRepository.existsByCooperativeNumber(coop)) {
            throw new ConflictException("DUPLICATE_COOPERATIVE_NUMBER",
                    "A farmer with cooperative number " + coop + " already exists.");
        }
        Farmer saved = farmerRepository.saveAndFlush(
                new Farmer(request.fullName().trim(), request.phone().trim(), coop, clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fullName", saved.getFullName());
        details.put("cooperativeNumber", coop);
        auditService.record(AuditAction.FARMER_CREATED, "FARMER", saved.getId(),
                "Farmer registered: " + saved.getFullName() + " (" + coop + ")", details);
        return toDto(saved, List.of());
    }

    @Transactional
    public FarmerDto update(Station station, Long id, FarmerRequest request) {
        Farmer farmer = find(id);
        String coop = normalizeCoop(request.cooperativeNumber());
        farmerRepository.findByCooperativeNumber(coop).filter(other -> !other.getId().equals(id)).ifPresent(o -> {
            throw new ConflictException("DUPLICATE_COOPERATIVE_NUMBER",
                    "A farmer with cooperative number " + coop + " already exists.");
        });

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "fullName", farmer.getFullName(), request.fullName().trim());
        track(changes, "phone", farmer.getPhone(), request.phone().trim());
        track(changes, "cooperativeNumber", farmer.getCooperativeNumber(), coop);
        if (request.active() != null) {
            track(changes, "active", farmer.isActive(), request.active());
            farmer.setActive(request.active());
        }
        farmer.setFullName(request.fullName().trim());
        farmer.setPhone(request.phone().trim());
        farmer.setCooperativeNumber(coop);
        farmer.touch(clock.instant());
        farmerRepository.saveAndFlush(farmer);

        auditService.record(AuditAction.FARMER_UPDATED, "FARMER", id,
                changes.isEmpty() ? "Farmer saved without changes" : "Farmer updated: " + String.join(", ", changes.keySet()),
                changes);
        return toDto(farmer, statsFor(station, List.of(id)).getOrDefault(id, List.of()));
    }

    // ------------------------------------------------------------------ helpers

    private Farmer find(Long id) {
        return farmerRepository.findById(id).orElseThrow(() -> new NotFoundException("Farmer", id));
    }

    private static String normalizeCoop(String s) {
        return s.trim().toUpperCase();
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            changes.put(field, Map.of("from", before, "to", after));
        }
    }

    private Map<Long, List<FarmerStatusStat>> statsFor(Station station, Collection<Long> ids) {
        Map<Long, List<FarmerStatusStat>> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (FarmerStatusStat s : deliveryRepository.statsByFarmerIds(station.getId(), ids)) {
            map.computeIfAbsent(s.farmerId(), k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    /** Total weight counts non-rejected deliveries; total amount is what has actually been paid. */
    private FarmerDto toDto(Farmer f, List<FarmerStatusStat> stats) {
        long count = 0;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (FarmerStatusStat s : stats) {
            count += s.count();
            if (s.status().countsTowardCapacity()) {
                weight = weight.add(s.weightKg());
            }
            if (s.status() == DeliveryStatus.PAID) {
                paid = paid.add(s.amount());
            }
        }
        return new FarmerDto(f.getId(), f.getFullName(), f.getPhone(), f.getCooperativeNumber(), f.isActive(),
                count, weight, paid, f.getCreatedAt());
    }
}
