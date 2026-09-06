package com.tcms;

import com.tcms.billing.BudgetBasis;
import com.tcms.billing.BudgetService;
import com.tcms.billing.BudgetWriteRequest;
import com.tcms.billing.ExpenseService;
import com.tcms.billing.ExpenseView;
import com.tcms.billing.ExpenseWriteRequest;
import com.tcms.billing.InvoiceDetailView;
import com.tcms.billing.InvoiceDraftInput;
import com.tcms.billing.InvoiceService;
import com.tcms.billing.InvoiceStatus;
import com.tcms.billing.InvoiceView;
import com.tcms.billing.Outcome;
import com.tcms.billing.RateCardEntryInput;
import com.tcms.billing.RateCardScope;
import com.tcms.billing.RateCardService;
import com.tcms.billing.RateCardSlotView;
import com.tcms.billing.RateCardView;
import com.tcms.billing.RateCardWriteRequest;
import com.tcms.crm.ClientDetailView;
import com.tcms.crm.ClientService;
import com.tcms.crm.ClientView;
import com.tcms.crm.ClientWriteRequest;
import com.tcms.crm.ProjectClientRequest;
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
import com.tcms.crm.ClientContactWriteRequest;
import com.tcms.repository.TestCaseDetailView;
import com.tcms.staffing.AbsenceInput;
import com.tcms.staffing.AbsenceKind;
import com.tcms.staffing.AbsenceView;
import com.tcms.staffing.AllocationInput;
import com.tcms.staffing.AllocationService;
import com.tcms.staffing.PersonProfileInput;
import com.tcms.staffing.PersonProfileView;
import com.tcms.staffing.PersonSkillsInput;
import com.tcms.staffing.ProfileService;
import com.tcms.staffing.SkillInput;
import com.tcms.staffing.SkillView;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.time.TimeEntryInput;
import com.tcms.time.TimeEntryService;
import com.tcms.time.TimeSubjectType;
import com.tcms.time.TimesheetDecisionInput;
import com.tcms.time.TimesheetPeriodView;
import com.tcms.time.TimesheetService;
import com.tcms.tracker.IssueFactProjection;
import com.tcms.tracker.IssueInput;
import com.tcms.tracker.IssueService;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.IssueTypeView;
import com.tcms.tracker.Priority;
import com.tcms.tracker.TransitionService;
import com.tcms.tracker.WorkflowService;
import com.tcms.tracker.WorkflowView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ProfileService staffingProfiles;
    private final AllocationService allocations;
    private final ClientService clients;
    private final TimeEntryService timeEntries;
    private final TimesheetService timesheets;
    private final IssueFactProjection issueFacts;
    private final RateCardService rateCards;
    private final BudgetService budgets;
    private final ExpenseService expenses;
    private final InvoiceService invoices;
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
            ProfileService staffingProfiles,
            AllocationService allocations,
            ClientService clients,
            TimeEntryService timeEntries,
            TimesheetService timesheets,
            IssueFactProjection issueFacts,
            RateCardService rateCards,
            BudgetService budgets,
            ExpenseService expenses,
            InvoiceService invoices,
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
        this.staffingProfiles = staffingProfiles;
        this.allocations = allocations;
        this.clients = clients;
        this.timeEntries = timeEntries;
        this.timesheets = timesheets;
        this.issueFacts = issueFacts;
        this.rateCards = rateCards;
        this.budgets = budgets;
        this.expenses = expenses;
        this.invoices = invoices;
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
        List<String> issueKeys = seedBoard(cast, projectId, full, report, label);
        // Before the FULL gate on purpose: the thin company's one project is PARTIAL, and a demo
        // where only the rich company has capacity figures cannot show that staffing is per company.
        seedStaffing(cast, projectId, report, label);
        // Likewise, and for a second reason: billing is per COMPANY, so a demo where only the rich
        // company has a client would show nothing about a rate card belonging to one tenancy.
        seedBilling(cast, projectId, issueKeys, report, label);

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
    private List<String> seedBoard(
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
                                Priority.MEDIUM, "In progress", cast.tester(), 4),
                        new Card("Review the release test plan",
                                "Needs a second pair of eyes before the release meeting.",
                                Priority.HIGH, "In review", cast.lead(), 2),
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
            return List.of();
        }
        UUID taskType = levelType(workflow.get(), "Task");
        if (taskType == null) {
            report.note("the project's workflow has no Task type, so no issues were seeded");
            return List.of();
        }

        // Returned rather than counted, because seedBilling needs a subject to log an hour AGAINST:
        // ck_time_entry_subject_shape makes an ISSUE entry the only kind that carries a project, so
        // the only priceable hour is one logged against one of these (019 D-e).
        List<String> keys = new ArrayList<>();
        int created = 0;
        for (Card card : cards) {
            var gate = issues.create(cast.lead(),
                    new IssueInput(projectId, taskType, card.title(), card.description(), null,
                            card.assignee(), card.priority(), null, null, null,
                            LocalDate.now(clock).plusDays(card.dueInDays()),
                            // No target release, deliberately: D14 says releases start empty, and
                            // a demo that seeded one would be this product inventing a version
                            // number for the person looking at it.
                            null,
                            // D30, on every second card. Not on all of them and not on none: a
                            // marker that is everywhere and a marker that is nowhere are equally
                            // invisible to somebody looking at the screen to see whether it works.
                            created % 2 == 0,
                            null),
                    DemoProjectSeeder.idempotencyKey(projectId, "card", card.title()));

            String key = keyOf(gate);
            if (key == null) {
                continue;
            }
            keys.add(key);
            created++;

            // Every issue is born in the project's FIRST status by position — an issue's column is
            // not a field on the create request — so anything that should sit further along is moved
            // there afterwards, through the workflow that would refuse an illegal step.
            UUID target = statusNamed(workflow.get(), card.status());
            if (target != null) {
                walkTo(cast.lead(), key, workflow.get(), target);
            }
        }
        report.line(label, created + " issues across the workflow");
        return List.copyOf(keys);
    }

    /**
     * Moves one issue to one status, walking the workflow a step at a time when it has to.
     *
     * <p><strong>A single hop stopped being enough at V13.</strong> The old six-status seed let
     * anything reach anything except Done, so "put this card In Progress" was one legal move from
     * where it was born. The seeded workflow is a LINE now (021 §2.3) — each status reaches the
     * next, plus Rework and Blocked — so the same single hop is refused, and because this seeder
     * deliberately ignores a refusal (a status the project does not have must not fail the seed)
     * the failure would be silent: every demo card sitting in the first column, which is exactly
     * the single stack this method exists to avoid.
     *
     * <p>The direct move is still tried first, and that is not just an optimisation: it is the only
     * thing that reaches a status <em>off</em> the line. Blocked is the last position but is
     * entered from every non-done status, so walking positions towards it would go through Done.
     *
     * <p>Abandoned at the first refusal rather than reported, for the reason above: demo data that
     * cannot place a card should leave it where it is, never stop the seed.
     */
    private void walkTo(UUID actorId, String key, WorkflowView workflow, UUID target) {
        if (moveAccepted(actorId, key, target)) {
            return;
        }
        List<IssueStatusView> line = workflow.statuses().stream()
                .sorted(Comparator.comparingInt(IssueStatusView::position))
                .toList();
        int destination = -1;
        for (int index = 0; index < line.size(); index++) {
            if (line.get(index).id().equals(target)) {
                destination = index;
            }
        }
        for (int step = 1; step <= destination; step++) {
            if (!moveAccepted(actorId, key, line.get(step).id())) {
                return;
            }
        }
    }

    private boolean moveAccepted(UUID actorId, String key, UUID statusId) {
        return transitions.move(actorId, key, statusId)
                instanceof GateResult.Allowed<TransitionService.Outcome> allowed
                && allowed.value() instanceof TransitionService.Outcome.Moved;
    }

    /**
     * Profiles, a skill vocabulary, allocations and one half-day absence.
     *
     * <p><strong>Without this, every capacity figure in the demo instance is an em dash</strong> —
     * which is the correct rendering for a person nobody has profiled (016 D-j) and a useless
     * demonstration of a module whose whole subject is hours. So the demo gives the cast contracted
     * hours, so that the directory, the company staffing view, the timesheet's capacity tile and My
     * Work's meter all have something to show.
     *
     * <p>Everything here is a find-or-create against what is already there, because
     * {@code seed-demo-data} is a top-up rather than a reset and it runs once per project — so the
     * profiles and the vocabulary are written on the first project and found on the rest.
     *
     * <p>The absence is <strong>half a day</strong>, deliberately: a whole day and a missing day
     * render identically on a timeline, and {@code member.html}'s "29 Aug · half day" is the case
     * that proves the fraction is real (017 D-d).
     */
    private void seedStaffing(
            DemoCast cast, UUID projectId, DemoSeedReport report, String label) {

        UUID admin = cast.adminId();
        Map<String, UUID> vocabulary = ensureSkills(admin, report);
        if (vocabulary.isEmpty()) {
            report.note("no skills could be created, so no staffing profiles were seeded");
            return;
        }

        // Contracted hours that are not all the same, so a reader can see that the percentage is of
        // THEIR capacity: 50% of the part-timer's week is half the hours of 50% of the lead's.
        profile(admin, cast.lead(), 40, "Test Lead", "Stockholm", 6, report);
        profile(admin, cast.engineer(), 40, "Test Engineer", "Lisbon", 3, report);
        profile(admin, cast.tester(), 20, "Test Engineer", "Osaka", 1, report);

        skills(admin, cast.lead(), vocabulary, List.of("Test strategy", "Playwright", "Java"), report);
        skills(admin, cast.engineer(), vocabulary, List.of("Playwright", "TypeScript"), report);
        skills(admin, cast.tester(), vocabulary, List.of("Java", "Accessibility"), report);

        LocalDate from = LocalDate.now(clock).withDayOfMonth(1);
        LocalDate to = from.plusMonths(2).withDayOfMonth(28);

        // The lead at 60 and the engineer at 50 on this project. Two projects in the rich company
        // therefore put both of them over 100 on the overlapping days, which is the Over-100% panel
        // having something in it — the one state that screen exists for.
        allocate(admin, projectId, cast.lead(), 60, from, to, report);
        allocate(admin, projectId, cast.engineer(), 50, from, to, report);

        halfDayOff(admin, cast.engineer(), from.plusDays(9), report);
        report.line(label, "staffing — three profiles, " + vocabulary.size()
                + " skills, two allocations and a half day off");
    }

    /**
     * Clients, rate cards, budgets, expenses and two invoices — <strong>and the approved hours the
     * whole module is computed from</strong>.
     *
     * <p><del>Mc shipped the client module and deliberately seeded no clients</del> (018), because
     * inventing customers would put this repository's guesses where a reader cannot tell them from a
     * client's own records, and because a company with no clients is the case that module's
     * acceptance criterion is about. <strong>M8 reverses that, and the reversal is the decision:</strong>
     * every screen this module builds is a screen about a client's money, so a seeded instance with
     * no clients renders a rate-card table with no rows, a budget list with no currency and an
     * invoice list that cannot be reached. The empty-state argument is still true and is still
     * demonstrable — Kessler Clinic below has no rate card and one project has no client at all.
     *
     * <p><strong>It also seeds time</strong>, which no seeder did before. An invoice line is
     * approved hours at a rate-card rate and nothing else (D24), so a demo without an approved
     * fortnight is a demo where every invoice is a document with no lines — technically written and
     * saying nothing. The hours are logged through {@code time}'s own service and approved through
     * its own state machine, so what lands is what a person could have produced.
     *
     * <h2>Three clients, and the third is the point of the other two</h2>
     *
     * <ul>
     *   <li><strong>Acme Retail — EUR.</strong> A card with three rungs at three effective dates,
     *       which is what makes 019 D-d's ladder visible: an {@code ALL_ROLES} floor, a {@code ROLE}
     *       rung above it, and a {@code PERSON} rung effective from this month only — so last
     *       month's hours price at one number and this month's at another, from one card.
     *       It carries the <strong>issued</strong> invoice.</li>
     *   <li><strong>Bergstrom Health — CHF.</strong> A card with <em>no</em> {@code ALL_ROLES} rung,
     *       which is the configuration that gives 019 D-g's second bucket real data: somebody who
     *       holds neither the named role nor the named person's account has approved hours that
     *       nothing can price. It carries the <strong>draft</strong>, so
     *       {@code invoice.html}'s amber note and its <em>no rate</em> row both have something in
     *       them.</li>
     *   <li><strong>Kessler Clinic — no card at all.</strong> {@code billing.html}'s <em>no rate
     *       card yet</em> row beside its {@code Set} button, with a project and approved hours
     *       behind it: the hours are recorded and cannot be priced, which is the sentence that
     *       mockup's footer makes.</li>
     * </ul>
     *
     * <p>A fourth project gets no client and no budget, which is 019 D-p's row — <em>None
     * (internal)</em> in the same table as three billed projects, with no warning tone.
     *
     * <p><strong>Everything here is find-or-create</strong>, because {@code seed-demo-data} is a
     * top-up rather than a reset and it runs once per project. A client is found by name, a card by
     * its slot, a budget is a target state, and an expense and an invoice are guarded by reading
     * what is already there — {@code timeEntries.log} and {@code expenses.record} have no natural
     * key, so a second run without the guard would double the hours and the costs.
     */
    private void seedBilling(
            DemoCast cast, UUID projectId, List<String> issueKeys, DemoSeedReport report,
            String label) {

        UUID admin = cast.adminId();
        List<ClientView> roster = ensureClients(admin, report);
        if (roster.size() != CLIENT_SLOTS.size()) {
            /*
              All three or none. The roster is indexed BY SLOT below — slot 0 is the EUR card and the
              issued invoice, slot 1 the floorless CHF card and the draft, slot 2 no card at all — so
              a roster missing one entry would shift every index and give a client the wrong
              currency. Refusing to seed is the readable failure; a CHF invoice for Acme Retail is
              not, and nothing downstream would report it.
            */
            report.note("expected " + CLIENT_SLOTS.size() + " demo clients and found "
                    + roster.size() + ", so nothing billing-related was seeded");
            return;
        }
        int slot = slotFor(admin, roster, projectId);
        if (slot < 0) {
            // Every client already has a project, so this one stays internal — 019 D-p's row, and
            // the reason it is a `return` rather than a fourth client is that a company that builds
            // some of its own things is the case the screen has to read correctly for.
            report.line(label, "billing — no client, which is a state and not a gap (019 D-p)");
            return;
        }
        ClientView client = roster.get(slot);

        /*
          The projection is refreshed HERE rather than trusted. issue_fact is fed by an outbox event
          and time.IssueFacts.byKey is what turns an issue key into a subject, so a seeder logging
          an hour against an issue created seconds ago would be racing a projection — and the
          failure would read as "that issue does not exist", naming nothing that is wrong.
          DemoExecutionSeeder calls the same method for the same reason.
        */
        issueFacts.refresh();

        String subject = issueKeys.isEmpty() ? null : issueKeys.get(0);
        seedRateCard(admin, cast, client, slot, report);
        seedHours(cast, projectId, subject, slot, report);
        seedBudget(admin, projectId, slot, report);
        seedExpense(admin, projectId, slot, report);
        seedInvoice(admin, client, slot, report);

        report.line(label, "billing — " + client.name() + " · "
                + (CLIENT_SLOTS.get(slot).currency() == null
                        ? "no rate card, deliberately" : CLIENT_SLOTS.get(slot).currency() + " card"));
    }

    /**
     * One demo client and the currency its card prices in — {@code null} for the one that has no
     * card, which is a slot rather than an omission.
     */
    private record ClientSlot(String name, String location, String industry, String currency) {
    }

    private static final List<ClientSlot> CLIENT_SLOTS = List.of(
            new ClientSlot("Acme Retail", "Stockholm", "Retail", "EUR"),
            new ClientSlot("Bergstrom Health", "Zurich", "Healthcare", "CHF"),
            new ClientSlot("Kessler Clinic", "Vienna", "Healthcare", null));

    /**
     * The three clients, created once and <strong>read back by name</strong>.
     *
     * <p>Read back rather than assumed from the create's own answer, which is {@code ensureSkills}'
     * argument one arm up: a second run answers {@code Conflict} for a name that already exists, and
     * a {@code Conflict} carries no id because it is a refusal and not a lookup.
     */
    private List<ClientView> ensureClients(UUID admin, DemoSeedReport report) {
        for (ClientSlot wanted : CLIENT_SLOTS) {
            clients.create(admin, new ClientWriteRequest(
                    wanted.name(), wanted.location(), wanted.industry(), null, null, null));
        }
        if (!(clients.list(admin, null, null) instanceof GateResult.Allowed<List<ClientView>> all)) {
            return List.of();
        }
        List<ClientView> ordered = new ArrayList<>();
        for (ClientSlot wanted : CLIENT_SLOTS) {
            all.value().stream()
                    .filter(client -> client.name().equals(wanted.name()))
                    .findFirst()
                    .ifPresent(found -> {
                        ordered.add(found);
                        contactFor(admin, found);
                    });
        }
        return List.copyOf(ordered);
    }

    /** One contact per client, so an invoice has a {@code billTo} to address (019 D-u). */
    private void contactFor(UUID admin, ClientView client) {
        if (client.contactCount() > 0) {
            return;
        }
        clients.addContact(admin, client.id(), new ClientContactWriteRequest(
                "Accounts payable", "Finance", "invoices@" + slugOf(client.name()) + ".test", null));
    }

    /**
     * Which client this project belongs to — the one it is already attached to, or the first with no
     * project yet.
     *
     * <p>Derived from what is on disk rather than from a counter, so a second run attaches nothing a
     * second time and moves nothing: attaching a project to a different client would silently
     * reprice every draft on both sides, which is the reason {@code RateCard.clientId} is
     * {@code updatable = false} one module over.
     *
     * @return the slot, or {@code -1} when every client already has a project
     */
    private int slotFor(UUID admin, List<ClientView> roster, UUID projectId) {
        for (int slot = 0; slot < roster.size(); slot++) {
            if (clients.read(admin, roster.get(slot).id())
                    instanceof GateResult.Allowed<ClientDetailView> detail
                    && detail.value().projects().stream()
                            .anyMatch(project -> project.id().equals(projectId))) {
                return slot;
            }
        }
        for (int slot = 0; slot < roster.size(); slot++) {
            if (roster.get(slot).projectCount() == 0) {
                clients.attachTo(admin, projectId, new ProjectClientRequest(roster.get(slot).id()));
                return slot;
            }
        }
        return -1;
    }

    /**
     * The client's card — three rungs at three effective dates for the first, two and no
     * {@code ALL_ROLES} for the second, and none at all for the third.
     *
     * <p>The second is the shape that matters and it is not a contrivance: a card naming a role and
     * a person, with no floor under them, is what a Finance user builds when the contract was
     * negotiated per named person. Everybody else's approved hours then land in the {@code NO_RATE}
     * bucket, counted and named (019 D-g), which is the state a screen has to render and a seeded
     * instance otherwise never reaches.
     */
    private void seedRateCard(
            UUID admin, DemoCast cast, ClientView client, int slot, DemoSeedReport report) {

        String currency = CLIENT_SLOTS.get(slot).currency();
        if (currency == null || hasCard(admin, client.id())) {
            return;
        }
        UUID testLead = roleNamed("Test Lead");
        if (testLead == null) {
            report.note("the Test Lead role could not be resolved, so no rate card was seeded");
            return;
        }
        LocalDate lastMonth = LocalDate.now(clock).withDayOfMonth(1).minusMonths(1);
        LocalDate thisMonth = LocalDate.now(clock).withDayOfMonth(1);

        List<RateCardEntryInput> rungs = slot == 0
                ? List.of(
                        new RateCardEntryInput(RateCardScope.ALL_ROLES, null, null,
                                new BigDecimal("95.00"), lastMonth.minusMonths(6)),
                        new RateCardEntryInput(RateCardScope.ROLE, null, testLead,
                                new BigDecimal("120.00"), lastMonth),
                        new RateCardEntryInput(RateCardScope.PERSON, cast.engineer(), null,
                                new BigDecimal("140.00"), thisMonth))
                // NO ALL_ROLES RUNG, deliberately — see this method's header. It is what puts real
                // hours in the NO_RATE bucket, and it is a card a Finance user could have built.
                : List.of(
                        new RateCardEntryInput(RateCardScope.ROLE, null, testLead,
                                new BigDecimal("150.00"), thisMonth.minusMonths(3)));

        GateResult<Outcome<RateCardView>> gate = rateCards.create(admin,
                new RateCardWriteRequest(client.id(), currency, rungs));
        if (!(gate instanceof GateResult.Allowed<Outcome<RateCardView>> allowed)
                || !(allowed.value() instanceof Outcome.Written)) {
            report.note("the rate card for " + client.name() + " was refused");
        }
    }

    private boolean hasCard(UUID admin, UUID clientId) {
        return rateCards.listSlots(admin) instanceof GateResult.Allowed<List<RateCardSlotView>> all
                && all.value().stream()
                        .anyMatch(row -> row.client().id().equals(clientId) && row.card() != null);
    }

    /**
     * Approved hours on this project, and — on the drafting client — an unapproved fortnight beside
     * them.
     *
     * <p>Both are needed and the second is the one that is easy to leave out:
     * {@code invoice.html}'s amber note reads <em>"38 hours are excluded because they are not
     * approved… They are counted here so nobody wonders where they went"</em>, and a seeded instance
     * where every period is approved renders that note never.
     *
     * <p>Logged as {@link DemoCast#lead()} and {@link DemoCast#engineer()} rather than as the admin,
     * because the rate ladder's middle rung is <em>a role that person actually holds on that
     * project</em> (019 D-d) — an invoice where every line said "Company Admin" would price
     * correctly and demonstrate nothing.
     */
    private void seedHours(
            DemoCast cast, UUID projectId, String subject, int slot, DemoSeedReport report) {

        if (subject == null) {
            report.note("no issue to log an hour against, so no billable time was seeded");
            return;
        }
        LocalDate lastMonth = LocalDate.now(clock).withDayOfMonth(1).minusMonths(1).plusDays(9);
        LocalDate thisMonth = LocalDate.now(clock).withDayOfMonth(1).plusDays(2);
        LocalDate later = LocalDate.now(clock).withDayOfMonth(1).plusDays(19);

        if (slot == 0) {
            logAndApprove(cast, cast.lead(), subject, lastMonth, 12.0, report);
            logAndApprove(cast, cast.engineer(), subject, lastMonth, 18.0, report);
            logAndApprove(cast, cast.engineer(), subject, thisMonth, 8.0, report);
        } else if (slot == 1) {
            logAndApprove(cast, cast.lead(), subject, thisMonth, 10.0, report);
            // The NO_RATE bucket, with a real person in it: the Company Admin holds no rung on a
            // card that names a role and a person, and their approved hours are therefore recorded
            // and unpriceable — billing.html's own sentence, with data behind it.
            logAndApprove(cast, cast.adminId(), subject, thisMonth, 4.0, report);
            // And the NOT_APPROVED bucket: logged, submitted, and left for somebody to decide.
            logAndSubmit(cast.adminId(), subject, later, 5.0, report);
        } else {
            /*
              The SECOND fortnight, and the day matters. A timesheet period is per person per half
              month, and this engineer already has an approved one covering the first half from the
              billed project's own pass — logging here would find it approved, and the guard would
              skip these hours silently. The card-less client's whole job is to have approved hours
              nothing can price, so those hours have to actually land.
            */
            logAndApprove(cast, cast.engineer(), subject, later, 9.0, report);
        }
    }

    /** Hours somebody logged and somebody else approved — the only kind an invoice may price. */
    private void logAndApprove(
            DemoCast cast, UUID personId, String subject, LocalDate on, double hours,
            DemoSeedReport report) {

        UUID period = logAndSubmit(personId, subject, on, hours, report);
        if (period == null) {
            return;
        }
        GateResult<TimesheetService.Outcome> gate = timesheets.decide(
                cast.adminId(), period, new TimesheetDecisionInput("APPROVE", null));
        if (!(gate instanceof GateResult.Allowed)) {
            report.note("a demo timesheet could not be approved for " + personId);
        }
    }

    /**
     * Logs the hours and submits the fortnight they fall in, or does nothing at all.
     *
     * <p>The guard is the fortnight's own logged hours rather than an idempotency key, because
     * {@code time} has none: a second seed run would otherwise double every figure on every billing
     * screen, and an approved period refuses the write anyway — so without this the second run also
     * fills the report with refusals that mean "already done".
     *
     * @return the period, or {@code null} when there was nothing to do or the write was refused
     */
    private UUID logAndSubmit(
            UUID personId, String subject, LocalDate on, double hours, DemoSeedReport report) {

        if (!(timesheets.current(personId, on)
                instanceof GateResult.Allowed<TimesheetPeriodView> before)) {
            report.note("a demo timesheet period was unreadable for " + personId);
            return null;
        }
        if (before.value().loggedHours() > 0) {
            return null;
        }
        timeEntries.log(personId, new TimeEntryInput(
                TimeSubjectType.ISSUE, subject, on, hours, "Demo data"));

        if (!(timesheets.current(personId, on)
                instanceof GateResult.Allowed<TimesheetPeriodView> after)) {
            return null;
        }
        UUID period = after.value().id();
        if (!(timesheets.submit(personId, period) instanceof GateResult.Allowed)) {
            report.note("a demo timesheet could not be submitted for " + personId);
            return null;
        }
        return period;
    }

    /** A MONEY ceiling on one project and an HOURS ceiling on another — 019 D-h's two readings. */
    private void seedBudget(UUID admin, UUID projectId, int slot, DemoSeedReport report) {
        BudgetWriteRequest wanted = switch (slot) {
            case 0 -> new BudgetWriteRequest(BudgetBasis.MONEY, new BigDecimal("40000.00"), "EUR");
            case 1 -> new BudgetWriteRequest(BudgetBasis.HOURS, new BigDecimal("200.00"), null);
            // The third project has none, so settings-general.html's "no budget set" row and the
            // project overview's absent panel are both reachable in a seeded instance.
            default -> null;
        };
        if (wanted == null) {
            return;
        }
        if (!(budgets.set(admin, projectId, wanted) instanceof GateResult.Allowed)) {
            report.note("the demo budget was refused on project " + projectId);
        }
    }

    /**
     * One recorded cost per billed project — <strong>never an invoice line</strong> (scope D24,
     * 019 D-j).
     *
     * <p>No screen renders these and none is planned; they exist because {@code billing:expense} is
     * in the permission catalogue and a key with no reachable endpoint is the unreachable row roles
     * spec §43 names. Seeding two of them is what makes the CLI and MCP verbs that read them answer
     * with something.
     */
    private void seedExpense(UUID admin, UUID projectId, int slot, DemoSeedReport report) {
        if (slot > 1) {
            return;
        }
        if (expenses.list(admin, projectId, null, null)
                instanceof GateResult.Allowed<List<ExpenseView>> existing
                && !existing.value().isEmpty()) {
            return;
        }
        ExpenseWriteRequest wanted = slot == 0
                ? new ExpenseWriteRequest(projectId, LocalDate.now(clock).minusDays(20),
                        "Device lab hire for the release regression", new BigDecimal("640.00"),
                        "EUR")
                : new ExpenseWriteRequest(projectId, LocalDate.now(clock).minusDays(6),
                        "Accessibility audit, external", new BigDecimal("1800.00"), "CHF");

        if (!(expenses.record(admin, wanted) instanceof GateResult.Allowed)) {
            report.note("the demo expense was refused on project " + projectId);
        }
    }

    /**
     * One <strong>issued</strong> invoice and one <strong>draft</strong>, and the pair is the point.
     *
     * <p>The issued one is last month's, on the client whose card has a floor, so its lines are a
     * record: editing that card afterwards leaves them exactly as they were sent (019 D-c). The
     * draft is this month's, on the client whose card has no floor, so it recomputes on every read
     * and carries both exclusion buckets. A seeded instance therefore shows the two halves of this
     * module's central decision side by side, which is the one thing a screenshot of either alone
     * cannot.
     *
     * <p>Guarded by reading what is already there: issuing is not idempotent and a second run would
     * otherwise raise a second invoice for a period whose hours the first one already took — which
     * would then be an <em>empty</em> invoice, because the exclusion query does its job.
     */
    private void seedInvoice(UUID admin, ClientView client, int slot, DemoSeedReport report) {
        if (slot > 1) {
            return;
        }
        if (invoices.list(admin, client.id(), null)
                instanceof GateResult.Allowed<List<InvoiceView>> existing
                && !existing.value().isEmpty()) {
            return;
        }
        LocalDate month = LocalDate.now(clock).withDayOfMonth(1).minusMonths(slot == 0 ? 1 : 0);
        InvoiceDraftInput wanted = new InvoiceDraftInput(
                client.id(), month, month.withDayOfMonth(month.lengthOfMonth()), null);

        if (!(invoices.draft(admin, wanted)
                instanceof GateResult.Allowed<Outcome<InvoiceDetailView>> drafted)
                || !(drafted.value() instanceof Outcome.Written<InvoiceDetailView> written)) {
            report.note("the demo invoice for " + client.name() + " was refused");
            return;
        }
        if (slot != 0) {
            return;
        }
        if (!(invoices.issue(admin, written.value().invoice().id())
                instanceof GateResult.Allowed<Outcome<InvoiceDetailView>> issued)
                || !(issued.value() instanceof Outcome.Written<InvoiceDetailView> record)
                || record.value().invoice().status() != InvoiceStatus.ISSUED) {
            report.note("the demo invoice for " + client.name() + " could not be issued");
        }
    }

    /** A role id by name — the same lookup {@code seedApiTokens} does for the Agent role. */
    private UUID roleNamed(String name) {
        return roles.findAllByOrderByNameAsc().stream()
                .filter(role -> role.getName().equals(name))
                .map(Role::getId)
                .findFirst()
                .orElse(null);
    }

    private static String slugOf(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /**
     * The company's vocabulary, created once and read back by name.
     *
     * <p>Read back rather than assumed from the create's own answer: a second run answers
     * {@code Conflict} for a name that is already there, and a {@code Conflict} carries no id (it is
     * a refusal, not a lookup). Listing is what makes this idempotent without the seeder inventing a
     * second find-or-create beside the one the service has.
     */
    private Map<String, UUID> ensureSkills(UUID admin, DemoSeedReport report) {
        for (String name : List.of(
                "Test strategy", "Playwright", "Java", "TypeScript", "Accessibility")) {
            staffingProfiles.createSkill(admin, new SkillInput(name));
        }
        Map<String, UUID> byName = new LinkedHashMap<>();
        if (staffingProfiles.listSkills(admin)
                instanceof GateResult.Allowed<List<SkillView>> allowed) {
            allowed.value().forEach(skill -> byName.put(skill.name(), skill.id()));
        } else {
            report.note("the skill vocabulary was unreadable, so no skills were assigned");
        }
        return byName;
    }

    private void profile(
            UUID admin, UUID personId, int weeklyHours, String jobTitle, String location,
            int yearsExperience, DemoSeedReport report) {

        GateResult<ProfileService.Outcome<PersonProfileView>> gate = staffingProfiles.putProfile(
                admin, personId,
                new PersonProfileInput((double) weeklyHours, jobTitle, location,
                        LocalDate.now(clock).minusYears(yearsExperience)));

        if (!(gate instanceof GateResult.Allowed<ProfileService.Outcome<PersonProfileView>> allowed)
                || !(allowed.value() instanceof ProfileService.Outcome.Written)) {
            report.note("a staffing profile was refused for " + personId);
        }
    }

    private void skills(
            UUID admin, UUID personId, Map<String, UUID> vocabulary, List<String> names,
            DemoSeedReport report) {

        List<UUID> ids = new ArrayList<>();
        names.stream().map(vocabulary::get).filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            report.note("no known skills to assign to " + personId);
            return;
        }
        staffingProfiles.putSkills(admin, personId, new PersonSkillsInput(ids));
    }

    /**
     * One allocation, and a re-run's {@code Overlaps} is success rather than a refusal to report.
     *
     * <p>017 D-n refuses a second allocation of the same person to the same project over overlapping
     * days, which is exactly what a second seed run asks for — so the conflict IS the idempotency
     * here, and reporting it would fill the seed report with refusals that mean "already done".
     */
    private void allocate(
            UUID admin, UUID projectId, UUID personId, int percent,
            LocalDate from, LocalDate to, DemoSeedReport report) {

        GateResult<AllocationService.Outcome> gate = allocations.create(admin, projectId,
                new AllocationInput(personId, percent, from, to, null));

        if (gate instanceof GateResult.Allowed<AllocationService.Outcome> allowed
                && allowed.value() instanceof AllocationService.Outcome.Invalid invalid) {
            report.note("an allocation was refused: " + invalid.errors());
        } else if (!(gate instanceof GateResult.Allowed)) {
            report.note("an allocation was refused on project " + projectId);
        }
    }

    /** A half day off, written once — checked by reading, because an absence has no natural key. */
    private void halfDayOff(UUID admin, UUID personId, LocalDate on, DemoSeedReport report) {
        if (staffingProfiles.listAbsences(admin, personId, on, on)
                instanceof GateResult.Allowed<List<AbsenceView>> allowed
                && !allowed.value().isEmpty()) {
            return;
        }
        GateResult<ProfileService.Outcome<AbsenceView>> gate = staffingProfiles.createAbsence(
                admin, personId,
                new AbsenceInput(on, on, AbsenceKind.LEAVE, 0.5, "Half day — dentist"));

        if (!(gate instanceof GateResult.Allowed)) {
            report.note("the demo absence was refused for " + personId);
        }
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
