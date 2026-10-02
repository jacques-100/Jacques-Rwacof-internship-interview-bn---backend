package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "farmers")
@Getter
public class Farmer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Setter
    @Column(nullable = false, length = 20)
    private String phone;

    @Setter
    @Column(name = "cooperative_number", nullable = false, unique = true, length = 30)
    private String cooperativeNumber;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Farmer() {}

    public Farmer(String fullName, String phone, String cooperativeNumber, Instant now) {
        this.fullName = fullName;
        this.phone = phone;
        this.cooperativeNumber = cooperativeNumber;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
