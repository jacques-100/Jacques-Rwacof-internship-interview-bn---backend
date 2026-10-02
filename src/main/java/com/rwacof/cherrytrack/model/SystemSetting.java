package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/** One editable system setting. Which keys exist and how they are validated is defined by SettingKey. */
@Entity
@Table(name = "system_settings")
@Getter
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 80)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected SystemSetting() {}

    public SystemSetting(String key, String value, Long updatedBy, Instant now) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public void change(String value, Long updatedBy, Instant now) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }
}
