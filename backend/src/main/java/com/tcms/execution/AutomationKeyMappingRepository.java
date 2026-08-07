package com.tcms.execution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learned mappings — §6c's third ladder step, and the writer's own lookups.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional</strong>; see
 * {@link EvidenceRepository}. Without it {@link #findByNormalizedTestNameIn} returns zero rows with
 * the right company in context, which reads as "no test has ever been mapped" — a ladder step that
 * silently stops working while every row is still there.
 */
@Transactional(readOnly = true)
public interface AutomationKeyMappingRepository
        extends JpaRepository<AutomationKeyMapping, UUID> {

    /**
     * The ladder's third step, <strong>batched</strong>.
     *
     * <p>One query for every still-unmatched name in the report, never one per test: a 5,000-test
     * report is what this endpoint exists for and a per-test lookup makes it 5,000 round trips
     * inside one transaction. Company-scoped by the Hibernate filter and by
     * {@code uq_automation_mapping}, deliberately not narrowed to a project — see
     * {@link AutomationKeyMapping}'s header for why the key is the company's.
     */
    List<AutomationKeyMapping> findByNormalizedTestNameIn(Collection<String> normalizedTestNames);

    /** The upsert's read half — the row a re-confirmation moves rather than duplicates. */
    Optional<AutomationKeyMapping> findByNormalizedTestName(String normalizedTestName);

    /**
     * One project's mappings, for the screen that lists them.
     *
     * <p>Project-scoped here where the match is company-scoped, and the two are consistent: the
     * unique key is the company's, so a name belongs to whichever project last claimed it, and this
     * lists the ones that are currently that project's. A company-wide list would show a reader rows
     * they cannot act on from the project they are in.
     */
    List<AutomationKeyMapping> findByProjectIdOrderByNormalizedTestNameAsc(UUID projectId);
}
