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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/** One period of employment for a user: where they work, in what capacity, and for how long. */
@Entity
@Table(name = "employments")
@Getter
public class Employment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id")
    private Station station;

    @Setter
    @Column(name = "job_title", nullable = false, length = 100)
    private String jobTitle;

    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType;

    @Setter
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Setter
    @Column(name = "end_date")
    private LocalDate endDate;

    @Setter
    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Employment() {}

    public Employment(User user, Department department, Station station, String jobTitle, EmploymentType type,
                      LocalDate startDate, LocalDate endDate, String notes, Instant now) {
        this.user = user;
        this.department = department;
        this.station = station;
        this.jobTitle = jobTitle;
        this.employmentType = type;
        this.startDate = startDate;
        this.endDate = endDate;
        this.notes = notes;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    /** ACTIVE, ENDED or UPCOMING relative to {@code today}. */
    public String statusOn(LocalDate today) {
        if (startDate.isAfter(today)) return "UPCOMING";
        if (endDate != null && endDate.isBefore(today)) return "ENDED";
        return "ACTIVE";
    }
}
