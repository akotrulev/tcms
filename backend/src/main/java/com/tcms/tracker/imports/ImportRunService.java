package com.tcms.tracker.imports;

import com.tcms.api.UserRef;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write half of the module: accept a confirmed import, record the run, dispatch it, and serve
 * the report afterwards.
 *
 * <h2>What is decided before the {@code 202}, and what is left to the run</h2>
 *
 * <p>Everything that can be answered without the whole snapshot is answered <strong>here</strong>,
 * synchronously, because the alternative is telling somebody their migration started and then
 * telling them it was never going to work:
 *
 * <ul>
 *   <li>the site, through {@link ImportSites#vet} — the same check the preview makes, and the one
 *       an {@link ImportSource} that composes no URL would otherwise slip;</li>
 *   <li><strong>every source status is mapped</strong>, against
 *       {@link ImportSource#statusNames} — one cheap call against the workflow definition, which is
 *       what makes the contract's {@code 400} a 400 (see that method's own header);</li>
 *   <li>every target status is one of <em>this</em> project's;</li>
 *   <li>the source project's issue count is under {@code tcms.tracker.imports.max-issues};</li>
 *   <li>no completed run already brought this source project in (D-m) — a {@code 409}, computed
 *       from the same repository method the preview's blocker is computed from.</li>
 * </ul>
 *
 * <p>What is left to the run is what needs the snapshot: the key collisions, and the write itself.
 * The preview has already reported the collisions as a blocker, so a run reaching one is a project
 * that changed between the preview and the confirmation — a real state, and the run ends
 * {@code FAILED} with a sentence rather than a constraint violation.
 *
 * <h2>The dispatch happens after the gate's transaction has committed</h2>
 *
 * <p>{@link ProjectService#writeGranted} opens the transaction the {@code RUNNING} row is written
 * in, and {@link ImportWriter#run} is called <em>after</em> that method returns. Dispatching inside
 * it would hand the worker a row id that is not visible to any other transaction yet, and the
 * worker's first read would find nothing — an empty import with no error anywhere.
 *
 * <h2>Every operation costs {@code issue:import}, reads included (D-e)</h2>
 *
 * <p>The report names the source site, the account that connected to it and the person who ran the
 * migration. That is operational detail about somebody's Jira account, and it belongs with the
 * people who can run one.
 */
@Service
public class ImportRunService {

    private static final String IMPORT = "issue:import";

    /** {@code report_job}'s bound, for the same column and the same reason. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    /** Enough to name the problem; not so many that the message becomes the workflow. */
    private static final int MAX_REPORTED_STATUSES = 10;

    private final ProjectService projects;
    private final ImportSources sources;
    private final ImportUrlGuard guard;
    private final ImportProperties properties;
    private final ImportRunRepository runs;
    private final ImportDroppedFieldRepository droppedFields;
    private final com.tcms.tracker.IssueStatusRepository statuses;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final ImportWriter writer;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportRunService(
            ProjectService projects,
            ImportSources sources,
            ImportUrlGuard guard,
            ImportProperties properties,
            ImportRunRepository runs,
            ImportDroppedFieldRepository droppedFields,
            com.tcms.tracker.IssueStatusRepository statuses,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            ImportWriter writer,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.sources = sources;
        this.guard = guard;
        this.properties = properties;
        this.runs = runs;
        this.droppedFields = droppedFields;
        this.statuses = statuses;
        this.users = users;
        this.memberships = memberships;
        this.writer = writer;
        this.json = json;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** A refusal the caller can act on — the contract's {@code 400}. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** This project has already imported that source project (D-m) — the contract's 409. */
        record AlreadyImported(String sourceProjectKey) implements Outcome {
        }

        /** Accepted and dispatched — {@code 202}, with the row to poll. */
        record Accepted(ImportRunView view) implements Outcome {
        }

        /** The {@code Idempotency-Key} had already started this run — 200, not a second one. */
        record Replayed(ImportRunView view) implements Outcome {
        }
    }

    /**
     * {@code POST /imports} — validate, record, dispatch.
     *
     * <p>The two phases are deliberate and are not one method that happens to be long: the first
     * runs inside {@link ProjectService#writeGranted}'s transaction and produces either a refusal
     * or a committed {@code RUNNING} row; the second runs after it and starts the work.
     */
    public GateResult<Outcome> start(UUID userId, ImportInput input, String idempotencyKey) {
        if (input == null || input.projectId() == null) {
            return new GateResult.Unreachable<>();
        }

        GateResult<Outcome> gated = projects.writeGranted(
                userId, input.projectId(), IMPORT,
                project -> accept(userId, project, input, idempotencyKey));

        if (gated instanceof GateResult.Allowed<Outcome> allowed
                && allowed.value() instanceof Outcome.Accepted accepted) {
            // AFTER the transaction above committed — see the class header. The connection is
            // handed over as an argument and is written nowhere (D-a).
            writer.run(new ImportDispatch(
                    CompanyContext.require(),
                    accepted.view().id(),
                    input.projectId(),
                    userId,
                    input.connection(),
                    input.sourceProjectKey(),
                    List.copyOf(input.statusMapping()),
                    input.options()));
        }
        return gated;
    }

    /** {@code GET /imports/{importRunId}} — the report, and the poll target while it is running. */
    @Transactional(readOnly = true)
    public GateResult<ImportRunView> report(UUID userId, UUID importRunId) {
        Optional<ImportRun> found = runs.findById(importRunId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        // Resolved first, for LabelService.onLabel's reason: the project to gate against is a
        // property of the stored row, so a caller whose run does not exist cannot be told 403
        // without the server inventing which project they failed on.
        return projects.readGranted(
                userId, found.get().getProjectId(), IMPORT, project -> viewOf(found.get()));
    }

    /** {@code GET /imports?projectId=} — {@code settings-import.html}'s table, newest first. */
    @Transactional(readOnly = true)
    public GateResult<List<ImportRunSummaryView>> list(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, IMPORT, project ->
                runs.findByProjectIdOrderByStartedAtDesc(project.getId()).stream()
                        .map(this::summaryOf)
                        .toList());
    }

    // =====================================================================

    /** Runs inside the gate's transaction. Either refuses, or leaves a committed {@code RUNNING} row. */
    private Outcome accept(UUID userId, Project project, ImportInput input, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<ImportRun> replay = runs.findByCreatedIdempotencyKey(idempotencyKey);
            if (replay.isPresent()) {
                // Before anything is read from the source, for IssueService.validateAndCreate's
                // reason: a retry of one intent must not cost a second whole read pass.
                return new Outcome.Replayed(viewOf(replay.get()));
            }
        }

        Map<String, List<String>> shape = validateShape(input);
        if (!shape.isEmpty()) {
            return new Outcome.Invalid(shape);
        }

        if (runs.existsByProjectIdAndProviderAndSiteUrlAndSourceProjectKeyAndStatus(
                project.getId(),
                input.connection().provider(),
                input.connection().siteUrl(),
                input.sourceProjectKey(),
                ImportRunStatus.COMPLETED)) {
            return new Outcome.AlreadyImported(input.sourceProjectKey());
        }

        ImportSites.vet(guard, input.connection());
        ImportSource source = sources.forProvider(input.connection().provider());

        Map<String, List<String>> refused = validateAgainstSource(project, input, source);
        if (!refused.isEmpty()) {
            return new Outcome.Invalid(refused);
        }

        ImportRun run = runs.save(ImportRun.start(
                UUID.randomUUID(),
                CompanyContext.require(),
                project.getId(),
                input.connection(),
                input.sourceProjectKey(),
                input.options(),
                userId,
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey,
                clock.instant()));

        return new Outcome.Accepted(viewOf(run));
    }

    /** Everything answerable without dialling anything. */
    private Map<String, List<String>> validateShape(ImportInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (input.connection() == null || input.connection().provider() == null
                || blank(input.connection().siteUrl()) || blank(input.connection().apiToken())) {
            errors.put("connection", List.of(
                    "Name the provider, the site address and an API token. The token is used for "
                            + "this import and is not stored afterwards."));
        }
        if (blank(input.sourceProjectKey())) {
            errors.put("sourceProjectKey", List.of("Name the project to import from."));
        }
        if (input.options() == null) {
            errors.put("options", List.of(
                    "Send both options. A dropped boolean would default to false, and one of the "
                            + "two defaults to true."));
        }
        if (input.statusMapping() == null || input.statusMapping().isEmpty()) {
            errors.put("statusMapping", List.of(
                    "Map every status the source project uses onto one of this project's. Nothing "
                            + "is defaulted: an unmapped status would put its issues in the wrong "
                            + "column of a board."));
        }
        return errors;
    }

    /**
     * The two refusals that need the source, and neither needs the snapshot.
     *
     * <p>{@code statusNames} and {@code projects} are one request each against definitions, where
     * the snapshot is minutes against every issue. That difference is the whole reason both of
     * these are a {@code 400} on this request rather than a {@code FAILED} row four minutes later.
     */
    private Map<String, List<String>> validateAgainstSource(
            Project project, ImportInput input, ImportSource source) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        Set<UUID> projectStatusIds = new LinkedHashSet<>();
        statuses.findByProjectIdOrderByPositionAsc(project.getId())
                .forEach(status -> projectStatusIds.add(status.getId()));

        List<String> foreign = input.statusMapping().stream()
                .map(StatusMappingEntry::targetStatusId)
                .filter(id -> id == null || !projectStatusIds.contains(id))
                .map(String::valueOf)
                .distinct()
                .toList();
        if (!foreign.isEmpty()) {
            errors.put("statusMapping", List.of(
                    foreign.size() + " of those target statuses do not belong to this project. A "
                            + "status is mapped onto one of this project's own, and never onto one "
                            + "the import would have to create."));
        }

        Set<String> mapped = new LinkedHashSet<>();
        input.statusMapping().forEach(entry -> mapped.add(fold(entry.sourceStatusName())));

        List<String> unmapped = source.statusNames(input.connection(), input.sourceProjectKey())
                .stream()
                .filter(name -> !mapped.contains(fold(name)))
                .toList();
        if (!unmapped.isEmpty()) {
            errors.merge("statusMapping", List.of(
                    "These source statuses have no mapping: "
                            + String.join(", ",
                                    unmapped.stream().limit(MAX_REPORTED_STATUSES).toList())
                            + (unmapped.size() > MAX_REPORTED_STATUSES
                                    ? " and " + (unmapped.size() - MAX_REPORTED_STATUSES) + " more"
                                    : "")
                            + ". Every one of them has to name one of this project's statuses "
                            + "before the import can start."),
                    (first, second) -> {
                        List<String> both = new ArrayList<>(first);
                        both.addAll(second);
                        return List.copyOf(both);
                    });
        }

        int sourceIssues = source.projects(input.connection()).stream()
                .filter(found -> found.key().equalsIgnoreCase(input.sourceProjectKey()))
                .findFirst()
                .map(SourceProject::issueCount)
                .orElseThrow(() -> new ImportSourceUnavailableException(
                        "That project was not found, or this account cannot read it."));
        if (sourceIssues > properties.maxIssues()) {
            errors.put("sourceProjectKey", List.of(
                    "That project holds " + sourceIssues + " issues and this instance imports at "
                            + "most " + properties.maxIssues() + " in one run."));
        }
        return errors;
    }

    // ---------------------------------------------------------------- views

    private ImportRunView viewOf(ImportRun run) {
        return new ImportRunView(
                run.getId(),
                run.getProjectId(),
                run.getProvider(),
                run.getSiteUrl(),
                run.getAccountEmail(),
                run.getSourceProjectKey(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                startedByOf(run.getStartedBy()),
                run.getIssuesImported(),
                run.getReleasesCreated(),
                run.getCommentsImported(),
                run.getLabelsCreated(),
                run.getDroppedValueCount(),
                run.isLabelPerSprint(),
                run.isBacklogOrderPreserved(),
                run.getFailureReason(),
                droppedFields.findByImportRunIdOrderByPositionAscFieldNameAsc(run.getId()).stream()
                        .map(ImportDroppedField::toView)
                        .toList(),
                readJson(run.getStatusMapping(), new TypeReference<List<AppliedStatusMappingView>>() { }),
                readJson(run.getPeople(), new TypeReference<List<ImportedPersonView>>() { }));
    }

    private ImportRunSummaryView summaryOf(ImportRun run) {
        return new ImportRunSummaryView(
                run.getId(),
                run.getProvider(),
                run.getSiteUrl(),
                run.getSourceProjectKey(),
                run.getStatus(),
                run.getStartedAt(),
                run.getIssuesImported(),
                readJson(run.getStatusMapping(),
                        new TypeReference<List<AppliedStatusMappingView>>() { }).size(),
                run.getLabelsCreated(),
                // Counted rather than read: the list is what the DETAIL serves, and thirty rows of
                // a table each carrying its own dropped-field list is the reason the summary is a
                // separate shape at all.
                droppedFields.findByImportRunIdOrderByPositionAscFieldNameAsc(run.getId()).size());
    }

    /**
     * Who ran it, named through the active company's membership.
     *
     * <p>The membership's display name wins and the account's canonical name is the fallback —
     * plan §6u's rule, which {@link UserRef} states and this does not restate a second time.
     * <strong>Null when the account no longer resolves</strong>, which is why the contract declares
     * this required and nullable: {@code import_run.started_by} carries no foreign key.
     */
    private UserRef startedByOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        Optional<AppUser> user = users.findById(userId);
        if (user.isEmpty()) {
            return null;
        }
        String name = memberships
                .findByCompanyIdAndUserId(CompanyContext.require(), userId)
                .map(CompanyMembership::getDisplayName)
                .orElseGet(() -> user.get().getCanonicalName());
        // avatarUrl is null for everyone, as it is everywhere else this record is built: nothing
        // uploads one.
        return new UserRef(userId, name, user.get().getEmail(), null);
    }

    /**
     * @throws IllegalStateException when the column will not parse. Loud rather than an empty list:
     *         the two JSONB columns are the report's substance, and a report that silently rendered
     *         "no statuses were mapped" would be worse than one that failed to render.
     */
    private <T> T readJson(String document, TypeReference<T> shape) {
        try {
            return json.readValue(document == null || document.isBlank() ? "[]" : document, shape);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "import_run holds a JSON document this build cannot read", unreadable);
        }
    }

    private static String fold(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
