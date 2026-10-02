package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.SettingsDtos.SystemSettingsDto;
import com.rwacof.cherrytrack.dto.SettingsDtos.UpdateSettingsRequest;
import com.rwacof.cherrytrack.service.SettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system-settings")
@Tag(name = "System settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** Readable by everyone signed in: every screen needs the currency, clerks need the rejection reasons. */
    @GetMapping
    public SystemSettingsDto get() {
        return settings.view();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public SystemSettingsDto update(@Valid @RequestBody UpdateSettingsRequest request) {
        return settings.update(request.values());
    }
}
