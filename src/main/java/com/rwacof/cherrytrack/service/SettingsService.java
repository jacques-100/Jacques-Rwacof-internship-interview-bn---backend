package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.SettingsDtos.SettingDefinitionDto;
import com.rwacof.cherrytrack.dto.SettingsDtos.SystemSettingsDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.SystemSetting;
import com.rwacof.cherrytrack.repository.SystemSettingRepository;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System-wide settings kept in the database. Anything never changed falls back to the catalogue default,
 * so a fresh install works, but every value shown or used by the application comes from here.
 */
@Service
public class SettingsService {

    private final SystemSettingRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public SettingsService(SystemSettingRepository repository, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, String> values() {
        Map<String, String> stored = new LinkedHashMap<>();
        repository.findAll().forEach(s -> stored.put(s.getKey(), s.getValue()));
        Map<String, String> result = new LinkedHashMap<>();
        for (SettingKey k : SettingKey.values()) {
            result.put(k.key(), stored.getOrDefault(k.key(), k.defaultValue()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public SystemSettingsDto view() {
        List<SettingDefinitionDto> defs = Arrays.stream(SettingKey.values())
                .map(k -> new SettingDefinitionDto(k.key(), k.label(), k.description(), k.type().name(), k.group(), k.defaultValue()))
                .toList();
        return new SystemSettingsDto(values(), defs);
    }

    @Transactional(readOnly = true)
    public String get(SettingKey key) {
        return repository.findById(key.key()).map(SystemSetting::getValue).orElse(key.defaultValue());
    }

    public String currency() {
        return get(SettingKey.CURRENCY_CODE);
    }

    public List<String> rejectionReasons() {
        String raw = get(SettingKey.REJECTION_REASONS);
        return raw.isBlank() ? List.of() : List.of(raw.split("\n"));
    }

    public int maxReportRangeDays() {
        return Integer.parseInt(get(SettingKey.REPORTS_MAX_RANGE_DAYS));
    }

    /** Validates every submitted value first, then stores only what actually changed. */
    @Transactional
    public SystemSettingsDto update(Map<String, String> submitted) {
        Map<String, String> current = values();
        Map<SettingKey, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : submitted.entrySet()) {
            SettingKey key = SettingKey.byKey(e.getKey())
                    .orElseThrow(() -> new BusinessRuleException("UNKNOWN_SETTING", "Unknown setting '" + e.getKey() + "'."));
            String normalized = key.normalize(e.getValue());
            if (!normalized.equals(current.get(key.key()))) {
                changes.put(key, normalized);
            }
        }
        Long userId = CurrentUser.get().map(u -> u.id()).orElse(null);
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<SettingKey, String> c : changes.entrySet()) {
            SystemSetting row = repository.findById(c.getKey().key()).orElse(null);
            if (row == null) {
                repository.save(new SystemSetting(c.getKey().key(), c.getValue(), userId, clock.instant()));
            } else {
                row.change(c.getValue(), userId, clock.instant());
            }
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", current.get(c.getKey().key()));
            change.put("to", c.getValue());
            details.put(c.getKey().key(), change);
        }
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.SETTINGS_UPDATED, "SETTINGS", 0L,
                    "System settings updated: " + String.join(", ", changes.keySet().stream().map(SettingKey::label).toList()), details);
        }
        repository.flush();
        return view();
    }
}
