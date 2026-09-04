package com.tcms.time;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One person's half-month of time (016 D-a).
 *
 * <p><strong>It spans projects deliberately.</strong> A period is a person's fortnight, not a
 * project's — the approvals mockup's own example carries two projects and a project-less meeting in
 * one submission — which is why 016 D-i has to say who may approve one, and why approving it
 * approves entries against projects the approver does not hold.
 *
 * <p>{@link #decisionNote} is <strong>not cleared when the period is re-submitted</strong>: the
 * engineer needs to see what they were asked to fix while they fix it, and
 * {@code ck_timesheet_period_note} makes a silent rejection impossible at the database as well.
 */
@Entity
@Table(name = "timesheet_period")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TimesheetPeriod {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "starts_on", nullable = false, updatable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false, updatable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TimesheetState state;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TimesheetPeriod() {
    }

    static TimesheetPeriod open(
            UUID id, UUID companyId, UUID personId, PeriodBounds bounds, Instant now) {

        TimesheetPeriod created = new TimesheetPeriod();
        created.id = id;
        created.companyId = companyId;
        created.personId = personId;
        created.startsOn = bounds.startsOn();
        created.endsOn = bounds.endsOn();
        created.state = TimesheetState.OPEN;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    void submit(Instant now) {
        state = TimesheetState.SUBMITTED;
        submittedAt = now;
        decidedAt = null;
        decidedBy = null;
        updatedAt = now;
    }

    void approve(UUID by, Instant now) {
        decide(TimesheetState.APPROVED, by, decisionNote, now);
    }

    /**
     * Sends the period back, and the note is a parameter rather than an option because
     * {@code ck_timesheet_period_note} refuses a {@code REJECTED} row without one — a caller that
     * reached here with a blank note would get a constraint name instead of a sentence.
     */
    void reject(UUID by, String note, Instant now) {
        decide(TimesheetState.REJECTED, by, note, now);
    }

    /**
     * Puts a period the approver sent back by mistake into {@code SUBMITTED} again.
     *
     * <p>The note stays. It is history at this point rather than an instruction, and deleting the
     * only record of a decision somebody is now undoing is a state this module refuses to be in.
     */
    void withdrawRejection(Instant now) {
        state = TimesheetState.SUBMITTED;
        decidedAt = null;
        decidedBy = null;
        updatedAt = now;
    }

    private void decide(TimesheetState decided, UUID by, String note, Instant now) {
        state = decided;
        decisionNote = note;
        decidedAt = now;
        decidedBy = by;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }


    UUID getPersonId() {
        return personId;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
    }

    TimesheetState getState() {
        return state;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    UUID getDecidedBy() {
        return decidedBy;
    }

    String getDecisionNote() {
        return decisionNote;
    }



}
