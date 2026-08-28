package com.tcms.execution;

import com.tcms.project.ReviewImpactSource;
import com.tcms.project.ReviewSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewImpactSource} for {@code execution} — <strong>which plans a run gate would stop</strong>
 * (005 N7, S9b).
 *
 * <p>The half of the Review settings screen's blast radius that {@code project} cannot see.
 * {@code project} declares {@code allowedDependencies = {"api", "tenancy"}} and this module depends
 * on {@code project}, so the dependency points the wrong way and {@code CompanyScopedEvent} does not
 * help — that seam is asynchronous write propagation and this is a synchronous read. The interface
 * lives in the foundation module and the module owning the tables implements it, which is
 * {@code PlanReviewSubjects}' shape one file over, and {@code ReviewSubjectDirectory}'s before that.
 *
 * <h2>Answering only when the gate is on is a decision, not an optimisation</h2>
 *
 * <p>{@code requireApprovedCaseForRun} off means no run is refused for this reason, so the honest
 * answer is an empty list and the query is not worth running. It matters that this reads the setting
 * it is <em>handed</em> rather than one it looks up: the screen asking is the one about to change
 * the policy, and a source that re-read the stored value could answer about a different setting from
 * the one the caller is reasoning with.
 *
 * <p><strong>It answers about the setting as it stands, and the screen is asking what would happen
 * if it changed.</strong> That is deliberate and is what the panel's copy has to say: on a project
 * with the gate already on, the list is the runs that <em>are</em> being refused; on one about to
 * turn it on, it is what would be. The counts are the same set either way — the gate does not change
 * which cases are approved — which is why one query serves both readings.
 */
@Component
class PlanRunGateImpact implements ReviewImpactSource {

    private final CaseFacts cases;

    PlanRunGateImpact(CaseFacts cases) {
        this.cases = cases;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BlockedPlan> plansBlockedFromRunning(UUID projectId, ReviewSettings settings) {
        if (!settings.requireApprovedCaseForRun()) {
            return List.of();
        }

        List<Object[]> rows = cases.plansBlockedByUnapprovedCases(projectId);
        List<BlockedPlan> blocked = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            blocked.add(new BlockedPlan((UUID) row[0], (String) row[1], (String) row[2]));
        }
        return List.copyOf(blocked);
    }
}
