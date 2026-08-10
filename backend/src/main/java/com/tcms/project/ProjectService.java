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
     * <p>Archived projects are included. The contract carries {@code archived} as a field rather
     * than as a filter: omitting them server-side would make an archived project unreachable rather
     * than marked, which is not what "suspend, never delete" means one level down — and it would
     * take the un-archive away from the only screen that could offer it.
     *
     * <p>~~and the SPA's picker distinguishes them~~ — <strong>it did not, from §34 to §159</strong>.
     * No file outside a test fixture in either the SPA or {@code packages/} so much as named the
     * field, because nothing could set it, so this sentence described a rendering that existed
     * nowhere while the state it rendered existed nowhere either. Both halves are true now (§159),
     * and the pair is worth remembering as one defect rather than two: the prose was consistent with
     * itself and with nothing else.
     */
    @Transactional(readOnly = true)
    public List<ProjectView> listVisible(UUID userId) {
        UUID companyId = CompanyContext.require();

        List<Project> visible = visibleProjects(userId);
        if (visible.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a
            // syntax error, so the grouped query below must not run. A member with no grants
            // is the ordinary case here, not an edge one.
            return List.of();
        }

        Map<UUID, Long> caseCounts =
                caseCounts(companyId, visible.stream().map(Project::getId).toList());
        Map<UUID, String> roleNames = access.projectRoleNames(userId);
        // The label for projects the caller holds no grant on but inherits a company-wide role into.
        // Read second, exactly as the evaluator reads it second, so the name on the card and the
        // permissions behind it cannot disagree.
        String inherited = access.inheritedProjectRoleName(userId).orElse(null);

        return visible.stream()
                .map(project -> view(project, caseCounts, roleNames, inherited))
                .toList();
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
                access.projectRoleNames(userId),
                access.inheritedProjectRoleName(userId).orElse(null)));
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
     * <strong>The gate for a read that costs a permission key beyond visibility.</strong> Runs
     * {@code read} only if the caller may see the project <em>and</em> holds {@code permission} on
     * it — {@link #writeGranted}'s shape, in a read-only transaction, and answering the same three
     * states.
     *
     * <h2>Why a read needs three states here when every other read needs two</h2>
     *
     * <p>{@link GateResult}'s header states the general rule and it still holds: a read collapses
     * "no such project", "another company's" and "yours but not granted" into one bodiless 404,
     * because telling them apart makes the endpoint an oracle for another tenancy (§6t). What that
     * argument is about is <em>project visibility</em> — the thing a caller could otherwise use the
     * response to discover.
     *
     * <p>This method is for the reads whose gate is a <strong>second</strong> question asked of a
     * caller who has already passed the first. {@code report:view} (§87) is the product's first:
     * the caller can see the project, is looking at it, and the only thing being withheld is a
     * feature of it. Answering 404 there conceals nothing they do not already know and instead says
     * the job they just requested does not exist — false, and alarming about the one thing a
     * reporting pipeline must never seem to have done. So the visibility half still answers 404 and
     * the permission half answers 403, which is exactly {@code writeGranted}'s split and exactly
     * the reason it has one.
     *
     * <p>It is a distinct method rather than a flag for {@link #writeGranted}'s stated reason,
     * turned around: a read that called the write gate would run in a read-write transaction and
     * would read, in the diff, as a write — and the two must not become the same call with an
     * argument deciding which, or the argument is what gets it wrong.
     *
     * @param permission the permission key the read needs, a constant at the call site as in
     *                   {@link #writeGranted} — never a value named by the request
     * @param read       applied to the gated project, for {@link #readVisible}'s reason: nothing
     *                   downstream should accept a {@code projectId} it has not had gated
     */
    @Transactional(readOnly = true)
    public <T> GateResult<T> readGranted(
            UUID userId, UUID projectId, String permission, Function<Project, T> read) {

        Optional<Project> project = visibleProject(userId, projectId);
        if (project.isEmpty()) {
            // Visibility first, as in writeGranted: a caller who cannot see the project must not
            // be able to tell a nonexistent id from an ungranted one by observing 403 over 404.
            return new GateResult.Unreachable<>();
        }
        if (!access.canDo(userId, project.get().getId(), permission)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(read.apply(project.get()));
    }

    /**
     * <strong>The read gate for a cross-project read</strong> — {@code readVisible} with no project
     * named. Runs {@code read} against every project in the active company this caller may see.
     *
     * <p>Added for §6l's cross-project "My Work" board (§83), which is the first read in the product
     * that is not about <em>a</em> project. There is no 404 to answer here and that is the shape's
     * whole difference: an empty list is the honest answer for a member holding no grants, where
     * {@link #readVisible} has to distinguish "not reachable" from "reachable and empty" because the
     * caller named something.
     *
     * <p>The read takes the <strong>whole list</strong> rather than being applied per project, so a
     * caller can answer in one query per fact instead of one per project — the N+1 the board would
     * otherwise have across four generators. It is the same reason {@link #listVisible} fetches its
     * case counts in one grouped query; what stays per project is the permission evaluation, which
     * this class's header accepts with its ceiling stated.
     *
     * @param read applied to the gated projects, for {@link #readVisible}'s reason: nothing
     *             downstream should accept a project it has not had gated
     */
    @Transactional(readOnly = true)
    public <T> T readAllVisible(UUID userId, Function<List<Project>, T> read) {
        return read.apply(visibleProjects(userId));
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
     * <strong>One project-scope permission question, answered without writing anything.</strong>
     *
     * <p>Not a gate — nothing runs behind it and nothing is refused by it. This is for the callers
     * that must ask a <em>second</em> permission after their own gate has already let them through,
     * in order to decide something other than whether the request proceeds. There are three, and
     * none of them could use {@link #writeGranted}, because in each the answer changes the shape of
     * a response rather than authorising it:
     *
     * <ul>
     *   <li>{@code CaseBulkService} — which of two refusal sentences an already-failing request
     *       gets, since an engineer missing only {@code case:approve} and a Viewer missing
     *       everything need opposite advice;</li>
     *   <li>{@code RunCreateService} and {@code PlanStatusService} (§82) — whether §6g's gating
     *       rules may be overridden on this request, which is a capability the caller opted into
     *       and not a permission the endpoint requires of everybody.</li>
     * </ul>
     *
     * <p>It lives here rather than as a private helper in each because §75 already generalised
     * {@code CaseBulkService}'s copy from {@code holdsEdit} to a keyed method, and the two §82
     * callers would have made it three byte-identical copies of the same delegation under three
     * comments saying the same thing — §58's E1, and the shape {@code PeopleDirectory} was extracted
     * from in §76 after exactly two copies had been allowed to exist.
     *
     * <p><strong>{@code false} for an unreachable project as well as for a held-nothing
     * caller</strong>, which is correct at all three sites: each is asking how much a caller may do,
     * and "the project does not resolve" is not more than nothing. A caller that needs to tell those
     * two apart is asking a gate's question and should be calling a gate.
     *
     * @param permission a constant at the call site, as in {@link #writeGranted} — this method is
     *                   just as unfit to be handed a key named by the request
     */
    @Transactional(readOnly = true)
    public boolean holds(UUID userId, UUID projectId, String permission) {
        return writeGranted(userId, projectId, permission, project -> true)
                instanceof GateResult.Allowed<Boolean> allowed && allowed.value();
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
     * <strong>The gate for a write whose permission is {@code PLAN_SCOPE}-qualified for some
     * role.</strong> {@link #writeGrantedByAuthorship}'s twin over the other restrictive axis: it
     * decides whether the caller holds {@code permission} on the project at all, and hands the
     * callback a {@link RowScopeGate} for the half only the callback can ask.
     *
     * <p>Everything the authorship gate's header says about the split applies unchanged, including
     * <strong>the honest cost</strong>: a callback that never calls {@link RowScopeGate#mayActOn}
     * silently gets the unqualified behaviour, so the endpoint's test asserts the refusal from the
     * outside — a Test Engineer creating a project-scope artifact — rather than trusting this
     * signature's shape.
     *
     * <p>The scope reaches the callback from the <em>request</em> on a create and from the
     * <em>row</em> on an edit, and both are correct: the qualifier restricts which rows a grant
     * covers, and a create is the act of bringing such a row into existence.
     *
     * @param permission the permission the action needs, a constant at the call site as in
     *                   {@link #writeGranted}
     */
    @Transactional
    public <T> GateResult<T> writeGrantedByRowScope(
            UUID userId,
            UUID projectId,
            String permission,
            BiFunction<Project, RowScopeGate, T> write) {

        Optional<Project> project = projects.findScopedById(projectId);
        if (project.isEmpty() || !access.canView(userId, project.get().getId())) {
            // The read gates first and in this order, for the reason writeGranted gives: a caller
            // who cannot see the project must not tell a nonexistent id from an ungranted one.
            return new GateResult.Unreachable<>();
        }
        UUID gatedProjectId = project.get().getId();
        if (!access.canDo(userId, gatedProjectId, permission)) {
            // Held-for-nothing is refused before any row is read, so a Viewer's 403 costs no lookup
            // and cannot depend on whether the row they named exists.
            return new GateResult.Denied<>();
        }
        RowScopeGate rowScope =
                planScoped -> access.canDoOnScope(userId, gatedProjectId, permission, planScoped);

        return new GateResult.Allowed<>(write.apply(project.get(), rowScope));
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
     * A permission and <strong>the scope it has to be held at</strong> — the two gate shapes this
     * class already implements separately, as one value a caller can compute.
     *
     * <p>{@link #writeGranted} asks {@link ProjectAccess#canDo}, which answers about one project;
     * {@link #createGranted} asks {@link ProjectAccess#canDoInCompany}, which answers from the
     * SYSTEM and COMPANY terms only. They are not interchangeable and the difference is not a
     * detail: asking a company-scope key at project scope is the "held anywhere in this company"
     * shortcut the roles spec §A3 records rejecting, and it would make a permission granted on one
     * project read true for the whole company.
     *
     * @param key   a permission key, a constant at the call site as everywhere else
     * @param scope where it must be held — {@link Scope#PROJECT} for a key granted per project,
     *              {@link Scope#COMPANY} for one the catalogue lists under Company
     */
    public record RequiredPermission(String key, Scope scope) {

        /** Which of the two questions to ask. Exhaustive at every consumer, under {@code -Werror}. */
        public enum Scope {
            PROJECT,
            COMPANY
        }

        public static RequiredPermission onProject(String key) {
            return new RequiredPermission(key, Scope.PROJECT);
        }

        public static RequiredPermission inCompany(String key) {
            return new RequiredPermission(key, Scope.COMPANY);
        }
    }

    /**
     * <strong>The gate for a write whose permission is decided by the row it names.</strong>
     * Resolves the target first, asks whatever permission that target requires, then writes.
     *
     * <h2>Why the permission cannot be a constant here, and why that is not a loophole</h2>
     *
     * <p>Every other write in this application names its permission at the call site, and the
     * javadoc on {@link #writeGranted} says why: letting a caller nominate the permission it is
     * checked against is the company-from-a-header defect one layer up. That rule is kept. What
     * varies here is not chosen by the request — it is read from the <em>stored row</em> the
     * request addressed. A classification dimension the company owns needs
     * {@code company:vocabulary}; the project's own Feature tree needs {@code project:settings};
     * which of the two applies is a property of the dimension row, not a field anyone can send.
     * {@code BulkOperations.permission} is the same shape one module over (handoff §62) — there
     * the deciding fact comes from the payload, here from the database, and here is the stricter
     * of the two.
     *
     * <h2>An unresolvable target is 404, decided before any permission is asked</h2>
     *
     * <p>{@code resolve} returning empty means the id names nothing this project has — another
     * project's Feature dimension, a value that was hard-deleted, a typo. That answers
     * {@link GateResult.Unreachable} and it has to be decided <em>first</em>, because the
     * alternative is incoherent: the permission to ask is a property of the row, so a caller whose
     * row does not exist cannot be told 403 without the server inventing which permission they
     * failed. Answering "not found" is both the honest answer and the one that conceals nothing
     * they could have learned anyway.
     *
     * @param resolve    the target the request names, inside the transaction and after the project's
     *                   read gates. Empty ⇒ 404
     * @param permission what that target requires — see {@link RequiredPermission}
     * @param write      applied to the gated project and the resolved target, so nothing downstream
     *                   accepts an id that has not been through both gates
     * @param <R>        the resolved target, passed through rather than looked up twice
     */
    @Transactional
    public <R, T> GateResult<T> writeGrantedOnTarget(
            UUID userId,
            UUID projectId,
            Function<Project, Optional<R>> resolve,
            Function<R, RequiredPermission> permission,
            BiFunction<Project, R, T> write) {

        Optional<Project> project = projects.findScopedById(projectId);
        if (project.isEmpty() || !access.canView(userId, project.get().getId())) {
            // The read gates first and in this order, for the reason writeGranted gives.
            return new GateResult.Unreachable<>();
        }
        Project gated = project.get();

        Optional<R> target = resolve.apply(gated);
        if (target.isEmpty()) {
            return new GateResult.Unreachable<>();
        }

        RequiredPermission required = permission.apply(target.get());
        boolean held = switch (required.scope()) {
            case PROJECT -> access.canDo(userId, gated.getId(), required.key());
            case COMPANY -> access.canDoInCompany(userId, required.key());
        };
        if (!held) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(write.apply(gated, target.get()));
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

    /**
     * The plural form of the same gate — every project in the company this caller may see, by name.
     *
     * <p>Private and shared by {@link #listVisible} and {@link #readAllVisible} rather than written
     * out at both, because the two lines it consists of <em>are</em> the visibility rule: the day a
     * third condition joins it, a second copy is the one that keeps answering the old question. §58's
     * E1, and the reason {@link #visibleProject} is a method rather than a convention one line up.
     */
    private List<Project> visibleProjects(UUID userId) {
        return projects.findAllByOrderByNameAsc().stream()
                .filter(project -> access.canView(userId, project.getId()))
                .toList();
    }

    private ProjectView view(
            Project project,
            Map<UUID, Long> caseCounts,
            Map<UUID, String> roleNames,
            String inheritedRoleName) {
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
                // A grant naming this project wins; otherwise the inherited role, which IS the
                // caller's role here. Null only when they reach the project some other way — a
                // Company Admin, whose role is a claim about the company rather than the project.
                roleNames.getOrDefault(project.getId(), inheritedRoleName));
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
        // The inherited name is read here too, and it is not ceremony: the creator of a project is
        // not automatically granted a role on it (§34), so a company-wide Test Lead creating one
        // reaches it purely by inheritance — and would otherwise be told they have no role on the
        // project they just made.
        return view(project, Map.of(), access.projectRoleNames(userId),
                access.inheritedProjectRoleName(userId).orElse(null));
    }

    /**
     * The wire shape of a project a write has just changed — {@link #viewOfCreated}'s sibling, and
     * the same argument for existing: one assembler, so the response to a write cannot come to
     * differ from the response to the {@code GET} the client makes next.
     *
     * <p>It runs the count query where {@code viewOfCreated} passes an empty map, and that is the
     * whole difference between them: a project created in this transaction provably has no cases,
     * an archived one usually has a great many, and returning zero for it would put a wrong number
     * on the card the caller is looking at.
     *
     * <p>Package-private, and the project is passed in rather than re-read: the only callers are
     * inside a write that has already been through {@link #writeGranted}, so re-fetching would be a
     * round trip whose one purpose would be to re-gate what is already gated.
     */
    ProjectView viewOfChanged(Project project, UUID userId) {
        return view(
                project,
                caseCounts(CompanyContext.require(), List.of(project.getId())),
                access.projectRoleNames(userId),
                access.inheritedProjectRoleName(userId).orElse(null));
    }

    private Map<UUID, Long> caseCounts(UUID companyId, List<UUID> projectIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : projects.countLiveCasesByProject(companyId, projectIds)) {
            counts.put(row.getProjectId(), row.getCaseCount());
        }
        return counts;
    }
}
