package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.AuditDtos.AuditLogDto;
import com.rwacof.cherrytrack.dto.PageResponse;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import com.rwacof.cherrytrack.service.AuditService;
import com.rwacof.cherrytrack.service.AuditService.AuditFilter;
import com.rwacof.cherrytrack.service.StationAccessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Read-only. There are deliberately no write endpoints for audit logs. Administrators see every entry;
 * supervisors only see entries of the stations they are assigned to.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
@Tag(name = "Audit")
public class AuditController {

    private final AuditService auditService;
    private final StationAccessService stationAccess;

    public AuditController(AuditService auditService, StationAccessService stationAccess) {
        this.auditService = auditService;
        this.stationAccess = stationAccess;
    }

    @GetMapping
    public PageResponse<AuditLogDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long stationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AuthUser actor = CurrentUser.require();
        if (stationId != null) {
            stationAccess.requireAccess(actor, stationId);
        }
        var restrict = actor.role() == Role.ADMIN ? null : stationAccess.accessibleIds(actor);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return auditService.search(new AuditFilter(from, to, userId, username, action, entityType, stationId, restrict), pageable);
    }
}
