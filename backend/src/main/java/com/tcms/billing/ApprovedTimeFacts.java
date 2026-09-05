package com.tcms.billing;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
     * One approved hour, with what pricing and burn need about it — <strong>as ids, not as
     * names.</strong>
     *
     * <p>Narrower than the invoice surface will need: the period's {@code decided_at} and
     * {@code decided_by}, and the entry's {@code subject_id}, are what an
     * {@code invoice_line_entry} snapshots, and they arrive when the class that writes one does.
     * A component nothing reads is a seam with no caller, which is the shape §121 deleted rather
     * than kept.
     */
    record ApprovedEntry(
            UUID timeEntryId, UUID personId, UUID projectId, LocalDate spentOn,
            BigDecimal hours) {
    }

    private static final String APPROVED_ENTRIES = """
            SELECT e.id, e.person_id, e.project_id, e.spent_on, e.hours
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.subject_type = 'ISSUE'
               AND e.project_id IN (:projectIds)
               AND p.state = 'APPROVED'
             ORDER BY e.spent_on, e.person_id
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

        return rows.stream()
                .map(row -> new ApprovedEntry(
                        (UUID) row[0],
                        (UUID) row[1],
                        (UUID) row[2],
                        ((java.sql.Date) row[3]).toLocalDate(),
                        (BigDecimal) row[4]))
                .toList();
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
