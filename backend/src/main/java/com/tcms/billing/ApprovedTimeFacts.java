package com.tcms.billing;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The only file in this module that names a {@code time}-owned table.</strong>
 *
 * <p>Read-only, and that is the module's rule rather than a property of this class — see the package
 * comment and {@code BillingReadsAndNeverWritesTest}, which asserts both that nothing here writes
 * those tables and that exactly one file names them. ArchUnit cannot see the rule, because SQL is
 * not a Java dependency; a test over source text is the only observer there is.
 *
 * <p>Sixth instance of a pattern with a name here: {@code work.BoardFacts} reads {@code tracker},
 * {@code execution.CaseFacts} reads {@code repository}, {@code tracker.release.ReleaseFacts} reads
 * {@code execution}, {@code time.IssueFacts} reads {@code tracker}, {@code staffing.TimeFacts} reads
 * {@code time}. Native SQL rather than a second JPA entity mapped over another module's table, for
 * {@code CaseFacts}' reason — one table with two sets of column expectations is two places a mapping
 * can drift, and {@code ddl-auto: validate} would check both.
 *
 * <p><strong>The name is {@code ApprovedTimeFacts} and not {@code TimeFacts}</strong> (019 D-r).
 * {@code staffing.TimeFacts} already exists and is a {@code @Component}; Spring's default bean name
 * is the simple class name, so two of them are a {@code ConflictingBeanDefinitionException} and a
 * context that does not start. {@code ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames}
 * is the rule, and §131 is why it exists. The qualifier is also true of the contents: every method
 * here answers about <em>approved</em> hours and nothing here can see a pending one.
 *
 * <h2>Two conditions on every statement, and neither is a courtesy filter</h2>
 *
 * <ul>
 *   <li><strong>{@code p.state = 'APPROVED'}</strong> — 016 D-d keeps approved and pending apart and
 *       nothing in this product sums them. Pricing a pending hour would bill work nobody has signed
 *       for.</li>
 *   <li><strong>{@code e.subject_type = 'ISSUE'}</strong> — {@code ck_time_entry_subject_shape}
 *       makes {@code project_id} NULL for {@code MEETING}, {@code LEAVE}, {@code TRAINING} and
 *       {@code INTERNAL}, so four of the five kinds have no project, therefore no client, therefore
 *       no rate card and no budget. The predicate is written anyway rather than left implied by the
 *       {@code project_id IN (…)} join: a reader must be able to see which population this is, and a
 *       sixth subject kind added later with a project would otherwise join it silently.</li>
 * </ul>
 *
 * <p>RLS covers every statement here: it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}. The gate on top of
 * it is the caller's — nothing in this class decides who may see anybody's hours.
 *
 * <p><strong>It resolves no names.</strong> {@code personId} comes back as a UUID and
 * {@code project.PeopleDirectory} turns it into a name, because V1 line 99 says user-facing names
 * come from {@code company_membership.display_name} and not from {@code app_user} — a join to
 * {@code app_user} here would be both the wrong name and a third module's table, weakening the one
 * claim this class exists to hold.
 */
@Component
class ApprovedTimeFacts {

    /**
     * One approved hour, with what pricing, burn and an invoice need about it — <strong>as ids, not
     * as names.</strong>
     *
     * <p>{@link #subjectId}, {@link #approvedAt} and {@link #approvedBy} are what an
     * {@code invoice_line_entry} snapshots, and they arrived in 019 T4 with the class that writes
     * one. They were left off T3's cut deliberately: a component nothing reads is a seam with no
     * caller, which is the shape §121 deleted rather than kept.
     *
     * <p><strong>{@link #approvedAt} and {@link #approvedBy} are the decision on the PERSON'S
     * PERIOD</strong> (019 D-f), which is a fortnight that spans projects. Named for what they are
     * rather than for what a reader might hope they are, at the one point in the stack where the
     * distinction could still be lost.
     */
    record ApprovedEntry(
            UUID timeEntryId,
            UUID personId,
            UUID projectId,
            UUID subjectId,
            LocalDate spentOn,
            BigDecimal hours,
            Instant approvedAt,
            UUID approvedBy) {
    }

    /** Unapproved hours in a window, per person — the {@code NOT_APPROVED} bucket's raw material. */
    record PersonHours(UUID personId, BigDecimal hours) {
    }

    private static final String APPROVED_COLUMNS = """
            SELECT e.id, e.person_id, e.project_id, e.subject_id, e.spent_on, e.hours,
                   p.decided_at, p.decided_by
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.subject_type = 'ISSUE'
               AND e.project_id IN (:projectIds)
               AND p.state = 'APPROVED'
            """;

    private static final String APPROVED_ENTRIES = APPROVED_COLUMNS + """
             ORDER BY e.spent_on, e.person_id
            """;

    /**
     * The same population, narrowed to a window and to hours <strong>no issued invoice has
     * taken</strong>.
     *
     * <p>The {@code NOT EXISTS} is the whole of 019 D-b's anti-double-billing rule, and it is
     * written here rather than filtered in Java for two reasons: the exclusion has to happen for a
     * whole fortnight in one statement, and it has to be visible beside the population it removes
     * from. {@code invoice_line_entry} rows exist only for ISSUED invoices, so this never subtracts
     * hours somebody else merely has open in a draft.
     */
    private static final String DRAFTABLE_ENTRIES = APPROVED_COLUMNS + """
               AND e.spent_on BETWEEN :from AND :to
               AND NOT EXISTS (SELECT 1 FROM invoice_line_entry b
                                WHERE b.time_entry_id = e.id)
             ORDER BY e.spent_on, e.person_id
            """;

    /**
     * Hours logged in the window against these projects whose period is <em>not</em> approved.
     *
     * <p>The {@code NOT_APPROVED} bucket, per person so the note can read <em>Tomas Ek 24h · Jun
     * Park 14h</em>. There is no invoice exclusion on this one and there could not be: an
     * unapproved hour has never been on a line.
     */
    private static final String UNAPPROVED_HOURS = """
            SELECT e.person_id, COALESCE(SUM(e.hours), 0)
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.subject_type = 'ISSUE'
               AND e.project_id IN (:projectIds)
               AND p.state <> 'APPROVED'
               AND e.spent_on BETWEEN :from AND :to
             GROUP BY e.person_id
            """;

    private static final String APPROVED_HOURS_BY_PROJECT = """
            SELECT e.project_id, COALESCE(SUM(e.hours), 0)
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.subject_type = 'ISSUE'
               AND e.project_id IN (:projectIds)
               AND p.state = 'APPROVED'
             GROUP BY e.project_id
            """;

    private final EntityManager entities;

    ApprovedTimeFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Every approved, issue-subject hour on these projects, all time — what a budget's money value
     * is computed from, one entry at a time because the rate depends on who logged it and when.
     *
     * <p><strong>Hours already on an issued invoice are included here</strong>, and that is the
     * difference from the draft's own query. A budget is what a project has consumed; whether
     * somebody has been billed for it is a different fact and moving it out of the burn would make a
     * project look cheaper the moment it was invoiced.
     */
    List<ApprovedEntry> approvedOn(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            // Not merely an optimisation: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a company whose every project is unbudgeted is an ordinary state.
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(APPROVED_ENTRIES)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream().map(ApprovedTimeFacts::toEntry).toList();
    }

    /**
     * The hours a draft over this window may offer: approved, issue-subject, on these projects, and
     * <strong>on no issued invoice</strong>.
     *
     * <p>This is the query {@code GET /invoices/{invoiceId}} runs on every read of a draft, which is
     * what makes {@code invoice.html}'s <em>"Approve them and this draft recalculates"</em> true
     * with no recalculate endpoint (019 D-c). It is also why two drafts open over an overlapping
     * period both offer the same hours and whichever issues first takes them: the second recomputes
     * on its next read with those hours gone. That cost was taken deliberately over reserving
     * entries at draft time, which would make an open draft a lock on the company's time data.
     */
    List<ApprovedEntry> draftableOn(Collection<UUID> projectIds, LocalDate from, LocalDate to) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(DRAFTABLE_ENTRIES)
                .setParameter("projectIds", projectIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream().map(ApprovedTimeFacts::toEntry).toList();
    }

    /**
     * Hours in the window whose period is not approved, per person (019 D-g).
     *
     * <p>A separate statement rather than a wider one over both states: 016 D-d keeps approved and
     * pending apart and nothing in this product sums them, so a single query answering both would
     * be the first place they meet — and the caller would then have to remember which rows it may
     * price. The two buckets are two questions and they are asked as two.
     */
    List<PersonHours> unapprovedOn(Collection<UUID> projectIds, LocalDate from, LocalDate to) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(UNAPPROVED_HOURS)
                .setParameter("projectIds", projectIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(row -> new PersonHours((UUID) row[0], (BigDecimal) row[1]))
                .toList();
    }

    private static ApprovedEntry toEntry(Object[] row) {
        return new ApprovedEntry(
                (UUID) row[0],
                (UUID) row[1],
                (UUID) row[2],
                (UUID) row[3],
                localDate(row[4]),
                (BigDecimal) row[5],
                instant(row[6]),
                (UUID) row[7]);
    }

    /**
     * {@code DATE} and {@code TIMESTAMPTZ} out of a native query, without asserting which Java type
     * the driver chose.
     *
     * <p>Not defensiveness: the two spellings are both real. The PostgreSQL driver hands a
     * {@code TIMESTAMPTZ} back as {@code java.time.Instant} here and as {@code java.sql.Timestamp}
     * in other configurations, and a cast to either one is a {@code ClassCastException} on the day
     * the other arrives — a failure with no relationship to what the query is about, which is
     * exactly what it looked like when it happened in 019 T4.
     */
    private static LocalDate localDate(Object value) {
        return value instanceof LocalDate already
                ? already : ((java.sql.Date) value).toLocalDate();
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Instant already
                ? already : ((java.sql.Timestamp) value).toInstant();
    }

    /**
     * The same population summed per project — the burn numerator, and the only figure a
     * {@code project:read} caller ever sees from this class.
     *
     * <p>A separate statement rather than a fold over {@link #approvedOn}: an hours budget needs the
     * sum and never the rows, and a project with a year of entries would otherwise load all of them
     * to answer a percentage a Project Manager reads on every page load. A project with no approved
     * hours is <strong>absent from the map</strong>, which callers read as zero — there is no
     * distinction here between "none" and "unknown", because an unapproved fortnight is a known
     * zero.
     */
    Map<UUID, BigDecimal> approvedHoursOn(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(APPROVED_HOURS_BY_PROJECT)
                .setParameter("projectIds", projectIds)
                .getResultList();

        Map<UUID, BigDecimal> byProject = new HashMap<>();
        for (Object[] row : rows) {
            byProject.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return byProject;
    }
}
