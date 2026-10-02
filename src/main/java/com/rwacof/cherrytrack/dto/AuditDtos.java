package com.rwacof.cherrytrack.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.rwacof.cherrytrack.model.AuditAction;

import java.time.Instant;

public final class AuditDtos {

    private AuditDtos() {}

    public record AuditLogDto(Long id, Instant occurredAt, Long userId, String username, AuditAction action,
                              String entityType, Long entityId, String description, JsonNode details) {}
}
