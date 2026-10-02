package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One image per owner: the company logo (owner 0) or a user's profile photo (owner = user id). */
@Entity
@Table(name = "stored_images")
public class StoredImage {

    public enum OwnerType { LOGO, AVATAR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoredImage() {}

    public StoredImage(OwnerType ownerType, Long ownerId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
    }

    public void replace(String contentType, byte[] data, Instant now) {
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
        this.updatedAt = now;
    }

    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    public Instant getUpdatedAt() { return updatedAt; }
}
