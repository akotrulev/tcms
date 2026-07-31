package com.tcms.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reconciles a case's steps against a submitted list — <strong>updating in place, never replacing.</strong>
 *
 * <h2>The one thing this class exists to prevent</h2>
 *
 * <p>{@code test_step.id} is stable and {@code step_result} references it, so a step that keeps its
 * id keeps every result and every piece of evidence ever recorded against it (handoff §3 load-bearing
 * detail 4). The obvious implementation — delete the case's steps, insert the submitted ones — is
 * four lines shorter and <strong>reassigns every id on every save</strong>. Nothing fails. The
 * screen looks right. Every past step result is orphaned from the step it described, and reordering
 * two steps silently rewrites which action each historical result was recorded against.
 *
 * <p>That failure is invisible to any test that checks the steps after an edit, because the steps
 * <em>are</em> correct — it is only visible from the results, which no test can create yet. So the
 * property is asserted directly on the ids instead: a step present before and after an edit has the
 * same id afterwards.
 *
 * <h2>Three operations, decided by the submitted id</h2>
 *
 * <ul>
 *   <li>id present and known → <strong>update</strong> the existing row, and set its position from
 *       the payload's order,</li>
 *   <li>id absent → <strong>insert</strong>,</li>
 *   <li>existing row whose id was not submitted → <strong>delete</strong>.</li>
 * </ul>
 *
 * <p>An id that is present but belongs to another case never reaches here — {@link CaseEdit} rejects
 * it, because honoring it would move another case's step, and its results, onto this one.
 *
 * <h2>Position comes from the payload order and is rewritten every time</h2>
 *
 * <p>{@code test_step} has no unique constraint on {@code (case_id, position)}, so nothing in the
 * database would refuse two steps sharing position 3 — and the reader orders by
 * {@code position, id}, which would then place them by id, i.e. arbitrarily. Assigning positions
 * from the list index makes the stored order exactly the submitted order and makes gaps impossible.
 *
 * <h2>The limitation this cannot yet handle, recorded because it will not announce itself</h2>
 *
 * <p><strong>{@code step_result.step_id} has no {@code ON DELETE} clause</strong>, so it defaults to
 * {@code NO ACTION}: deleting a step that has ever been executed raises a foreign-key violation.
 * Today every delete here succeeds, because {@code test_execution} has no Java writer and no
 * {@code step_result} row exists in any environment. The moment execution ships, removing a step
 * from a case somebody has run becomes a constraint violation surfacing as a 500.
 *
 * <p>It is left unhandled rather than guarded speculatively, because the guard cannot be triggered
 * and this project's rule is that a behaviour you cannot trigger is one you have not tested. The
 * decision it needs is a product one — soft-delete the step, refuse the edit, or detach the result —
 * and it belongs to the slice that can write a result to prove the choice works.
 */
@Component
public class CaseSteps {

    private final TestStepRepository steps;

    public CaseSteps(TestStepRepository steps) {
        this.steps = steps;
    }

    /**
     * Brings the case's stored steps in line with {@code submitted}, in the caller's transaction.
     *
     * @return the case's steps afterwards, in order — so the caller renders what it wrote rather
     *         than re-reading and hoping the flush ordering agrees
     */
    List<TestStep> reconcile(TestCase testCase, List<CaseEdit.StepEdit> submitted) {
        Map<UUID, TestStep> existing = new LinkedHashMap<>();
        for (TestStep step : steps.findForCase(testCase.getId())) {
            existing.put(step.getId(), step);
        }

        List<TestStep> result = new ArrayList<>();
        for (int position = 0; position < submitted.size(); position++) {
            CaseEdit.StepEdit edit = submitted.get(position);
            // remove() rather than get(): what stays in the map afterwards is exactly the set of
            // steps the payload did not mention, which is the set to delete. Tracking that in a
            // second collection is how the two lists drift apart.
            TestStep step = edit.id() == null ? null : existing.remove(edit.id());
            if (step == null) {
                step = TestStep.of(testCase.getId(), position, edit);
            } else {
                step.applyEdit(position, edit);
            }
            result.add(step);
        }

        steps.saveAll(result);
        if (!existing.isEmpty()) {
            steps.deleteAll(existing.values());
        }
        // Flushed by the caller's transaction. Returned in submitted order, which is now also
        // stored order — see the header on why position is not trusted from the request.
        return result;
    }
}
