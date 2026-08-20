package com.tcms.repository;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a suite.
 *
 * <p>A suite's own words live in {@code name} where a case's live in {@code title}. That the port
 * calls both {@code title} is the point of it: three columns, one question.
 *
 * <p>See {@link CaseReviewSubjects} for why the read is transactional and why it goes through
 * {@code findLiveInProject}.
 */
@Component
class SuiteReviewSubjects implements ReviewSubjectDirectory {

    private final SuiteRepository suites;

    SuiteReviewSubjects(SuiteRepository suites) {
        this.suites = suites;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.SUITE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return suites.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getName()));
    }
}
