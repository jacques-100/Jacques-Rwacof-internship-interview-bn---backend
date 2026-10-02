package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.GradeDtos.GradeDto;
import com.rwacof.cherrytrack.dto.GradeDtos.GradeRequest;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.GradeDefinition;
import com.rwacof.cherrytrack.repository.GradeDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The grades the station can award. Managed in the application, not in code. */
@Service
public class GradeService {

    private final GradeDefinitionRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public GradeService(GradeDefinitionRepository repository, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GradeDto> list(boolean activeOnly) {
        return (activeOnly ? repository.findByActiveTrueOrderBySortOrderAscCodeAsc() : repository.findAllByOrderBySortOrderAscCodeAsc())
                .stream().map(GradeDto::of).toList();
    }

    /** Returns the grade, or throws if it doesn't exist or has been switched off. */
    @Transactional(readOnly = true)
    public GradeDefinition requireActive(String code) {
        GradeDefinition grade = repository.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_GRADE", "Grade '" + code + "' does not exist."));
        if (!grade.isActive()) {
            throw new BusinessRuleException("GRADE_INACTIVE", grade.getName() + " is switched off and cannot be awarded.");
        }
        return grade;
    }

    @Transactional(readOnly = true)
    public GradeDefinition require(String code) {
        return repository.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_GRADE", "Grade '" + code + "' does not exist."));
    }

    @Transactional
    public GradeDto create(GradeRequest request) {
        String code = request.code().trim().toUpperCase();
        if (repository.existsByCode(code)) {
            throw new ConflictException("DUPLICATE_GRADE", "Grade code " + code + " already exists.");
        }
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw new ConflictException("DUPLICATE_GRADE_NAME", "A grade named '" + request.name().trim() + "' already exists.");
        }
        int order = request.sortOrder() != null ? request.sortOrder() : (int) repository.count() + 1;
        GradeDefinition saved = repository.saveAndFlush(new GradeDefinition(code, request.name().trim(), blankToNull(request.description()), order, clock.instant()));
        auditService.record(AuditAction.GRADE_CREATED, "GRADE", saved.getId(), "Grade " + code + " (" + saved.getName() + ") created",
                Map.of("code", code, "name", saved.getName()));
        return GradeDto.of(saved);
    }

    @Transactional
    public GradeDto update(Long id, GradeRequest request) {
        GradeDefinition grade = repository.findById(id).orElseThrow(() -> new NotFoundException("Grade", id));
        if (!grade.getCode().equalsIgnoreCase(request.code().trim())) {
            throw new BusinessRuleException("GRADE_CODE_FIXED", "A grade's code cannot change because deliveries and prices refer to it.");
        }
        repository.findAll().stream()
                .filter(g -> !g.getId().equals(id) && g.getName().equalsIgnoreCase(request.name().trim())).findAny()
                .ifPresent(g -> { throw new ConflictException("DUPLICATE_GRADE_NAME", "A grade named '" + request.name().trim() + "' already exists."); });

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "name", grade.getName(), request.name().trim());
        track(changes, "description", grade.getDescription(), blankToNull(request.description()));
        if (request.active() != null) {
            track(changes, "active", grade.isActive(), request.active());
            grade.setActive(request.active());
        }
        if (request.sortOrder() != null) {
            track(changes, "sortOrder", grade.getSortOrder(), request.sortOrder());
            grade.setSortOrder(request.sortOrder());
        }
        grade.setName(request.name().trim());
        grade.setDescription(blankToNull(request.description()));
        grade.touch(clock.instant());
        repository.saveAndFlush(grade);
        auditService.record(AuditAction.GRADE_UPDATED, "GRADE", id, "Grade " + grade.getCode() + " updated", changes);
        return GradeDto.of(grade);
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", before);
            change.put("to", after);
            changes.put(field, change);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
