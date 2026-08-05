package com.tcms.repository;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's tags in use — what both tag controls complete from.
 *
 * <h2>What this closes</h2>
 *
 * <p>§96 shipped two tag controls, on a case and on a suite's rules, and both opened on an
 * <strong>empty option list</strong>: a tag is free text, so nothing in the product could say which
 * tags existed. That was survivable while a tag was a label somebody typed. §97 makes a tag a
 * <em>fill mechanism</em> — a plan section claiming {@code smoke} is defined by it — and a picker
 * that cannot tell you what exists makes that unusable, because the failure of a mistyped tag is
 * silence: the section resolves to nothing and reads exactly like a section nobody has filled.
 *
 * <h2>A picker's list and never a validator's</h2>
 *
 * <p>Nothing narrows a write to what this returns. A tag is free text (§96) and a client may send
 * one this endpoint did not return — that is how the first case to carry a new tag gets it, and it
 * is the property that keeps {@code case_tag} an open vocabulary. Validating against this list would
 * close it, which is precisely what a controlled dimension is for and why the product has those
 * separately.
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>Through {@link ProjectService#readVisible}, so a caller passes the two gates
 * {@code GET /projects/{projectId}} applies — company first, then the explicit project grant.
 * {@link StepSuggestionService} argues why a derived corpus needs the project gate rather than the
 * company one, and the same holds here with less text at stake: a tag vocabulary is a list of a
 * client's feature names and release labels, nobody chose what went into it, and every row is
 * genuinely in the caller's company.
 */
@Service
public class TagService {

    private final ProjectService projects;
    private final CaseTagRepository tags;

    public TagService(ProjectService projects, CaseTagRepository tags) {
        this.projects = projects;
        this.tags = tags;
    }

    /**
     * The project's tags in use, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers: the first is
     * a 404, the second is the ordinary state of a project nobody has tagged. Both controls must
     * render the second as <em>"no tags yet"</em> rather than as a failure to load.
     *
     * <p><strong>Uncapped, unlike step suggestions.</strong> That endpoint caps at 50 because its
     * corpus is every step ever written and a typeahead renders a dozen. A project's distinct tags
     * are a vocabulary a team keeps in their heads — tens, not thousands — and a cap here would
     * silently hide the rarely-used tag, which is the one somebody is searching the list to find.
     */
    @Transactional(readOnly = true)
    public Optional<List<TagUsageView>> findTagsInUse(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project ->
                tags.findTagUsage(project.getId()).stream()
                        .map(row -> new TagUsageView((String) row[0], ((Number) row[1]).longValue()))
                        .toList());
    }
}
