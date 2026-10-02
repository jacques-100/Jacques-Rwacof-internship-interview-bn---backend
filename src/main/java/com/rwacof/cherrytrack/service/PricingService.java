package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.GradeDtos.GradeDto;
import com.rwacof.cherrytrack.dto.PriceDtos.PriceDto;
import com.rwacof.cherrytrack.dto.PriceDtos.PriceRequest;
import com.rwacof.cherrytrack.dto.PriceDtos.PricesResponse;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.GradePrice;
import com.rwacof.cherrytrack.repository.GradePriceRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Prices are data, not code: each configured grade has a price history. Setting a price appends a
 * record; existing records and the price snapshots stored on graded deliveries never change.
 */
@Service
public class PricingService {

    private final GradePriceRepository repository;
    private final UserRepository userRepository;
    private final GradeService gradeService;
    private final AuditService auditService;
    private final SettingsService settings;
    private final Clock clock;

    public PricingService(GradePriceRepository repository, UserRepository userRepository, GradeService gradeService,
                          AuditService auditService, SettingsService settings, Clock clock) {
        this.settings = settings;
        this.repository = repository;
        this.userRepository = userRepository;
        this.gradeService = gradeService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** The price in effect at {@code at}. Throws if none is configured: a delivery can't be graded without one. */
    @Transactional(readOnly = true)
    public GradePrice priceAt(String grade, Instant at) {
        return find(grade, at).orElseThrow(() -> new BusinessRuleException("PRICE_NOT_CONFIGURED",
                "No price is configured for Grade " + grade + ". Ask a supervisor to set one."));
    }

    @Transactional(readOnly = true)
    public PricesResponse list() {
        Instant now = clock.instant();
        List<PriceDto> current = new ArrayList<>();
        Set<Long> currentIds = new HashSet<>();
        for (GradeDto g : gradeService.list(true)) {
            find(g.code(), now).ifPresent(p -> {
                currentIds.add(p.getId());
                current.add(PriceDto.of(p, true));
            });
        }
        List<PriceDto> history = repository.findAllByOrderByEffectiveFromDescIdDesc().stream()
                .map(p -> PriceDto.of(p, currentIds.contains(p.getId())))
                .toList();
        return new PricesResponse(current, history);
    }

    /**
     * Adds a new price record. A past effective date is clamped to "now" so history can't be rewritten.
     */
    @Transactional
    public PriceDto create(PriceRequest request) {
        String grade = gradeService.requireActive(request.grade()).getCode();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant effective = request.effectiveFrom() == null || request.effectiveFrom().isBefore(now)
                ? now : request.effectiveFrom().truncatedTo(ChronoUnit.MILLIS);
        BigDecimal price = request.pricePerKg().setScale(2, java.math.RoundingMode.UNNECESSARY);

        Optional<GradePrice> previous = find(grade, effective);
        var by = userRepository.getReferenceById(CurrentUser.require().id());
        GradePrice saved = repository.saveAndFlush(new GradePrice(grade, price, effective, by, now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("grade", grade);
        details.put("pricePerKg", price);
        details.put("effectiveFrom", effective.toString());
        previous.ifPresent(p -> details.put("previousPricePerKg", p.getPricePerKg()));
        auditService.record(AuditAction.PRICE_CHANGED, "PRICE", saved.getId(),
                "Grade " + grade + " price set to " + price.toPlainString() + " " + settings.currency() + "/kg"
                        + previous.map(p -> " (was " + p.getPricePerKg().toPlainString() + ")").orElse(""),
                details);
        return PriceDto.of(saved, !effective.isAfter(now));
    }

    private Optional<GradePrice> find(String grade, Instant at) {
        return repository.findFirstByGradeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(grade, at);
    }
}
