package com.tcms.documentation;

import com.tcms.project.ReviewImpactSource;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewImpactSource} for {@code documentation} — <strong>how many pages a {@code DOC} review
 * would cover</strong> (005 N7, S9b).
 *
 * <p>{@link DocReviewSubjects}' sibling, and the second thing this module implements for
 * {@code project}: the directory answers <em>what is this review about</em> and this answers
 * <em>how much work is there</em>. Both exist for the same reason — {@code project} may import only
 * {@code api} and {@code tenancy}, so it cannot see {@code doc_page} at any width of
 * {@code allowedDependencies} plan §3 permits, and the alternative to an SPI is a raw-SQL read of
 * another module's table from the foundation, which is the one thing 005 §4 names as the wrong tool
 * (ArchUnit cannot see it, so it would invert the architecture invisibly).
 *
 * <h2>{@code DRAFT}, not "unpublished"</h2>
 *
 * <p>{@code ARCHIVED} is a third state and is not a draft: an archived page is retired content that
 * keeps its blocks and its links, and counting it as review work would tell a lead they have pages
 * to get through that nobody intends to publish. {@code DocPageStatus}' own header makes the point —
 * archiving is not deleting, and it is not un-publishing either.
 */
@Component
class DocReviewImpact implements ReviewImpactSource {

    private final DocPageRepository pages;

    DocReviewImpact(DocPageRepository pages) {
        this.pages = pages;
    }

    @Override
    @Transactional(readOnly = true)
    public int draftDocPages(UUID projectId) {
        return Math.toIntExact(pages.countLiveByStatus(projectId, DocPageStatus.DRAFT));
    }
}
