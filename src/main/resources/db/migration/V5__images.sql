-- V5: the company logo and users' profile photos live in the database, like everything else.

CREATE TABLE stored_images (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_type   VARCHAR(20) NOT NULL,
    owner_id     BIGINT      NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    size_bytes   INT         NOT NULL,
    data         LONGBLOB    NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    UNIQUE KEY uq_stored_images_owner (owner_type, owner_id),
    CONSTRAINT ck_stored_images_owner_type CHECK (owner_type IN ('LOGO', 'AVATAR')),
    CONSTRAINT ck_stored_images_size CHECK (size_bytes > 0 AND size_bytes <= 2097152)
) ENGINE = InnoDB;

-- Lets the app show "has a photo" (and bust caches) without loading the image itself.
ALTER TABLE users ADD COLUMN avatar_updated_at DATETIME(3) NULL;
