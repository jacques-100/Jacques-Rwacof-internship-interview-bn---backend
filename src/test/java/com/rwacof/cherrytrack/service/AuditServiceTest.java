package com.rwacof.cherrytrack.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.AuditLog;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.repository.AuditLogRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Africa/Kigali"));
    private final AuditService service = new AuditService(repository, new ObjectMapper(), clock);

    @Test
    void recordsActorTimeActionAndJsonDetails() {
        MDC.put("correlationId", "corr-1");
        try {
            CurrentUser.runAs(new AuthUser(7L, "clerk1", Role.CLERK, com.rwacof.cherrytrack.model.Permission.template(Role.CLERK)), () ->
                    service.record(AuditAction.DELIVERY_CREATED, "DELIVERY", 42L, "Delivery created - 350 kg",
                            Map.of("weightKg", 350)));
        } finally {
            MDC.remove("correlationId");
        }
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getUsername()).isEqualTo("clerk1");
        assertThat(saved.getAction()).isEqualTo(AuditAction.DELIVERY_CREATED);
        assertThat(saved.getEntityType()).isEqualTo("DELIVERY");
        assertThat(saved.getEntityId()).isEqualTo(42L);
        assertThat(saved.getOccurredAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(saved.getDetails()).isEqualTo("{\"weightKg\":350}");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void fallsBackToSystemWhenThereIsNoAuthenticatedUser() {
        service.record(AuditAction.PRICE_CHANGED, "PRICE", 1L, "x", null);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("system");
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getDetails()).isNull();
    }
}
