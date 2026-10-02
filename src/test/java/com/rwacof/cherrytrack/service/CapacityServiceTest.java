package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.CapacityExceededException;
import com.rwacof.cherrytrack.model.DailyCapacity;
import com.rwacof.cherrytrack.model.DailyCapacityId;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.repository.DailyCapacityRepository;
import com.rwacof.cherrytrack.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapacityServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final DailyCapacityRepository capacityRepository = mock(DailyCapacityRepository.class);
    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private CapacityService service;
    private Station station;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Africa/Kigali"));
        station = new Station("TST", "Test", null, "Africa/Kigali", new BigDecimal("5000"), new BigDecimal("500"), new BigDecimal("500"), NOW);
        setField(station, "id", 1L);
        service = new CapacityService(capacityRepository, deliveryRepository, mock(AuditService.class),
                new StationAccessService(null, clock), clock);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A real (not mocked) capacity row already holding {@code accepted} kg, with the given day limit. */
    private DailyCapacity rowWith(String accepted, String limit) throws Exception {
        var ctor = DailyCapacity.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        DailyCapacity row = ctor.newInstance();
        setField(row, "stationId", 1L);
        setField(row, "deliveryDate", DAY);
        setField(row, "limitKg", new BigDecimal(limit));
        row.addWeight(new BigDecimal(accepted), Instant.EPOCH);
        return row;
    }

    private DailyCapacity rowWith(String accepted) throws Exception {
        return rowWith(accepted, "5000");
    }

    @Test
    void reservesWeightThatFitsAndReturnsIncreasingSequence() throws Exception {
        DailyCapacity row = rowWith("4650");
        assertThat(service.reserve(row, new BigDecimal("200"))).isEqualTo(1);
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("4850");
        assertThat(service.reserve(row, new BigDecimal("150"))).isEqualTo(2);
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("5000");
    }

    @Test
    void acceptsExactlyTheDailyLimit() throws Exception {
        DailyCapacity row = rowWith("4500");
        service.reserve(row, new BigDecimal("500"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("5000");
    }

    @Test
    void rejectsWeightThatWouldExceedTheLimitAndLeavesRowUnchanged() throws Exception {
        DailyCapacity row = rowWith("4700");
        assertThatThrownBy(() -> service.reserve(row, new BigDecimal("300.01")))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("300 kg");
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("4700");
        assertThat(row.getDeliverySeq()).isZero();
    }

    @Test
    void theDaysOwnLimitDecidesNotTheStationDefault() throws Exception {
        DailyCapacity raised = rowWith("5000", "6000");
        service.reserve(raised, new BigDecimal("1000"));                       // fits: this day was raised to 6,000
        assertThat(raised.getAcceptedKg()).isEqualByComparingTo("6000");

        DailyCapacity lowered = rowWith("1000", "1200");
        assertThatThrownBy(() -> service.reserve(lowered, new BigDecimal("300")))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("1200 kg");
    }

    @Test
    void weightCorrectionIncreaseIsCheckedAgainstTheLimit() throws Exception {
        DailyCapacity row = rowWith("4950");
        assertThatThrownBy(() -> service.adjust(row, new BigDecimal("51"))).isInstanceOf(CapacityExceededException.class);
        service.adjust(row, new BigDecimal("50"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("5000");
    }

    @Test
    void weightCorrectionDecreaseAlwaysSucceeds() throws Exception {
        DailyCapacity row = rowWith("5000");
        service.adjust(row, new BigDecimal("-120"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("4880");
    }

    @Test
    void releaseFreesCapacity() throws Exception {
        DailyCapacity row = rowWith("5000");
        service.release(row, new BigDecimal("200"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("4800");
        service.reserve(row, new BigDecimal("200"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("5000");
    }

    @Test
    void limitCannotBeLoweredBelowWhatWasAlreadyAccepted() throws Exception {
        DailyCapacity row = rowWith("3200");
        when(capacityRepository.findForUpdate(anyLong(), any())).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.adjustLimit(station, DAY, new BigDecimal("3000"), "less drying space"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("3200 kg already accepted");
        assertThat(row.getLimitKg()).isEqualByComparingTo("5000");
    }

    @Test
    void limitCannotBeAdjustedForAFutureDate() {
        assertThatThrownBy(() -> service.adjustLimit(station, DAY.plusDays(1), new BigDecimal("6000"), "x"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void limitCanBeRaisedAndTheNewHeadroomIsUsable() throws Exception {
        DailyCapacity row = rowWith("5000");
        when(capacityRepository.findForUpdate(anyLong(), any())).thenReturn(Optional.of(row));
        when(capacityRepository.findByStationIdAndDeliveryDate(anyLong(), any())).thenReturn(Optional.of(row));
        when(deliveryRepository.statsByDate(anyLong(), any())).thenReturn(List.of());

        var dto = service.adjustLimit(station, DAY, new BigDecimal("6500"), "extra drying beds");
        assertThat(dto.dailyLimitKg()).isEqualByComparingTo("6500");
        assertThat(dto.remainingKg()).isEqualByComparingTo("1500");
        service.reserve(row, new BigDecimal("500"));
        assertThat(row.getAcceptedKg()).isEqualByComparingTo("5500");
    }

    @Test
    void dayViewReportsFullAlertWhenNothingRemains() throws Exception {
        when(capacityRepository.findByStationIdAndDeliveryDate(1L, DAY)).thenReturn(Optional.of(rowWith("5000")));
        when(deliveryRepository.statsByDate(anyLong(), any())).thenReturn(List.of());
        var dto = service.getDay(station, DAY);
        assertThat(dto.alert().name()).isEqualTo("FULL");
        assertThat(dto.remainingKg()).isEqualByComparingTo("0");
        assertThat(dto.utilizationPercent()).isEqualByComparingTo("100.0");
    }

    @Test
    void dayViewReportsLowAlertAtTheStationsThreshold() throws Exception {
        when(capacityRepository.findByStationIdAndDeliveryDate(1L, DAY)).thenReturn(Optional.of(rowWith("4650")));
        when(deliveryRepository.statsByDate(anyLong(), any())).thenReturn(List.of());
        var dto = service.getDay(station, DAY);
        assertThat(dto.alert().name()).isEqualTo("LOW");
        assertThat(dto.remainingKg()).isEqualByComparingTo("350");
        assertThat(dto.utilizationPercent()).isEqualByComparingTo("93.0");
    }

    @Test
    void dayViewForANewDayUsesTheStationDefaultLimit() {
        when(capacityRepository.findByStationIdAndDeliveryDate(1L, DAY)).thenReturn(Optional.empty());
        when(deliveryRepository.statsByDate(anyLong(), any())).thenReturn(List.of());
        var dto = service.getDay(station, DAY);
        assertThat(dto.alert().name()).isEqualTo("NONE");
        assertThat(dto.dailyLimitKg()).isEqualByComparingTo("5000");
        assertThat(dto.acceptedKg()).isEqualByComparingTo("0");
        assertThat(dto.averageAcceptedWeightKg()).isEqualByComparingTo("0");
    }

    @Test
    void idClassEqualityIsByStationAndDate() {
        assertThat(new DailyCapacityId(1L, DAY)).isEqualTo(new DailyCapacityId(1L, DAY)).isNotEqualTo(new DailyCapacityId(2L, DAY));
    }
}
