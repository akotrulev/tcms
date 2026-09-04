package com.tcms.staffing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * Contracted hours, and the three facts {@code directory.html} shows about a person.
 *
 * <p><strong>Most people have no row here, and that is a real state rather than a default.</strong>
 * Nobody has recorded their contracted hours, so every hours figure the API serves for them is null
 * and the screens read an em dash — not zero, and not a notional 40 (016 D-j's refusal, kept). The
 * {@code DEFAULT 40} in {@code V10} applies to a row somebody creates without naming the hours,
 * never to a person who has no row.
 *
 * <p>Company-owned rather than instance-level because one account may belong to several companies
 * ({@code identity}'s package comment) and be contracted differently in each — 40h at one client,
 * 20h at another. {@code user_id} therefore carries no company of its own and the isolation column
 * is this row's own, as {@code time_entry} does.
 *
 * <p>{@link #experienceSince} is a <strong>date</strong> and not a count of years (017 D-b):
 * {@code directory.html} renders "6 years", and a stored integer is correct on the day it is typed
 * and wrong every day after, silently, with nothing in the product to notice.
 */
@Entity
@Table(name = "person_profile")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class PersonProfile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "weekly_hours", nullable = false)
    private BigDecimal weeklyHours;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "location")
    private String location;

    @Column(name = "experience_since")
    private LocalDate experienceSince;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected PersonProfile() {
    }

    static PersonProfile create(
            UUID id,
            UUID companyId,
            UUID userId,
            BigDecimal weeklyHours,
            String jobTitle,
            String location,
            LocalDate experienceSince,
            Instant now) {

        PersonProfile created = new PersonProfile();
        created.id = id;
        created.companyId = companyId;
        created.userId = userId;
        created.weeklyHours = weeklyHours;
        created.jobTitle = jobTitle;
        created.location = location;
        created.experienceSince = experienceSince;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /**
     * A whole-body replacement, which is what {@code PUT …/profile} is.
     *
     * <p>A {@code PUT} rather than a {@code POST} plus a {@code PATCH} because a profile is one row
     * per person and the caller has no id to address it by — so the operation creates or replaces,
     * and an absent optional field here really does mean "there is no job title", not "leave it".
     */
    void replace(
            BigDecimal weeklyHours,
            String jobTitle,
            String location,
            LocalDate experienceSince,
            Instant now) {

        this.weeklyHours = weeklyHours;
        this.jobTitle = jobTitle;
        this.location = location;
        this.experienceSince = experienceSince;
        this.updatedAt = now;
    }

    UUID getUserId() {
        return userId;
    }

    BigDecimal getWeeklyHours() {
        return weeklyHours;
    }

    String getJobTitle() {
        return jobTitle;
    }

    String getLocation() {
        return location;
    }

    LocalDate getExperienceSince() {
        return experienceSince;
    }
}
