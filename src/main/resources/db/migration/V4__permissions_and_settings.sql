-- V4: permissions are data (editable per role) and system settings are data (editable in the app).

-- ---------------------------------------------------------------- role permissions
CREATE TABLE role_permissions (
    job_role_id BIGINT      NOT NULL,
    permission  VARCHAR(40) NOT NULL,
    PRIMARY KEY (job_role_id, permission),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (job_role_id) REFERENCES job_roles (id)
) ENGINE = InnoDB;

-- Seed every existing role from the template of its access level, so nobody gains or loses anything
-- by the upgrade. Every level gets the clerk set...
INSERT INTO role_permissions (job_role_id, permission)
SELECT r.id, t.permission
FROM job_roles r
         JOIN (SELECT 'DELIVERY_CREATE' AS permission
               UNION ALL SELECT 'DELIVERY_CORRECT_WEIGHT'
               UNION ALL SELECT 'DELIVERY_GRADE'
               UNION ALL SELECT 'DELIVERY_REJECT'
               UNION ALL SELECT 'FARMER_MANAGE') t;

-- ...supervisors and administrators also run the station...
INSERT INTO role_permissions (job_role_id, permission)
SELECT r.id, t.permission
FROM job_roles r
         JOIN (SELECT 'DELIVERY_PAY' AS permission
               UNION ALL SELECT 'PRICE_MANAGE'
               UNION ALL SELECT 'GRADE_MANAGE'
               UNION ALL SELECT 'CAPACITY_ADJUST'
               UNION ALL SELECT 'REPORT_VIEW'
               UNION ALL SELECT 'AUDIT_VIEW') t
WHERE r.access_level IN ('SUPERVISOR', 'ADMIN');

-- ...and administrators administer the system.
INSERT INTO role_permissions (job_role_id, permission)
SELECT r.id, t.permission
FROM job_roles r
         JOIN (SELECT 'STATION_MANAGE' AS permission
               UNION ALL SELECT 'USER_MANAGE'
               UNION ALL SELECT 'PERMISSION_MANAGE'
               UNION ALL SELECT 'SETTINGS_MANAGE') t
WHERE r.access_level = 'ADMIN';

-- ---------------------------------------------------------------- system settings
CREATE TABLE system_settings (
    setting_key   VARCHAR(80) NOT NULL,
    setting_value TEXT        NOT NULL,
    updated_at    DATETIME(3) NOT NULL,
    updated_by    BIGINT      NULL,
    PRIMARY KEY (setting_key),
    CONSTRAINT fk_system_settings_user FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE = InnoDB;

-- First-boot values. After this they are edited in the application (Settings page), never in code.
INSERT INTO system_settings (setting_key, setting_value, updated_at) VALUES
    ('organization.name', 'CherryTrack', NOW(3)),
    ('currency.code', 'RWF', NOW(3)),
    ('station.default-timezone', 'Africa/Kigali', NOW(3)),
    ('station.default-daily-capacity-kg', '5000', NOW(3)),
    ('station.default-max-delivery-kg', '500', NOW(3)),
    ('station.default-low-threshold-kg', '500', NOW(3)),
    ('reports.max-range-days', '366', NOW(3)),
    ('delivery.rejection-reasons', 'Unripe cherries\nExcess moisture and debris\nOver-fermented\nForeign matter', NOW(3));
