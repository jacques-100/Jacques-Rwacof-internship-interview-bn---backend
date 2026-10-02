-- V2: multi-station operation, configurable grades, adjustable capacity, staff directory, profiles.
-- Upgrades an existing V1 database in place: existing deliveries, capacity rows and users are
-- attached to a default station so no history is lost. A fresh database simply starts empty.

-- ---------------------------------------------------------------- stations
CREATE TABLE stations (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    code              VARCHAR(10)    NOT NULL,
    name              VARCHAR(120)   NOT NULL,
    location          VARCHAR(160)   NULL,
    timezone          VARCHAR(40)    NOT NULL DEFAULT 'Africa/Kigali',
    daily_capacity_kg DECIMAL(10, 2) NOT NULL,
    max_delivery_kg   DECIMAL(8, 2)  NOT NULL,
    low_threshold_kg  DECIMAL(10, 2) NOT NULL,
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        DATETIME(3)    NOT NULL,
    updated_at        DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_stations_code UNIQUE (code),
    CONSTRAINT uq_stations_name UNIQUE (name),
    CONSTRAINT ck_stations_capacity CHECK (daily_capacity_kg > 0),
    CONSTRAINT ck_stations_max_delivery CHECK (max_delivery_kg > 0),
    CONSTRAINT ck_stations_threshold CHECK (low_threshold_kg >= 0)
) ENGINE = InnoDB;

-- Only when the database already holds operational data (an upgrade): keep it under one station.
INSERT INTO stations (code, name, location, timezone, daily_capacity_kg, max_delivery_kg, low_threshold_kg, active, created_at, updated_at)
SELECT 'MAIN', 'Main Washing Station', NULL, 'Africa/Kigali', 5000, 500, 500, TRUE, NOW(3), NOW(3)
FROM DUAL
WHERE EXISTS (SELECT 1 FROM deliveries) OR EXISTS (SELECT 1 FROM daily_capacity);

-- ---------------------------------------------------------------- grades (configurable)
CREATE TABLE grades (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(10)  NOT NULL,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(255) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_grades_code UNIQUE (code),
    CONSTRAINT uq_grades_name UNIQUE (name)
) ENGINE = InnoDB;

INSERT INTO grades (code, name, description, active, sort_order, created_at, updated_at) VALUES
    ('A', 'Grade A', 'Premium cherries: ripe, clean and uniform.', TRUE, 1, NOW(3), NOW(3)),
    ('B', 'Grade B', 'Standard cherries.', TRUE, 2, NOW(3), NOW(3));

-- grade_prices: grade is now any configured grade code
ALTER TABLE grade_prices DROP CHECK ck_grade_prices_grade;
ALTER TABLE grade_prices MODIFY grade VARCHAR(10) NOT NULL;
ALTER TABLE grade_prices ADD CONSTRAINT fk_grade_prices_grade FOREIGN KEY (grade) REFERENCES grades (code);

-- ---------------------------------------------------------------- staff directory
CREATE TABLE job_roles (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(255) NULL,
    -- What the role may do in the system. Authorisation is always decided from this level.
    access_level VARCHAR(20)  NOT NULL,
    system_role  BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_job_roles_name UNIQUE (name),
    CONSTRAINT ck_job_roles_level CHECK (access_level IN ('ADMIN', 'SUPERVISOR', 'CLERK'))
) ENGINE = InnoDB;

INSERT INTO job_roles (name, description, access_level, system_role, active, created_at, updated_at) VALUES
    ('Administrator', 'Full access, including stations, users and configuration.', 'ADMIN', TRUE, TRUE, NOW(3), NOW(3)),
    ('Supervisor', 'Runs a station: payments, prices, reports and audit.', 'SUPERVISOR', TRUE, TRUE, NOW(3), NOW(3)),
    ('Clerk', 'Daily operations: receiving, grading and rejecting deliveries.', 'CLERK', TRUE, TRUE, NOW(3), NOW(3));

ALTER TABLE users
    ADD COLUMN email       VARCHAR(160) NULL,
    ADD COLUMN phone       VARCHAR(20)  NULL,
    ADD COLUMN job_role_id BIGINT       NULL;

UPDATE users u JOIN job_roles r ON r.access_level = u.role AND r.system_role = TRUE SET u.job_role_id = r.id;

ALTER TABLE users MODIFY job_role_id BIGINT NOT NULL;
ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email),
    ADD CONSTRAINT fk_users_job_role FOREIGN KEY (job_role_id) REFERENCES job_roles (id);

CREATE TABLE departments (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(255) NULL,
    head_user_id BIGINT       NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_departments_code UNIQUE (code),
    CONSTRAINT uq_departments_name UNIQUE (name),
    CONSTRAINT fk_departments_head FOREIGN KEY (head_user_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- Which stations a user is assigned to. Being assigned to a station means managing it.
CREATE TABLE user_stations (
    user_id    BIGINT NOT NULL,
    station_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, station_id),
    CONSTRAINT fk_user_stations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_stations_station FOREIGN KEY (station_id) REFERENCES stations (id),
    INDEX idx_user_stations_station (station_id)
) ENGINE = InnoDB;

INSERT INTO user_stations (user_id, station_id) SELECT u.id, s.id FROM users u JOIN stations s;

CREATE TABLE employments (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    department_id   BIGINT       NULL,
    station_id      BIGINT       NULL,
    job_title       VARCHAR(100) NOT NULL,
    employment_type VARCHAR(20)  NOT NULL,
    start_date      DATE         NOT NULL,
    end_date        DATE         NULL,
    notes           VARCHAR(500) NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_employments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_employments_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_employments_station FOREIGN KEY (station_id) REFERENCES stations (id),
    CONSTRAINT ck_employments_type CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'SEASONAL', 'CONTRACT')),
    CONSTRAINT ck_employments_dates CHECK (end_date IS NULL OR end_date >= start_date),
    INDEX idx_employments_user (user_id),
    INDEX idx_employments_department (department_id),
    INDEX idx_employments_station (station_id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- deliveries
ALTER TABLE deliveries DROP CHECK ck_deliveries_grade;
ALTER TABLE deliveries DROP CHECK ck_deliveries_grading;
ALTER TABLE deliveries DROP CHECK ck_deliveries_weight;

ALTER TABLE deliveries MODIFY grade VARCHAR(10) NULL;
ALTER TABLE deliveries
    ADD COLUMN station_id       BIGINT        NULL,
    ADD COLUMN moisture_percent DECIMAL(4, 1) NULL,
    ADD COLUMN grade_notes      VARCHAR(500)  NULL;

UPDATE deliveries SET station_id = (SELECT id FROM stations ORDER BY id LIMIT 1);

ALTER TABLE deliveries MODIFY station_id BIGINT NOT NULL;
ALTER TABLE deliveries
    ADD CONSTRAINT fk_deliveries_station FOREIGN KEY (station_id) REFERENCES stations (id),
    ADD CONSTRAINT fk_deliveries_grade FOREIGN KEY (grade) REFERENCES grades (code),
    -- The upper bound is now a per-station setting enforced by the application.
    ADD CONSTRAINT ck_deliveries_weight CHECK (weight_kg > 0),
    ADD CONSTRAINT ck_deliveries_moisture CHECK (moisture_percent IS NULL OR (moisture_percent >= 0 AND moisture_percent <= 100)),
    ADD CONSTRAINT ck_deliveries_grading CHECK (
        (status IN ('GRADED', 'PAID') AND grade IS NOT NULL AND price_per_kg IS NOT NULL AND amount_owed IS NOT NULL)
        OR (status IN ('RECEIVED', 'REJECTED') AND grade IS NULL AND price_per_kg IS NULL AND amount_owed IS NULL)
    ),
    ADD INDEX idx_deliveries_station_date (station_id, delivery_date, status);

-- ---------------------------------------------------------------- daily capacity per station
ALTER TABLE daily_capacity DROP CHECK ck_daily_capacity_accepted;
ALTER TABLE daily_capacity
    ADD COLUMN station_id BIGINT         NULL,
    ADD COLUMN limit_kg   DECIMAL(10, 2) NOT NULL DEFAULT 5000;

UPDATE daily_capacity SET station_id = (SELECT id FROM stations ORDER BY id LIMIT 1);

ALTER TABLE daily_capacity
    DROP PRIMARY KEY,
    MODIFY station_id BIGINT NOT NULL,
    ADD PRIMARY KEY (station_id, delivery_date),
    ADD CONSTRAINT fk_daily_capacity_station FOREIGN KEY (station_id) REFERENCES stations (id),
    -- The limit is stored per day (copied from the station when the day starts) so it can be adjusted
    -- for one date without rewriting the utilisation history of other days.
    ADD CONSTRAINT ck_daily_capacity_accepted CHECK (accepted_kg >= 0 AND accepted_kg <= limit_kg);
ALTER TABLE daily_capacity ALTER COLUMN limit_kg DROP DEFAULT;

-- ---------------------------------------------------------------- audit logs per station
ALTER TABLE audit_logs ADD COLUMN station_id BIGINT NULL;
UPDATE audit_logs a JOIN deliveries d ON a.entity_type = 'DELIVERY' AND a.entity_id = d.id SET a.station_id = d.station_id;
ALTER TABLE audit_logs
    ADD CONSTRAINT fk_audit_logs_station FOREIGN KEY (station_id) REFERENCES stations (id),
    ADD INDEX idx_audit_station (station_id);
