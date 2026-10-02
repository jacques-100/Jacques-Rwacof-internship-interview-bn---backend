package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Append-only. There is intentionally no setter and no update/delete path. */
@Entity
@Table(name = "audit_logs")
@Getter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false, length = 50)
    private String username;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 40)
    private AuditAction action;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 30)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private Long entityId;

    @Column(nullable = false, updatable = false, length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "json")
    private String details;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "station_id", updatable = false)
    private Long stationId;

    protected AuditLog() {}

    public AuditLog(Instant occurredAt, Long userId, String username, AuditAction action, String entityType,
                    Long entityId, String description, String details, String correlationId, Long stationId) {
        this.occurredAt = occurredAt;
        this.userId = userId;
        this.username = username;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.description = description;
        this.details = details;
        this.correlationId = correlationId;
        this.stationId = stationId;
    }
}
