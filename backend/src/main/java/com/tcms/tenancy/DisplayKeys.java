package com.tcms.tenancy;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates a company's display keys — {@code TC-1}, {@code TS-4}, {@code TP-12} (plan §6t).
 *
 * <h2>Why this lives in {@code tenancy} and not in the module that creates the first one</h2>
 *
 * <p>Five entity types share one table and one racing statement: cases, suites, plans, runs and doc
 * pages. Putting the allocator in whichever module happens to create first makes the other four
 * import it for an unrelated reason — {@code documentation} depending on {@code repository} to number
 * a page — and, worse, makes a second copy the obvious alternative. Two copies of this statement is
 * two chances to reach for {@code MAX(key_seq) + 1}. {@code tenancy} is depended on by everything and
 * depends on nothing, which is the shape a per-company counter needs.
 *
 * <h2>One statement, and the two wrong versions of it</h2>
 *
 * <p>The statement is prescribed by {@code V1}'s comment on the table and by {@code V5}'s header:
 *
 * <pre>{@code
 * UPDATE key_sequence SET next_value = next_value + :n
 *  WHERE company_id = :c AND entity_type = :t
 * RETURNING prefix, next_value - :n;
 * }</pre>
 *
 * <ul>
 *   <li><strong>Not {@code MAX(key_seq) + 1}.</strong> Two concurrent creations read the same maximum
 *       and issue the same key, and the failure surfaces as a unique-constraint violation on
 *       {@code uq_test_case_key} under load and never in a test. The row lock the {@code UPDATE}
 *       takes is what serialises them.</li>
 *   <li><strong>Not read-then-write.</strong> {@code SELECT next_value} followed by an
 *       {@code UPDATE} is the same race with more code.</li>
 * </ul>
 *
 * <h2>A range, even though today's only caller asks for one</h2>
 *
 * <p>ERD §5 and plan §6t both say bulk import and CI quarantine auto-create <strong>must</strong>
 * reserve N numbers in one statement — all creation in a company serialises on this single row, so
 * importing 5,000 cases one round trip at a time is pathological. The SQL is byte-identical for
 * {@code n = 1} and {@code n = 500}, so the range API costs nothing here and means the bulk slice has
 * nothing to invent. {@link Allocation} hands back the whole reserved block.
 *
 * <p>Gaps are possible and accepted: a transaction that allocates and then rolls back does not return
 * its numbers, because the counter is deliberately not transactional in that sense. {@code V1}'s
 * comment says so — {@code key_seq} owns ordering and nothing promises the sequence is gapless.
 *
 * <h2>The prefix comes from the row, not from Java</h2>
 *
 * <p>{@code V5} seeds {@code TC- TS- TP- TR- DOC-} as data. A constant here would be a second
 * definition of a string that is immutable by contract and already printed in reports and tickets —
 * and the two definitions would be edited months apart.
 *
 * <h2>Zero rows is an exception, and this is the whole reason it is one</h2>
 *
 * <p>An {@code UPDATE … RETURNING} that matches nothing is an <em>empty result</em>, not an error.
 * {@code V5} exists because no path created these rows and describes exactly this: the caller sees
 * "could not allocate" and the tempting repairs are the racing {@code MAX()+1} or an
 * {@code INSERT … ON CONFLICT} on the hot path of every creation forever. So a missing row throws,
 * loudly, naming the actual fix — {@code provision-company} is idempotent and re-running it tops the
 * company up.
 *
 * <p><strong>Company comes from {@link CompanyContext}, never from an argument</strong> (ERD §0.1
 * point 2), and {@code key_sequence} is under {@code FORCE ROW LEVEL SECURITY} besides — so a caller
 * with the wrong context active cannot allocate another client's numbers even if it tries: the policy
 * matches no row and this throws.
 */
@Component
public class DisplayKeys {

    /**
     * The five keyed entity types, exactly as {@code key_sequence.entity_type}'s {@code CHECK}
     * constraint spells them.
     *
     * <p>An enum rather than a {@code String} parameter because the failure modes are indistinguishable
     * from each other: a typo'd {@code "TEST_CASE"} matches zero rows, which this class reports as
     * "this company cannot allocate keys" — sending a reader to re-run provisioning for a bug in a
     * string literal.
     */
    public enum EntityType {
        CASE, SUITE, PLAN, RUN, DOC_PAGE
    }

    /**
     * A reserved block of numbers, and the keys they spell.
     *
     * @param prefix     from the row — {@code TC-}
     * @param firstValue the first reserved number, inclusive
     * @param count      how many were reserved
     */
    public record Allocation(String prefix, long firstValue, int count) {

        /** The {@code key_seq} at {@code index} — the only column that may order the key (§3.6). */
        public long seqAt(int index) {
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(
                        "Reserved " + count + " keys from " + firstValue + ", asked for index " + index);
            }
            return firstValue + index;
        }

        /** The display key at {@code index} — {@code TC-1}. Never zero-padded: {@code key_seq} sorts. */
        public String keyAt(int index) {
            return prefix + seqAt(index);
        }
    }

    private static final String ALLOCATE = """
            UPDATE key_sequence SET next_value = next_value + :count
             WHERE company_id = CAST(:companyId AS uuid)
               AND entity_type = :entityType
            RETURNING prefix, next_value - :count
            """;

    private final EntityManager entityManager;

    public DisplayKeys(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Reserves {@code count} consecutive numbers for the active company.
     *
     * <p>Joins the caller's transaction rather than starting its own, which is required rather than
     * incidental: the row lock this takes must be held until the rows that use the numbers commit, and
     * a separate transaction would release it early — and would also allocate numbers that survive a
     * caller's rollback while the case that needed them does not.
     *
     * @throws IllegalStateException if the company has no counter for {@code type}
     */
    @Transactional
    public Allocation allocate(EntityType type, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Allocate at least one key, not " + count);
        }
        UUID companyId = CompanyContext.require();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(ALLOCATE)
                .setParameter("count", count)
                .setParameter("companyId", companyId.toString())
                .setParameter("entityType", type.name())
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Company " + companyId + " has no " + type + " key counter. Re-run "
                            + "provision-company for it — the command is idempotent and seeds the "
                            + "five key_sequence rows (migration V5).");
        }
        Object[] row = rows.getFirst();
        return new Allocation((String) row[0], ((Number) row[1]).longValue(), count);
    }
}
