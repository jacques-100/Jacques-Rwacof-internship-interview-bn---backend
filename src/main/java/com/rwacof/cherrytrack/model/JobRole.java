package com.rwacof.cherrytrack.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A named job role such as "Weighing Clerk" or "Quality Inspector". What a person may do is always
 * decided by the role's {@link #accessLevel}, so custom roles can never gain permissions that
 * the three built-in levels don't define.
 */
@Entity
@Table(name = "job_roles")
@Getter
public class JobRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Setter
    @Column(length = 255)
    private String description;

    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "access_level", nullable = false, length = 20)
    private Role accessLevel;

    @Column(name = "system_role", nullable = false, updatable = false)
    private boolean systemRole;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    /** What holders of this role may do. Edited on the Permissions tab; takes effect on their next request. */
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "job_role_id"))
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "permission", nullable = false, length = 40)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobRole() {}

    public JobRole(String name, String description, Role accessLevel, Instant now) {
        this.name = name;
        this.description = description;
        this.accessLevel = accessLevel;
        this.permissions = Permission.template(accessLevel);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void replacePermissions(Set<Permission> next) {
        this.permissions.clear();
        this.permissions.addAll(next);
    }
}
