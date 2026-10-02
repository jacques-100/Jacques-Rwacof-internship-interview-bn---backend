package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.CapacityDtos.CapacityDto;
import com.rwacof.cherrytrack.dto.DashboardDto;
import com.rwacof.cherrytrack.dto.DashboardDto.StatusSlice;
import com.rwacof.cherrytrack.dto.DashboardDto.TrendPoint;
import com.rwacof.cherrytrack.model.DailyCapacity;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.repository.DailyCapacityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final int TREND_DAYS = 7;

    private final CapacityService capacityService;
    private final DeliveryService deliveryService;
    private final DailyCapacityRepository capacityRepository;

    public DashboardService(CapacityService capacityService, DeliveryService deliveryService,
                            DailyCapacityRepository capacityRepository) {
        this.capacityService = capacityService;
        this.deliveryService = deliveryService;
        this.capacityRepository = capacityRepository;
    }

    @Transactional(readOnly = true)
    public DashboardDto dashboard(Station station, LocalDate date) {
        LocalDate day = date == null ? capacityService.today(station) : date;
        CapacityDto capacity = capacityService.getDay(station, day);

        Map<LocalDate, BigDecimal> byDate = new HashMap<>();
        for (DailyCapacity c : capacityRepository.findByStationIdAndDeliveryDateBetweenOrderByDeliveryDateAsc(
                station.getId(), day.minusDays(TREND_DAYS - 1), day)) {
            byDate.put(c.getDeliveryDate(), c.getAcceptedKg());
        }
        List<TrendPoint> trend = new ArrayList<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = day.minusDays(i);
            trend.add(new TrendPoint(d, byDate.getOrDefault(d, BigDecimal.ZERO)));
        }

        List<StatusSlice> distribution = new ArrayList<>();
        for (DeliveryStatus s : DeliveryStatus.values()) {
            distribution.add(new StatusSlice(s, capacity.statusCounts().getOrDefault(s, 0L)));
        }
        return new DashboardDto(capacity, trend, distribution, deliveryService.recent(station, 10));
    }
}
