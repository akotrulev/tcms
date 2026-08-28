package com.tcms.documentation;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a documentation page (005 S9b) — the fourth implementation of
 * the interface {@code project} declares, in the module that owns the table, exactly as the case,
 * suite and plan ones are.
 */
@Component
class DocReviewSubjects implements ReviewSubjectDirectory {

    private final DocPageRepository pages;

    DocReviewSubjects(DocPageRepository pages) {
        this.pages = pages;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.DOC;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return pages.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getTitle()));
    }
}
