package com.tcms.repository;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's step corpus — what the editor's action field completes from.
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>The read goes through {@link ProjectService#readVisible}, so a caller passes exactly the two
 * gates {@code GET /projects/{projectId}} applies — company first, then the explicit project grant.
 * Nothing in this class decides whether a caller may see a project, which is the rule the whole
 * module follows and the one a permission check appearing here would break.
 *
 * <p>The gate matters more here than on any other library read in this module, and it is worth
 * saying why rather than inheriting it silently. {@link ConditionService} argues that a condition
 * library describes how a client's system is operated. <strong>This endpoint serves the text of
 * every test step in the project</strong> — which is that same description, exhaustively, without
 * anyone having curated it. An endpoint gated only on the company would pass every cross-company
 * test in the suite, because the rows genuinely are in the caller's company, while handing a member
 * granted nothing a readable transcript of the client's entire product. Derived corpora leak more
 * than curated ones precisely because nobody chose what went in.
 *
 * <h2>Why the cap lives here</h2>
 *
 * <p>{@link #MAX_SUGGESTIONS} is the service's decision and not the caller's: there is no
 * {@code limit} parameter on the contract. A typeahead renders a dozen rows and a mature project's
 * corpus is thousands, so an unbounded read is a payload nobody displays; making it a parameter
 * would let a client ask for the whole corpus and call it a page size.
 */
@Service
public class StepSuggestionService {

    /**
     * How many suggestions a keystroke may return.
     *
     * <p>Fifty rather than the dozen a dropdown shows: the ranking is by use count, so the tail of
     * the list is where a rarely-shared step lives, and a cap at the render size would make the
     * endpoint's answer depend on how tall the menu happens to be.
     */
    static final int MAX_SUGGESTIONS = 50;

    private final ProjectService projects;
    private final TestStepRepository steps;

    public StepSuggestionService(ProjectService projects, TestStepRepository steps) {
        this.projects = projects;
        this.steps = steps;
    }

    /**
     * The project's matching step suggestions, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and nothing matched". Collapsing them would turn both a project whose cases have no
     * steps yet — the state of every freshly provisioned project, since nothing seeds steps — and a
     * typeahead narrowed past the corpus into a 404 the author cannot back out of. An editor that
     * 404s while you type is one that looks broken at the exact moment it is working.
     */
    @Transactional(readOnly = true)
    public Optional<List<StepSuggestionView>> findSuggestions(
            UUID userId, UUID projectId, StepSuggestionQuery request) {

        return projects.readVisible(userId, projectId, project ->
                steps.findSuggestions(
                        project.getId(), request.needle(), Limit.of(MAX_SUGGESTIONS)));
    }
}
