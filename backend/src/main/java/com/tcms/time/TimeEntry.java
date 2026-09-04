package com.tcms.time;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * Hours somebody worked, against an issue, a meeting, leave, training or internal work.
 *
 * <p><strong>{@link #subjectId} carries no foreign key, and that is the module rule rather than an
 * omission.</strong> ArchUnit permits a cross-module dependency only toward the five foundation
 * modules and {@code tracker} is not one, so a module downstream of an issue references it
 * polymorphically and keeps its own projection — the price {@code doc_link.source_id},
 * {@code attachment.owner_id} and {@code review.subject_id} already pay. What it costs is stated in
 * {@code V9__time.sql}: referential integrity between an entry and its issue lives in application
 * code, and the mitigation is the tracker's soft delete.
 *
 * <p>{@link #projectId} <em>is</em> a real foreign key by contrast, because {@code project} is a
 * foundation module — and because the approvals queue groups by it (016 D-i).
 *
 * <p><strong>Hours, never a rate and never a cost.</strong> What an hour is worth is M8's, gated on
 * {@code billing:*} and {@code ratecard:read}; see the package comment.
 */
@Entity
@Table(name = "time_entry")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TimeEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private TimeSubjectType subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(name = "hours", nullable = false)
    private BigDecimal hours;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TimeEntry() {
    }

    static TimeEntry log(
            UUID id,
            UUID companyId,
            UUID periodId,
            UUID personId,
            TimeSubjectType subjectType,
            UUID subjectId,
            UUID projectId,
            LocalDate spentOn,
            BigDecimal hours,
            String note,
            UUID by,
            Instant now) {

        TimeEntry created = new TimeEntry();
        created.id = id;
        created.companyId = companyId;
        created.periodId = periodId;
        created.personId = personId;
        created.subjectType = subjectType;
        created.subjectId = subjectId;
        created.projectId = projectId;
        created.spentOn = spentOn;
        created.hours = hours;
        created.note = note;
        created.createdAt = now;
        created.createdBy = by;
        created.updatedAt = now;
        created.updatedBy = by;
        return created;
    }

    /**
     * A whole-body replacement, which is what {@code PATCH /timesheets/entries/{entryId}} is.
     *
     * <p>{@link #periodId} is not among the fields it may move, and that is the amend rule rather
     * than an oversight: a date that falls into a different half-month is a different period, and a
     * period that is already submitted must not gain a row by way of an edit somewhere else.
     * {@code TimeEntryService} refuses the move rather than performing it silently.
     */
    void amend(
            TimeSubjectType subjectType,
            UUID subjectId,
            UUID projectId,
            LocalDate spentOn,
            BigDecimal hours,
            String note,
            UUID by,
            Instant now) {

        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.projectId = projectId;
        this.spentOn = spentOn;
        this.hours = hours;
        this.note = note;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    public UUID getId() {
        return id;
    }

    UUID getPeriodId() {
        return periodId;
    }

    UUID getPersonId() {
        return personId;
    }

    TimeSubjectType getSubjectType() {
        return subjectType;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getProjectId() {
        return projectId;
    }

    LocalDate getSpentOn() {
        return spentOn;
    }

    BigDecimal getHours() {
        return hours;
    }

    String getNote() {
        return note;
    }
}
