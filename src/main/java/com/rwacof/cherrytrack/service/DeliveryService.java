package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.DeliveryDtos.CorrectWeightRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.CreateDeliveryRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.DeliveryDto;
import com.rwacof.cherrytrack.dto.DeliveryDtos.GradeRequest;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.DailyCapacity;
import com.rwacof.cherrytrack.model.Delivery;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.GradeDefinition;
import com.rwacof.cherrytrack.model.GradePrice;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.DeliveryKey;
import com.rwacof.cherrytrack.repository.DeliveryRepository;
import com.rwacof.cherrytrack.repository.DeliverySpecs;
import com.rwacof.cherrytrack.repository.DeliverySpecs.DeliveryFilter;
import com.rwacof.cherrytrack.repository.FarmerRepository;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivery workflow. Every state change happens in one transaction together with its audit record.
 *
 * <p>Write methods run at READ COMMITTED so that reads after taking the capacity lock see the latest
 * committed data (not a stale REPEATABLE READ snapshot). Operations that change accepted weight
 * (create, correct weight, reject) first take the station-day's capacity lock; grade and pay don't
 * affect capacity and rely on the delivery's optimistic version, so two clerks acting on the same
 * delivery can't both win.
 *
 * <p>Every operation is station-scoped: the caller must be assigned to the delivery's station
 * (administrators can work in all stations).
 */
@Service
public class DeliveryService {

    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<String> SORTABLE = Set.of(
            "reference", "deliveryDate", "weightKg", "amountOwed", "pricePerKg", "grade", "status", "createdAt");

    private final DeliveryRepository deliveryRepository;
    private final FarmerRepository farmerRepository;
    private final StationRepository stationRepository;
    private final UserRepository userRepository;
    private final CapacityService capacityService;
    private final PricingService pricingService;
    private final GradeService gradeService;
    private final StationAccessService stationAccess;
    private final AuditService auditService;
    private final SettingsService settings;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveryRepository, FarmerRepository farmerRepository,
                           StationRepository stationRepository, UserRepository userRepository,
                           CapacityService capacityService, PricingService pricingService, GradeService gradeService,
                           StationAccessService stationAccess, AuditService auditService, SettingsService settings, Clock clock) {
        this.settings = settings;
        this.deliveryRepository = deliveryRepository;
        this.farmerRepository = farmerRepository;
        this.stationRepository = stationRepository;
        this.userRepository = userRepository;
        this.capacityService = capacityService;
        this.pricingService = pricingService;
        this.gradeService = gradeService;
        this.stationAccess = stationAccess;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commands

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryDto create(Station station, CreateDeliveryRequest request) {
        AuthUser actor = CurrentUser.require();
        validateDate(station, request.deliveryDate());
        BigDecimal weight = normalizeWeight(request.weightKg(), station.getMaxDeliveryKg());

        Farmer farmer = farmerRepository.findById(request.farmerId())
                .orElseThrow(() -> new NotFoundException("Farmer", request.farmerId()));
        if (!farmer.isActive()) {
            throw new BusinessRuleException("FARMER_INACTIVE", "Deliveries cannot be recorded for an inactive farmer.");
        }

        DailyCapacity capacity = capacityService.lockOrCreate(station, request.deliveryDate());
        int seq = capacityService.reserve(capacity, weight);   // throws CapacityExceededException when it doesn't fit
        String reference = "DLV-" + station.getCode() + "-" + REF_DATE.format(request.deliveryDate()) + "-" + String.format("%05d", seq);

        Instant now = clock.instant();
        User creator = userRepository.getReferenceById(actor.id());
        Delivery saved = deliveryRepository.save(
                Delivery.receive(reference, station, farmer, request.deliveryDate(), weight, creator, now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference);
        details.put("station", station.getCode());
        details.put("farmerId", farmer.getId());
        details.put("farmerName", farmer.getFullName());
        details.put("weightKg", weight);
        auditService.record(station.getId(), AuditAction.DELIVERY_CREATED, "DELIVERY", saved.getId(),
                "Delivery created - " + plain(weight) + " kg", details);
        return DeliveryDto.of(saved, actor.permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryDto correctWeight(Long id, CorrectWeightRequest request) {
        AuthUser actor = CurrentUser.require();

        // Lock order: capacity row first, then the delivery (re-read fresh after the lock).
        DeliveryKey key = keyOf(id, actor);
        Station station = stationRepository.findById(key.stationId()).orElseThrow(() -> new NotFoundException("Station", key.stationId()));
        BigDecimal newWeight = normalizeWeight(request.newWeightKg(), station.getMaxDeliveryKg());
        DailyCapacity capacity = capacityService.lockExisting(key.stationId(), key.deliveryDate());
        Delivery delivery = deliveryRepository.findDetailedById(id)
                .orElseThrow(() -> new NotFoundException("Delivery", id));

        BigDecimal oldWeight = delivery.getWeightKg();
        // Status is checked by the domain method; do it before touching capacity so a terminal
        // delivery reports the right error instead of a capacity one.
        delivery.correctWeight(newWeight, clock.instant());
        capacityService.adjust(capacity, newWeight.subtract(oldWeight));
        deliveryRepository.save(delivery);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", delivery.getReference());
        details.put("oldWeightKg", oldWeight);
        details.put("newWeightKg", newWeight);
        details.put("reason", request.reason().trim());
        auditService.record(key.stationId(), AuditAction.WEIGHT_CORRECTED, "DELIVERY", id,
                "Weight corrected " + plain(oldWeight) + " → " + plain(newWeight) + " kg", details);
        return DeliveryDto.of(delivery, actor.permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryDto grade(Long id, GradeRequest request) {
        AuthUser actor = CurrentUser.require();
        Delivery delivery = load(id, actor);
        Instant now = clock.instant();

        // Grade must be one the station has configured and left switched on. The price is resolved
        // here on the server; the client never supplies price or amount.
        GradeDefinition grade = gradeService.requireActive(request.grade());
        GradePrice price = pricingService.priceAt(grade.getCode(), now);
        BigDecimal moisture = request.moisturePercent();
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        delivery.grade(grade.getCode(), price.getPricePerKg(), moisture, notes, userRepository.getReferenceById(actor.id()), now);
        deliveryRepository.save(delivery);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", delivery.getReference());
        details.put("grade", grade.getCode());
        details.put("weightKg", delivery.getWeightKg());
        details.put("pricePerKg", delivery.getPricePerKg());
        details.put("amountOwed", delivery.getAmountOwed());
        if (moisture != null) details.put("moisturePercent", moisture);
        if (notes != null) details.put("reason", notes);
        auditService.record(delivery.getStation().getId(), AuditAction.DELIVERY_GRADED, "DELIVERY", id,
                "Delivery graded - " + grade.getName() + ", price " + plain(delivery.getPricePerKg()) + " " + settings.currency() + "/kg, amount "
                        + plain(delivery.getAmountOwed()) + " " + settings.currency(), details);
        return DeliveryDto.of(delivery, actor.permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryDto reject(Long id, String reason) {
        AuthUser actor = CurrentUser.require();
        DeliveryKey key = keyOf(id, actor);
        DailyCapacity capacity = capacityService.lockExisting(key.stationId(), key.deliveryDate());
        Delivery delivery = deliveryRepository.findDetailedById(id)
                .orElseThrow(() -> new NotFoundException("Delivery", id));

        delivery.reject(reason.trim(), userRepository.getReferenceById(actor.id()), clock.instant());
        capacityService.release(capacity, delivery.getWeightKg());
        deliveryRepository.save(delivery);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", delivery.getReference());
        details.put("weightKg", delivery.getWeightKg());
        details.put("reason", reason.trim());
        auditService.record(key.stationId(), AuditAction.DELIVERY_REJECTED, "DELIVERY", id,
                "Delivery rejected - " + plain(delivery.getWeightKg()) + " kg released. Reason: " + reason.trim(), details);
        return DeliveryDto.of(delivery, actor.permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryDto pay(Long id) {
        AuthUser actor = CurrentUser.require();
        Delivery delivery = load(id, actor);
        delivery.markPaid(userRepository.getReferenceById(actor.id()), clock.instant());
        deliveryRepository.save(delivery);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", delivery.getReference());
        details.put("amountOwed", delivery.getAmountOwed());
        auditService.record(delivery.getStation().getId(), AuditAction.DELIVERY_PAID, "DELIVERY", id,
                "Marked as paid - " + plain(delivery.getAmountOwed()) + " " + settings.currency(), details);
        return DeliveryDto.of(delivery, actor.permissions());
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public DeliveryDto get(Long id) {
        AuthUser actor = CurrentUser.require();
        return DeliveryDto.of(load(id, actor), actor.permissions());
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryDto> search(DeliveryFilter filter, int page, int size, String sortBy, boolean desc) {
        String property = SORTABLE.contains(sortBy) ? sortBy : "createdAt";
        Sort sort = Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, property)
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort);
        var held = CurrentUser.require().permissions();
        return PageResponse.of(deliveryRepository.findAll(DeliverySpecs.of(filter), pageable),
                d -> DeliveryDto.of(d, held));
    }

    @Transactional(readOnly = true)
    public List<DeliveryDto> recent(Station station, int limit) {
        var held = CurrentUser.require().permissions();
        return deliveryRepository.findTop10ByStationIdOrderByCreatedAtDescIdDesc(station.getId()).stream()
                .limit(limit).map(d -> DeliveryDto.of(d, held)).toList();
    }

    // ------------------------------------------------------------------ helpers

    /** Station and date of a delivery, after checking the caller may work in that station. */
    private DeliveryKey keyOf(Long id, AuthUser actor) {
        DeliveryKey key = deliveryRepository.findKeyById(id).orElseThrow(() -> new NotFoundException("Delivery", id));
        stationAccess.requireAccess(actor, key.stationId());
        return key;
    }

    private Delivery load(Long id, AuthUser actor) {
        Delivery delivery = deliveryRepository.findDetailedById(id).orElseThrow(() -> new NotFoundException("Delivery", id));
        stationAccess.requireAccess(actor, delivery.getStation().getId());
        return delivery;
    }

    private void validateDate(Station station, LocalDate date) {
        if (date.isAfter(stationAccess.today(station))) {
            throw new BusinessRuleException("DELIVERY_DATE_IN_FUTURE", "Delivery date cannot be in the future.");
        }
    }

    /** Defence in depth: Bean Validation checks the format at the API edge, the station's limits are checked here. */
    private BigDecimal normalizeWeight(BigDecimal weight, BigDecimal max) {
        if (weight == null || weight.signum() <= 0 || weight.compareTo(max) > 0) {
            throw new BusinessRuleException("INVALID_WEIGHT",
                    "Delivery weight must be greater than 0 kg and no more than " + plain(max) + " kg at this station.");
        }
        if (weight.scale() > 2) {
            throw new BusinessRuleException("INVALID_WEIGHT", "Weight allows at most 2 decimal places.");
        }
        return weight.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
