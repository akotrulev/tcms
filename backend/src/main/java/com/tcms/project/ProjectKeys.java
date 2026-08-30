package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates a project's issue keys — {@code ACME-1}, {@code OLB-42} (expansion scope D5).
 *
 * <h2>Why this is a second allocator and not a widened first one</h2>
 *
 * <p>{@link com.tcms.tenancy.DisplayKeys} numbers five entity types <em>per company</em>, which
 * §6t argued for and which nothing about issues makes wrong: {@code TC-1} is unambiguous everywhere
 * inside a client, and moving a case between projects is a non-event because a collision is
 * impossible. Issue keys are the opposite trade by D5 — {@code ACME-12} names its project out loud,
 * which is the whole point of a key somebody types into a commit message.
 *
 * <p>The scope document proposed widening {@code key_sequence}'s primary key to
 * {@code (company_id, entity_type, project_id)} with {@code project_id} nullable for the five
 * existing types. <strong>That statement cannot run</strong> — a primary key cannot contain a
 * nullable column — and plan 009 review R1 replaced it with a new table. So {@code key_sequence},
 * its allocator, its five-member enum and its seven readers are untouched, and there is no
 * primary-key change on a live table anywhere in this expansion.
 *
 * <h2>Why it lives in {@code project} and not in {@code tenancy}</h2>
 *
 * <p>The prefix is {@code project.key}, and the counter is one row per project — so the table this
 * reads is joined to {@code project} in the same statement. {@code project} is one of the five
 * foundation modules ({@code ArchitectureRules.FOUNDATION_MODULES}), which is what makes
 * {@code tracker} importing this legal. {@code tenancy} would work too and would be worse: it would
 * have to learn what a project key is, which is a {@code project} concept.
 *
 * <h2>One statement, and the two wrong versions of it</h2>
 *
 * <pre>{@code
 * UPDATE project_key_sequence s SET next_value = s.next_value + :n
 *   FROM project p
 *  WHERE s.company_id = :c AND s.project_id = :p AND p.id = s.project_id
 * RETURNING p.key, s.next_value - :n;
 * }</pre>
 *
 * <ul>
 *   <li><strong>Not {@code MAX(key) + 1}.</strong> Two concurrent creations read the same maximum
 *       and issue the same key; the failure surfaces as a unique-constraint violation under load
 *       and never in a test. The row lock the {@code UPDATE} takes is what serialises them.</li>
 *   <li><strong>Not read-then-write.</strong> The same race with more code.</li>
 * </ul>
 *
 * <p>Gaps are possible and accepted: a transaction that allocates and rolls back does not return
 * its numbers. The contract is uniqueness and immutability, not gaplessness.
 *
 * <h2>Zero rows is an exception, and that is the whole reason it is one</h2>
 *
 * <p>An {@code UPDATE … RETURNING} matching nothing is an <em>empty result</em>, not an error, so
 * the failure mode without this check is a project whose issues silently cannot be numbered. Two
 * things produce it and the exception names both: a project created before {@code V2} that the
 * backfill missed, and a caller whose {@link CompanyContext} is another company's — under
 * {@code FORCE ROW LEVEL SECURITY} the policy matches no row, which is indistinguishable here from
 * the row being absent and is the correct answer to both.
 */
@Component
public class ProjectKeys {

    /**
     * A reserved block of numbers, and the keys they spell.
     *
     * @param prefix     the project's own {@code key} — {@code ACME}
     * @param firstValue the first reserved number, inclusive
     * @param count      how many were reserved
     */
    public record Allocation(String prefix, long firstValue, int count) {

        /** The sequence number at {@code index} — the only column that may order an issue key. */
        public long seqAt(int index) {
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(
                        "Reserved " + count + " keys from " + firstValue + ", asked for index " + index);
            }
            return firstValue + index;
        }

        /** The display key at {@code index} — {@code ACME-1}. Never zero-padded. */
        public String keyAt(int index) {
            return prefix + "-" + seqAt(index);
        }
    }

    private static final String ALLOCATE = """
            UPDATE project_key_sequence s SET next_value = s.next_value + :count
              FROM project p
             WHERE s.company_id = CAST(:companyId AS uuid)
               AND s.project_id = CAST(:projectId AS uuid)
               AND p.id = s.project_id
            RETURNING p.key, s.next_value - :count
            """;

    private final EntityManager entityManager;

    public ProjectKeys(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Reserves {@code count} consecutive issue numbers for {@code projectId} in the active company.
     *
     * <p>Joins the caller's transaction rather than starting its own, which is required rather than
     * incidental: the row lock must be held until the issues that use the numbers commit, and a
     * separate transaction would release it early and would hand out numbers that survive a
     * caller's rollback while the issue that needed them does not.
     *
     * @throws IllegalStateException if the project has no counter row, or is not visible in the
     *                               active company — the two are one answer here, deliberately
     */
    @Transactional
    public Allocation allocate(UUID projectId, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Allocate at least one key, not " + count);
        }
        UUID companyId = CompanyContext.require();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(ALLOCATE)
                .setParameter("count", count)
                .setParameter("companyId", companyId.toString())
                .setParameter("projectId", projectId.toString())
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Project " + projectId + " has no issue key counter in company " + companyId
                            + ". Either the project is not visible in this company, or it predates "
                            + "V2 and the backfill missed it — tcms_seed_project_defaults creates "
                            + "the row and is idempotent.");
        }
        Object[] row = rows.getFirst();
        return new Allocation((String) row[0], ((Number) row[1]).longValue(), count);
    }
}
