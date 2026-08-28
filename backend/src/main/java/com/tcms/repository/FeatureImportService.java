package com.tcms.repository;

import com.tcms.api.AutomationKeys;
import com.tcms.api.PatchBody;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /projects/{projectId}/feature-imports} — a folder of {@code .feature} files becomes
 * suites, library preconditions and cases keyed for Cucumber.
 *
 * <h2>Plan, then apply, and the plan is the same code both times</h2>
 *
 * <p>{@code PREVIEW} builds a {@link FeatureImportPlan} and returns it. {@code APPLY} builds the
 * <em>same</em> plan inside the transaction and then executes it. There is no second decision
 * procedure and no "what the caller approved" carried across a request — a plan is cheap, the
 * inputs are in the body, and recomputing is what makes a preview honest under a concurrent writer:
 * a case created between the two calls turns a {@code CREATE} row into a {@code SKIP} rather than
 * into a constraint violation.
 *
 * <h2>This class assembles nothing</h2>
 *
 * <p>No {@code new TestCase}, no {@code new TestStep}, no {@code new Condition}. Every write goes
 * through {@link CaseCreateService}, {@link CaseUpdateService}, {@link SuiteWriteService} or
 * {@link ConditionService}, so an imported case is validated by {@link CaseEdit}, versioned by
 * {@code CaseVersions}, audited, and health-recomputed exactly as an authored one is. The
 * alternative was measured elsewhere in this repository and has a name: a second normalizer
 * produces content hashes that disagree with the editor's, silently, and the next ordinary save of
 * every imported case writes a version whose diff is empty ({@code CaseEdit.of}'s header).
 *
 * <p>The one thing written directly is the provenance triple, through
 * {@link TestCase#recordImport} — it is not authored content, so it is not on {@link CaseEdit} and
 * has no business in a version.
 *
 * <h2>Permission is decided PER ROW, and never as a 403</h2>
 *
 * <p>{@code case:read} on a visible project is what it takes to call this at all. Everything else
 * is a row: a caller with {@code case:read} and nothing else gets a complete plan in which every
 * {@code CREATE} is {@code REFUSE "needs case:create"}. That is the shape a preview needs — a
 * request that 403s tells a test engineer nothing about which of their forty files they could
 * import if somebody granted them one key — and it is why {@link ProjectService#holds} is asked
 * once per key here rather than being left to the services' own gates. The services still gate;
 * this decides what to attempt.
 *
 * <h2>One transaction</h2>
 *
 * <p>Conditions, then suites, then cases, then provenance. Nothing half-imported: a constraint
 * violation on a raced key rolls the whole request back and answers 409, because a folder that
 * imported its first eleven files is a state nobody can reason about or repeat.
 */
@Service
public class FeatureImportService {

    private static final String CREATE_CASE = "case:create";
    private static final String EDIT_CASE = "case:edit";
    private static final String MANAGE_SUITE = "suite:manage";
    private static final String MANAGE_LIBRARY = "library:manage";

    /** {@code api-surface.md} §3 — the request-shape bounds, all four checked before anything else. */
    static final int MAX_FILES = 200;
    static final int MAX_PATH = 1024;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    /** The same cap {@code POST /cases/bulk} uses, applied to the unit this endpoint writes. */
    static final int MAX_SCENARIOS = 500;

    /**
     * The prefix on every {@code condition.imported_key} — {@code data-model.md} §3.
     *
     * <p>A key that is visibly namespaced rather than a bare hash, so a human reading the column
     * can tell what wrote it, and so a later import of something that is not a Background has a
     * prefix of its own to take rather than a collision to debug.
     */
    private static final String KEY_PREFIX = "bg:";

    /**
     * Where a title is cut, and why it is cut rather than refused (OQ3, answer A).
     *
     * <p>{@code ConditionEdit.MAX_TITLE} is 150 and a Gherkin step is occasionally longer than
     * that. Refusing the file would make one long sentence in one {@code Background:} block the
     * reason forty files did not import, for a limit that exists to keep a picker readable. So the
     * title is cut at 147 and marked, the {@code conditions[]} row says {@code "title truncated to
     * 150 characters"}, and the <em>body</em> — which is what a tester actually follows — keeps the
     * step whole.
     */
    private static final int TITLE_BUDGET = ConditionEdit.MAX_TITLE - 3;

    private final ProjectService projects;
    /**
     * Read ONLY to name a sibling project in one refusal sentence — never to decide anything.
     *
     * <p>This module's package comment draws the line and it is the same one: {@code identity} is
     * asked <em>what is this person called</em> and never <em>may they</em>. This is that
     * distinction one module over. {@code test_case_automation_key_idx} is unique per COMPANY, so a
     * key taken in a sibling project makes the row impossible here, and
     * {@code api-surface.md} §5.4 fixes the sentence that says so:
     * {@code "automationKey already used by TC-412 in project PAY"}. Answering it without the
     * project key would leave a test engineer told their scenario cannot be imported and not told
     * where the key went.
     *
     * <p>{@code findScopedById} is RLS-scoped to the company, so nothing outside the caller's own
     * client is reachable through it, and no permission question is asked or answered here.
     */
    private final ProjectRepository projectRows;
    private final TestCaseRepository cases;
    private final TestStepRepository stepRows;
    private final SuiteRepository suites;
    private final ConditionRepository conditionRows;
    private final CaseConditions caseConditions;
    private final CaseCreateService creation;
    private final CaseUpdateService updates;
    private final SuiteWriteService suiteWrites;
    private final ConditionService conditions;
    private final Clock clock;

    public FeatureImportService(
            ProjectService projects,
            ProjectRepository projectRows,
            TestCaseRepository cases,
            TestStepRepository stepRows,
            SuiteRepository suites,
            ConditionRepository conditionRows,
            CaseConditions caseConditions,
            CaseCreateService creation,
            CaseUpdateService updates,
            SuiteWriteService suiteWrites,
            ConditionService conditions,
            Clock clock) {

        this.projects = projects;
        this.projectRows = projectRows;
        this.cases = cases;
        this.stepRows = stepRows;
        this.suites = suites;
        this.conditionRows = conditionRows;
        this.caseConditions = caseConditions;
        this.creation = creation;
        this.updates = updates;
        this.suiteWrites = suiteWrites;
        this.conditions = conditions;
        this.clock = clock;
    }

    /** What the controller renders. */
    public sealed interface Outcome {

        /** Project absent, in another company, or one the caller holds no grant on — one 404. */
        record NotFound() implements Outcome {
        }

        /** The request shape itself is wrong — {@code errors.files} or {@code errors.suiteId}. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The plan, in either mode. A refused row is in here, not out here. */
        record Planned(FeatureImportPlan plan) implements Outcome {
        }
    }

    /**
     * Plans, and in {@code APPLY} executes.
     *
     * <p>Gated by {@link ProjectService#readVisible} rather than {@code writeGranted}: the write
     * keys are per-row, and a caller who can read the project is entitled to ask what an import
     * would do. The unreachable project answers the same bodiless 404 every read in this module
     * answers, from an empty {@link Optional} the controller has nothing to un-collapse.
     */
    @Transactional
    public Outcome run(UUID userId, UUID projectId, FeatureImportRequest request) {
        return projects.readVisible(userId, projectId, project -> plan(userId, project.getId(), request))
                .orElseGet(Outcome.NotFound::new);
    }

    // =====================================================================
    // the plan
    // =====================================================================

    private Outcome plan(UUID userId, UUID projectId, FeatureImportRequest request) {
        Map<String, List<String>> errors = shapeErrors(request);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        List<Suite> liveSuites = suites.findLiveForProject(projectId);
        if (request.suiteId() != null
                && liveSuites.stream().noneMatch(suite -> suite.getId().equals(request.suiteId()))) {
            return new Outcome.Invalid(Map.of("suiteId",
                    List.of("That suite is not a live suite of this project.")));
        }

        boolean apply = request.mode() == FeatureImportRequest.Mode.APPLY;
        Keys keys = new Keys(
                projects.holds(userId, projectId, CREATE_CASE),
                projects.holds(userId, projectId, EDIT_CASE),
                projects.holds(userId, projectId, MANAGE_SUITE),
                projects.holds(userId, projectId, MANAGE_LIBRARY));

        List<ParsedFile> parsed = parseAll(request);
        if (countScenarios(parsed) > MAX_SCENARIOS) {
            return new Outcome.Invalid(Map.of("files",
                    List.of("more than " + MAX_SCENARIOS + " scenarios in one request")));
        }

        // ---- conditions: one per DISTINCT Background step across the whole request (AM1) ----
        Map<String, Backgrounds.Entry> backgrounds = Backgrounds.distinctIn(parsed);
        Map<String, Condition> existingConditions = backgrounds.isEmpty()
                ? new HashMap<>()
                : byKey(conditionRows.findLiveImportedInProject(projectId, backgrounds.keySet()));
        List<FeatureImportPlan.ConditionAction> conditionActions =
                planConditions(backgrounds, existingConditions, keys);

        // ---- suites: one per distinct Feature: name, unless the request named one ----
        Map<String, FeatureImportPlan.SuiteAction> suiteActions =
                planSuites(parsed, liveSuites, request, keys);

        // ---- cases ----
        Map<String, TestCase> byAutomationKey = existingCases(parsed);

        if (apply) {
            writeConditions(userId, projectId, backgrounds, existingConditions, conditionActions);
            writeSuites(userId, projectId, suiteActions);
        }

        List<FeatureImportPlan.ImportRow> rows = new ArrayList<>();
        for (ParsedFile file : parsed) {
            rows.addAll(planFile(
                    userId, projectId, file, request, keys, suiteActions, byAutomationKey,
                    backgrounds, existingConditions, apply));
        }

        return new Outcome.Planned(new FeatureImportPlan(
                request.mode(),
                apply,
                List.copyOf(rows),
                List.copyOf(suiteActions.values()),
                List.copyOf(conditionActions),
                orphans(projectId, request, suiteActions, parsed),
                totals(rows)));
    }

    /** The four write keys, answered once per request rather than once per row. */
    private record Keys(boolean create, boolean edit, boolean suite, boolean library) {
    }

    // =====================================================================
    // request shape
    // =====================================================================

    /**
     * The four bounds on {@code files}, all collected — a caller sending 300 files of 2 MiB each
     * should be told both things, not the first one.
     */
    private Map<String, List<String>> shapeErrors(FeatureImportRequest request) {
        List<String> problems = new ArrayList<>();
        List<FeatureImportRequest.FeatureFile> files =
                request.files() == null ? List.of() : request.files();

        if (files.isEmpty()) {
            problems.add("Send at least one .feature file.");
        }
        if (files.size() > MAX_FILES) {
            problems.add("Send at most " + MAX_FILES + " files in one request.");
        }

        Set<String> paths = new HashSet<>();
        long total = 0;
        for (FeatureImportRequest.FeatureFile file : files) {
            String path = file == null ? null : file.path();
            if (path == null || path.isBlank() || path.length() > MAX_PATH) {
                problems.add("Every file needs a path of 1 to " + MAX_PATH + " characters.");
                continue;
            }
            if (!paths.add(path)) {
                problems.add("Two files share the path '" + path + "'.");
            }
            int bytes = content(file).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_FILE_BYTES) {
                problems.add("'" + path + "' is larger than 1 MiB.");
            }
            total += bytes;
        }
        if (total > MAX_TOTAL_BYTES) {
            problems.add("The files total more than 8 MiB.");
        }

        // De-duplicated: 300 oversized files must not answer with 300 identical sentences.
        List<String> distinct = List.copyOf(new LinkedHashSet<>(problems));
        return distinct.isEmpty() ? Map.of() : Map.of("files", distinct);
    }

    private static String content(FeatureImportRequest.FeatureFile file) {
        return file == null || file.content() == null ? "" : file.content();
    }

    // =====================================================================
    // parsing
    // =====================================================================

    /**
     * One file, parsed — or one file, refused.
     *
     * @param refusal the whole-file reason, or null. A file with a refusal contributes exactly one
     *                {@code REFUSE} row and no scenarios, which is what keeps a syntax error in one
     *                file from being an error for the request.
     */
    private record ParsedFile(String path, ParsedFeature feature, String refusal) {
    }

    private List<ParsedFile> parseAll(FeatureImportRequest request) {
        List<ParsedFile> parsed = new ArrayList<>(request.files().size());
        for (FeatureImportRequest.FeatureFile file : request.files()) {
            try {
                ParsedFeature feature = GherkinScenarios.parseFeature(content(file));
                if (feature.name() == null) {
                    parsed.add(new ParsedFile(file.path(), null, "Feature has no name"));
                } else {
                    parsed.add(new ParsedFile(file.path(), feature, null));
                }
            } catch (GherkinSyntaxException refused) {
                // The parser's own sentence, with its line and column. Re-wording it here would
                // leave the caller a list of file names and nothing to open the file at.
                parsed.add(new ParsedFile(file.path(), null, refused.getMessage()));
            }
        }
        return List.copyOf(parsed);
    }

    private static int countScenarios(List<ParsedFile> parsed) {
        int total = 0;
        for (ParsedFile file : parsed) {
            total += file.feature() == null ? 0 : file.feature().scenarios().size();
        }
        return total;
    }

    // =====================================================================
    // conditions — one per distinct Background step (AM1)
    // =====================================================================

    /**
     * The distinct {@code Background:} steps of a request, keyed by their text hash.
     *
     * <p>Distinct across the WHOLE request and not per file: the same {@code Given I am signed in}
     * in six features is one library entry for the project, which is the difference between a
     * library and a pile. Insertion-ordered so the {@code conditions[]} section reads in the order
     * the files were sent.
     */
    private static final class Backgrounds {

        private record Entry(String title, boolean truncated, List<String> body) {
        }

        private Backgrounds() {
        }

        static Map<String, Entry> distinctIn(List<ParsedFile> parsed) {
            Map<String, Entry> byKey = new LinkedHashMap<>();
            for (ParsedFile file : parsed) {
                if (file.feature() == null) {
                    continue;
                }
                for (ParsedFeature.ParsedScenario scenario : file.feature().scenarios()) {
                    for (GherkinParseResult.ParsedBackground step : scenario.background()) {
                        byKey.computeIfAbsent(keyOf(step), ignored -> entryOf(step));
                    }
                }
            }
            return byKey;
        }

        static Entry entryOf(GherkinParseResult.ParsedBackground step) {
            String full = step.title();
            boolean truncated = full.length() > ConditionEdit.MAX_TITLE;
            return new Entry(
                    truncated ? full.substring(0, TITLE_BUDGET) + "…" : full,
                    truncated,
                    step.body());
        }

        /**
         * {@code "bg:"} + the first 16 hex of SHA-256 over the normalised step.
         *
         * <p>Normalised means: the step line and its block lines joined with {@code \n}, each line
         * stripped, internal whitespace collapsed to one space. Two files that indent their
         * {@code Background:} differently — which is a formatting choice a linter makes for
         * somebody — must hash the same, or a re-format of an automation repository would mint a
         * second copy of every condition in it.
         *
         * <p>Truncated to 16 hex characters (64 bits) rather than kept whole: the population is one
         * project's Background steps, a collision needs about four billion of them, and the column
         * is read by humans looking at a library.
         */
        static String keyOf(GherkinParseResult.ParsedBackground step) {
            String normalised = step.body().stream()
                    .map(line -> line.strip().replaceAll("\\s+", " "))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(normalised.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(KEY_PREFIX);
                for (int i = 0; i < 8; i++) {
                    hex.append(String.format("%02x", digest[i]));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) {
                // Every JRE ships SHA-256 (JCA standard names). Wrapped rather than declared so the
                // signature does not carry an exception no caller can act on.
                throw new IllegalStateException("SHA-256 is not available", impossible);
            }
        }
    }

    private static Map<String, Condition> byKey(List<Condition> found) {
        Map<String, Condition> byKey = new HashMap<>();
        for (Condition condition : found) {
            byKey.put(condition.getImportedKey(), condition);
        }
        return byKey;
    }

    private List<FeatureImportPlan.ConditionAction> planConditions(
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> existing,
            Keys keys) {

        List<FeatureImportPlan.ConditionAction> actions = new ArrayList<>(backgrounds.size());
        for (Map.Entry<String, Backgrounds.Entry> step : backgrounds.entrySet()) {
            Backgrounds.Entry entry = step.getValue();
            Condition already = existing.get(step.getKey());
            String reason = entry.truncated()
                    ? "title truncated to " + ConditionEdit.MAX_TITLE + " characters"
                    : null;

            FeatureImportPlan.ConditionVerdict verdict;
            if (!keys.library()) {
                verdict = FeatureImportPlan.ConditionVerdict.REFUSE;
                reason = "needs library:manage for the Background";
            } else if (already == null) {
                verdict = FeatureImportPlan.ConditionVerdict.CREATE;
            } else if (!already.getTitle().equals(entry.title())) {
                // The only way an existing entry changes: the key hashes the BODY, so the body
                // cannot have moved. A title can, because it is the truncation of the step text and
                // the budget is a constant somebody may one day raise.
                verdict = FeatureImportPlan.ConditionVerdict.UPDATE;
            } else {
                verdict = FeatureImportPlan.ConditionVerdict.UNCHANGED;
            }

            actions.add(new FeatureImportPlan.ConditionAction(
                    step.getKey(),
                    entry.title(),
                    verdict,
                    already == null ? null : already.getId(),
                    reason));
        }
        return actions;
    }

    /**
     * Creates the library entries the plan says to create, and re-titles the one kind that can move.
     *
     * <p>The action list is rewritten in place with the new ids so the rows below can attach by id
     * without a second lookup, and so the returned plan names the condition that now exists.
     */
    private void writeConditions(
            UUID userId,
            UUID projectId,
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> existing,
            List<FeatureImportPlan.ConditionAction> actions) {

        for (int i = 0; i < actions.size(); i++) {
            FeatureImportPlan.ConditionAction action = actions.get(i);
            Backgrounds.Entry entry = backgrounds.get(action.importedKey());
            switch (action.action()) {
                case CREATE -> {
                    Condition created = conditions.createImported(
                            userId,
                            projectId,
                            new ConditionWriteRequest(
                                    entry.title(), ConditionType.PRE, entry.body()),
                            action.importedKey());
                    existing.put(action.importedKey(), created);
                    actions.set(i, new FeatureImportPlan.ConditionAction(
                            action.importedKey(), action.title(), action.action(), created.getId(),
                            action.reason()));
                }
                case UPDATE -> conditions.retitleImported(
                        userId, projectId, existing.get(action.importedKey()).getId(),
                        entry.title());
                case UNCHANGED, REFUSE -> {
                    // Nothing. UNCHANGED is the point of hashing the step; REFUSE has already
                    // turned every scenario of the affected feature into a refused row.
                }
            }
        }
    }

    // =====================================================================
    // suites — one per distinct Feature: name
    // =====================================================================

    private Map<String, FeatureImportPlan.SuiteAction> planSuites(
            List<ParsedFile> parsed,
            List<Suite> liveSuites,
            FeatureImportRequest request,
            Keys keys) {

        Map<String, FeatureImportPlan.SuiteAction> actions = new LinkedHashMap<>();
        if (request.suiteId() != null) {
            // Nothing is matched and nothing is created: the caller has already answered the
            // question this section exists to answer.
            return actions;
        }

        for (ParsedFile file : parsed) {
            if (file.feature() == null || actions.containsKey(file.feature().name())) {
                continue;
            }
            String name = file.feature().name();
            List<Suite> matches = liveSuites.stream()
                    .filter(suite -> name.equals(suite.getName()))
                    .toList();

            if (matches.size() > 1) {
                // Sorted, so the sentence is the same on every run: findLiveForProject orders by
                // the tree, and two suites with one name have no defined order within it.
                String keysNamed = matches.stream().map(Suite::getKey).sorted()
                        .reduce((left, right) -> left + ", " + right).orElse("");
                actions.put(name, new FeatureImportPlan.SuiteAction(
                        name, FeatureImportPlan.SuiteVerdict.REFUSE, null,
                        "two suites are named '" + name + "' (" + keysNamed + ") — pass suiteId"));
            } else if (matches.size() == 1) {
                actions.put(name, new FeatureImportPlan.SuiteAction(
                        name, FeatureImportPlan.SuiteVerdict.REUSE, matches.get(0).getId(), null));
            } else if (!keys.suite()) {
                actions.put(name, new FeatureImportPlan.SuiteAction(
                        name, FeatureImportPlan.SuiteVerdict.REFUSE, null,
                        "needs suite:manage for suite '" + name + "'"));
            } else {
                actions.put(name, new FeatureImportPlan.SuiteAction(
                        name, FeatureImportPlan.SuiteVerdict.CREATE, null, null));
            }
        }
        return actions;
    }

    private void writeSuites(
            UUID userId, UUID projectId, Map<String, FeatureImportPlan.SuiteAction> actions) {

        for (Map.Entry<String, FeatureImportPlan.SuiteAction> entry : actions.entrySet()) {
            FeatureImportPlan.SuiteAction action = entry.getValue();
            if (action.action() != FeatureImportPlan.SuiteVerdict.CREATE) {
                continue;
            }
            SuiteWriteService.Outcome outcome = suiteWrites.create(
                    userId,
                    projectId,
                    // A flat suite with a name and nothing else: no tag rules, no members, no
                    // conditions. A feature is a grouping, and everything else about a suite is a
                    // human decision the import has no input to make it with.
                    new SuiteWriteRequest(action.name(), null, null, null, null, null),
                    null);
            if (outcome instanceof SuiteWriteService.Outcome.Created created) {
                entry.setValue(new FeatureImportPlan.SuiteAction(
                        action.name(), action.action(), created.view().id(), action.reason()));
            } else {
                // The plan asked whether the caller holds suite:manage before proposing CREATE, so
                // the only remaining refusals are ones nothing here can repair. Reported as a
                // refused suite rather than thrown, so the scenarios of the OTHER features still
                // import.
                entry.setValue(new FeatureImportPlan.SuiteAction(
                        action.name(), FeatureImportPlan.SuiteVerdict.REFUSE, null,
                        "needs suite:manage for suite '" + action.name() + "'"));
            }
        }
    }

    // =====================================================================
    // cases
    // =====================================================================

    private Map<String, TestCase> existingCases(List<ParsedFile> parsed) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ParsedFile file : parsed) {
            if (file.feature() == null) {
                continue;
            }
            for (ParsedFeature.ParsedScenario scenario : file.feature().scenarios()) {
                if (scenario.name() != null) {
                    wanted.add(AutomationKeys.cucumber(file.feature().name(), scenario.name()));
                }
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, TestCase> byKey = new HashMap<>();
        for (TestCase found : cases.findLiveByAutomationKeys(wanted)) {
            byKey.put(found.getAutomationKey(), found);
        }
        return byKey;
    }

    /**
     * Every row one file contributes — DEC-1, in the order the table states it.
     *
     * <p>The ordering of the checks is the hit policy: first match wins, R4 (another project)
     * before R5 (approved), because a caller cannot fix the second without the first. R7 —
     * permission — is applied last by construction: the action is chosen, and then the key it needs
     * is checked.
     */
    private List<FeatureImportPlan.ImportRow> planFile(
            UUID userId,
            UUID projectId,
            ParsedFile file,
            FeatureImportRequest request,
            Keys keys,
            Map<String, FeatureImportPlan.SuiteAction> suiteActions,
            Map<String, TestCase> byAutomationKey,
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> conditionsByKey,
            boolean apply) {

        if (file.refusal() != null) {
            return List.of(new FeatureImportPlan.ImportRow(
                    file.path(), null, null, null, null,
                    FeatureImportPlan.Action.REFUSE, file.refusal(), null, List.of()));
        }

        String feature = file.feature().name();
        FeatureImportPlan.SuiteAction suiteAction = suiteActions.get(feature);
        List<FeatureImportPlan.ImportRow> rows = new ArrayList<>();

        for (ParsedFeature.ParsedScenario scenario : file.feature().scenarios()) {
            rows.add(planScenario(
                    userId, projectId, file, feature, scenario, request, keys, suiteAction,
                    byAutomationKey, backgrounds, conditionsByKey, apply));
        }
        return rows;
    }

    private FeatureImportPlan.ImportRow planScenario(
            UUID userId,
            UUID projectId,
            ParsedFile file,
            String feature,
            ParsedFeature.ParsedScenario scenario,
            FeatureImportRequest request,
            Keys keys,
            FeatureImportPlan.SuiteAction suiteAction,
            Map<String, TestCase> byAutomationKey,
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> conditionsByKey,
            boolean apply) {

        String key = scenario.name() == null
                ? null
                : AutomationKeys.cucumber(feature, scenario.name());

        // R1 — an Outline, or a nameless scenario there is no way to key.
        if (scenario.refusal() != null) {
            return refused(file, scenario, feature, key, scenario.refusal());
        }
        if (scenario.name() == null) {
            return refused(file, scenario, feature, null, "Scenario has no name");
        }

        TestCase existing = byAutomationKey.get(key);

        // R4 — the key is taken in a SIBLING project. Before R5: the caller cannot act on a
        // lifecycle they cannot reach.
        if (existing != null && !existing.getProjectId().equals(projectId)) {
            return refused(file, scenario, feature, key,
                    "automationKey already used by " + existing.getKey() + " in project "
                            + projectKeyOf(existing));
        }

        // The Background this scenario carries. Whether the caller may write the library is asked
        // BELOW, after the action is chosen: a row that turns out to be SKIP needs no key at all,
        // and refusing it for library:manage would tell a Viewer that an untouched case is a
        // permission problem.
        List<String> ownedKeys = scenario.background().stream()
                .map(Backgrounds::keyOf)
                .toList();

        if (existing == null) {
            return planCreate(
                    userId, projectId, file, feature, scenario, request, keys, suiteAction,
                    key, ownedKeys, backgrounds, conditionsByKey, apply);
        }
        return planExisting(
                userId, projectId, file, feature, scenario, request, keys, existing, key,
                ownedKeys, backgrounds, conditionsByKey, apply);
    }

    // ------------------------------------------------------------------ create ----

    private FeatureImportPlan.ImportRow planCreate(
            UUID userId,
            UUID projectId,
            ParsedFile file,
            String feature,
            ParsedFeature.ParsedScenario scenario,
            FeatureImportRequest request,
            Keys keys,
            FeatureImportPlan.SuiteAction suiteAction,
            String key,
            List<String> ownedKeys,
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> conditionsByKey,
            boolean apply) {

        List<String> preconditionTitles = titlesOf(ownedKeys, backgrounds);

        // R7 for a create, and its two riders.
        if (!keys.create()) {
            return refused(file, scenario, feature, key, "needs case:create");
        }
        if (suiteAction != null && suiteAction.action() == FeatureImportPlan.SuiteVerdict.REFUSE) {
            return refused(file, scenario, feature, key, suiteAction.reason());
        }
        if (!ownedKeys.isEmpty() && !keys.library()) {
            // The WHOLE scenario, not a partial attach: a case imported without its setup steps is
            // one a tester runs and fails for a reason the file already stated.
            return refused(file, scenario, feature, key, "needs library:manage for the Background");
        }

        UUID suiteId = request.suiteId() != null
                ? request.suiteId()
                : (suiteAction == null ? null : suiteAction.suiteId());

        if (!apply) {
            return new FeatureImportPlan.ImportRow(
                    file.path(), scenario.line(), feature, scenario.name(), key,
                    FeatureImportPlan.Action.CREATE, null, null, preconditionTitles);
        }

        CaseCreateService.Outcome outcome = creation.create(
                userId,
                projectId,
                caseRequest(scenario, key, suiteId, attachments(ownedKeys, conditionsByKey)),
                null);
        if (!(outcome instanceof CaseCreateService.Outcome.Created created)) {
            return refused(file, scenario, feature, key, whyRefused(outcome));
        }
        stamp(created.view().id(), projectId, file.path(), scenario);
        return new FeatureImportPlan.ImportRow(
                file.path(), scenario.line(), feature, scenario.name(), key,
                FeatureImportPlan.Action.CREATE, null, created.view().key(), preconditionTitles);
    }

    // ------------------------------------------------------------------ existing ----

    private FeatureImportPlan.ImportRow planExisting(
            UUID userId,
            UUID projectId,
            ParsedFile file,
            String feature,
            ParsedFeature.ParsedScenario scenario,
            FeatureImportRequest request,
            Keys keys,
            TestCase existing,
            String key,
            List<String> ownedKeys,
            Map<String, Backgrounds.Entry> backgrounds,
            Map<String, Condition> conditionsByKey,
            boolean apply) {

        // What this case's preconditions are AFTER the row is applied: the file's owned set in
        // Background order, then the human-attached ones in their existing relative order (catalog
        // addendum I.3). Read from the PLAN rather than from the library, so a preview names the
        // conditions a clean import is going to create — which do not exist yet.
        List<UUID> humanPre = humanAttachedPre(existing.getId());
        List<String> titles = new ArrayList<>(titlesOf(ownedKeys, backgrounds));
        titles.addAll(titlesOfIds(humanPre));

        // R3 — the caller asked for SKIP.
        if (request.onExisting() == FeatureImportRequest.OnExisting.SKIP) {
            return new FeatureImportPlan.ImportRow(
                    file.path(), scenario.line(), feature, scenario.name(), key,
                    FeatureImportPlan.Action.SKIP, "exists (SKIP)", existing.getKey(),
                    List.copyOf(titles));
        }

        // R5 — a lifecycle an UPDATE may not touch.
        if (existing.getLifecycleStatus() == LifecycleStatus.APPROVED) {
            return skipped(file, scenario, feature, key, existing,
                    "approved — not touched by UPDATE", List.copyOf(titles));
        }
        if (existing.getLifecycleStatus() == LifecycleStatus.DEPRECATED) {
            return skipped(file, scenario, feature, key, existing,
                    "deprecated — not touched by UPDATE", List.copyOf(titles));
        }

        /*
          R6's own short-circuit, and the ONE reader of imported_source: the file has not changed
          since this case was written from it, so there is nothing to write and no version to mint.

          BOTH halves are needed, and the second is not redundant. `imported_source` is cut from the
          SCENARIO's own lines (`GherkinScenarios.parseFeature` — the AST has no verbatim form), and
          a `Background:` lives ABOVE the first scenario: editing one leaves every scenario's text
          byte-identical. On the first half alone, removing a Background step from a file could
          never detach the condition it minted, on any re-import, ever — the case would report
          "unchanged since import" while carrying a precondition the file no longer states.
        */
        if (scenario.source().equals(existing.getImportedSource())
                && ownedKeys.equals(attachedImportedKeys(existing.getId()))) {
            return skipped(
                    file, scenario, feature, key, existing, "unchanged since import",
                    List.copyOf(titles));
        }

        if (!keys.edit()) {
            return refused(file, scenario, feature, key, "needs case:edit");
        }
        if (!ownedKeys.isEmpty() && !keys.library()) {
            return refused(file, scenario, feature, key, "needs library:manage for the Background");
        }
        if (!apply) {
            return new FeatureImportPlan.ImportRow(
                    file.path(), scenario.line(), feature, scenario.name(), key,
                    FeatureImportPlan.Action.UPDATE, null, existing.getKey(),
                    List.copyOf(titles));
        }

        List<CaseConditionInput> full = new ArrayList<>(attachments(ownedKeys, conditionsByKey));
        humanPre.forEach(id -> full.add(new CaseConditionInput(id, null)));

        // The stored priority, spelled back. `CaseUpdateRequest.overlay` takes the four contract-
        // REQUIRED fields from the body as sent rather than from the row — deliberately, so an
        // absent one is still a 400 — so sending the import's own default here would silently reset
        // every re-imported case to MEDIUM. api-surface.md §5.4 says priority is untouched by an
        // UPDATE; this line is what makes that true.
        CaseUpdateRequest sent = caseRequest(
                scenario, key, existing.getSuiteId(), existing.getPriority(), full,
                keptStepIds(existing, scenario));
        CaseUpdateService.Outcome outcome =
                updates.update(userId, projectId, existing.getId(), sent, UPDATES_EVERYTHING, null);
        if (!(outcome instanceof CaseUpdateService.Outcome.Updated)) {
            return refused(file, scenario, feature, key, whyRefused(outcome));
        }
        stamp(existing.getId(), projectId, file.path(), scenario);
        return new FeatureImportPlan.ImportRow(
                file.path(), scenario.line(), feature, scenario.name(), key,
                FeatureImportPlan.Action.UPDATE, null, existing.getKey(), List.copyOf(titles));
    }

    /**
     * The steps this update sends, with the ids of the stored rows it can keep.
     *
     * <p>Each submitted step claims the first still-unclaimed stored row with the same
     * {@code (keyword, action)}: a claimed row keeps its id and therefore every
     * {@code step_result} recorded against it, an unclaimed stored row is deleted, and an
     * unmatched submitted step is inserted. Claiming rather than matching by content alone is what
     * makes a scenario that repeats a step ({@code And I wait}) bind its second occurrence to the
     * second row instead of to the first one twice.
     *
     * <p>Without this, {@code CaseSteps.reconcile} sees a set of ids-free steps, deletes every
     * stored row and re-inserts — which succeeds, looks right on the case screen, and loses the
     * evidence and verdicts of every run that ever executed the case.
     */
    private List<CaseUpdateRequest.StepInput> keptStepIds(
            TestCase existing, ParsedFeature.ParsedScenario scenario) {

        List<TestStep> stored = stepRows.findForCase(existing.getId());
        List<TestStep> available = new ArrayList<>(stored);
        List<CaseUpdateRequest.StepInput> steps = new ArrayList<>(scenario.steps().size());

        for (CaseUpdateRequest.StepInput step : scenario.steps()) {
            UUID id = null;
            for (int i = 0; i < available.size(); i++) {
                TestStep candidate = available.get(i);
                String keyword = candidate.getKeyword() == null
                        ? null
                        : candidate.getKeyword().name();
                if (Objects.equals(keyword, step.keyword())
                        && Objects.equals(candidate.getAction(), step.action())) {
                    id = candidate.getId();
                    // Consumed, so a scenario repeating a step binds its second occurrence to the
                    // SECOND stored row rather than to the first one twice.
                    available.remove(i);
                    break;
                }
            }
            steps.add(new CaseUpdateRequest.StepInput(
                    id, step.keyword(), step.action(), step.testData(), step.expectedResult(),
                    step.docString(), step.dataTable()));
        }
        return List.copyOf(steps);
    }

    // =====================================================================
    // shared helpers
    // =====================================================================

    /**
     * The body an imported case is written from — {@code api-surface.md} §5.4's CREATE bullet.
     *
     * <p>Everything the file states, and nothing it does not. {@code priority} and
     * {@code automationStatus} are the SPA's own New Case defaults ({@code lib/caseDefaults.ts})
     * except that {@code automationStatus} is {@code AUTOMATED}, which is the one thing the import
     * knows about a case that a human opening an empty form does not: a scenario in a
     * {@code .feature} file is run by a runner.
     */
    private CaseUpdateRequest caseRequest(
            ParsedFeature.ParsedScenario scenario,
            String automationKey,
            UUID suiteId,
            List<CaseConditionInput> preconditions) {

        return caseRequest(
                scenario, automationKey, suiteId, Priority.MEDIUM, preconditions,
                scenario.steps());
    }

    private CaseUpdateRequest caseRequest(
            ParsedFeature.ParsedScenario scenario,
            String automationKey,
            UUID suiteId,
            Priority priority,
            List<CaseConditionInput> preconditions,
            List<CaseUpdateRequest.StepInput> steps) {

        return new CaseUpdateRequest(
                scenario.name(),
                suiteId,
                null,
                scenario.objective(),
                null,
                StepFormat.GHERKIN,
                priority,
                null,
                AutomationStatus.AUTOMATED,
                automationKey,
                null,
                null,
                steps,
                CHANGE_COMMENT,
                // Classification is left alone: nothing in a .feature file says what dimension a
                // case belongs to, and inventing one would overwrite a human's answer on every
                // re-import.
                null,
                scenario.tags(),
                null,
                null,
                preconditions,
                // NEVER sent. Null is "leave them alone", which is what makes a case's POST
                // conditions survive any re-import — api-surface.md §5.4, amendment AM1.
                null,
                null);
    }

    /** The change comment every imported version carries, so history says where the edit came from. */
    private static final String CHANGE_COMMENT = "Imported from a .feature file";

    /**
     * The PATCH presence map for an update: every field the import states is mentioned.
     *
     * <p>{@link PatchBody} answers presence and never value, so nulls are exactly right. The fields
     * NOT named here are the ones an import must not move — {@code lifecycleStatus} is not on this
     * record at all, and {@code severity}, {@code classificationValueIds}, {@code postconditions}
     * and {@code declinedConditionIds} are absent so {@code CaseUpdateRequest.overlay} carries the
     * stored values through.
     */
    private static final PatchBody UPDATES_EVERYTHING = new PatchBody(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .putNull("title")
                    .putNull("objective")
                    .putNull("stepFormat")
                    .putNull("automationStatus")
                    .putNull("automationKey")
                    .putNull("steps")
                    .putNull("tags")
                    .putNull("preconditions"));

    private static List<CaseConditionInput> attachments(
            List<String> ownedKeys, Map<String, Condition> conditionsByKey) {

        List<CaseConditionInput> attachments = new ArrayList<>(ownedKeys.size());
        for (String key : ownedKeys) {
            Condition condition = conditionsByKey.get(key);
            if (condition != null) {
                attachments.add(new CaseConditionInput(condition.getId(), null));
            }
        }
        return List.copyOf(attachments);
    }

    /** The Background titles, in Background order — the preview's answer before anything exists. */
    private static List<String> titlesOf(
            List<String> ownedKeys, Map<String, Backgrounds.Entry> backgrounds) {

        return ownedKeys.stream()
                .map(backgrounds::get)
                .filter(entry -> entry != null)
                .map(Backgrounds.Entry::title)
                .toList();
    }

    /** The titles of some library entries, in the order the ids were given. */
    private List<String> titlesOfIds(List<UUID> conditionIds) {
        if (conditionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> known = new HashMap<>();
        conditionRows.findLiveByIds(conditionIds)
                .forEach(condition -> known.put(condition.getId(), condition.getTitle()));

        return conditionIds.stream().map(known::get).filter(title -> title != null).toList();
    }

    /**
     * The case's existing {@code PRE} attachments that the import does NOT own.
     *
     * <p>Ownership is the condition's {@code imported_key} being non-null, and that is the whole of
     * the rule (amendment AM1). Everything a human attached comes back, in its existing relative
     * order, after the file's set — so a re-import replaces what it wrote and leaves what somebody
     * else did.
     */
    private List<UUID> humanAttachedPre(UUID caseId) {
        List<UUID> attached = caseConditions.liveAttachedIdsOf(caseId, ConditionKind.PRE);
        if (attached.isEmpty()) {
            return List.of();
        }
        Set<UUID> owned = new HashSet<>();
        conditionRows.findLiveByIds(attached).stream()
                .filter(condition -> condition.getImportedKey() != null)
                .forEach(condition -> owned.add(condition.getId()));

        return attached.stream().filter(id -> !owned.contains(id)).toList();
    }

    /**
     * The {@code imported_key}s this case currently carries as {@code PRE}, in attachment order.
     *
     * <p>The counterpart of {@link #humanAttachedPre}, and the second half of R6's comparison: it
     * answers what the LAST import attached, so it can be compared with what THIS file says. Order
     * is significant on purpose — the Background's order is the order the preconditions render in,
     * so a reordered Background is a real change even when the set is identical.
     */
    private List<String> attachedImportedKeys(UUID caseId) {
        List<UUID> attached = caseConditions.liveAttachedIdsOf(caseId, ConditionKind.PRE);
        if (attached.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> owned = new HashMap<>();
        conditionRows.findLiveByIds(attached).stream()
                .filter(condition -> condition.getImportedKey() != null)
                .forEach(condition -> owned.put(condition.getId(), condition.getImportedKey()));

        return attached.stream().map(owned::get).filter(key -> key != null).toList();
    }

    private void stamp(
            UUID caseId, UUID projectId, String path, ParsedFeature.ParsedScenario scenario) {

        cases.findLiveInProject(projectId, caseId).ifPresent(row ->
                row.recordImport(scenario.source(), path, scenario.line(), clock.instant()));
    }

    private String projectKeyOf(TestCase existing) {
        return projectRows.findScopedById(existing.getProjectId())
                .map(com.tcms.project.Project::getKey)
                .orElse("another project");
    }

    private static FeatureImportPlan.ImportRow refused(
            ParsedFile file, ParsedFeature.ParsedScenario scenario, String feature, String key,
            String reason) {

        return new FeatureImportPlan.ImportRow(
                file.path(), scenario.line(), feature, scenario.name(), key,
                FeatureImportPlan.Action.REFUSE, reason, null, List.of());
    }

    private static FeatureImportPlan.ImportRow skipped(
            ParsedFile file, ParsedFeature.ParsedScenario scenario, String feature, String key,
            TestCase existing, String reason, List<String> titles) {

        return new FeatureImportPlan.ImportRow(
                file.path(), scenario.line(), feature, scenario.name(), key,
                FeatureImportPlan.Action.SKIP, reason, existing.getKey(), titles);
    }

    /**
     * A write the plan expected to succeed and did not.
     *
     * <p>Reachable only when the plan and the service disagree — the permission keys were checked
     * before the action was chosen, so what is left is a validation failure on content out of a
     * file. Reported as a refused row naming the field, because the alternative is a 500 for one
     * bad scenario in a folder of forty files.
     */
    private static String whyRefused(Object outcome) {
        if (outcome instanceof CaseCreateService.Outcome.Invalid invalid) {
            return "refused: " + invalid.errors();
        }
        if (outcome instanceof CaseUpdateService.Outcome.Invalid invalid) {
            return "refused: " + invalid.errors();
        }
        return "refused by the case write";
    }

    // =====================================================================
    // orphans and totals
    // =====================================================================

    /**
     * Keyed cases filed in the target suites that these files do not name.
     *
     * <p>Listed and never touched. A scenario renamed in the automation repository leaves the old
     * case behind, and deleting it would be this endpoint destroying a case's whole history —
     * including the runs it appears in — on the strength of a file somebody edited. Whether it is a
     * rename or a deliberate retirement is a question only a human can answer, so the plan asks it.
     */
    private List<FeatureImportPlan.Orphan> orphans(
            UUID projectId,
            FeatureImportRequest request,
            Map<String, FeatureImportPlan.SuiteAction> suiteActions,
            List<ParsedFile> parsed) {

        Set<UUID> targets = new LinkedHashSet<>();
        if (request.suiteId() != null) {
            targets.add(request.suiteId());
        } else {
            suiteActions.values().stream()
                    .map(FeatureImportPlan.SuiteAction::suiteId)
                    .filter(id -> id != null)
                    .forEach(targets::add);
        }
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<String> named = new HashSet<>();
        for (ParsedFile file : parsed) {
            if (file.feature() == null) {
                continue;
            }
            for (ParsedFeature.ParsedScenario scenario : file.feature().scenarios()) {
                if (scenario.name() != null) {
                    named.add(AutomationKeys.cucumber(file.feature().name(), scenario.name()));
                }
            }
        }

        return cases.findLiveFiledIn(targets).stream()
                .filter(row -> !named.contains(row.getAutomationKey()))
                .map(row -> new FeatureImportPlan.Orphan(row.getKey(), row.getAutomationKey()))
                .toList();
    }

    private static FeatureImportPlan.ImportTotals totals(List<FeatureImportPlan.ImportRow> rows) {
        int create = 0;
        int update = 0;
        int skip = 0;
        int refuse = 0;
        for (FeatureImportPlan.ImportRow row : rows) {
            switch (row.action()) {
                case CREATE -> create++;
                case UPDATE -> update++;
                case SKIP -> skip++;
                case REFUSE -> refuse++;
            }
        }
        return new FeatureImportPlan.ImportTotals(create, update, skip, refuse);
    }
}
