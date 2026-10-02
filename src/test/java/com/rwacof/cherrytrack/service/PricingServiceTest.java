package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.model.GradePrice;
import com.rwacof.cherrytrack.repository.GradePriceRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final GradePriceRepository repository = mock(GradePriceRepository.class);
    private final PricingService service = new PricingService(repository, mock(UserRepository.class),
            mock(GradeService.class), mock(AuditService.class), mock(SettingsService.class), Clock.fixed(NOW, ZoneId.of("UTC")));

    @Test
    void returnsThePriceInEffectAtTheRequestedInstant() {
        GradePrice price = new GradePrice("A", new BigDecimal("1200.00"), Instant.parse("2026-01-01T00:00:00Z"), null, NOW);
        when(repository.findFirstByGradeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc("A", NOW))
                .thenReturn(Optional.of(price));
        assertThat(service.priceAt("A", NOW).getPricePerKg()).isEqualByComparingTo("1200");
    }

    @Test
    void failsWithAClearBusinessErrorWhenNoPriceExists() {
        when(repository.findFirstByGradeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any(), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.priceAt("B", NOW))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Grade B");
    }
}
