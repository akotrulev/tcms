package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.DisplayKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, renaming and deleting suites — <strong>the writes that make a provisioned company
 * usable.</strong>
 *
 * <p>Until this class existed, {@code provision-company} produced a company whose projects had no
 * folders and could therefore hold no cases: {@code tcms_seed_project_defaults} seeds a
 * configuration and the status vocabulary, deliberately not a folder, and a case requires a
 * {@code suiteId}. The §29 live pass had to insert a suite with hand-written SQL to test the case
 * endpoint it was building.
 *
 * <h2>Two permissions, and collapsing them would erase a boundary the spec called out</h2>
 *
 * <p>{@code suite:manage} (create, rename) is held by Test Engineer and above; {@code suite:delete}
 * by leads only. The roles spec states the reason in one line: deleting a subtree is the most
 * destructive repository action, so it sits with leads while contributors keep organising the tree
 * freely. It also lists that split as a boundary that was <em>previously wrong</em> and got fixed —
 * so gating both on {@code suite:manage} would not be a simplification, it would be the reversion.
 *
 * <h2>What this class refuses, and why refusing is the feature</h2>
 *
 * <ol>
 *   <li><strong>Moving.</strong> {@link SuiteEdit#validateRename} rejects a payload whose
 *       {@code parentId} differs from the stored one. Reparenting rewrites every descendant's
 *       {@code path} and needs cycle prevention; until that exists the only honest answers are "done"
 *       and "refused", and silently dropping the field is the third one — the one where a client
 *       shows the folder in its new place, reloads, and finds it back.</li>
 *   <li><strong>Deleting anything that is not empty.</strong> {@code suite:delete} is specced
 *       "folder + subtree", and the cascade is deliberately not built here. A folder delete that
 *       soft-deletes the cases beneath it unmakes <em>other people's</em> work through a permission
 *       whose sibling, {@code case:delete}, carries an {@code OWN} qualifier precisely to prevent
 *       that — and it does so with no preview of what it is about to touch. A non-empty suite answers
 *       409 naming the counts. The cascade belongs to a slice that can show its work; the contract
 *       already has the shape for it in {@code BulkResult}.</li>
 * </ol>
 *
 * <h2>What commits together</h2>
 *
 * <p>On a create: the counter increment, the suite row, its v1 composition version and the audit
 * row, in one transaction. The counter is in it for {@link DisplayKeys}' reason — its row lock must
 * be held until the row that used the number commits.
 */
@Service
public class SuiteWriteService {

    /** Roles spec §6b. Create, rename, move and reorder — the last two are not built. */
    private static final String MANAGE_SUITE = "suite:manage";

    /** Roles spec §6b. Leads and above; deliberately not held by Test Engineer. */
    private static final String DELETE_SUITE = "suite:delete";

    private static final String AUDIT_TARGET = "suite";

    private final ProjectService projects;
    private final SuiteRepository suites;
    private final SuiteService tree;
    private final SuiteVersions versions;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public SuiteWriteService(
            ProjectService projects,
            SuiteRepository suites,
            SuiteService tree,
            SuiteVersions versions,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.suites = suites;
        this.tree = tree;
        this.versions = versions;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so every controller mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a suite that is not in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission the action needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The client's {@code If-Match} did not match the stored {@code lockVersion}. */
        record Stale() implements Outcome {
        }

        /**
         * The delete was refused because the folder is not empty.
         *
         * <p>Carries the counts rather than a rendered sentence: the message is the controller's,
         * and a service that returns prose is a service two callers eventually word differently.
         */
        record NotEmpty(long childSuites, long cases) implements Outcome {
        }

        /** A new suite. 201. */
        record Created(SuiteView view) implements Outcome {
        }

        /** A renamed suite. 200. */
        record Updated(SuiteView view) implements Outcome {
        }

        /** Soft-deleted. 204. */
        record Deleted() implements Outcome {
        }
    }

    /**
     * Creates a suite.
     *
     * <p>No {@code Idempotency-Key}, unlike case creation, and the difference is not an omission. A
     * duplicate case is expensive to spot — the grid shows two rows with different keys and identical
     * titles among hundreds. A duplicate folder is visible in the tree the moment the create returns,
     * next to its twin, and is removed by the delete this same slice ships. The header is worth its
     * column on {@code test_case} and would be ceremony here.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, SuiteWriteRequest request) {
        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, MANAGE_SUITE,
                project -> applyCreate(companyId, userId, project, request)));
    }

    private Outcome applyCreate(
            UUID companyId, UUID userId, Project project, SuiteWriteRequest request) {

        List<Suite> live = suites.findLiveForProject(project.getId());

        Set<UUID> liveIds = new HashSet<>();
        for (Suite suite : live) {
            liveIds.add(suite.getId());
        }

        SuiteEdit.Outcome validated = SuiteEdit.validateCreate(request, liveIds);
        if (validated instanceof SuiteEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        SuiteEdit edit = ((SuiteEdit.Outcome.Valid) validated).edit();

        // The parent's path, resolved from the list already fetched rather than by a second query.
        // Null for a root, which is what Suite.create expects.
        String parentPath = null;
        if (edit.parentId() != null) {
            for (Suite suite : live) {
                if (suite.getId().equals(edit.parentId())) {
                    parentPath = suite.getPath();
                    break;
                }
            }
        }

        // AFTER validation, so a rejected payload does not burn a number — §29's rule, and the
        // counter only ever moves forward, so a burned number is a permanent hole.
        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.SUITE, 1);

        Instant now = clock.instant();
        Suite created = Suite.create(
                project.getId(),
                edit.parentId(),
                parentPath,
                allocated.keyAt(0),
                allocated.seqAt(0),
                edit,
                userId,
                now);

        // Flushed before the version row so the suite exists for suite_version.suite_id to
        // reference, and so a constraint this class did not validate — the unique key — fails at its
        // cause rather than underneath the version written on top of it.
        suites.saveAndFlush(created);
        versions.writeInitial(created, userId, now);

        audit(project.getId(), userId, "suite.created", created, now);

        return new Outcome.Created(view(companyId, project.getId(), created));
    }

    /**
     * Renames a suite, and edits its description.
     *
     * @param expectedVersion the {@code If-Match} the client sent, or null for last-write-wins
     */
    @Transactional
    public Outcome rename(
            UUID userId,
            UUID projectId,
            UUID suiteId,
            SuiteWriteRequest request,
            Long expectedVersion) {

        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, MANAGE_SUITE, project -> {
            Optional<Suite> found = suites.findLiveInProject(project.getId(), suiteId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            Suite suite = found.get();

            // Before validation: a client holding a stale copy should be told to reload rather than
            // told its name is wrong, because the name it is arguing about is not the current one.
            if (expectedVersion != null && expectedVersion != suite.getLockVersion()) {
                return new Outcome.Stale();
            }

            SuiteEdit.Outcome validated =
                    SuiteEdit.validateRename(request, suite.getParentId());
            if (validated instanceof SuiteEdit.Outcome.Invalid invalid) {
                return new Outcome.Invalid(invalid.errors());
            }
            SuiteEdit edit = ((SuiteEdit.Outcome.Valid) validated).edit();

            Instant now = clock.instant();
            suite.applyEdit(edit, userId, now);
            suites.saveAndFlush(suite);

            // No version row: a name is content, and suite_version holds composition. See
            // SuiteVersions for why bumping here would start the every-edit-bumps-every-ancestor
            // spiral V1's own schema comment exists to refuse.
            audit(project.getId(), userId, "suite.renamed", suite, now);

            return new Outcome.Updated(view(companyId, project.getId(), suite));
        }));
    }

    /**
     * Soft-deletes an empty suite.
     *
     * <p>The emptiness check is two queries and both are needed: a folder can be empty of cases while
     * holding child folders, and empty of child folders while holding cases. Answering 204 to either
     * would remove a node the tree still renders children under.
     */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID suiteId) {
        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, DELETE_SUITE, project -> {
            Optional<Suite> found = suites.findLiveInProject(project.getId(), suiteId);
            if (found.isEmpty()) {
                // Includes the already-deleted case, which answers 404 rather than a second 204:
                // findLiveInProject excludes soft-deleted rows, and reporting success for a delete
                // this request did not perform is the same untruth as a 201 on a replayed create.
                return new Outcome.NotFound();
            }
            Suite suite = found.get();

            long children = suites.countLiveChildren(suite.getId());
            long cases = suites.countLiveCasesInSubtree(
                    companyId, project.getId(), suite.getPath());
            if (children > 0 || cases > 0) {
                return new Outcome.NotEmpty(children, cases);
            }

            Instant now = clock.instant();
            suite.softDelete(userId, now);
            suites.saveAndFlush(suite);

            audit(project.getId(), userId, "suite.deleted", suite, now);

            return new Outcome.Deleted();
        }));
    }

    /**
     * Collapses the gate's three states onto this class's own.
     *
     * <p>One place rather than three identical switches, so a fourth gate state cannot be handled in
     * two of the three writes — the failure {@link GateResult}'s sealing exists to make visible and
     * this method makes impossible.
     */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The suite as the API serves it — read back through {@link SuiteService}'s renderer rather than
     * assembled here.
     *
     * <p>Not a second implementation of a suite's shape, for {@link CaseCreateService#render}'s
     * reason: this response is the first thing anyone sees of a folder, so a field rendered
     * differently would show one value on create and another on the reload that follows, with neither
     * screen revealing which is stored. The cost is re-reading the tree, which is one query over a
     * table with as many rows as the project has folders.
     */
    private SuiteView view(UUID companyId, UUID projectId, Suite suite) {
        return tree.render(companyId, projectId).stream()
                .filter(rendered -> rendered.id().equals(suite.getId()))
                .findFirst()
                // Unreachable: the row was written and flushed in this transaction, so the read that
                // follows sees it. Thrown rather than defaulted because a suite that vanished between
                // its own INSERT and the next SELECT is a broken invariant, not a case to render
                // around.
                .orElseThrow(() -> new IllegalStateException(
                        "Suite " + suite.getId() + " was written and did not read back"));
    }

    private void audit(UUID projectId, UUID userId, String action, Suite suite, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(suite.getId()),
                // The key and the name, not the whole row. The key is what an audit reader
                // recognises the folder by and is the one thing the id does not tell them; the name
                // is here because a deleted suite's name is otherwise only recoverable by reading a
                // soft-deleted row, which is precisely what an audit trail should not require.
                "{\"key\":\"" + suite.getKey() + "\",\"name\":"
                        + jsonString(suite.getName()) + "}",
                now));
    }

    /**
     * Escapes a name into a JSON string literal.
     *
     * <p>A suite may be called {@code "Payments" — v2\new}. Concatenating that into the detail JSON
     * unescaped produces a malformed document in a {@code JSONB} column, which fails the insert and
     * takes the whole write down — so a folder with a quote in its name would be uncreatable, and the
     * error would name the audit table rather than the name. The two cases that matter are quotes and
     * backslashes; control characters are escaped as well because a pasted name can carry them.
     */
    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
