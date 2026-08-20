package com.tcms.repository;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a test case.
 *
 * <p>{@code @Transactional} because the read is RLS-protected and the caller is the outbox
 * dispatcher's thread, which holds no transaction of its own — {@code integration}'s
 * {@code NotificationProjects} states the reason, one module over.
 *
 * <p>Resolved through {@link TestCaseRepository#findLiveInProject}, which is what makes a deleted
 * case answer "no such artifact" rather than a row a notice would then name.
 */
@Component
class CaseReviewSubjects implements ReviewSubjectDirectory {

    private final TestCaseRepository cases;

    CaseReviewSubjects(TestCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.CASE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return cases.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getTitle()));
    }
}
