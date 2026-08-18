package com.tcms.identity;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit trail's first reader — <strong>the client's own trail, never the operator's</strong>.
 *
 * <h2>Why this is in {@code identity} and not beside the entity in {@code tenancy}</h2>
 *
 * <p>{@link com.tcms.tenancy.AuditEvent} lives in {@code tenancy} because everything writes to it
 * and {@code tenancy} depends on nothing — {@code package-info} there declares
 * {@code allowedDependencies = {}}. Reading is the opposite shape. It needs the permission
 * evaluator, {@link ProjectService#readGranted} and the §6u display-name rule, all of which are
 * above {@code tenancy} in the module graph, so a reader written there would fail
 * {@code ModuleStructureTest} on its first import. {@code identity} is
 * {@code {api, tenancy, project}} and reaches all three; {@code OrgChartService} is the same shape
 * and the precedent (§241). The write half stays where it is and nothing about it moves.
 *
 * <h2>The two gates are different questions, so they are asked in different ways</h2>
 *
 * <p>The project trail costs {@code project:audit} and goes through {@link
 * ProjectService#readGranted}: visibility answers 404 and the permission answers 403, which is that
 * seam's whole shape (§6t). The company trail costs {@code audit:view} at COMPANY scope, which has
 * no project to be invisible, so it is asked of {@link PermissionEvaluator#evaluateCompanyWide}
 * directly — the shape {@link AdminDirectoryService#reachOf} uses, and deliberately <em>not</em> a
 * new term on {@link AdminReach}: reach is about which projects a caller can see and staff, and
 * folding an unrelated company key into it would make every consumer of that record re-derive N
 * project evaluations to answer a question with no project in it.
 *
 * <h2>A refusal is a 403, never an empty page</h2>
 *
 * <p>The other direction is available — {@link AdminDirectoryService} answers an empty list rather
 * than a 403, on the ground that its contract declares only 200. It is the wrong answer here and
 * the difference is what the surface means. An empty directory reads as "nobody is here", which is
 * merely unhelpful. An empty audit trail reads as "nothing happened", which is a false statement
 * about the past that the reader has no way to detect, on the one screen whose entire purpose is
 * to be trusted about the past. So both operations declare 403 and both return {@link
 * GateResult.Denied} rather than an empty page.
 */
@Service
public class AuditLogService {

    /** Roles spec §A3, PROJECT scope — Instance Admin, Company Admin and Test Lead. */
    static final String READ_PROJECT_AUDIT = "project:audit";

    /** Roles spec §A3, COMPANY scope — Instance Admin and Company Admin only. */
    static final String READ_COMPANY_AUDIT = "audit:view";

    private final ProjectService projects;
    private final PermissionEvaluator permissions;
    private final AuditEventRepository events;
    private final AdminDirectoryService directory;
    private final ApiTokenRepository tokens;

    AuditLogService(
            ProjectService projects,
            PermissionEvaluator permissions,
            AuditEventRepository events,
            AdminDirectoryService directory,
            ApiTokenRepository tokens) {

        this.projects = projects;
        this.permissions = permissions;
        this.events = events;
        this.directory = directory;
        this.tokens = tokens;
    }

    /**
     * One project's trail. {@code project:audit}, with visibility answering 404 first.
     *
     * <p>{@link AuditLogQuery#projectId} is ignored here and that is deliberate: the project is the
     * path's, which is what was gated. Honouring a second one from the query string would let a
     * caller pass the gate on a project they may audit and read the rows of one they may not.
     */
    @Transactional(readOnly = true)
    public GateResult<AuditEventPageView> forProject(
            UUID userId, UUID projectId, AuditLogQuery query) {

        return projects.readGranted(userId, projectId, READ_PROJECT_AUDIT, project ->
                render(query, events.pageForProject(
                        project.getId(),
                        query.actorUserId(),
                        query.action(),
                        query.occurredAfter(),
                        query.occurredBefore(),
                        pageableOf(query))));
    }

    /**
     * The whole company's trail, project-scoped rows included. {@code audit:view}.
     *
     * <p>Answers {@link GateResult.Denied} or {@link GateResult.Allowed} and never {@link
     * GateResult.Unreachable} — there is no named subject here that could fail to exist, so the
     * third state has nothing to describe. It shares the type with {@link #forProject} anyway so
     * the controller has one renderer rather than two that can come to disagree about what a
     * refusal looks like.
     *
     * <p><strong>Not narrowed to the caller's project grants.</strong> {@code audit:view} is held
     * by the two roles who already staff every project in the company, and clipping the trail to
     * the holder's own grants would leave the company log unable to answer the question it exists
     * for. The project trail is the surface for a reader who holds only a project grant.
     */
    @Transactional(readOnly = true)
    public GateResult<AuditEventPageView> forCompany(UUID userId, AuditLogQuery query) {
        if (!permissions.evaluateCompanyWide(userId).has(READ_COMPANY_AUDIT)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(render(query, events.pageForCompany(
                query.projectId(),
                query.actorUserId(),
                query.action(),
                query.occurredAfter(),
                query.occurredBefore(),
                pageableOf(query))));
    }

    /**
     * Unsorted, because the sort is in the query.
     *
     * <p>Passing a {@code Sort} here as well would have Spring Data append a second {@code ORDER
     * BY} to a statement that already has one, and the tiebreaker the repository's header argues
     * for would end up second in precedence behind whatever this said.
     */
    private static Pageable pageableOf(AuditLogQuery query) {
        return PageRequest.of(query.page(), query.size());
    }

    /**
     * Resolves the page's actors and renders it.
     *
     * <p>Two lookups for the whole page rather than two per row. The people map is the company's
     * directory, which is one query regardless of how many distinct actors the page holds; the
     * tokens are fetched by the distinct ids actually present, because that table is not a
     * directory anybody wants whole and a page frequently names none of it.
     */
    private AuditEventPageView render(AuditLogQuery query, Page<AuditEvent> page) {
        Map<UUID, UserRef> people = directory.peopleById();
        Map<UUID, String> tokenNames = tokenNamesFor(page.getContent());

        List<AuditEventView> items = page.getContent().stream()
                .map(event -> new AuditEventView(
                        event.getId(),
                        event.getProjectId(),
                        event.getActorUserId(),
                        nameOf(people, event.getActorUserId()),
                        event.getActorTokenId(),
                        event.getActorTokenId() == null
                                ? null
                                : tokenNames.get(event.getActorTokenId()),
                        event.getAction(),
                        event.getTargetType(),
                        event.getTargetIds(),
                        event.getChanges(),
                        event.getBulkOperationId(),
                        event.getOccurredAt()))
                .toList();

        return new AuditEventPageView(items, query.page(), query.size(), page.getTotalElements());
    }

    /**
     * The names of the tokens this page names, and only those.
     *
     * <p>Through {@code findAllByIdIn} rather than {@code findAllById} for that method's own
     * reason: {@code findAllById} goes through {@code EntityManager.find}, which Hibernate does not
     * apply {@code @Filter} to, so the company predicate would rest on RLS alone. Revoked tokens
     * resolve here deliberately — an act performed by a credential that has since been withdrawn is
     * precisely the row somebody is reading this screen to explain.
     */
    private Map<UUID, String> tokenNamesFor(List<AuditEvent> page) {
        Set<UUID> tokenIds = new LinkedHashSet<>();
        page.forEach(event -> {
            if (event.getActorTokenId() != null) {
                tokenIds.add(event.getActorTokenId());
            }
        });
        if (tokenIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        tokens.findAllByIdIn(tokenIds).forEach(token -> names.put(token.getId(), token.getName()));
        return names;
    }

    /**
     * The actor's display name, or null.
     *
     * <p>Null means the act had no person, or that the id names a membership row that no longer
     * exists. It does <strong>not</strong> mean the person left: {@link
     * AdminDirectoryService#peopleById} reads memberships without filtering on status, so an
     * offboarded colleague still resolves — see {@link AuditEventView}, where the correction to
     * this slice's plan is recorded.
     */
    private static String nameOf(Map<UUID, UserRef> people, UUID actorUserId) {
        if (actorUserId == null) {
            return null;
        }
        UserRef person = people.get(actorUserId);
        return person == null ? null : person.displayName();
    }
}
