package com.tcms.repository;

import com.tcms.project.AuthorshipGate;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removing a case from the repository — <del>the first caller of the {@code OWN} qualifier the roles
 * spec has specified since M0</del> → <strong>as of {@code V51} (§207) the only caller of a qualifier
 * nothing carries.</strong>
 *
 * <h2>{@code case:delete} is no longer authorship-scoped</h2>
 *
 * <p>It was, for Test Engineer, from {@code V12} until {@code V51}: authorship conferred full control
 * of a case and a contributor could not unmake a colleague's. That is reversed. <strong>CRUD on
 * plans, cases and steps belongs to every non-Viewer contributor no matter who first created the
 * row</strong> — this product's subject is a <em>shared</em> test repository, and the duplicate a
 * colleague left behind last quarter is exactly the row somebody else has to remove. Soft delete is
 * what makes that recoverable; the qualifier was the wrong instrument for it. Roles spec §A3.5 and
 * §A5 carry the argument, and it is the same one that de-qualified {@code case:edit} on 2026-07-31.
 *
 * <h2>Two refusals, and only one of them can still happen</h2>
 *
 * <p>The gate below is unchanged and still asks the authorship question, because the qualifier is
 * DATA — see {@link com.tcms.identity.IdentityProjectAccess#canDoOnAuthored} for why the arm stays
 * wired against an empty population rather than being deleted. So the two 403s remain distinct
 * sentences, and one of them is now unreachable from the shipped catalogue:
 *
 * <ul>
 *   <li><strong>Not held at all.</strong> A Viewer. Nothing they can do changes the answer. This is
 *       the only refusal seeded data can produce today.</li>
 *   <li><strong>Held, but only over their own work</strong> ({@link Outcome.NotTheAuthor}).
 *       Reachable only if a future migration requalifies a grant to {@code OWN}. Kept because
 *       deleting it is what would make such a migration fail <em>open</em> here, and because the
 *       message is what makes that grant comprehensible to the person hitting it: the same caller
 *       deletes their own cases all day, so "you do not have permission to delete test cases" would
 *       be false — and false in the direction that sends someone to ask an admin for a permission
 *       they already hold.</li>
 * </ul>
 *
 * <p>Neither message names a permission key, for the reason every other 403 here does not:
 * a key is not actionable by someone who cannot grant it to themselves, and naming it publishes the
 * catalogue to exactly that caller.
 *
 * <h2>Where the authorship decision is made, and where it is not</h2>
 *
 * <p>This class does not know what {@code OWN} means. It looks the case up — it owns that row — and
 * hands the row's author to {@link AuthorshipGate}, which {@code project} supplied and
 * {@code identity} computed. That keeps this package free of permission logic, which its own
 * package documentation requires in as many words: <em>a permission check appearing anywhere in this
 * package is a defect regardless of what the dependency list permits</em>.
 *
 * <h2>Soft, and nothing cascades</h2>
 *
 * <p>{@link TestCase#softDelete} carries the reason. The one thing worth repeating here is what does
 * <em>not</em> happen: no step, condition, tag or attachment is touched, and the suite above is not
 * consulted. A delete that reached downward would unmake structure the caller did not name, which is
 * the same argument {@link SuiteWriteService} uses one level up to refuse a cascading folder delete.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>The edit takes a precondition because two editors can lose each other's work. There is no
 * lost-delete: deleting a case someone else just edited deletes the case they edited, which is what
 * was asked for. A stale {@code lockVersion} here would refuse a delete for a change the caller does
 * not care about, and the second attempt would delete it anyway.
 */
@Service
public class CaseDeleteService {

    /**
     * Roles spec §A5. <del>The one permission in the catalogue whose grant may be qualified</del> →
     * since {@code V51} no grant is {@code OWN}-qualified at all, so the authorship gate this service
     * uses resolves to the plain one for every seeded role. It is kept because it reads the
     * qualifier from data rather than assuming it.
     */
    private static final String DELETE_CASE = "case:delete";

    private static final String AUDIT_ACTION = "case.deleted";
    private static final String AUDIT_TARGET = "case";

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteVersions suiteVersions;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public CaseDeleteService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteVersions suiteVersions,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suiteVersions = suiteVersions;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * Unreachable project, a case that is not in it, or a case already deleted — one bodiless
         * 404 (§6t).
         */
        record NotFound() implements Outcome {
        }

        /** {@code case:delete} is not held here at all. */
        record Forbidden() implements Outcome {
        }

        /** Held, but only over cases the caller wrote — and this is not one of them. */
        record NotTheAuthor() implements Outcome {
        }

        /** Soft-deleted. 204. */
        record Deleted() implements Outcome {
        }
    }

    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID caseId) {
        GateResult<Outcome> gated = projects.writeGrantedByAuthorship(
                userId, projectId, DELETE_CASE,
                (project, authorship) -> apply(userId, project, caseId, authorship));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID userId, Project project, UUID caseId, AuthorshipGate authorship) {

        // The second gate a two-segment path needs: is this case in THAT project. Without it,
        // /projects/{granted}/cases/{ungranted} deletes a case the caller was never given — and
        // every cross-company test still passes, because the row is in the caller's own company.
        //
        // findLiveInProject excludes soft-deleted rows, so a second delete is a 404 rather than a
        // second 204: reporting success for a delete this request did not perform is the same
        // untruth as a 201 on a replayed create.
        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase testCase = found.get();

        // AFTER the lookup, because the answer depends on the row — and a caller who fails this
        // check has already read the case, so 403 discloses nothing a 404 would conceal.
        if (!authorship.mayActOn(testCase.getCreatedBy())) {
            return new Outcome.NotTheAuthor();
        }

        // Read before the delete, and this is the path the two-phase shape exists for (§238): a
        // soft-deleted case is excluded from all three membership arms, so after the write nothing
        // relates it to the suites it was in. Every suite here is a loser and none of them is
        // findable a line later.
        Set<UUID> suitesBefore =
                suiteVersions.holdersBefore(project.getId(), List.of(testCase.getId()));

        Instant now = clock.instant();
        testCase.softDelete(userId, now);
        cases.saveAndFlush(testCase);

        suiteVersions.reconcileAfterCaseWrite(
                project.getId(), suitesBefore, List.of(testCase.getId()), userId, now);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(testCase.getId()),
                // The key and the title, like the suite delete: the key is what an audit reader
                // recognises the case by, and the title is here because a deleted case's title is
                // otherwise only recoverable by reading a soft-deleted row — precisely what an audit
                // trail should not require.
                "{\"key\":\"" + testCase.getKey() + "\",\"title\":"
                        + AuditEvent.jsonString(testCase.getTitle()) + "}",
                now));

        return new Outcome.Deleted();
    }
}
