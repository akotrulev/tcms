package com.tcms.staffing;

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
 * Days somebody is away, and the fraction of each one.
 *
 * <p>{@link #dayFraction} rather than a {@code half_day} boolean (017 D-d): {@code member.html}
 * shows <em>"29 Aug · half day"</em>, and a boolean answers that mockup and nothing else, while
 * start and end <em>times</em> would invent a working-hours-of-day model this product has no other
 * use for.
 *
 * <p>An absence is per person and needs no project. It zeroes capacity everywhere capacity is shown
 * — the company staffing view, the directory, the timesheet's KPI tile and My Work's meter —
 * because all four read {@link Capacity}, which is the one function that subtracts it.
 */
@Entity
@Table(name = "absence")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Absence {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private AbsenceKind kind;

    @Column(name = "day_fraction", nullable = false)
    private BigDecimal dayFraction;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Absence() {
    }

    static Absence recorded(
            UUID id,
            UUID companyId,
            UUID personId,
            LocalDate startsOn,
            LocalDate endsOn,
            AbsenceKind kind,
            BigDecimal dayFraction,
            String note,
            UUID by,
            Instant now) {

        Absence created = new Absence();
        created.id = id;
        created.companyId = companyId;
        created.personId = personId;
        created.startsOn = startsOn;
        created.endsOn = endsOn;
        created.kind = kind;
        created.dayFraction = dayFraction;
        created.note = note;
        created.createdAt = now;
        created.createdBy = by;
        return created;
    }

    UUID getId() {
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

    AbsenceKind getKind() {
        return kind;
    }

    BigDecimal getDayFraction() {
        return dayFraction;
    }

    String getNote() {
        return note;
    }
}
