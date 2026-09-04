package com.tcms.staffing;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A percentage of one person's capacity, on one project, over a date range.
 *
 * <p><strong>The percentage is of <em>their</em> capacity, not of a notional 40-hour week.</strong>
 * Somebody contracted 20 hours who is allocated 50% is allocated 10 hours, and the same 50% on a
 * full-timer is 20. {@link Capacity} is what turns the percentage into hours, and it is the only
 * thing that does.
 *
 * <p>{@link #projectId} is a real foreign key because {@code project} is a foundation module.
 * {@link #personId} references {@code app_user}, which is instance-level and has no company of its
 * own — the boundary on this row is its own {@code company_id}, exactly as {@code time_entry} does.
 *
 * <p><strong>No rate and no cost.</strong> What an hour of this allocation is worth is M8's, gated
 * on {@code billing:*} and {@code ratecard:read}; see the package comment.
 */
@Entity
@Table(name = "allocation")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Allocation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    /**
     * A whole percent, 1..100.
     *
     * <p><strong>{@code short}, because {@code V10} declares the column {@code SMALLINT}</strong> —
     * a {@code int} field maps to {@code integer} and {@code ddl-auto: validate} refuses to start
     * with "found [int2 (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]". That is a boot
     * failure rather than a compile one, so it appears as every {@code @SpringBootTest} failing at
     * once. The wire carries an integer and the accessors widen, which keeps the narrowing in the
     * one place the column is described.
     */
    @Column(name = "percent", nullable = false)
    private short percent;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

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
    protected Allocation() {
    }

    static Allocation of(
            UUID id,
            UUID companyId,
            UUID projectId,
            UUID personId,
            int percent,
            LocalDate startsOn,
            LocalDate endsOn,
            String note,
            UUID by,
            Instant now) {

        Allocation created = new Allocation();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.personId = personId;
        created.percent = (short) percent;
        created.startsOn = startsOn;
        created.endsOn = endsOn;
        created.note = note;
        created.createdAt = now;
        created.createdBy = by;
        created.updatedAt = now;
        created.updatedBy = by;
        return created;
    }

    /**
     * A true delta — the caller passes what it wants each field to become, having already resolved
     * the absent ones against what is here.
     *
     * <p>{@link #personId} and {@link #projectId} are not among them, and that is the amend rule
     * rather than an oversight: moving an allocation to a different person is deleting one and
     * creating another, and doing it as an edit would slip past the overlap check that keys on
     * exactly those two columns (017 D-n).
     */
    void amend(int percent, LocalDate startsOn, LocalDate endsOn, String note, UUID by, Instant now) {
        this.percent = (short) percent;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.note = note;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getPersonId() {
        return personId;
    }

    int getPercent() {
        return percent;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
    }

    String getNote() {
        return note;
    }
}
