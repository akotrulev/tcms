package com.tcms.tracker.imports;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueRepository;
import com.tcms.tracker.IssueStatus;
import com.tcms.tracker.IssueStatusRepository;
import com.tcms.tracker.IssueType;
import com.tcms.tracker.IssueTypeRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dry run — what an import would bring across, what it would drop, and what would stop it
 * (D32, story J1).
 *
 * <h2>Nothing here writes, and that is an observer rather than a convention</h2>
 *
 * <p>{@code ImportPreviewWritesNothingTest} snapshots every company-owned table before and after a
 * preview and fails on any difference. {@code import-preview.html} tells somebody <em>"No data has
 * been written. Leaving this page now changes nothing"</em> at the moment they are deciding whether
 * to trust this product with a migration, and a promise on a screen with nothing behind it is the
 * one that quietly stops being true.
 *
 * <h2>Three passes, and the middle one is deliberately outside every transaction (D-b)</h2>
 *
 * <ol>
 *   <li>The gate, in {@link ProjectService#readGranted}'s read-only transaction, which ends before
 *       anything is dialled.</li>
 *   <li>The <strong>read</strong> — minutes long, against a third party, holding no database
 *       connection at all. A transaction spanning it would hold a pool slot open against somebody
 *       else's availability, one per import, on the operation most likely to be slow.</li>
 *   <li>{@link #assemble}, a short read-only transaction over this project's own types, statuses
 *       and keys.</li>
 * </ol>
 *
 * <p>The gate is not re-asked in pass 3, and it does not need to be: the caller is the same and a
 * grant revoked between the two would at worst let one preview finish, which writes nothing.
 *
 * <h2>The mapping is a function of the snapshot and this project, and of nothing else</h2>
 *
 * <p>No provider appears below this line. That is what makes T5's second source a file rather than
 * a slice, and it is why {@link #assemble} can be driven by {@code FakeImportSource} without a
 * Jira anywhere in the test.
 */
@Service
public class ImportPreviewService {

    /**
     * Every operation in this module costs this key, reads included (D-e) — the report names the
     * source site, the account that connected to it and the person who ran the migration, which is
     * operational detail about somebody's Jira account.
     */
    private static final String IMPORT = "issue:import";

    /** Enough to make the collision concrete; not so many that the screen becomes the list. */
    private static final int MAX_REPORTED_COLLISIONS = 10;

    private final ProjectService projects;
    private final ImportSources sources;
    private final ImportUrlGuard guard;
    private final ImportProperties properties;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final IssueRepository issues;
    private final EntityManager entityManager;
    private final TransactionTemplate readOnly;

    public ImportPreviewService(
            ProjectService projects,
            ImportSources sources,
            ImportUrlGuard guard,
            ImportProperties properties,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            IssueRepository issues,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {

        this.projects = projects;
        this.sources = sources;
        this.guard = guard;
        this.properties = properties;
        this.types = types;
        this.statuses = statuses;
        this.issues = issues;
        this.entityManager = entityManager;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * The projects one credential can see, for the connect step's dropdown.
     *
     * <p><strong>Gated on holding {@code issue:import} on any visible project, which is the only
     * question this request can answer.</strong> The body carries a credential and no project id —
     * deliberately, because the operation is a convenience for finding a source key and not a step
     * anybody is required to take — so there is no project to gate on. Asking the key at company
     * scope instead would be wrong in the other direction: {@code issue:import} is granted per
     * project (roles spec §A3), so a Project Manager holding it on their own project would be
     * refused. What is conceded by this shape is stated rather than hidden: somebody with the key
     * on one project can list the source projects a credential of <em>theirs</em> can see, which
     * discloses nothing this instance knows.
     *
     * <p>Two states rather than three, for {@link ProjectService#createGranted}'s reason: nothing
     * is named, so there is nothing whose existence a 404 would conceal.
     */
    public GateResult<SourceProjectListView> sourceProjects(
            UUID userId, ImportConnection connection) {

        if (!mayImportSomewhere(userId)) {
            return new GateResult.Denied<>();
        }
        vet(connection);
        List<SourceProject> found = sources.forProvider(connection.provider()).projects(connection);
        return new GateResult.Allowed<>(new SourceProjectListView(
                found.stream().map(SourceProjectView::of).toList()));
    }

    /** What the import would do, computed against this project and written nowhere. */
    public GateResult<ImportPreviewView> preview(UUID userId, ImportPreviewInput input) {
        GateResult<ProjectFacts> gate =
                projects.readGranted(userId, input.projectId(), IMPORT, ProjectFacts::of);

        return switch (gate) {
            case GateResult.Unreachable<ProjectFacts> ignored -> new GateResult.Unreachable<>();
            case GateResult.Denied<ProjectFacts> ignored -> new GateResult.Denied<>();
            case GateResult.Allowed<ProjectFacts> allowed ->
                    new GateResult.Allowed<>(read(allowed.value(), input));
        };
    }

    /**
     * The credential-carrying pass — <strong>no transaction is open here</strong>, which is the
     * whole of D-b's first half.
     */
    private ImportPreviewView read(ProjectFacts project, ImportPreviewInput input) {
        vet(input.connection());
        ImportSource source = sources.forProvider(input.connection().provider());
        int sourceIssues = issueCountOf(source, input);

        if (sourceIssues > properties.maxIssues()) {
            return overCap(project, input, sourceIssues);
        }
        SourceProjectSnapshot snapshot =
                source.snapshot(input.connection(), input.sourceProjectKey(), count -> { });
        return readOnly.execute(status -> assemble(project, input, snapshot));
    }

    /**
     * The site, checked <strong>before a source is chosen</strong>, let alone dialled.
     *
     * <p>{@link ImportHttp} guards every URL it composes as well, and that is not this check
     * repeated: the guard belongs to the <em>connection</em> here — a policy about the address
     * somebody typed, decided before any adapter sees the credential — and to the whole address
     * there, which is the only place a site that passes and a path that goes elsewhere can be told
     * apart. Only this one is reachable by an {@link ImportSource} that composes no URL, which is
     * every fake and would be every future source that speaks something other than HTTP.
     *
     * @throws ImportUrlRefusedException which the controller answers 400 — the address is wrong and
     *         the person who typed it can fix it
     */
    private void vet(ImportConnection connection) {
        ImportUrlGuard.Verdict verdict = guard.check(connection.siteUrl());
        if (verdict != ImportUrlGuard.Verdict.ALLOWED) {
            throw ImportUrlRefusedException.forUrl(connection.siteUrl(), verdict);
        }
    }

    /**
     * The source's own count for the named project, asked before anything is read whole.
     *
     * <p>One extra round trip, against reading a project that cannot be imported: D-b holds the
     * whole snapshot in heap, so an over-large source has to be refused by a sentence rather than
     * discovered by a run that dies at minute four. This is what {@link SourceProject#issueCount}
     * exists for.
     *
     * @throws ImportSourceUnavailableException when the credential cannot see the named project —
     *         one state for every source-side failure, so the endpoint is not an oracle for what
     *         exists in a Jira the caller cannot read
     */
    private int issueCountOf(ImportSource source, ImportPreviewInput input) {
        return source.projects(input.connection()).stream()
                .filter(project -> project.key().equalsIgnoreCase(input.sourceProjectKey()))
                .findFirst()
                .map(SourceProject::issueCount)
                .orElseThrow(() -> new ImportSourceUnavailableException(
                        "That project was not found, or this account cannot read it."));
    }

    /**
     * The refusal, shaped as a preview rather than as an error.
     *
     * <p>A blocker is how this module says no — {@code blockers} non-empty is what disables the
     * wizard's Continue — so an over-large source answers 200 with one blocker and empty counts,
     * and not a 4xx the connect step would render as "something went wrong". The number is in the
     * sentence because "too large" without it sends somebody to guess at what would fit.
     */
    private ImportPreviewView overCap(ProjectFacts project, ImportPreviewInput input, int found) {
        return new ImportPreviewView(
                project.id(),
                input.sourceProjectKey(),
                input.sourceProjectKey(),
                project.key(),
                new ImportPreviewTotalsView(found, 0, 0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(ImportBlockerView.of(
                        ImportBlockerKind.TOO_MANY_ISSUES,
                        "That project holds " + found + " issues and this instance imports at "
                                + "most " + properties.maxIssues() + " in one run.")));
    }

    /**
     * The pure half — a snapshot and this project's own workflow become the preview.
     *
     * <p>Short, and run through a <strong>read-only</strong> {@link TransactionTemplate} rather
     * than behind {@code @Transactional}: the call is a self-invocation from {@link #read}, which
     * Spring's proxy does not see, so the annotation would be a transaction boundary that silently
     * is not there and every RLS-protected read inside would return zero rows.
     * {@code ReportJobRunner} took the same idiom for the same reason. Read-only is not decoration
     * here either — it is the module's central promise enforced one layer below the test that
     * asserts it.
     */
    ImportPreviewView assemble(
            ProjectFacts project, ImportPreviewInput input, SourceProjectSnapshot snapshot) {

        List<IssueType> projectTypes = types.findByProjectIdOrderByPositionAsc(project.id());
        List<IssueStatus> projectStatuses = statuses.findByProjectIdOrderByPositionAsc(project.id());

        return new ImportPreviewView(
                project.id(),
                input.sourceProjectKey(),
                sourceKeyPrefix(snapshot, input.sourceProjectKey()),
                project.key(),
                totalsOf(snapshot),
                snapshot.types().stream().map(type -> typeRow(type, projectTypes)).toList(),
                snapshot.statuses().stream()
                        .map(status -> statusRow(status, projectStatuses))
                        .toList(),
                // false: the option is chosen on the mapping step, after the preview has been read
                // (D-h). The row is in the list either way; only its sentence moves.
                DroppedFields.of(snapshot, false),
                blockersOf(project, input, snapshot));
    }

    private static ImportPreviewTotalsView totalsOf(SourceProjectSnapshot snapshot) {
        Set<String> labels = new LinkedHashSet<>();
        int comments = 0;
        int withSprints = 0;
        for (SourceIssue issue : snapshot.issues()) {
            labels.addAll(issue.labels());
            comments += issue.comments().size();
            if (!issue.sprintNames().isEmpty()) {
                withSprints++;
            }
        }
        return new ImportPreviewTotalsView(
                snapshot.issues().size(),
                labels.size(),
                comments,
                snapshot.historyEntryCount(),
                snapshot.versions().size(),
                // Issues holding a sprint value, not distinct sprint names: this is the count on
                // the same footing as DroppedField.valueCount, which is what the screen shows it
                // beside.
                withSprints);
    }

    /**
     * The prefix imported keys keep (D5) — taken from the issues themselves rather than from the
     * key the request named.
     *
     * <p>They are the same on every source this module reads, and taking it from the data is what
     * makes the screen's <em>"PROJ-4812 stays PROJ-4812"</em> line true of the keys that will
     * actually be written rather than of what somebody typed into the wizard.
     */
    private static String sourceKeyPrefix(SourceProjectSnapshot snapshot, String requested) {
        return snapshot.issues().stream()
                .map(SourceIssue::key)
                .filter(key -> key.contains("-"))
                .findFirst()
                .map(key -> key.substring(0, key.indexOf('-')))
                .orElse(requested);
    }

    /**
     * By name, then by level — and every arm lands on a type that exists.
     *
     * <p>Type mapping is nobody's decision, unlike the status mapping, because a wrong type is a
     * chip with the wrong letter on it while a wrong status puts an issue in the wrong column of a
     * board. The fallback is the level rather than a default type, so a source {@code Sub-task}
     * arrives as something the depth rule treats as a task (scope §4 — levels, never names).
     */
    private static PreviewTypeRow typeRow(SourceType source, List<IssueType> projectTypes) {
        IssueType target = byName(projectTypes, source.name())
                .or(() -> byLevel(projectTypes, levelFor(source)))
                .orElseThrow(() -> new IllegalStateException(
                        "the project has no issue type at level " + levelFor(source)
                                + "; tcms_seed_project_defaults gives every project one of each, "
                                + "so this is a project whose workflow was emptied"));

        return new PreviewTypeRow(
                source.name(),
                target.getId(),
                target.getName(),
                target.getLevel(),
                target.isCountsTowardCompletion(),
                source.count());
    }

    private static IssueLevel levelFor(SourceType source) {
        if (source.subtask()) {
            return IssueLevel.TASK;
        }
        return "epic".equalsIgnoreCase(source.name()) ? IssueLevel.EPIC : IssueLevel.STORY;
    }

    private static Optional<IssueType> byName(List<IssueType> projectTypes, String name) {
        return projectTypes.stream()
                .filter(type -> type.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    private static Optional<IssueType> byLevel(List<IssueType> projectTypes, IssueLevel level) {
        return projectTypes.stream().filter(type -> type.getLevel() == level).findFirst();
    }

    /**
     * A <strong>suggestion</strong>, and never a decision.
     *
     * <p>Name equality ignoring case is the mockup's <em>name matched</em> badge and the only claim
     * this product will make about a status somebody else named. An unmatched status arrives here
     * with a null suggestion and the run refuses it unless the confirmed request maps it.
     */
    private static PreviewStatusRow statusRow(
            SourceStatus source, List<IssueStatus> projectStatuses) {

        Optional<IssueStatus> matched = projectStatuses.stream()
                .filter(status -> status.getName().equalsIgnoreCase(source.name()))
                .findFirst();

        return new PreviewStatusRow(
                source.name(),
                source.count(),
                matched.isPresent(),
                matched.map(IssueStatus::getId).orElse(null));
    }

    private List<ImportBlockerView> blockersOf(
            ProjectFacts project, ImportPreviewInput input, SourceProjectSnapshot snapshot) {

        List<ImportBlockerView> blockers = new ArrayList<>();

        if (snapshot.issues().size() > properties.maxIssues()) {
            // The source's own count was under the cap and the read came back over it — a project
            // that grew between the two calls, or a source whose count is an estimate. Checked
            // again rather than trusted, because the cap is a bound on this process's heap and the
            // number that matters is the one actually read.
            blockers.add(ImportBlockerView.of(
                    ImportBlockerKind.TOO_MANY_ISSUES,
                    "That project holds " + snapshot.issues().size() + " issues and this instance "
                            + "imports at most " + properties.maxIssues() + " in one run."));
        }

        List<String> colliding = collidingKeys(snapshot);
        if (!colliding.isEmpty()) {
            blockers.add(new ImportBlockerView(
                    ImportBlockerKind.KEY_COLLISION,
                    colliding.size() + " of the source's issue keys already exist in this company. "
                            + "Imported issues keep their original key, so the import cannot "
                            + "create a second one.",
                    colliding.stream().limit(MAX_REPORTED_COLLISIONS).toList()));
        }

        if (alreadyImported(project.id(), input)) {
            blockers.add(ImportBlockerView.of(
                    ImportBlockerKind.ALREADY_IMPORTED,
                    "This source project has already been imported into this project. There is no "
                            + "delta import: re-importing would try to create every issue a "
                            + "second time."));
        }
        return List.copyOf(blockers);
    }

    /**
     * Keys that already exist, company-wide and <strong>including soft-deleted issues</strong>.
     *
     * <p>{@code uq_issue_key} is {@code (company_id, issue_key)} with no {@code deleted_at}
     * predicate, so a deleted {@code PROJ-4812} still owns the key. Filtering deleted rows out here
     * would let the preview promise something the database refuses.
     */
    private List<String> collidingKeys(SourceProjectSnapshot snapshot) {
        List<String> sourceKeys = snapshot.issues().stream().map(SourceIssue::key).toList();
        if (sourceKeys.isEmpty()) {
            return List.of();
        }
        return issues.findExistingKeys(sourceKeys);
    }

    /**
     * Whether a completed run already brought this source project into this one (D-m).
     *
     * <p>Native, and against no entity: {@code ImportRun} is T4's, and a preview that could not be
     * written until the writer exists would be the wrong dependency between the two. The predicate
     * is {@code uq_import_run_completed_source}'s, so this answers the same question the constraint
     * would answer four minutes later.
     */
    private boolean alreadyImported(UUID projectId, ImportPreviewInput input) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM import_run
                        WHERE project_id = :projectId
                          AND provider = :provider
                          AND site_url = :siteUrl
                          AND source_project_key = :sourceProjectKey
                          AND status = 'COMPLETED'
                        LIMIT 1
                        """)
                .setParameter("projectId", projectId)
                .setParameter("provider", input.connection().provider().name())
                .setParameter("siteUrl", input.connection().siteUrl())
                .setParameter("sourceProjectKey", input.sourceProjectKey())
                .getResultList()
                .isEmpty();
    }

    boolean mayImportSomewhere(UUID userId) {
        List<UUID> visible =
                projects.readAllVisible(userId, all -> all.stream().map(Project::getId).toList());
        return visible.stream().anyMatch(projectId -> projects.holds(userId, projectId, IMPORT));
    }

    /**
     * The two things about the gated project the passes after the gate need.
     *
     * <p>A record and not the {@link Project} entity, because the gate's transaction closes before
     * the source system is dialled and an entity carried out of it is detached — the trap
     * {@code TransitionService} and {@code RankService} both paid a round trip to find.
     */
    record ProjectFacts(UUID id, String key) {

        static ProjectFacts of(Project project) {
            return new ProjectFacts(project.getId(), project.getKey());
        }
    }
}
