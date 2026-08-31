package com.tcms;

import com.tcms.documentation.ArtifactInput;
import com.tcms.documentation.ArtifactKind;
import com.tcms.documentation.ArtifactScope;
import com.tcms.documentation.ArtifactWriteService;
import com.tcms.documentation.DocBlockInput;
import com.tcms.documentation.DocBlockType;
import com.tcms.documentation.DocPageInput;
import com.tcms.documentation.DocPageStatusInput;
import com.tcms.documentation.DocPageWriteService;
import com.tcms.identity.ApiTokenIssueRequest;
import com.tcms.identity.ApiTokenService;
import com.tcms.identity.Role;
import com.tcms.identity.RoleRepository;
import com.tcms.integration.NotificationChannelInput;
import com.tcms.integration.NotificationChannelKind;
import com.tcms.integration.NotificationChannelService;
import com.tcms.project.GateResult;
import com.tcms.reporting.ReportJobRunner;
import com.tcms.reporting.ReportJobService;
import com.tcms.repository.TestCaseDetailView;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tracker.IssueInput;
import com.tcms.tracker.IssueService;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.IssueTypeView;
import com.tcms.tracker.Priority;
import com.tcms.tracker.TransitionService;
import com.tcms.tracker.WorkflowService;
import com.tcms.tracker.WorkflowView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Everything a project has that is not its tests or its runs: documentation, artifacts, generated
 * reports, the board, notification channels, API tokens and the issue-tracker binding.
 *
 * <p>Grouped by "not the repository and not execution" rather than by module, deliberately. These
 * are six small seeds that each fill one screen, and splitting them into six classes would put
 * more ceremony in this package than content.
 */
@Component
public class DemoWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkspaceSeeder.class);

    private final DocPageWriteService docPages;
    private final ArtifactWriteService artifacts;
    private final ReportJobService reportJobs;
    private final ReportJobRunner reportRunner;
    private final IssueService issues;
    private final TransitionService transitions;
    private final WorkflowService workflows;
    private final NotificationChannelService channels;
    private final ApiTokenService apiTokens;
    private final RoleRepository roles;
    private final Clock clock;

    public DemoWorkspaceSeeder(
            DocPageWriteService docPages,
            ArtifactWriteService artifacts,
            ReportJobService reportJobs,
            ReportJobRunner reportRunner,
            IssueService issues,
            TransitionService transitions,
            WorkflowService workflows,
            NotificationChannelService channels,
            ApiTokenService apiTokens,
            RoleRepository roles,
            Clock clock) {

        this.docPages = docPages;
        this.artifacts = artifacts;
        this.reportJobs = reportJobs;
        this.reportRunner = reportRunner;
        this.issues = issues;
        this.transitions = transitions;
        this.workflows = workflows;
        this.channels = channels;
        this.apiTokens = apiTokens;
        this.roles = roles;
        this.clock = clock;
    }

    void seed(
            DemoCast cast,
            DemoSeedPlan.ProjectSpec spec,
            UUID projectId,
            List<TestCaseDetailView> cases,
            DemoSeedReport report,
            String label) {

        boolean full = spec.depth() == DemoSeedPlan.Depth.FULL;

        seedDocumentation(cast, projectId, full, report, label);
        seedBoard(cast, projectId, full, report, label);

        if (!full) {
            return;
        }
        seedArtifacts(cast, projectId, report, label);
        seedReports(cast, projectId, report, label);
        seedChannels(cast, projectId, report, label);
        seedApiTokens(cast, projectId, report, label);
    }

    /**
     * A two-level documentation tree with every block type on it, one page published and one left
     * as a draft.
     *
     * <p>Every block type on purpose: {@code doc_block.block_type} is the only thing that decides
     * how a block renders, so a page made of paragraphs proves nothing about the five other arms
     * of that switch.
     */
    private void seedDocumentation(
            DemoCast cast, UUID projectId, boolean full, DemoSeedReport report, String label) {

        UUID parent = createPage(cast, projectId, "Test approach", null, List.of(
                new DocBlockInput(null, DocBlockType.HEADING, 1, "How we test this product", null, null),
                new DocBlockInput(null, DocBlockType.PARAGRAPH, null,
                        "Risk-based, and biased towards the paths that carry money. Everything "
                                + "below the checkout line is regression-tested on every release; "
                                + "everything above it is tested when it changes.", null, null),
                new DocBlockInput(null, DocBlockType.HEADING, 2, "What we automate", null, null),
                new DocBlockInput(null, DocBlockType.LIST, null,
                        "Anything run more than twice a release\n"
                                + "Anything whose failure is silent\n"
                                + "Nothing that needs a human to judge whether it looks right",
                        null, null),
                new DocBlockInput(null, DocBlockType.TABLE, null, "", List.of(
                        List.of("Area", "Owner", "Cadence"),
                        List.of("Checkout", "Storefront Squad", "Every build"),
                        List.of("Search", "Storefront Squad", "Nightly"),
                        List.of("Account", "Quality Engineering", "Weekly")), null),
                new DocBlockInput(null, DocBlockType.CODE, null,
                        "npm run test:e2e -- --project=checkout --reporter=junit", null, null)),
                report);

        if (parent == null) {
            return;
        }
        docPages.setStatus(cast.lead(), projectId, parent, new DocPageStatusInput("PUBLISHED"));

        createPage(cast, projectId, "Entry and exit criteria", parent, List.of(
                new DocBlockInput(null, DocBlockType.PARAGRAPH, null,
                        "A build enters test when it deploys clean and the smoke suite passes. "
                                + "It leaves when the exit criteria on the release plan are met, "
                                + "or when somebody signs off on why they are not.", null, null)),
                report);

        if (full) {
            createPage(cast, projectId, "Environments — draft", parent, List.of(
                    new DocBlockInput(null, DocBlockType.PARAGRAPH, null,
                            "Still being written. Staging, the payments sandbox and what each of "
                                    + "them can and cannot do.", null, null)),
                    report);
        }
        report.line(label, "a documentation tree, one page published and one still a draft");
    }

    private UUID createPage(
            DemoCast cast, UUID projectId, String title, UUID parentId, List<DocBlockInput> blocks,
            DemoSeedReport report) {

        DocPageWriteService.Outcome outcome = docPages.create(cast.lead(), projectId,
                new DocPageInput(title, parentId, blocks),
                DemoProjectSeeder.idempotencyKey(projectId, "doc", title));

        if (outcome instanceof DocPageWriteService.Outcome.Created created) {
            return created.view().id();
        }
        if (outcome instanceof DocPageWriteService.Outcome.Invalid invalid) {
            log.warn("Doc page '{}' was refused: Invalid {}", title, invalid.errors());
            report.refusal("Doc page '%s' was refused: Invalid %s".formatted(title, invalid.errors()));
        } else {
            log.warn("Doc page '{}' was refused: {}", title, outcome.getClass().getSimpleName());
            report.refusal("Doc page '%s' was refused: %s"
                    .formatted(title, outcome.getClass().getSimpleName()));
        }
        return null;
    }

    /** One artifact of each kind the catalogue can hold without a file behind it. */
    private void seedArtifacts(
            DemoCast cast, UUID projectId, DemoSeedReport report, String label) {

        create(cast, projectId, new ArtifactInput(
                ArtifactScope.PROJECT, null, "Standards", ArtifactKind.EXTERNAL_URL,
                "Northwind accessibility standard (WCAG 2.2 AA)",
                "The standard the accessibility sweep is judged against.",
                null, "https://standards.northwind.test/accessibility", null, false, null), report);

        create(cast, projectId, new ArtifactInput(
                ArtifactScope.PROJECT, null, "Reference", ArtifactKind.EXTERNAL_URL,
                "Payments sandbox test cards",
                "Which card number produces which decline reason.",
                null, "https://docs.payments.test/sandbox/cards", null, false, null), report);

        report.line(label, "two catalogue artifacts");
    }

    private void create(DemoCast cast, UUID projectId, ArtifactInput input, DemoSeedReport report) {
        ArtifactWriteService.Outcome outcome = artifacts.create(cast.lead(), projectId, input,
                DemoProjectSeeder.idempotencyKey(projectId, "artifact", input.title()));
        if (!(outcome instanceof ArtifactWriteService.Outcome.Created)) {
            log.warn("Artifact '{}' was refused: {}", input.title(),
                    outcome.getClass().getSimpleName());
            report.refusal("Artifact '%s' was refused: %s"
                    .formatted(input.title(), outcome.getClass().getSimpleName()));
        }
    }

    /**
     * A saved report definition's worth of work, generated for real.
     *
     * <p>The job is requested and then <strong>drained here</strong> rather than left to
     * {@code ReportJobScheduler}, whose first tick is a minute away and which a CLI run exits long
     * before. Without the drain the reports screen shows a queued job that never finishes, and
     * {@code report_artifact} — the row the download reads — is never written at all.
     */
    private void seedReports(DemoCast cast, UUID projectId, DemoSeedReport report, String label) {
        ReportJobService.Outcome outcome = reportJobs.request(cast.lead(), projectId, null,
                "aggregate_execution", "HTML", "{}",
                DemoProjectSeeder.idempotencyKey(projectId, "report", "aggregate"));

        if (!(outcome instanceof ReportJobService.Outcome.Accepted)) {
            report.note("report job was refused: " + outcome.getClass().getSimpleName());
            return;
        }
        int generated = reportRunner.runPendingJobs();
        report.line(label, generated + " generated report" + (generated == 1 ? "" : "s"));
    }

    /**
     * Issues across the workflow, so the board is not a single stack — and one assigned to each
     * person, so "my work" answers differently depending on who is signed in.
     *
     * <p>~~Board cards~~ → <strong>issues since 009 module M1.</strong> The row behind a board card
     * is a {@code tracker} issue now, so this seeds through {@code POST /issues}' service and moves
     * them with the transition service — which also means the demo data exercises the project's real
     * workflow rather than a five-member enum, and a status this seed names that the seeded workflow
     * does not have is a card that stays where it started rather than a failure.
     */
    private void seedBoard(
            DemoCast cast, UUID projectId, boolean full, DemoSeedReport report, String label) {

        record Card(String title, String description, Priority priority, String status,
                    UUID assignee, int dueInDays) {
        }

        List<Card> cards = full
                ? List.of(
                        new Card("Automate the gift-card checkout case",
                                "Blocked on the sandbox supporting partial captures.",
                                Priority.HIGH, "Blocked", cast.engineer(), 9),
                        new Card("Rewrite the search filter cases after the facet change",
                                "The facet names all changed; the cases still name the old ones.",
                                Priority.MEDIUM, "In Progress", cast.tester(), 4),
                        new Card("Review the release test plan",
                                "Needs a second pair of eyes before the release meeting.",
                                Priority.HIGH, "In Review", cast.lead(), 2),
                        new Card("Retire the duplicated smoke cases",
                                "Three of them test the same thing twice.",
                                Priority.LOW, null, cast.engineer(), 21),
                        new Card("Write the accessibility sweep charter",
                                null, Priority.MEDIUM, null, cast.tester(), 14),
                        new Card("Move the nightly run to the new agent pool",
                                "Done — the schedule now points at pool B.",
                                Priority.MEDIUM, "Done", cast.engineer(), -3))
                : List.of(
                        new Card("Triage the offline queue failures",
                                null, Priority.HIGH, null, cast.engineer(), 7));

        Optional<WorkflowView> workflow = workflows.workflow(cast.lead(), projectId);
        if (workflow.isEmpty()) {
            report.note("the project's workflow was unreadable, so no issues were seeded");
            return;
        }
        UUID taskType = levelType(workflow.get(), "Task");
        if (taskType == null) {
            report.note("the project's workflow has no Task type, so no issues were seeded");
            return;
        }

        int created = 0;
        for (Card card : cards) {
            var gate = issues.create(cast.lead(),
                    new IssueInput(projectId, taskType, card.title(), card.description(), null,
                            card.assignee(), card.priority(), null, null, null,
                            LocalDate.now(clock).plusDays(card.dueInDays()), null),
                    DemoProjectSeeder.idempotencyKey(projectId, "card", card.title()));

            String key = keyOf(gate);
            if (key == null) {
                continue;
            }
            created++;

            // Every issue is born in the project's FIRST status by position — an issue's column is
            // not a field on the create request — so anything that should sit further along is moved
            // there afterwards, through the workflow that would refuse an illegal step.
            UUID target = statusNamed(workflow.get(), card.status());
            if (target != null) {
                transitions.move(cast.lead(), key, target);
            }
        }
        report.line(label, created + " issues across the workflow");
    }

    /** The created issue's key, or null if the tracker refused it. */
    private String keyOf(GateResult<IssueService.Outcome> gate) {
        if (gate instanceof GateResult.Allowed<IssueService.Outcome> allowed
                && allowed.value() instanceof IssueService.Outcome.Created created) {
            return created.view().key();
        }
        return null;
    }

    private static UUID levelType(WorkflowView workflow, String name) {
        for (IssueTypeView type : workflow.types()) {
            if (type.name().equals(name)) {
                return type.id();
            }
        }
        return null;
    }

    /**
     * The status this project calls {@code name}, or null when it has none.
     *
     * <p>Null rather than a throw, and null for a null argument too: a demo instance whose workflow
     * somebody renamed is a project with different columns, not a broken seed, and a card that stays
     * where it started is a better outcome than a provisioning command that stops halfway.
     */
    private static UUID statusNamed(WorkflowView workflow, String name) {
        if (name == null) {
            return null;
        }
        for (IssueStatusView status : workflow.statuses()) {
            if (status.name().equals(name)) {
                return status.id();
            }
        }
        return null;
    }

    /**
     * A Slack channel and an email channel, subscribed to different events, so the channel list
     * shows two kinds and the subscription editor has something to open.
     */
    private void seedChannels(
            DemoCast cast, UUID projectId, DemoSeedReport report, String label) {

        int created = 0;
        created += channel(cast, projectId, "#quality-alerts", new NotificationChannelInput(
                NotificationChannelKind.SLACK,
                "#quality-alerts",
                true,
                List.of(),
                "https://hooks.slack.test/services/T000/B000/seeded-demo-webhook",
                List.of(new NotificationChannelInput.SubscriptionInput(
                                OutboxEvents.RUN_COMPLETED, 1),
                        new NotificationChannelInput.SubscriptionInput(
                                OutboxEvents.REVIEW_REQUESTED, 0))), report);

        created += channel(cast, projectId, "Release leads", new NotificationChannelInput(
                NotificationChannelKind.EMAIL,
                "Release leads",
                true,
                List.of("lead@northwind.test", "admin@northwind.test"),
                null,
                List.of(new NotificationChannelInput.SubscriptionInput(
                        OutboxEvents.REVIEW_APPROVED, 0))), report);

        if (created > 0) {
            report.line(label, created + " notification channel"
                    + (created == 1 ? "" : "s") + " and their subscriptions");
        }
    }

    /**
     * Creates one channel, and answers whether it landed.
     *
     * <p>The outcome used to be discarded, so the report said "two notification channels" whatever
     * happened. A channel whose name is already taken now answers {@code Invalid} rather than
     * reaching the unique index, which is what makes a second run of this command survive; that is
     * the expected answer on a re-run and so is not worth a line, but anything else is.
     */
    private int channel(
            DemoCast cast, UUID projectId, String name, NotificationChannelInput input,
            DemoSeedReport report) {

        NotificationChannelService.Outcome outcome =
                channels.create(cast.adminId(), projectId, input);
        if (outcome instanceof NotificationChannelService.Outcome.Saved) {
            return 1;
        }
        if (!(outcome instanceof NotificationChannelService.Outcome.Invalid)) {
            log.warn("Channel '{}' was refused: {}", name, outcome.getClass().getSimpleName());
            report.refusal("Channel '%s' was refused: %s"
                    .formatted(name, outcome.getClass().getSimpleName()));
        }
        return 0;
    }

    /**
     * One CI token and one agent token — the two non-human principals — so the token screen shows
     * both roles and the "issued once, never shown again" copy has a row to sit on.
     */
    private void seedApiTokens(
            DemoCast cast, UUID projectId, DemoSeedReport report, String label) {

        issue(cast, projectId, "Automation Service", "Nightly CI", null, report);
        // An agent token is refused without an expiry — ApiTokenService states why: it lives on
        // somebody's machine rather than in a pipeline. Ninety days is far enough out that the
        // fixture is not born expired and near enough to be a date somebody would accept.
        issue(cast, projectId, "Agent", "Case-drafting agent",
                clock.instant().plus(Duration.ofDays(90)), report);
        report.line(label, "two API tokens (Automation Service, Agent)");
    }

    private void issue(
            DemoCast cast, UUID projectId, String roleName, String tokenName, Instant expiresAt,
            DemoSeedReport report) {
        Optional<Role> role = roles.findByName(roleName);
        if (role.isEmpty()) {
            return;
        }
        ApiTokenService.Outcome outcome = apiTokens.issue(cast.adminId(),
                new ApiTokenIssueRequest(tokenName, role.get().getId(), projectId, expiresAt));
        if (outcome instanceof ApiTokenService.Outcome.Invalid invalid) {
            log.warn("Token '{}' was refused: Invalid {}", tokenName, invalid.errors());
            report.refusal("Token '%s' was refused: Invalid %s".formatted(tokenName, invalid.errors()));
        } else if (!(outcome instanceof ApiTokenService.Outcome.Issued)) {
            log.warn("Token '{}' was refused: {}", tokenName, outcome.getClass().getSimpleName());
            report.refusal("Token '%s' was refused: %s"
                    .formatted(tokenName, outcome.getClass().getSimpleName()));
        }
    }
}
