package com.rwacof.cherrytrack.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rwacof.cherrytrack.dto.AuditDtos.AuditLogDto;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.AuditLog;
import com.rwacof.cherrytrack.repository.AuditLogRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuditService {

    /**
     * {@code stationId} narrows to one station. {@code restrictToStations}, when not null, limits the
     * result to entries of those stations (used for non-administrators); entries that belong to no
     * station (users, prices, grades...) are then excluded.
     */
    public record AuditFilter(LocalDate from, LocalDate to, Long userId, String username, AuditAction action,
                              String entityType, Long stationId, Set<Long> restrictToStations) {}

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** An organisation-wide entry (not tied to a station), such as a user or price change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entityType, Long entityId, String description, Map<String, Object> details) {
        record(null, action, entityType, entityId, description, details);
    }

    /**
     * Writes an audit entry in the caller's transaction, so a business change and its audit record
     * commit or roll back together. MANDATORY makes calling it outside a transaction an error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long stationId, AuditAction action, String entityType, Long entityId, String description,
                       Map<String, Object> details) {
        AuthUser user = CurrentUser.get().orElse(null);
        AuditLog entry = new AuditLog(
                clock.instant(),
                user == null ? null : user.id(),
                user == null ? "system" : user.username(),
                action, entityType, entityId, description,
                toJson(details), MDC.get("correlationId"), stationId);
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> search(AuditFilter filter, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (filter.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"),
                        filter.from().atStartOfDay(clock.getZone()).toInstant()));
            }
            if (filter.to() != null) {
                p.add(cb.lessThan(root.get("occurredAt"),
                        filter.to().plusDays(1).atStartOfDay(clock.getZone()).toInstant()));
            }
            if (filter.userId() != null) {
                p.add(cb.equal(root.get("userId"), filter.userId()));
            }
            if (filter.username() != null && !filter.username().isBlank()) {
                p.add(cb.equal(cb.lower(root.get("username")), filter.username().trim().toLowerCase()));
            }
            if (filter.action() != null) {
                p.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.entityType() != null && !filter.entityType().isBlank()) {
                p.add(cb.equal(root.get("entityType"), filter.entityType().toUpperCase()));
            }
            if (filter.stationId() != null) {
                p.add(cb.equal(root.get("stationId"), filter.stationId()));
            }
            if (filter.restrictToStations() != null) {
                p.add(filter.restrictToStations().isEmpty() ? cb.disjunction() : root.get("stationId").in(filter.restrictToStations()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return PageResponse.of(repository.findAll(spec, pageable), this::toDto);
    }

    @Transactional(readOnly = true)
    public List<AuditLogDto> forEntity(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(entityType, entityId)
                .stream().map(this::toDto).toList();
    }

    private AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getOccurredAt(), a.getUserId(), a.getUsername(), a.getAction(),
                a.getEntityType(), a.getEntityId(), a.getDescription(), parse(a.getDetails()));
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise audit details", e);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
