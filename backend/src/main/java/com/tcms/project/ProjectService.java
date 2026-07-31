package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which projects a caller may see, and what they are told about them.
 *
 * <h2>The two gates, and why the second one is the whole point</h2>
 *
 * <p>Access is two <em>independent</em> gates (plan §6b, handoff §3):
 *
 * <ol>
 *   <li><strong>company</strong> — the RLS policy and the Hibernate filter, established from
 *       the authenticated session before this class runs. It scopes the candidate rows to the
 *       active company and nothing here can widen it.</li>
 *   <li><strong>project grant</strong> — an explicit grant per project, asked through
 *       {@link ProjectAccess}. <strong>Company membership conveys authentication and zero
 *       project visibility.</strong> Without this gate a member of Acme who was granted nothing
 *       would see Acme's entire project list — its names, its count, its clients' product
 *       portfolio — and <em>every cross-company test in the suite would still pass</em>, because
 *       those rows are in the caller's own company. That is persona 3 of the isolation harness,
 *       promoted here from a permission question to an HTTP one.</li>
 * </ol>
 *
 * <h2>Why visibility is resolved per project rather than in one clever query</h2>
 *
 * <p>The obvious implementation is a single native query joining {@code role_assignment},
 * {@code team_membership} and {@code role_permission} to return visible project ids. It would
 * be one round trip instead of N, and it is the wrong shape: it is a <strong>second
 * authorization-resolution path beside the permission evaluator</strong>, which is the bug class
 * plan §6b names explicitly and the reason the session token deliberately carries no grant
 * claims (handoff §18). Two implementations of "may they?" diverge eventually, and the one that
 * diverges quietly is the one that decides what a client can read.
 *
 * <p>So the evaluator stays the only answer, and the cost is accepted with its ceiling stated:
 * a company holds <em>a handful of whole products</em>, not dozens of feature areas (plan §1),
 * so N is small and every query behind it is index-covered. The two things that would otherwise
 * be N+1 <em>per project</em> — the case counts and the role names — are each fetched in one
 * grouped query, so what remains is N permission evaluations rather than 3N queries. When the
 * evaluator gets the Caffeine cache §6b specifies, this improves for free and no call site here
 * changes.
 *
 * <h2>Why an unreachable project is 404 and not 403</h2>
 *
 * <p>{@link #findVisible} returns an empty {@link Optional} for a project that does not exist,
 * one that belongs to another company, and one that exists here with no grant for this caller —
 * identically, so the controller answers all three the same way. Telling them apart turns the
 * endpoint into an oracle for what exists in another client's tenancy (§6t, handoff §3 lesson
 * 13). Same rule {@code InvitationController} follows on the one surface a stranger can reach.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectAccess access;

    public ProjectService(ProjectRepository projects, ProjectAccess access) {
        this.projects = projects;
        this.access = access;
    }

    /**
     * Every project in the active company this caller may see, by name.
     *
     * <p>Archived projects are included. The contract carries {@code archived} as a field
     * rather than as a filter, and the SPA's picker distinguishes them — omitting them
     * server-side would make an archived project unreachable rather than marked, which is not
     * what "suspend, never delete" means one level down.
     */
    @Transactional(readOnly = true)
    public List<ProjectView> listVisible(UUID userId) {
        UUID companyId = CompanyContext.require();

        List<Project> visible = projects.findAllByOrderByNameAsc().stream()
                .filter(project -> access.canView(userId, project.getId()))
                .toList();
        if (visible.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a
            // syntax error, so the grouped query below must not run. A member with no grants
            // is the ordinary case here, not an edge one.
            return List.of();
        }

        Map<UUID, Long> caseCounts =
                caseCounts(companyId, visible.stream().map(Project::getId).toList());
        Map<UUID, String> roleNames = access.projectRoleNames(userId);

        return visible.stream().map(project -> view(project, caseCounts, roleNames)).toList();
    }

    /**
     * One project, or empty when the caller may not see it — for any of the three reasons.
     *
     * @see #listVisible for why the emptiness is deliberately undifferentiated
     */
    @Transactional(readOnly = true)
    public Optional<ProjectView> findVisible(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();

        return visibleProject(userId, projectId).map(project -> view(
                project,
                caseCounts(companyId, List.of(project.getId())),
                access.projectRoleNames(userId)));
    }

    /**
     * <strong>The gate, for every project-scoped read in the application.</strong> Runs
     * {@code read} against the project only if the caller may see it; empty otherwise, for all
     * three unreachable reasons alike.
     *
     * <h2>Why this is a method others call rather than a rule others follow</h2>
     *
     * <p>Everything the SPA fetches under {@code /projects/{projectId}/…} — the classification
     * vocabularies and statuses today, the suite tree and cases next — has to pass the same two
     * gates. Left as a convention, each new endpoint would re-answer "may they?" in its own
     * service, and the one that gets it subtly wrong is the one nobody notices: a leak here
     * returns another client's <em>vocabulary</em>, which reads like innocuous reference data
     * right up to the point where it names their features.
     *
     * <p>So the gate is a single call site, and a new endpoint gets it by construction rather than
     * by remembering. Deleting the grant check inside it now fails tests across every endpoint
     * built on it, not just the one whose author removed it — the property that makes this worth a
     * higher-order method instead of a copied two-line filter.
     *
     * @param read applied to the project itself, so a caller never has to re-fetch it — and never
     *             has a reason to accept a {@code projectId} it has not had gated
     */
    @Transactional(readOnly = true)
    public <T> Optional<T> readVisible(UUID userId, UUID projectId, Function<Project, T> read) {
        return visibleProject(userId, projectId).map(read);
    }

    /**
     * <strong>The gate for every project-scoped write.</strong> Runs {@code write} against the
     * project only if the caller may see it <em>and</em> holds {@code permission} on it.
     *
     * <p>The read gate's sibling, and deliberately a different method rather than a flag on it: a
     * read that accidentally omitted a permission argument would still compile and would still be
     * gated (on visibility), which is exactly the mistake that must not be silent. A write that
     * calls the wrong one of these two is visible in the diff, because it has to name a permission
     * to get here at all.
     *
     * <p><strong>The permission is a constant at the call site, never a parameter from the
     * request.</strong> Letting a caller nominate the permission it is checked against is the same
     * class of defect as reading the company from a header (ERD §0.1 point 2), one layer up.
     *
     * <p>The write runs inside this transaction, so a failure after the gate rolls the whole thing
     * back — including the {@code audit_event} row, which must not survive a change that did not
     * commit.
     *
     * @param permission the permission key the action needs, e.g. {@code case:edit}. Must not be an
     *                   {@code OWN}-qualified one — see {@link ProjectAccess#canDo}.
     * @param write      applied to the gated project, for the reason {@link #readVisible} gives:
     *                   nothing downstream should accept a {@code projectId} it has not had gated
     */
    @Transactional
    public <T> GateResult<T> writeGranted(
            UUID userId, UUID projectId, String permission, Function<Project, T> write) {

        Optional<Project> project = projects.findScopedById(projectId);
        if (project.isEmpty() || !access.canView(userId, project.get().getId())) {
            // Both gates of the READ path first, and in this order: a caller who cannot see the
            // project must not be able to tell a nonexistent id from one they hold no grant on by
            // observing 403 instead of 404. Checking the write permission first would leak exactly
            // that, because the evaluator answers "no" for both and this method would then have to
            // guess which "no" it was looking at.
            return new GateResult.Unreachable<>();
        }
        if (!access.canDo(userId, project.get().getId(), permission)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(write.apply(project.get()));
    }

    /**
     * <strong>The gate for a write whose permission is {@code OWN}-qualified for some role.</strong>
     * Runs {@code write} when the caller may see the project and holds {@code permission} on it in
     * <em>any</em> form — and hands it an {@link AuthorshipGate} for the second half of the question,
     * which only the caller can ask because only the caller has the row.
     *
     * <h2>Why the row-level check cannot be folded in here</h2>
     *
     * <p>The qualifier restricts <em>which rows</em>, so answering it needs the row's author, and
     * finding the row needs the project this method has only just finished gating. Loading it here
     * would put a case lookup in {@code project}, or a {@code Function<Project, Optional<UUID>>}
     * parameter that reads the row once for the check and leaves the callback to read it again.
     *
     * <p>So the split is: this method decides <em>whether the caller holds the permission</em>, the
     * callback decides <em>which row</em> and asks {@link AuthorshipGate#mayActOn} about it. The
     * verdict itself is still computed in one place — {@code identity}'s evaluator — and the callback
     * cannot compute one of its own, because all it is given is a yes/no function.
     *
     * <p><strong>The honest cost:</strong> a callback that never calls {@code mayActOn} silently
     * gets the unqualified behaviour. That is why the endpoint's test asserts the refusal from the
     * outside — an author-restricted caller acting on someone else's row — rather than trusting the
     * shape of this signature.
     *
     * @param permission the permission the action needs, a constant at the call site as in
     *                   {@link #writeGranted}
     */
    @Transactional
    public <T> GateResult<T> writeGrantedByAuthorship(
            UUID userId,
            UUID projectId,
            String permission,
            BiFunction<Project, AuthorshipGate, T> write) {

        Optional<Project> project = projects.findScopedById(projectId);
        if (project.isEmpty() || !access.canView(userId, project.get().getId())) {
            // The read gates first and in this order, for the reason writeGranted gives: a caller
            // who cannot see the project must not tell a nonexistent id from an ungranted one.
            return new GateResult.Unreachable<>();
        }
        UUID gatedProjectId = project.get().getId();
        if (!access.canDo(userId, gatedProjectId, permission)) {
            // Held-for-nothing is refused before any row is read, so a Viewer's 403 costs no
            // lookup and cannot depend on whether the row they named exists.
            return new GateResult.Denied<>();
        }
        AuthorshipGate authorship =
                authorId -> access.canDoOnAuthored(userId, gatedProjectId, permission, authorId);

        return new GateResult.Allowed<>(write.apply(project.get(), authorship));
    }

    /**
     * <strong>The gate for a write that has no project to be gated on.</strong> Runs {@code write}
     * only if the caller holds {@code permission} company-wide in the company currently open.
     *
     * <h2>Two states, not three — and the missing one is the interesting part</h2>
     *
     * <p>{@link #writeGranted} has an {@link GateResult.Unreachable} state because a project id in
     * the request may name nothing the caller can see, and telling that apart from "no permission"
     * turns the endpoint into an oracle (§6t). A create names no target: there is nothing to
     * conceal the existence of, so the only refusal is {@link GateResult.Denied} and it is honest.
     * The type stays {@link GateResult} anyway so the controller's switch is the same exhaustive
     * shape as every other write's — and so a later variant that <em>does</em> take a target
     * (create-in-folder, say) has nowhere to quietly drop the 404 arm.
     *
     * <p>The company itself is never a parameter. {@link CompanyContext#require()} is read inside
     * the write, and {@code CompanyAwareTransactionManager} has already turned the same value into
     * the RLS GUC for this transaction — so a project can only be created into the session's own
     * company, whatever a request body says (ERD §0.1 point 2).
     *
     * @param permission a COMPANY-scope key, constant at the call site — see {@link #writeGranted}
     */
    @Transactional
    public <T> GateResult<T> createGranted(UUID userId, String permission, Supplier<T> write) {
        if (!access.canDoInCompany(userId, permission)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(write.get());
    }

    /**
     * The gate itself — <strong>the one implementation of "may this caller see this project".</strong>
     *
     * <p>Private and unannotated on purpose. Every caller ({@link #findVisible} in this class,
     * {@link #readVisible} from another bean) is already inside a read-only transaction, so an
     * annotation here would be decoration at best: a private call never crosses the Spring proxy,
     * which is precisely why {@code findVisible} calls <em>this</em> rather than the public
     * {@code readVisible} — a self-invocation of an annotated method is the kind of line that reads
     * as if it establishes a transaction and does not.
     *
     * <p>The transaction is not optional, though, and the requirement lands on the callers: without
     * one {@code app.current_company} is never set and {@code findScopedById} returns nothing under
     * RLS — a gate that fails closed, but for the wrong reason and silently.
     *
     * <p>{@code findScopedById} rather than {@code findById}: the latter resolves by primary key
     * through the persistence context, and Hibernate filters do NOT apply to that path. RLS would
     * still cover it, but using one layer where both are available is how a layered defence quietly
     * becomes a single point (see {@link ProjectRepository}'s header).
     */
    private Optional<Project> visibleProject(UUID userId, UUID projectId) {
        return projects.findScopedById(projectId)
                .filter(project -> access.canView(userId, project.getId()));
    }

    private ProjectView view(
            Project project, Map<UUID, Long> caseCounts, Map<UUID, String> roleNames) {
        return new ProjectView(
                project.getId(),
                project.getKey(),
                project.getName(),
                project.getDescription(),
                project.isArchived(),
                // A project with no cases has no GROUP BY row at all, so this default is the
                // normal path for a new project rather than a fallback for a broken query.
                caseCounts.getOrDefault(project.getId(), 0L),
                // null when the caller reaches this project through a COMPANY-scope grant —
                // the contract's stated meaning of myRole, not a lookup that failed.
                roleNames.get(project.getId()));
    }

    /**
     * The wire shape of a project that was created moments ago, assembled by the <em>same</em>
     * method the list and detail endpoints use.
     *
     * <p>Package-private and deliberately not a second assembler: {@link ProjectView} has two
     * per-caller fields, and a create that built them itself is how the response to a create comes
     * to differ from the response to the {@code GET} the client makes right after it — the field
     * that would drift first being {@code myRole}, which is null for exactly the callers who are
     * able to reach this endpoint.
     *
     * <p>An empty count map rather than a query: a project created in this transaction has no
     * cases, and {@link #view} already treats an absent project as zero because a {@code GROUP BY}
     * cannot invent rows. Running the count anyway would be a round trip whose only possible answer
     * is the default.
     */
    ProjectView viewOfCreated(Project project, UUID userId) {
        return view(project, Map.of(), access.projectRoleNames(userId));
    }

    private Map<UUID, Long> caseCounts(UUID companyId, List<UUID> projectIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : projects.countLiveCasesByProject(companyId, projectIds)) {
            counts.put(row.getProjectId(), row.getCaseCount());
        }
        return counts;
    }
}
