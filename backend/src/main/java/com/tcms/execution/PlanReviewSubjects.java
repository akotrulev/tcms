package com.tcms.execution;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a test plan — the third and, since {@link ReviewSubjectType}
 * has three members, the last. A fourth member with no bean behind it fails in
 * {@code integration}'s index, naming the type.
 *
 * <p>{@code @Transactional} because the read is RLS-protected and the caller is the outbox
 * dispatcher's thread, which holds no transaction of its own.
 */
@Component
class PlanReviewSubjects implements ReviewSubjectDirectory {

    private final TestPlanRepository plans;

    PlanReviewSubjects(TestPlanRepository plans) {
        this.plans = plans;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.PLAN;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return plans.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getName()));
    }
}
