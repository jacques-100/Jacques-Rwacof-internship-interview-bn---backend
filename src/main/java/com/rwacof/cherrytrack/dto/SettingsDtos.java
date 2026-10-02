package com.rwacof.cherrytrack.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class SettingsDtos {

    private SettingsDtos() {}

    public record SettingDefinitionDto(String key, String label, String description, String type, String group, String defaultValue) {}

    /** Current values (defaults filled in for anything never changed) plus how to render and describe each. */
    public record SystemSettingsDto(Map<String, String> values, List<SettingDefinitionDto> definitions) {}

    public record UpdateSettingsRequest(@NotNull Map<String, String> values) {}
}
