package com.tcms.staffing;

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
 * <p>Read-only, and that is the module's rule rather than a property of this class — see the
 * package comment and {@code StaffingReadsNoTimeEntryTest}, which asserts both that nothing here
 * writes those tables and that exactly one file names them. ArchUnit cannot see the rule, because
 * SQL is not a Java dependency; a test over source text is the only observer there is.
 *
 * <p>Fifth instance of a pattern with a name here: {@code work.BoardFacts} reads {@code tracker},
 * {@code execution.CaseFacts} reads {@code repository}, {@code tracker.release.ReleaseFacts} reads
 * {@code execution}, {@code time.IssueFacts} reads {@code tracker}. Native SQL rather than a second
 * JPA entity mapped over another module's table, for {@code CaseFacts}' reason — one table with two
 * sets of column expectations is two places a mapping can drift, and {@code ddl-auto: validate}
 * would check both.
 *
 * <p><strong>APPROVED hours only, and utilisation therefore denominates over approved</strong>
 * (017 D-i, 016 D-d). {@code approvedHours} and {@code pendingHours} are separate on the wire and
 * nothing in this product sums them; hours nobody has accepted are not yet a fact about the money,
 * and Finance holds {@code staffing:directory} for precisely the money reading of this number.
 *
 * <p>RLS covers every statement here: it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}. The gate on top of
 * it is the caller's — nothing in this class decides who may see anybody's hours.
 */
@Component
class TimeFacts {

    private static final String APPROVED_BY_PERSON = """
            SELECT e.person_id, COALESCE(SUM(e.hours), 0)
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.person_id IN (:personIds)
               AND e.spent_on BETWEEN :from AND :to
               AND p.state = 'APPROVED'
             GROUP BY e.person_id
            """;

    private final EntityManager entities;

    TimeFacts(EntityManager entities) {
        this.entities = entities;
    }

    /** Approved hours per person over the window. A person with none is absent from the map. */
    Map<UUID, BigDecimal> approvedHoursBy(
            Collection<UUID> personIds, LocalDate from, LocalDate to) {

        if (personIds.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a company whose every member is unprofiled is an ordinary state.
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(APPROVED_BY_PERSON)
                .setParameter("personIds", personIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<UUID, BigDecimal> byPerson = new HashMap<>();
        for (Object[] row : rows) {
            byPerson.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return byPerson;
    }
}
