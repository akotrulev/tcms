package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reconciles a case's classification against a submitted set — <strong>bounded by the vocabulary
 * the caller was served</strong>, which is the whole content of this class.
 *
 * <h2>The bound, and the defect it prevents</h2>
 *
 * <p>A classification value is retired by a soft delete, because cases already carry it and
 * executions already ran under it ({@code classification_value.deleted_at}). Everything that reads
 * a case therefore <em>drops</em> values the project no longer offers — {@link CaseSummaries} does
 * it for the names, and the detail view's {@code classificationValueIds} for the ids, so the two
 * cannot disagree about what a case is classified as.
 *
 * <p>Which means a client is never shown a retired assignment, and so <strong>cannot resubmit
 * one</strong>. If a reconcile treated "absent from the payload" as "delete it", the first save of
 * any case carrying a retired value would silently discard it — a classification nobody chose to
 * drop, removed by a save that never mentioned it, and invisible afterwards because the thing that
 * would have shown it is the thing that was deleted. The bound closes that: only assignments to
 * values the project still offers are candidates for removal, and everything else is left exactly
 * as it is.
 *
 * <p>The consequence is worth stating rather than discovering. A retired value's assignment is
 * <strong>immutable through this API</strong>: it cannot be added (validation refuses a value the
 * project does not offer) and cannot be removed (the reconcile does not reach it). It stops
 * existing when the value is restored and then deselected, or when the case row is deleted. That is
 * the same shape §64 settled for a retired shared step — references survive a retirement by design —
 * arrived at from the delete side instead of the save side.
 *
 * <h2>Set semantics, and why there is no update arm</h2>
 *
 * <p>Unlike {@link CaseSteps}, which updates rows in place because {@code test_step.id} is
 * referenced by results, {@code case_classification} has no identity beyond
 * {@code (case_id, value_id)} and nothing references it. A row is present or it is not, so the
 * operations are insert and delete and there is nothing an update could mean.
 *
 * <p>Rows a case already carries and still wants are left untouched rather than deleted and
 * reinserted. Not an optimization: {@code V2}'s {@code case_classification_integrity} trigger takes
 * a {@code FOR NO KEY UPDATE} lock on the parent case for every insert on a SINGLE axis, and a
 * delete-then-insert of an unchanged value would take that lock, and re-run the whole check, on
 * every save of every case that never changed its Test Type.
 */
@Component
public class CaseClassifications {

    private final CaseClassificationRepository assignments;

    public CaseClassifications(CaseClassificationRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * What the case is classified as, restricted to values the project still offers — the
     * <strong>one</strong> implementation of that filter, and both places it is needed read it here.
     *
     * <p>The renderer serves this as {@code classificationValueIds}, and {@link CaseEdit#validate}
     * takes it as the value an edit carries when the request omits the field. Two implementations
     * would let the ids a client is served differ from the ids a save preserves, and the difference
     * would show up as a classification that disappears on a save which did not mention it —
     * exactly the defect the bound in this class exists to prevent, reintroduced one layer up.
     *
     * <p>It is the same rule {@link CaseSummaries} applies to the names, applied to the ids, which
     * is what lets the contract promise the two can never disagree.
     */
    Set<UUID> liveValueIdsOf(UUID caseId, ClassificationVocabulary vocabulary) {
        Set<UUID> offered = vocabulary.liveValueIds();
        Set<UUID> carried = new HashSet<>();
        for (CaseClassification row : assignments.findForCase(caseId)) {
            if (offered.contains(row.getValueId())) {
                carried.add(row.getValueId());
            }
        }
        return carried;
    }

    /**
     * Brings the case's stored classification in line with {@code submitted}, in the caller's
     * transaction.
     *
     * @param submitted the values the case should carry afterwards — already validated against the
     *                  same vocabulary, so every id here has a dimension in it
     * @param vocabulary what the project offers; both the source of each row's denormalized
     *                  {@code dimension_id} and, through {@link
     *                  ClassificationVocabulary#liveValueIds()}, the bound on what may be removed
     * @param companyId the case's company, written onto every row — RLS refuses an insert whose
     *                  value does not match {@code app.current_company} (ERD §0.1), so this is what
     *                  makes the row readable again rather than one this connection can write once
     *                  and never see
     */
    void reconcile(
            TestCase testCase,
            List<UUID> submitted,
            ClassificationVocabulary vocabulary,
            UUID companyId) {

        Set<UUID> wanted = new HashSet<>(submitted);
        Set<UUID> offered = vocabulary.liveValueIds();

        List<CaseClassification> stale = new ArrayList<>();
        Set<UUID> alreadyCarried = new HashSet<>();
        for (CaseClassification row : assignments.findForCase(testCase.getId())) {
            if (!offered.contains(row.getValueId())) {
                // Retired since it was assigned. Not a candidate for removal — see the header.
                // Deliberately NOT added to alreadyCarried either: it cannot be in `wanted`,
                // because validation would have refused it, so the two branches never meet.
                continue;
            }
            if (wanted.contains(row.getValueId())) {
                alreadyCarried.add(row.getValueId());
            } else {
                stale.add(row);
            }
        }

        List<CaseClassification> added = new ArrayList<>();
        for (UUID valueId : submitted) {
            if (alreadyCarried.contains(valueId)) {
                continue;
            }
            added.add(CaseClassification.of(
                    testCase.getId(), valueId, vocabulary.dimensionOf(valueId), companyId));
        }

        // Deleted BEFORE inserting, and flushed, or replacing a SINGLE axis's value fails: V2's
        // trigger counts the case's existing rows on that dimension, and with the insert first it
        // would find the outgoing value still there and refuse the incoming one as a second value.
        // Hibernate orders inserts before deletes within a flush regardless of call order, so the
        // sequencing has to be made explicit rather than implied by the lines below.
        if (!stale.isEmpty()) {
            assignments.deleteAll(stale);
            assignments.flush();
        }
        if (!added.isEmpty()) {
            assignments.saveAll(added);
        }
    }
}
