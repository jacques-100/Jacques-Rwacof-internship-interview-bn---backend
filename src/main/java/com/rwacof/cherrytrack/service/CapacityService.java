package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.CapacityDtos.AlertLevel;
import com.rwacof.cherrytrack.dto.CapacityDtos.CapacityDto;
import com.rwacof.cherrytrack.dto.CapacityDtos.CapacityPreviewDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.CapacityExceededException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.DailyCapacity;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.repository.DailyCapacityRepository;
import com.rwacof.cherrytrack.repository.DeliveryRepository;
import com.rwacof.cherrytrack.repository.StatusStat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the daily-capacity invariant (accepted weight &lt;= the day's limit) for each station.
 *
 * <p>Write path: {@link #lockOrCreate} / {@link #lockExisting} take an exclusive row lock on the
 * station-day's capacity row; every operation that can change accepted weight (create, weight
 * correction, reject, limit change) goes through it, so those operations are serialised per station
 * and day and the limit cannot be exceeded by concurrent requests. Always acquire this lock before
 * touching delivery rows, to keep a single lock order.
 */
@Service
public class CapacityService {

    private final DailyCapacityRepository capacityRepository;
    private final DeliveryRepository deliveryRepository;
    private final AuditService auditService;
    private final StationAccessService stationAccess;
    private final Clock clock;

    public CapacityService(DailyCapacityRepository capacityRepository, DeliveryRepository deliveryRepository,
                           AuditService auditService, StationAccessService stationAccess, Clock clock) {
        this.capacityRepository = capacityRepository;
        this.deliveryRepository = deliveryRepository;
        this.auditService = auditService;
        this.stationAccess = stationAccess;
        this.clock = clock;
    }

    public LocalDate today(Station station) {
        return stationAccess.today(station);
    }

    // ------------------------------------------------------------------ write path

    /** Locks the day's row, creating it first (with the station's default limit) when the day is new. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DailyCapacity lockOrCreate(Station station, LocalDate date) {
        capacityRepository.ensureRow(station.getId(), date, station.getDailyCapacityKg());
        return capacityRepository.findForUpdate(station.getId(), date)
                .orElseThrow(() -> new IllegalStateException("Capacity row missing for " + station.getCode() + " " + date));
    }

    /** Locks a row that must already exist (any delivery's day does). */
    @Transactional(propagation = Propagation.MANDATORY)
    public DailyCapacity lockExisting(Long stationId, LocalDate date) {
        return capacityRepository.findForUpdate(stationId, date)
                .orElseThrow(() -> new IllegalStateException("Capacity row missing for station " + stationId + " " + date));
    }

    /** Reserves weight on a locked row and returns the day's next delivery sequence number. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reserve(DailyCapacity locked, BigDecimal kg) {
        checkFits(locked, kg, "Recording this delivery");
        locked.addWeight(kg, clock.instant());
        return locked.nextSequence(clock.instant());
    }

    /** Applies a weight change (positive or negative) on a locked row, enforcing the limit when it grows. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adjust(DailyCapacity locked, BigDecimal deltaKg) {
        if (deltaKg.signum() > 0) {
            checkFits(locked, deltaKg, "This weight correction");
        }
        locked.addWeight(deltaKg, clock.instant());
    }

    /** Releases weight of a rejected delivery. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(DailyCapacity locked, BigDecimal kg) {
        locked.addWeight(kg.negate(), clock.instant());
    }

    private void checkFits(DailyCapacity locked, BigDecimal kg, String what) {
        BigDecimal remaining = locked.getLimitKg().subtract(locked.getAcceptedKg());
        if (kg.compareTo(remaining) > 0) {
            throw new CapacityExceededException(
                    what + " would exceed the daily station capacity of " + plain(locked.getLimitKg()) + " kg on "
                            + locked.getDeliveryDate() + ". Remaining capacity: " + plain(remaining.max(BigDecimal.ZERO)) + " kg.");
        }
    }

    /**
     * Raises or lowers the limit for a single date (for example when extra drying capacity is available).
     * The new limit can never be lower than the weight already accepted that day.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CapacityDto adjustLimit(Station station, LocalDate date, BigDecimal newLimit, String reason) {
        if (date.isAfter(today(station))) {
            throw new BusinessRuleException("DATE_IN_FUTURE", "Capacity can only be adjusted for today or a past day.");
        }
        DailyCapacity row = lockOrCreate(station, date);
        if (newLimit.compareTo(row.getAcceptedKg()) < 0) {
            throw new BusinessRuleException("LIMIT_BELOW_ACCEPTED",
                    "The limit cannot be lower than the " + plain(row.getAcceptedKg()) + " kg already accepted on " + date + ".");
        }
        BigDecimal previous = row.getLimitKg();
        row.changeLimit(newLimit.setScale(2, RoundingMode.UNNECESSARY), clock.instant());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", date.toString());
        details.put("previousLimitKg", previous);
        details.put("newLimitKg", newLimit);
        details.put("reason", reason.trim());
        auditService.record(station.getId(), AuditAction.CAPACITY_ADJUSTED, "STATION", station.getId(),
                "Capacity for " + date + " changed " + plain(previous) + " → " + plain(newLimit) + " kg. Reason: " + reason.trim(), details);
        return getDay(station, date);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ read path

    private BigDecimal limitFor(Station station, DailyCapacity row) {
        return row != null ? row.getLimitKg() : station.getDailyCapacityKg();
    }

    @Transactional(readOnly = true)
    public CapacityPreviewDto preview(Station station, LocalDate date) {
        DailyCapacity row = capacityRepository.findByStationIdAndDeliveryDate(station.getId(), date).orElse(null);
        BigDecimal accepted = row == null ? BigDecimal.ZERO : row.getAcceptedKg();
        BigDecimal limit = limitFor(station, row);
        return new CapacityPreviewDto(station.getId(), date, accepted, limit, limit.subtract(accepted).max(BigDecimal.ZERO));
    }

    @Transactional(readOnly = true)
    public CapacityDto getDay(Station station, LocalDate date) {
        DailyCapacity row = capacityRepository.findByStationIdAndDeliveryDate(station.getId(), date).orElse(null);
        BigDecimal limit = limitFor(station, row);
        BigDecimal accepted = row == null ? BigDecimal.ZERO : row.getAcceptedKg();
        BigDecimal remaining = limit.subtract(accepted).max(BigDecimal.ZERO);
        BigDecimal utilization = accepted.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);

        List<StatusStat> stats = deliveryRepository.statsByDate(station.getId(), date);
        Map<DeliveryStatus, Long> counts = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus s : DeliveryStatus.values()) {
            counts.put(s, 0L);
        }
        long total = 0;
        long acceptedCount = 0;
        BigDecimal rejectedKg = BigDecimal.ZERO;
        BigDecimal amountOwed = BigDecimal.ZERO;
        BigDecimal amountPaid = BigDecimal.ZERO;
        for (StatusStat s : stats) {
            counts.put(s.status(), s.count());
            total += s.count();
            if (s.status().countsTowardCapacity()) {
                acceptedCount += s.count();
            } else {
                rejectedKg = rejectedKg.add(s.weightKg());
            }
            if (s.status() == DeliveryStatus.GRADED || s.status() == DeliveryStatus.PAID) {
                amountOwed = amountOwed.add(s.amount());
            }
            if (s.status() == DeliveryStatus.PAID) {
                amountPaid = amountPaid.add(s.amount());
            }
        }
        BigDecimal average = acceptedCount == 0 ? BigDecimal.ZERO
                : accepted.divide(BigDecimal.valueOf(acceptedCount), 2, RoundingMode.HALF_UP);

        AlertLevel alert = remaining.signum() == 0 ? AlertLevel.FULL
                : remaining.compareTo(station.getLowThresholdKg()) <= 0 ? AlertLevel.LOW : AlertLevel.NONE;

        return new CapacityDto(station.getId(), date, limit, accepted, remaining, utilization, alert, total, counts,
                rejectedKg, average, amountOwed, amountPaid);
    }
}
