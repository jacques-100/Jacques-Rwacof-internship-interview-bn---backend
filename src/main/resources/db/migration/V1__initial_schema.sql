-- CherryTrack initial schema.
-- Money is DECIMAL, weights are DECIMAL(8,2). Deliveries are never deleted.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'SUPERVISOR', 'CLERK'))
) ENGINE = InnoDB;

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_refresh_tokens_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE farmers (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    full_name          VARCHAR(120) NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    cooperative_number VARCHAR(30)  NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_farmers_cooperative_number UNIQUE (cooperative_number),
    INDEX idx_farmers_full_name (full_name),
    INDEX idx_farmers_phone (phone)
) ENGINE = InnoDB;

CREATE TABLE grade_prices (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    grade          VARCHAR(1)     NOT NULL,
    price_per_kg   DECIMAL(12, 2) NOT NULL,
    effective_from DATETIME(3)    NOT NULL,
    created_by     BIGINT         NULL,
    created_at     DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_grade_prices_grade_effective UNIQUE (grade, effective_from),
    CONSTRAINT ck_grade_prices_grade CHECK (grade IN ('A', 'B')),
    CONSTRAINT ck_grade_prices_price CHECK (price_per_kg > 0),
    CONSTRAINT fk_grade_prices_user FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE deliveries (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    reference     VARCHAR(30)    NOT NULL,
    farmer_id     BIGINT         NOT NULL,
    delivery_date DATE           NOT NULL,
    weight_kg     DECIMAL(8, 2)  NOT NULL,
    grade         VARCHAR(1)     NULL,
    price_per_kg  DECIMAL(12, 2) NULL,
    amount_owed   DECIMAL(14, 2) NULL,
    status        VARCHAR(10)    NOT NULL,
    reject_reason VARCHAR(500)   NULL,
    created_by    BIGINT         NOT NULL,
    graded_by     BIGINT         NULL,
    paid_by       BIGINT         NULL,
    rejected_by   BIGINT         NULL,
    created_at    DATETIME(3)    NOT NULL,
    updated_at    DATETIME(3)    NOT NULL,
    graded_at     DATETIME(3)    NULL,
    paid_at       DATETIME(3)    NULL,
    rejected_at   DATETIME(3)    NULL,
    version       BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_deliveries_reference UNIQUE (reference),
    CONSTRAINT fk_deliveries_farmer FOREIGN KEY (farmer_id) REFERENCES farmers (id),
    CONSTRAINT fk_deliveries_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_deliveries_graded_by FOREIGN KEY (graded_by) REFERENCES users (id),
    CONSTRAINT fk_deliveries_paid_by FOREIGN KEY (paid_by) REFERENCES users (id),
    CONSTRAINT fk_deliveries_rejected_by FOREIGN KEY (rejected_by) REFERENCES users (id),
    CONSTRAINT ck_deliveries_weight CHECK (weight_kg > 0 AND weight_kg <= 500),
    CONSTRAINT ck_deliveries_status CHECK (status IN ('RECEIVED', 'GRADED', 'PAID', 'REJECTED')),
    CONSTRAINT ck_deliveries_grade CHECK (grade IS NULL OR grade IN ('A', 'B')),
    -- A graded/paid delivery must carry its grade, price snapshot and amount;
    -- received/rejected deliveries must carry none of them.
    CONSTRAINT ck_deliveries_grading CHECK (
        (status IN ('GRADED', 'PAID') AND grade IS NOT NULL AND price_per_kg IS NOT NULL AND amount_owed IS NOT NULL)
        OR (status IN ('RECEIVED', 'REJECTED') AND grade IS NULL AND price_per_kg IS NULL AND amount_owed IS NULL)
    ),
    INDEX idx_deliveries_date (delivery_date),
    INDEX idx_deliveries_date_status (delivery_date, status),
    INDEX idx_deliveries_farmer (farmer_id),
    INDEX idx_deliveries_status (status),
    INDEX idx_deliveries_created_at (created_at)
) ENGINE = InnoDB;

-- One row per day. It is the lock target that serialises intake for that day,
-- stores the running accepted weight and owns the per-day reference sequence.
CREATE TABLE daily_capacity (
    delivery_date DATE          NOT NULL,
    accepted_kg   DECIMAL(10, 2) NOT NULL DEFAULT 0,
    delivery_seq  INT           NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (delivery_date),
    -- Hard backstop for the configured limit (cherrytrack.capacity.daily-limit-kg).
    CONSTRAINT ck_daily_capacity_accepted CHECK (accepted_kg >= 0 AND accepted_kg <= 5000)
) ENGINE = InnoDB;

CREATE TABLE audit_logs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(3)  NOT NULL,
    user_id        BIGINT       NULL,
    username       VARCHAR(50)  NOT NULL,
    action         VARCHAR(40)  NOT NULL,
    entity_type    VARCHAR(30)  NOT NULL,
    entity_id      BIGINT       NOT NULL,
    description    VARCHAR(500) NOT NULL,
    details        JSON         NULL,
    correlation_id VARCHAR(64)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_audit_occurred_at (occurred_at),
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_user (user_id),
    INDEX idx_audit_action (action)
) ENGINE = InnoDB;
