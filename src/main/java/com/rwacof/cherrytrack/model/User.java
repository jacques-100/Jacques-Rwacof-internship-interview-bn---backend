package com.rwacof.cherrytrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Setter
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Setter
    @Column(unique = true, length = 160)
    private String email;

    @Setter
    @Column(length = 20)
    private String phone;

    /** Access level, always equal to {@code jobRole.accessLevel}. Authorisation reads only this. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Role role;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "job_role_id", nullable = false)
    private JobRole jobRole;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    /** Stations this user is assigned to. Assigned users manage those stations; admins see all. */
    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinTable(name = "user_stations", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "station_id"))
    private Set<Station> stations = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the profile photo last changed; null when there is none. The image itself is in stored_images. */
    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;

    protected User() {}

    public User(String username, String passwordHash, String fullName, JobRole jobRole, Instant now) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.jobRole = jobRole;
        this.role = jobRole.getAccessLevel();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Changes the job role and, with it, the access level. */
    public void assignJobRole(JobRole jobRole) {
        this.jobRole = jobRole;
        this.role = jobRole.getAccessLevel();
    }

    public Instant getAvatarUpdatedAt() {
        return avatarUpdatedAt;
    }

    public void setAvatarUpdatedAt(Instant avatarUpdatedAt) {
        this.avatarUpdatedAt = avatarUpdatedAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
