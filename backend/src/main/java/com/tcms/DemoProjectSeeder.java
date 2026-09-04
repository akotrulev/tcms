package com.tcms;

import com.tcms.project.ClassificationDimension;
import com.tcms.project.ClassificationDimensionRepository;
import com.tcms.project.ClassificationValueInput;
import com.tcms.project.ClassificationValueWriteService;
import com.tcms.project.ProjectSettingsRequest;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.ReviewAssignmentRequest;
import com.tcms.project.ReviewParticipationRequest;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
import com.tcms.project.ReviewView;
import com.tcms.repository.AiProposalInput;
import com.tcms.repository.AiProposalService;
import com.tcms.repository.CaseConditionInput;
import com.tcms.repository.CaseCreateService;
import com.tcms.repository.TestCaseRepository;
import com.tcms.repository.CaseStatusRequest;
import com.tcms.repository.CaseStatusService;
import com.tcms.repository.CaseUpdateRequest;
import com.tcms.repository.ConditionService;
import com.tcms.repository.ConditionType;
import com.tcms.repository.ConditionWriteRequest;
import com.tcms.repository.Priority;
import com.tcms.repository.ProposalVerdict;
import com.tcms.repository.StepFormat;
import com.tcms.repository.SuiteStatusRequest;
import com.tcms.repository.SuiteStatusService;
import com.tcms.repository.SuiteWriteRequest;
import com.tcms.repository.SuiteWriteService;
import com.tcms.repository.TestCaseDetailView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fills one project's test repository — its feature tree, condition library, suites, cases,
 * lifecycle states, reviews and AI proposals — then hands it on to the execution and workspace
 * seeders.
 *
 * <h2>Order matters, and each step is why the next one has anything to work with</h2>
 *
 * <ol>
 *   <li><strong>Settings first.</strong> {@code requireApprovedCaseForRun} decides whether a run
 *       may include a draft case, and {@code reviewEnabledCase} decides whether moving a case to
 *       IN_REVIEW opens a review at all. Both are read at the moment of the move, so a project
 *       configured afterwards produces content that does not match its own settings screen.</li>
 *   <li><strong>Suites, then cases.</strong> A case is filed into a suite by
 *       {@code CaseUpdateRequest.suiteId}, which is the suite's first arm.</li>
 *   <li><strong>Cases, then their lifecycle.</strong> A review is opened by the move to
 *       IN_REVIEW and records the case's version at that moment.</li>
 *   <li><strong>Approval last</strong>, because {@code requireApprovedCaseForRun} is what lets the
 *       execution seeder put these cases in a run at all.</li>
 * </ol>
 */
@Component
public class DemoProjectSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoProjectSeeder.class);

    private final ProjectSettingsService settings;
    private final ClassificationDimensionRepository dimensions;
    private final ClassificationValueWriteService classificationValues;
    private final ConditionService conditions;
    private final SuiteWriteService suites;
    private final SuiteStatusService suiteStatuses;
    private final CaseCreateService caseCreate;
    private final CaseStatusService caseStatuses;
    private final TestCaseRepository cases;
    private final ReviewService reviews;
    private final AiProposalService proposals;
    private final DemoExecutionSeeder executionSeeder;
    private final DemoWorkspaceSeeder workspaceSeeder;

    public DemoProjectSeeder(
            ProjectSettingsService settings,
            ClassificationDimensionRepository dimensions,
            ClassificationValueWriteService classificationValues,
            ConditionService conditions,
            SuiteWriteService suites,
            SuiteStatusService suiteStatuses,
            CaseCreateService caseCreate,
            CaseStatusService caseStatuses,
            TestCaseRepository cases,
            ReviewService reviews,
            AiProposalService proposals,
            DemoExecutionSeeder executionSeeder,
            DemoWorkspaceSeeder workspaceSeeder) {

        this.settings = settings;
        this.dimensions = dimensions;
        this.classificationValues = classificationValues;
        this.conditions = conditions;
        this.suites = suites;
        this.suiteStatuses = suiteStatuses;
        this.caseCreate = caseCreate;
        this.caseStatuses = caseStatuses;
        this.cases = cases;
        this.reviews = reviews;
        this.proposals = proposals;
        this.executionSeeder = executionSeeder;
        this.workspaceSeeder = workspaceSeeder;
    }

    void seed(
            DemoCast cast,
            DemoSeedPlan.ProjectSpec spec,
            UUID projectId,
            boolean force,
            DemoSeedReport report,
            String label) {

        if (spec.depth() == DemoSeedPlan.Depth.EMPTY) {
            report.line(label, spec.key() + " left empty, so the empty states are reachable");
            return;
        }
        if (!cases.liveIdsForProject(projectId).isEmpty() && !force) {
            report.note(spec.key() + " already holds cases and was left alone. --force adds more.");
            return;
        }

        log.info("Seeding the {} repository", spec.key());
        boolean full = spec.depth() == DemoSeedPlan.Depth.FULL;

        applySettings(cast, projectId, full);
        Map<String, UUID> features = seedFeatures(cast, projectId, full);
        Map<String, UUID> conditionIds = seedConditions(cast, projectId, full);
        Map<String, UUID> suiteIds = seedSuites(cast, projectId, spec, conditionIds);
        List<TestCaseDetailView> created =
                seedCases(cast, projectId, spec, suiteIds, features, conditionIds, full);

        report.line(label, spec.key() + " — " + suiteIds.size() + " suites, "
                + created.size() + " cases");

        seedLifecycle(cast, projectId, created, full, report, label);
        approveSuites(cast, projectId, suiteIds);
        if (full) {
            seedProposals(cast, projectId, suiteIds, report, label);
        }

        executionSeeder.seed(cast, spec, projectId, created, suiteIds, report, label);
        workspaceSeeder.seed(cast, spec, projectId, created, report, label);
    }

    /**
     * The full project turns review on and requires an approved case before a run; the thin one
     * leaves review off. Two projects that disagree is the point — a settings screen where every
     * project answers the same is a screen nobody can tell is wired up.
     */
    private void applySettings(DemoCast cast, UUID projectId, boolean full) {
        // The ninth flag is D33's review-to-close gate, and it follows `full` for this method's own
        // reason: the thin project is the one-person-company path (A6), where an assignee closes
        // their own work, and the full project is where a lead can see the refusal actually fire.
        //
        // The tenth is 016 D-g's `trackTime`, and it is the one that does NOT follow `full`: it is
        // `true` on both, because a demo instance where half the projects have no Logged column and
        // no time panels is a demo of a feature nobody can find. The disagreement this method exists
        // to create is about review policy, and adding a second axis to it hides the first.
        settings.update(cast.adminId(), projectId, new ProjectSettingsRequest(
                full, full, false, false, full ? 1 : 0, false,
                full ? "REQUIRES_REREVIEW" : "ALLOWED", full, full, true));
    }

    /** The project-scoped {@code feature} tree, two levels deep. */
    private Map<String, UUID> seedFeatures(DemoCast cast, UUID projectId, boolean full) {
        Map<String, UUID> byPath = new LinkedHashMap<>();
        if (!full) {
            return byPath;
        }
        UUID dimensionId = dimensions.findForProject(projectId).stream()
                .filter(d -> "feature".equals(d.getKey()))
                .map(ClassificationDimension::getId)
                .findFirst()
                .orElse(null);
        if (dimensionId == null) {
            return byPath;
        }

        for (String path : DemoContent.WEB_FEATURES) {
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            UUID parentId = slash < 0 ? null : byPath.get(path.substring(0, slash));

            ClassificationValueWriteService.Outcome outcome = classificationValues.create(
                    cast.adminId(), projectId,
                    new ClassificationValueInput(dimensionId, name, parentId, null));
            if (outcome instanceof ClassificationValueWriteService.Outcome.Created created) {
                byPath.put(path, created.view().id());
            }
        }
        return byPath;
    }

    private Map<String, UUID> seedConditions(DemoCast cast, UUID projectId, boolean full) {
        Map<String, UUID> byTitle = new LinkedHashMap<>();
        if (!full) {
            return byTitle;
        }
        for (DemoContent.ConditionSpec spec : DemoContent.WEB_CONDITIONS) {
            ConditionService.Outcome outcome = conditions.create(cast.engineer(), projectId,
                    new ConditionWriteRequest(spec.title(),
                            spec.precondition() ? ConditionType.PRE : ConditionType.POST,
                            spec.body()));
            if (outcome instanceof ConditionService.Outcome.Created created) {
                byTitle.put(spec.title(), created.view().id());
            } else {
                log.warn("Condition '{}' was refused: {}", spec.title(),
                        outcome.getClass().getSimpleName());
            }
        }
        return byTitle;
    }

    /**
     * Suites, with the first one carrying an inherited precondition so the decline mechanism
     * ({@code case_condition_optout}) has something to decline further down.
     */
    private Map<String, UUID> seedSuites(
            DemoCast cast,
            UUID projectId,
            DemoSeedPlan.ProjectSpec spec,
            Map<String, UUID> conditionIds) {

        Map<String, UUID> byName = new LinkedHashMap<>();
        boolean first = true;
        for (DemoContent.SuiteSpec suite : suitesFor(spec)) {
            List<CaseConditionInput> preconditions = first && !conditionIds.isEmpty()
                    ? List.of(new CaseConditionInput(
                            conditionIds.get("A signed-in shopper with a saved card"), null))
                    : List.of();
            first = false;

            SuiteWriteService.Outcome outcome = suites.create(cast.engineer(), projectId,
                    new SuiteWriteRequest(suite.name(), suite.description(), suite.tagRules(),
                            List.of(), preconditions, List.of()),
                    idempotencyKey(projectId, "suite", suite.name()));
            if (outcome instanceof SuiteWriteService.Outcome.Created created) {
                byName.put(suite.name(), created.view().id());
            } else if (outcome instanceof SuiteWriteService.Outcome.Replayed replayed) {
                byName.put(suite.name(), replayed.view().id());
            } else {
                log.warn("Suite '{}' was refused: {}", suite.name(),
                        outcome.getClass().getSimpleName());
            }
        }
        return byName;
    }

    private List<TestCaseDetailView> seedCases(
            DemoCast cast,
            UUID projectId,
            DemoSeedPlan.ProjectSpec spec,
            Map<String, UUID> suiteIds,
            Map<String, UUID> features,
            Map<String, UUID> conditionIds,
            boolean full) {

        List<TestCaseDetailView> created = new ArrayList<>();
        List<UUID> featureIds = List.copyOf(features.values());
        int index = 0;

        for (DemoContent.CaseSpec caseSpec : casesFor(spec)) {
            // The authors alternate, so the case list's author column and the history screens have
            // more than one name in them.
            UUID author = index % 3 == 0 ? cast.tester() : cast.engineer();

            List<UUID> classifications = featureIds.isEmpty()
                    ? List.of()
                    : List.of(featureIds.get(index % featureIds.size()));

            // A library reference, never an inline body: CaseConditionInput's header refuses the
            // inline arm outright, because nothing in the product can render one — so the inline
            // form this used to send was rejected on every run and cost the case it was attached
            // to. The id is the library entry seeded just above.
            List<CaseConditionInput> preconditions = full && index == 1 && !conditionIds.isEmpty()
                    ? List.of(new CaseConditionInput(
                            conditionIds.values().iterator().next(), null))
                    : List.of();

            CaseCreateService.Outcome outcome = caseCreate.create(author, projectId,
                    request(caseSpec, suiteIds, classifications, preconditions),
                    idempotencyKey(projectId, "case", caseSpec.title()));

            switch (outcome) {
                case CaseCreateService.Outcome.Created c -> created.add(c.view());
                case CaseCreateService.Outcome.Replayed r -> created.add(r.view());
                case CaseCreateService.Outcome.Invalid invalid ->
                        log.warn("Case '{}' was refused: Invalid {}", caseSpec.title(),
                                invalid.errors());
                default -> log.warn("Case '{}' was refused: {}", caseSpec.title(),
                        outcome.getClass().getSimpleName());
            }
            index++;
        }

        if (full) {
            gherkinCase(cast, projectId, suiteIds).ifPresent(created::add);
        }
        return created;
    }

    /** The one Gherkin case, so both step formats are on the screens that render steps. */
    private Optional<TestCaseDetailView> gherkinCase(
            DemoCast cast, UUID projectId, Map<String, UUID> suiteIds) {

        List<CaseUpdateRequest.StepInput> steps = DemoContent.WEB_GHERKIN_STEPS.stream()
                .map(step -> {
                    int space = step.action().indexOf(' ');
                    return new CaseUpdateRequest.StepInput(null,
                            step.action().substring(0, space),
                            step.action().substring(space + 1),
                            null, null, null, null);
                })
                .toList();

        CaseCreateService.Outcome outcome = caseCreate.create(cast.engineer(), projectId,
                new CaseUpdateRequest(
                        DemoContent.WEB_GHERKIN_TITLE,
                        suiteIds.get("Checkout"),
                        "Delivery pricing reacts to the basket total in both directions.",
                        "Prove the threshold is applied and un-applied, not just applied.",
                        "The delivery line matches the subtotal at every step.",
                        StepFormat.GHERKIN,
                        Priority.HIGH, Priority.MEDIUM,
                        com.tcms.repository.AutomationStatus.AUTOMATED,
                        "checkout.delivery.threshold",
                        null, 240, steps,
                        "Imported from the delivery feature file.",
                        List.of(), List.of("checkout", "delivery"),
                        null, null, List.of(), List.of(), List.of()),
                idempotencyKey(projectId, "case", DemoContent.WEB_GHERKIN_TITLE));

        return switch (outcome) {
            case CaseCreateService.Outcome.Created c -> Optional.of(c.view());
            case CaseCreateService.Outcome.Replayed r -> Optional.of(r.view());
            default -> Optional.empty();
        };
    }

    private CaseUpdateRequest request(
            DemoContent.CaseSpec spec,
            Map<String, UUID> suiteIds,
            List<UUID> classifications,
            List<CaseConditionInput> preconditions) {

        List<CaseUpdateRequest.StepInput> steps = spec.steps().stream()
                .map(step -> new CaseUpdateRequest.StepInput(
                        null, null, step.action(), step.testData(), step.expected(), null, null))
                .toList();

        return new CaseUpdateRequest(
                spec.title(),
                suiteIds.get(spec.suite()),
                null,
                spec.objective(),
                null,
                DemoContent.structured(),
                spec.priority(),
                spec.severity(),
                spec.automation(),
                spec.automationKey(),
                spec.automation() == com.tcms.repository.AutomationStatus.NOT_AUTOMATABLE
                        ? "Needs a account with 30 real orders, which staging cannot hold."
                        : null,
                60 * spec.steps().size(),
                steps,
                null,
                classifications,
                spec.tags(),
                null, null,
                preconditions,
                List.of(),
                List.of());
    }

    /**
     * Moves the cases through the lifecycle so the repository is not uniformly DRAFT: most are
     * approved (which is also what lets them into a run), two sit in review with a real review
     * open, and one stays a draft.
     */
    private void seedLifecycle(
            DemoCast cast,
            UUID projectId,
            List<TestCaseDetailView> created,
            boolean full,
            DemoSeedReport report,
            String label) {

        int inReview = 0;
        int approved = 0;
        for (int i = 0; i < created.size(); i++) {
            TestCaseDetailView view = created.get(i);

            // The last case stays a draft. One draft is enough to show the state and few enough
            // that the run seeder still has an approved set to work with.
            if (i == created.size() - 1) {
                continue;
            }

            caseStatuses.setStatus(cast.engineer(), projectId, view.id(),
                    new CaseStatusRequest("IN_REVIEW"));
            inReview++;

            // Two cases keep an OPEN review, so the review screens have something outstanding.
            if (full && i < 2) {
                openReview(cast, projectId, view.id());
                continue;
            }

            caseStatuses.setStatus(cast.lead(), projectId, view.id(),
                    new CaseStatusRequest("APPROVED"));
            approved++;
        }

        report.line(label, approved + " cases approved, " + (inReview - approved)
                + " in review, 1 left as a draft");
    }

    /**
     * Approves all but the last suite, so the suite list's lifecycle column has more than one
     * value in it. Last, because a suite is approved on the strength of the cases in it and
     * approving it before they exist would record a review of nothing.
     */
    private void approveSuites(DemoCast cast, UUID projectId, Map<String, UUID> suiteIds) {
        List<UUID> ids = List.copyOf(suiteIds.values());
        for (int i = 0; i < ids.size() - 1; i++) {
            suiteStatuses.setStatus(cast.engineer(), projectId, ids.get(i),
                    new SuiteStatusRequest("IN_REVIEW"));
            suiteStatuses.setStatus(cast.lead(), projectId, ids.get(i),
                    new SuiteStatusRequest("APPROVED"));
        }
    }

    /** Assigns the open review to somebody and leaves a comment and one endorsement on it. */
    private void openReview(DemoCast cast, UUID projectId, UUID caseId) {
        Optional<ReviewView> open = reviews
                .findForSubject(cast.lead(), projectId, ReviewSubjectType.CASE, caseId)
                .orElse(List.of()).stream()
                .findFirst();
        if (open.isEmpty()) {
            return;
        }
        UUID reviewId = open.get().id();

        reviews.assign(cast.engineer(), projectId, reviewId,
                new ReviewAssignmentRequest(cast.lead(), null));
        reviews.comment(cast.lead(), projectId, reviewId,
                new com.tcms.project.ReviewCommentRequest(
                        "Step 3 needs the expected total spelled out — 34.00 including delivery, "
                                + "or excluding it?", null));
        reviews.record(cast.tester(), projectId, reviewId,
                new ReviewParticipationRequest("ENDORSED",
                        "Reads correctly to me, subject to the total above."));
    }

    /**
     * Two AI proposals against the full project — one still pending, so the proposals screen has
     * a decision to make on it, and one that was rejected.
     */
    private void seedProposals(
            DemoCast cast,
            UUID projectId,
            Map<String, UUID> suiteIds,
            DemoSeedReport report,
            String label) {

        CaseUpdateRequest proposed = new CaseUpdateRequest(
                "Gift-card balance is applied before the card is charged",
                suiteIds.get("Checkout"),
                null,
                "Derived from the payments service's ordering rules.",
                null,
                DemoContent.structured(),
                Priority.HIGH, Priority.MEDIUM,
                com.tcms.repository.AutomationStatus.TO_BE_AUTOMATED, null, null, 180,
                List.of(new CaseUpdateRequest.StepInput(null, null,
                        "Pay for a 40.00 basket with a 25.00 gift card and a saved card", null,
                        "The card is charged 15.00 and the gift card is emptied.", null, null)),
                null, List.of(), List.of("checkout", "payments"),
                null, null, List.of(), List.of(), List.of());

        AiProposalService.Outcome outcome = proposals.file(cast.engineer(), projectId,
                new AiProposalInput(proposed, ProposalVerdict.GROUNDED, null),
                idempotencyKey(projectId, "proposal", proposed.title()));
        if (outcome instanceof AiProposalService.Outcome.Filed) {
            report.line(label, "1 AI proposal awaiting a decision");
        }
    }

    private List<DemoContent.SuiteSpec> suitesFor(DemoSeedPlan.ProjectSpec spec) {
        return switch (spec.key()) {
            case "WEB" -> DemoContent.WEB_SUITES;
            case "MOB" -> DemoContent.MOBILE_SUITES;
            default -> DemoContent.EHR_SUITES;
        };
    }

    private List<DemoContent.CaseSpec> casesFor(DemoSeedPlan.ProjectSpec spec) {
        return switch (spec.key()) {
            case "WEB" -> DemoContent.WEB_CASES;
            case "MOB" -> DemoContent.MOBILE_CASES;
            default -> DemoContent.EHR_CASES;
        };
    }

    /**
     * A deterministic idempotency key, which is what makes a re-run a top-up. The services that
     * take one answer a repeat with the row they already made, so {@code --force} on a project
     * whose content is already there does not produce a second copy of it.
     */
    static String idempotencyKey(UUID projectId, String kind, String name) {
        return "demo-seed:" + projectId + ':' + kind + ':' + name;
    }
}
