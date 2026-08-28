package com.tcms.identity;

import com.tcms.api.UserRef;
import com.tcms.project.ProjectAccess;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who can be given work in a project.
 *
 * <h2>One predicate, two callers — which is the reason this class exists at all</h2>
 *
 * <p>{@link #isAssignableTo} answers "may this person be handed work here", and it is asked from
 * exactly two places: {@link #membersOf}, which builds the list a picker renders, and
 * {@code work.BoardService}, which validates the {@code assigneeId} a create sends. Before §54 the
 * second existed and the first did not — {@code GET /projects/{id}/members} was §53's named missing
 * capability — so the SPA created every task unassigned.
 *
 * <p>The obvious way to add the list is a second query that "should" agree with the validator. It
 * would not agree for long, and the direction it drifts is the harmful one: a picker offering
 * somebody the server refuses is a form the user fills in correctly and cannot submit, with a field
 * error naming a person they were just shown. That is §31's disable-don't-hide rule applied to a
 * <em>set</em> rather than to a button — an affordance the server will reject must not be offered —
 * and the only way to hold it is for the two to be the same code rather than the same intention.
 *
 * <h2>Both halves of the predicate, and what each one alone lets through</h2>
 *
 * <ul>
 *   <li><strong>Active membership of this company.</strong> {@code app_user} is instance-level with
 *       no {@code company_id} (ERD §0.1's exception list, because one person works with several
 *       clients), so an existence check accepts a user belonging to another client entirely — which
 *       here would mean printing a stranger's name on this company's board. {@code ACTIVE} and not
 *       merely present: an {@code INVITED} membership conveys nothing until it is redeemed, so an
 *       unaccepted invitation must not become a way to be handed work.</li>
 *   <li><strong>A grant on this project.</strong> Company membership conveys zero project
 *       visibility (roles spec §A1). Without this half, a card is assigned to a colleague who
 *       cannot open the project it lives in: it renders on their board with their name on it, and
 *       404s when they click it. The only person who ever sees the problem is the assignee.</li>
 * </ul>
 *
 * <h2>Why the grant is resolved through the evaluator rather than read from {@code role_assignment}</h2>
 *
 * <p>A grant reaches a person directly, through a team, or company-wide, and a query over
 * {@code role_assignment WHERE project_id = ?} finds only the first. It would silently omit every
 * person staffed through a team — the shape the org chart exists to encourage — and omit every
 * Company Admin, who can see every project and would be missing from the picker for all of them.
 *
 * <p>More than a completeness bug, it is the second authorization-resolution path plan §6b forbids.
 * {@link PermissionEvaluator} is the one place effective permissions are computed, and
 * {@link ProjectAccess#canView} is the question asked with {@code project:read} — the permission
 * that means "visible", named in one place rather than at every call site.
 */
@Service
public class ProjectMembershipService {

    private final ProjectService projects;
    private final ProjectAccess access;
    private final CompanyMembershipRepository memberships;
    private final AdminDirectoryService directory;

    public ProjectMembershipService(
            ProjectService projects,
            ProjectAccess access,
            CompanyMembershipRepository memberships,
            AdminDirectoryService directory) {

        this.projects = projects;
        this.access = access;
        this.memberships = memberships;
        this.directory = directory;
    }

    /**
     * The people who may be given work in this project, by display name — or empty when the caller
     * cannot see the project, which the controller renders as a 404.
     *
     * <p>An empty <em>list</em> and an empty {@link Optional} mean different things, as they do on
     * every read in this codebase: the first is a reachable project nobody but the caller is
     * staffed on, the second is a project the caller may not know exists (§6t).
     *
     * <p>The gate is {@link ProjectService#readVisible} — the caller must be able to see the
     * project, and nothing more. Not {@code project:members}: that is the permission to <em>change</em>
     * staffing, held by a Test Lead and up, while the reason to read this list is to assign a task,
     * which is gated on {@code workitem:manage} at the moment the task is created. Requiring the
     * management permission to see who is on your own project would leave a Test Engineer able to
     * create a task and unable to name anybody on it.
     *
     * <h2>One evaluation per member, and the ceiling that makes it acceptable</h2>
     *
     * <p>{@code AdminDirectoryService.reachOf} accepts the mirror-image cost — one evaluation per
     * project for one caller — with an argument that holds here too: a company is a handful of whole
     * products (plan §1) staffed by a directory of people, not a public SaaS tenant with fifty
     * thousand accounts, and every query behind an evaluation is index-covered. The alternative is a
     * clever join that computes visibility directly, which is the second resolution path the header
     * refuses. When the evaluator gets its cache, this improves for free.
     */
    /**
     * The read gate is project visibility — {@code project:read} — and nothing stronger (005 Q2,
     * decided 2026-08-28). Who is on a project is not a secret from the company: the org chart
     * discloses the same fact to every member, and a picker that 403s for a Viewer would hide the
     * one list that tells them who to ask. Every write on the path keeps {@code project:members}.
     * {@code ProjectMemberEndpointTest} pins the gate from both sides.
     */
    @Transactional(readOnly = true)
    public Optional<List<UserRef>> membersOf(UUID callerId, UUID projectId) {
        return projects.readVisible(callerId, projectId, project -> {
            Map<UUID, UserRef> people = directory.peopleById();

            return activeMemberIds().stream()
                    .filter(userId -> access.canView(userId, project.getId()))
                    .map(people::get)
                    .filter(person -> person != null)
                    .sorted(Comparator.comparing(
                            UserRef::displayName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        });
    }

    /**
     * May {@code userId} be handed work in this project?
     *
     * <p>Asked by {@code work.BoardService} about the {@code assigneeId} on a create. It is the
     * per-person form of the filter {@link #membersOf} applies to the whole directory, and the two
     * must stay the same question — see the class header for what happens when they are merely
     * intended to agree.
     *
     * <p><strong>This is not a check on the caller.</strong> It asks about the <em>assignee</em>,
     * who is not making the request and may hold far less than the person assigning them. The
     * caller's own right to create the task is a separate gate ({@code workitem:manage}), already
     * applied by the time this runs.
     */
    @Transactional(readOnly = true)
    public boolean isAssignableTo(UUID userId, UUID projectId) {
        return memberships.existsByCompanyIdAndUserIdAndStatus(
                        CompanyContext.require(), userId, MembershipStatus.ACTIVE)
                && access.canView(userId, projectId);
    }

    /**
     * Who is staffed on this project — {@code project.ProjectMemberDirectory}'s answer (004 D19).
     *
     * <p>The same predicate {@link #membersOf} applies, with the caller half removed: this is asked
     * <em>about</em> a project by an endpoint that has already gated the caller, so resolving the
     * project through somebody's visibility again would ask a settled question with the wrong
     * subject. Reusing {@link #activeMemberIds} and the evaluator is what keeps it from becoming
     * the third copy of a predicate the class header spends four paragraphs on.
     */
    @Transactional(readOnly = true)
    public List<UUID> memberIdsOf(UUID projectId) {
        return activeMemberIds().stream()
                .filter(userId -> access.canView(userId, projectId))
                .toList();
    }

    /**
     * Every active membership in the company currently open.
     *
     * <p>{@code REMOVED} and {@code INVITED} are both excluded, which is narrower than
     * {@link AdminDirectoryService#listUsers} — that list renders invitees so an administrator can
     * see who has not accepted yet. Here an invitee is not a candidate: they hold no grant, cannot
     * sign in, and offering them would be offering somebody the create would refuse.
     */
    private List<UUID> activeMemberIds() {
        return memberships.findByCompanyId(CompanyContext.require()).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .map(CompanyMembership::getUserId)
                .toList();
    }
}
