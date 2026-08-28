package com.tcms.project;

import java.util.List;
import java.util.UUID;

/**
 * The SPI behind {@code ReviewImpact}'s two counts that live outside this module (005 §4, N7).
 *
 * <p>{@code project} may import only {@code api} and {@code tenancy}; {@code documentation} and
 * {@code execution} both import {@code project}. So the two numbers the Review settings screen
 * needs from them — draft pages, plans a run gate would refuse — are asked through an interface
 * declared here and implemented by the module that owns the rows, the way
 * {@link ReviewSubjectDirectory} already does for a subject's key and title. Dependency inversion,
 * and nothing a raw-SQL read across module tables could do invisibly to ArchUnit.
 *
 * <p><strong>Every method has a default</strong>, answering "none", and {@link ProjectSettingsService}
 * folds every registered bean. That is what lets the interface land before its implementations
 * (build batch B2 declares it, B3 implements it) without a build that fails on a missing leaf —
 * and it is also the honest semantics: a module with nothing to say contributes zero, not null.
 * {@code ReviewImpact} in the contract is non-nullable on every member for the same reason.
 */
public interface ReviewImpactSource {

    /** Documentation pages in {@code DRAFT} that a {@code DOC} review would cover. */
    default int draftDocPages(UUID projectId) {
        return 0;
    }

    /**
     * Plans whose scope holds a case that is not {@code APPROVED}, while
     * {@code requireApprovedCaseForRun} is on — the runs that would be refused. Empty when the gate
     * is off; the implementor reads the gate from the settings it is handed.
     */
    default List<BlockedPlan> plansBlockedFromRunning(UUID projectId, ReviewSettings settings) {
        return List.of();
    }

    /** {@code ReviewImpact.plansBlockedFromRunning[]} — named, because "3 plans" is not actionable. */
    record BlockedPlan(UUID planId, String key, String name) {
    }
}
