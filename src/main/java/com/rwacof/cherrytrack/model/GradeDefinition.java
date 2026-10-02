package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A grade the station can award (A, B, AA, "Peaberry", ...). The code is what deliveries and
 * prices store, so it never changes once created; name, description, order and active can.
 */
@Entity
@Table(name = "grades")
@Getter
public class GradeDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10, updatable = false)
    private String code;

    @Setter
    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Setter
    @Column(length = 255)
    private String description;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Setter
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GradeDefinition() {}

    public GradeDefinition(String code, String name, String description, int sortOrder, Instant now) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
