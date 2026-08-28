package com.tcms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.repository.GherkinBackfill;
import com.tcms.repository.GherkinBackfillReport;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code migrate-gherkin-source} — §255 T5, the one-time backfill that empties
 * {@code test_case.gherkin_source}.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A migration that writes rows and no version.</strong> The rows are the visible
 *       half and the version is the load-bearing one: {@code CaseVersions}' content hash covers the
 *       steps, so rows written past it leave every migrated case with a stored hash that no longer
 *       describes it — and the <em>next ordinary save by a real author</em> writes the version,
 *       attributing this operator's migration to them. Nothing fails, nothing looks wrong, and the
 *       one screen whose job is to say who changed what is wrong about every migrated case,
 *       forever. So the version, its author and its comment are asserted, not the rows alone.</li>
 *   <li><strong>The company context opened inside the transaction.</strong> The §246 shape:
 *       {@code CompanyAwareTransactionManager} reads it in {@code doBegin}, so a context opened too
 *       late leaves the RLS GUC unset and the command reports a clean nothing. Two companies, and
 *       the second one's case is asserted untouched — a run that migrated across companies and a
 *       run that migrated none are both loud here, and neither is visible with one company.</li>
 *   <li><strong>A blob the parser refuses taken as an empty procedure.</strong> The text is a
 *       person's, and being unparseable is an ordinary state. Losing it would be silent: the case
 *       would have no rows and no blob, and nothing on screen would say a procedure had ever
 *       existed. Every refusal is asserted to leave the column exactly as it was.</li>
 *   <li><strong>A replay doing the work twice.</strong> An operator runs this, reads the report,
 *       fixes two cases and runs it again. The second run must write no version for the cases the
 *       first one migrated — a second version whose diff is empty is exactly the noise plan §6j
 *       added the hash to keep out, and here it would arrive on every case at once.</li>
 *   <li><strong>{@code --dry-run} writing.</strong> It is the flag an operator reaches for
 *       <em>because</em> they are unsure, so a dry run that wrote anything would be worse than not
 *       offering one.</li>
 * </ol>
 *
 * <p>It sits in the composition root beside the command rather than in
 * {@code com.tcms.provisioning} with its four siblings' tests, because the command does — see
 * {@link MigrateGherkinSourceCommand}'s header for why, and note that both halves of what this
 * class drives ({@code resolve} and {@code GherkinBackfill}) are reachable from here without a
 * single widening: the resolution is package-private in this package, and the backfill is public
 * because {@code provisioning}'s successor had to be able to call it.
 *
 * <p>Company, project, user and case ids are unique to this class ({@code 2b…}), and so are the
 * slugs and the email addresses — {@code CaseHealthRecomputeTest}'s header records what happens
 * when only the ids are: eleven failures in the full suite and none alone, with a stack trace
 * pointing at the base class.
 */
@DisplayName("migrate-gherkin-source — the one-time backfill off gherkin_source")
class MigrateGherkinSourceTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("2b000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("2b000000-0000-4000-8000-000000000002");

    /** Granted to the actor. */
    private static final UUID ACME_LEDGER = UUID.fromString("2b100000-0000-4000-8000-000000000001");
    /** Same company, and the actor holds no grant on it. */
    private static final UUID ACME_MOBILE = UUID.fromString("2b100000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_PROJECT =
            UUID.fromString("2b100000-0000-4000-8000-000000000003");

    /** The operator's account: an ACTIVE member of Acme, granted on ACME_LEDGER only. */
    private static final UUID ACTOR = UUID.fromString("2b200000-0000-4000-8000-000000000001");
    /** An account with no membership in Acme at all. */
    private static final UUID STRANGER = UUID.fromString("2b200000-0000-4000-8000-000000000002");
    /** A membership that was raised and never accepted — INVITED conveys nothing. */
    private static final UUID INVITEE = UUID.fromString("2b200000-0000-4000-8000-000000000003");

    private static final UUID LEDGER_SUITE = UUID.fromString("2b300000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_SUITE = UUID.fromString("2b300000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("2b300000-0000-4000-8000-000000000003");

    /** A Feature with one Scenario and three steps — the ordinary case. */
    private static final UUID TEMPLATE = UUID.fromString("2b400000-0000-4000-8000-000000000001");
    /** A Background beside the scenario: the scenario migrates, the Background is reported. */
    private static final UUID WITH_BACKGROUND = UUID.fromString("2b400000-0000-4000-8000-000000000002");
    /** A Scenario Outline — plan §6m, not expanded by either lens yet. */
    private static final UUID OUTLINE = UUID.fromString("2b400000-0000-4000-8000-000000000003");
    /** Text that is not Gherkin at all. */
    private static final UUID UNPARSABLE = UUID.fromString("2b400000-0000-4000-8000-000000000004");
    /** A blob AND rows — what every case looks like on the second run. */
    private static final UUID ALREADY = UUID.fromString("2b400000-0000-4000-8000-000000000005");
    /** STRUCTURED, no blob. Must not appear in the report at all. */
    private static final UUID STRUCTURED = UUID.fromString("2b400000-0000-4000-8000-000000000006");
    /** In the project the actor cannot see. */
    private static final UUID UNSEEN = UUID.fromString("2b400000-0000-4000-8000-000000000007");
    /** Umbra's. Its blob must survive an Acme run untouched. */
    private static final UUID FOREIGN = UUID.fromString("2b400000-0000-4000-8000-000000000008");

    private static final String TEMPLATE_BLOB = """
            Feature: The ledger
              Scenario: A refund lands on the ledger
                Given a captured payment of 100 EUR
                When I refund it
                Then the ledger shows a credit
            """;

    private static final String BACKGROUND_BLOB = """
            Feature: The ledger
              Background:
                Given the operator is signed in
                And the ledger is open
              Scenario: A second refund is refused
                When I refund it twice
                Then the second attempt is refused
            """;

    private static final String OUTLINE_BLOB = """
            Feature: The ledger
              Scenario Outline: Refunds of several sizes
                Given a captured payment of <amount>
                Then the ledger shows a credit

                Examples:
                  | amount |
                  | 100    |
            """;

    private static final String UNPARSABLE_BLOB = """
            Feature: The ledger
              Scenario: A refund
                Given a captured payment
              Background:
                Given this cannot follow a scenario
            """;

    private static final String ACME_SLUG = "mgs-acme";
    private static final String UMBRA_SLUG = "mgs-umbra";
    private static final String ACTOR_EMAIL = "ops@mgs-acme.test";

    @Autowired
    private MigrateGherkinSourceCommand command;

    @Autowired
    private GherkinBackfill backfill;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, ACME_SLUG, "Acme", now);
            company(UMBRA, UMBRA_SLUG, "Umbra", now);
            activeUser(ACTOR, ACTOR_EMAIL, now);
            activeUser(STRANGER, "stranger@mgs-elsewhere.test", now);
            activeUser(INVITEE, "invitee@mgs-acme.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_LEDGER, ACME, "MGSL", "Ledger", now);
            seedProject(ACME_MOBILE, ACME, "MGSM", "Mobile", now);
            member(ACME, ACTOR, "O. Perator", now, true);
            member(ACME, INVITEE, "I. Nvitee", now, false);
            grantOnProject("actor-ledger", ACME, Role.TEST_ENGINEER_ID, ACTOR, ACME_LEDGER, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_PROJECT, UMBRA, "MGSU", "Umbra Web", now)));

        seedCases();
    }

    // =====================================================================
    // migrating
    // =====================================================================

    @Nested
    @DisplayName("what a run does to a case")
    class Migrating {

        @Test
        @DisplayName("a scenario becomes step rows, in order, with their keywords")
        void theBlobBecomesRows() throws SQLException {
            GherkinBackfillReport report = run(false);

            assertThat(entry(report, "MGSL-1").outcome())
                    .isEqualTo(GherkinBackfillReport.Outcome.MIGRATED);
            assertThat(entry(report, "MGSL-1").steps()).isEqualTo(3);
            assertThat(stepsOf(ACME, TEMPLATE))
                    .containsExactly(
                            "GIVEN a captured payment of 100 EUR",
                            "WHEN I refund it",
                            "THEN the ledger shows a credit");
        }

        @Test
        @DisplayName("and a version the actor authored, one past the case's own counter")
        void aVersionIsMinted() throws SQLException {
            // The half nothing on screen would show as missing, and the half that decides whether
            // the NEXT author's save is attributed to them or to this command. See the class
            // header's first item.
            run(false);

            List<Version> versions = versionsOf(ACME, TEMPLATE);
            assertThat(versions).hasSize(1);
            assertThat(versions.getFirst().versionNo()).isEqualTo(2);
            assertThat(versions.getFirst().createdBy())
                    .as("a version nobody authored is a change the history cannot attribute")
                    .isEqualTo(ACTOR);
            assertThat(versions.getFirst().changeComment())
                    .isEqualTo("Migrated from gherkin_source by migrate-gherkin-source");
            assertThat(currentVersionNoOf(ACME, TEMPLATE))
                    .as("the case's own counter and the version it points at must agree")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the snapshot carries the steps, because that is what a run pins")
        void theSnapshotCarriesTheSteps() throws Exception {
            // A version whose snapshot is the case as it was BEFORE the migration would pin an
            // empty procedure into every run planned afterwards — the rows would be right and the
            // execution checklist empty, which is the state this whole initiative exists to end.
            run(false);

            /*
              PARSED, not substring-matched. `snapshot` is `jsonb`, so `snapshot::text` is
              PostgreSQL's canonical rendering — `"keyword": "GIVEN"`, with the space — and an
              assertion spelling `"keyword":"GIVEN"` tests the database's formatting rather than
              anything this migration writes. It cannot pass whatever the backfill does.
            */
            JsonNode snapshot = new ObjectMapper().readTree(snapshotOf(ACME, TEMPLATE));
            JsonNode steps = snapshot.get("steps");
            assertThat(steps).hasSize(3);
            assertThat(steps.get(0).get("keyword").asText()).isEqualTo("GIVEN");
            assertThat(steps.get(0).get("action").asText())
                    .isEqualTo("a captured payment of 100 EUR");
            assertThat(steps.get(1).get("keyword").asText()).isEqualTo("WHEN");
            assertThat(steps.get(2).get("keyword").asText()).isEqualTo("THEN");
        }

        @Test
        @DisplayName("gherkin_source is left exactly as it was")
        void theBlobSurvives() throws SQLException {
            // 002- reads it as the proof the migration was faithful and drops it afterwards.
            // Clearing it here would make a bad parse unrecoverable, on text nobody has re-read.
            run(false);

            assertThat(gherkinSourceOf(ACME, TEMPLATE)).isEqualTo(TEMPLATE_BLOB);
        }

        @Test
        @DisplayName("a Background is reported and its scenario still migrates")
        void aBackgroundIsListedAndTheScenarioSurvives() throws SQLException {
            // The Background's steps would have to become library conditions, and an operator
            // command has no library:manage semantics to create them under (data-model §4). The
            // wrong repair is to refuse the case: that leaves a perfectly parsable scenario on the
            // blob to make a point about its preconditions.
            GherkinBackfillReport report = run(false);

            GherkinBackfillReport.Entry entry = entry(report, "MGSL-2");
            assertThat(entry.outcome()).isEqualTo(GherkinBackfillReport.Outcome.MIGRATED);
            assertThat(entry.note()).contains("background-skipped").contains("2 step(s)");
            assertThat(stepsOf(ACME, WITH_BACKGROUND))
                    .containsExactly(
                            "WHEN I refund it twice",
                            "THEN the second attempt is refused");
            assertThat(titleOf(ACME, WITH_BACKGROUND))
                    .as("the blob's Scenario: name is not written back over a title somebody "
                            + "authored — data-model §4")
                    .isEqualTo("Refunding the same payment twice");
        }

        @Test
        @DisplayName("a case that already has rows is listed and left alone")
        void aMigratedCaseIsSkipped() throws SQLException {
            GherkinBackfillReport report = run(false);

            assertThat(entry(report, "MGSL-5").outcome())
                    .isEqualTo(GherkinBackfillReport.Outcome.ALREADY_MIGRATED);
            assertThat(entry(report, "MGSL-5").reason()).isEqualTo("already has steps");
            assertThat(stepsOf(ACME, ALREADY))
                    .as("the existing row must keep its id and its text — step_result points at it")
                    .containsExactly("GIVEN a step somebody already wrote");
            assertThat(versionsOf(ACME, ALREADY)).isEmpty();
        }

        @Test
        @DisplayName("a case with no blob is not in the report at all")
        void aStructuredCaseIsNotAsubject() {
            assertThat(run(false).entries())
                    .as("the report is the operator's list of work; padding it with cases that "
                            + "were never on the blob is how the one that matters gets missed")
                    .noneMatch(entry -> entry.caseKey().equals("MGSL-6"));
        }
    }

    // =====================================================================
    // refusals
    // =====================================================================

    @Nested
    @DisplayName("what a run refuses, and what it leaves behind")
    class Refusing {

        @Test
        @DisplayName("a Scenario Outline is listed with the line and the remedy")
        void anOutlineIsListed() throws SQLException {
            GherkinBackfillReport report = run(false);

            GherkinBackfillReport.Entry entry = entry(report, "MGSL-3");
            assertThat(entry.outcome()).isEqualTo(GherkinBackfillReport.Outcome.NOT_MIGRATED);
            assertThat(entry.reason())
                    .contains("Line 2")
                    .contains("Scenario Outline");
            assertThat(stepsOf(ACME, OUTLINE)).isEmpty();
            assertThat(gherkinSourceOf(ACME, OUTLINE)).isEqualTo(OUTLINE_BLOB);
        }

        @Test
        @DisplayName("unparsable text is listed with its line, and its blob survives")
        void unparsableTextIsListed() throws SQLException {
            GherkinBackfillReport report = run(false);

            GherkinBackfillReport.Entry entry = entry(report, "MGSL-4");
            assertThat(entry.outcome()).isEqualTo(GherkinBackfillReport.Outcome.NOT_MIGRATED);
            assertThat(entry.reason())
                    .as("a reason with no line is a reason nobody can act on")
                    .containsPattern("Line \\d+");
            assertThat(stepsOf(ACME, UNPARSABLE)).isEmpty();
            assertThat(versionsOf(ACME, UNPARSABLE)).isEmpty();
            assertThat(gherkinSourceOf(ACME, UNPARSABLE)).isEqualTo(UNPARSABLE_BLOB);
        }

        @Test
        @DisplayName("a case in a project the actor cannot see is listed, not silently migrated")
        void anUngrantedProjectIsListed() throws SQLException {
            // The version snapshot records the case's classification, and `renderer.vocabularies`
            // is a GATED read that answers "none" for a caller without the grant. Migrating anyway
            // would write a version claiming every classified case in that project is
            // unclassified — a false record, in the table whose whole purpose is that past claims
            // stay true.
            GherkinBackfillReport report = run(false);

            GherkinBackfillReport.Entry entry = entry(report, "MGSM-1");
            assertThat(entry.outcome()).isEqualTo(GherkinBackfillReport.Outcome.NOT_MIGRATED);
            assertThat(entry.reason()).contains("cannot see");
            assertThat(stepsOf(ACME, UNSEEN)).isEmpty();
        }

        @Test
        @DisplayName("the report counts what is still on the blob — 002-'s gate, as a number")
        void theGateIsCountable() {
            GherkinBackfillReport report = run(false);

            assertThat(report.countOf(GherkinBackfillReport.Outcome.MIGRATED)).isEqualTo(2);
            assertThat(report.countOf(GherkinBackfillReport.Outcome.ALREADY_MIGRATED)).isEqualTo(1);
            assertThat(report.stillOnTheBlob())
                    .as("the Outline, the unparsable one and the unreachable one")
                    .isEqualTo(3);
        }
    }

    // =====================================================================
    // dry run and replay
    // =====================================================================

    @Nested
    @DisplayName("rehearsing and repeating")
    class RehearsingAndRepeating {

        @Test
        @DisplayName("--dry-run writes nothing at all")
        void aDryRunWritesNothing() throws SQLException {
            GherkinBackfillReport report = run(true);

            assertThat(report.dryRun()).isTrue();
            assertThat(entry(report, "MGSL-1").outcome())
                    .isEqualTo(GherkinBackfillReport.Outcome.MIGRATED);
            assertThat(stepsOf(ACME, TEMPLATE))
                    .as("the flag an operator reaches for BECAUSE they are unsure")
                    .isEmpty();
            assertThat(stepsOf(ACME, WITH_BACKGROUND)).isEmpty();
            assertThat(versionsOf(ACME, TEMPLATE)).isEmpty();
            assertThat(currentVersionNoOf(ACME, TEMPLATE)).isEqualTo(1);
        }

        @Test
        @DisplayName("and reports exactly what the real run then does")
        void aDryRunPredictsTheRealOne() {
            // The rehearsal is worth nothing if the two disagree — an operator who reads a clean
            // dry run and then gets a different answer has learned that the dry run is advisory.
            List<String> rehearsed = outcomes(run(true));
            List<String> performed = outcomes(run(false));

            assertThat(rehearsed).isEqualTo(performed);
        }

        @Test
        @DisplayName("a replay migrates nothing and says so case by case")
        void aReplayIsANoOp() throws SQLException {
            run(false);

            GherkinBackfillReport second = run(false);

            assertThat(second.countOf(GherkinBackfillReport.Outcome.MIGRATED)).isZero();
            assertThat(entry(second, "MGSL-1").outcome())
                    .isEqualTo(GherkinBackfillReport.Outcome.ALREADY_MIGRATED);
            assertThat(versionsOf(ACME, TEMPLATE))
                    .as("a second version whose diff is empty is exactly the noise the content "
                            + "hash exists to keep out, and here it would arrive on every case")
                    .hasSize(1);
            assertThat(currentVersionNoOf(ACME, TEMPLATE)).isEqualTo(2);
        }
    }

    // =====================================================================
    // isolation and actor resolution
    // =====================================================================

    @Nested
    @DisplayName("the boundaries — one company, one authorised actor")
    class Boundaries {

        @Test
        @DisplayName("the other company's case is not touched, and is not in the report")
        void theOtherCompanyIsUntouched() throws SQLException {
            // The §246 shape. The company context is opened OUTSIDE every transaction here; opened
            // inside, doBegin would see no company, the GUC would stay unset and this run would
            // report a clean nothing — which is the failure that looks most like success.
            GherkinBackfillReport report = run(false);

            assertThat(report.entries())
                    .noneMatch(entry -> entry.caseKey().startsWith("MGSU-"));
            assertThat(stepsOf(UMBRA, FOREIGN)).isEmpty();
            assertThat(gherkinSourceOf(UMBRA, FOREIGN)).isEqualTo(TEMPLATE_BLOB);
            assertThat(versionsOf(UMBRA, FOREIGN)).isEmpty();
        }

        @Test
        @DisplayName("the run finds something — so the assertion above is not vacuous")
        void theRunIsNotEmpty() {
            assertThat(run(false).entries())
                    .as("every isolation assertion in this class passes against a command that "
                            + "reads zero rows; this is what stops that being the reason")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("an address nobody holds is refused, naming both it and the company")
        void anUnknownActorIsRefused() {
            MigrateGherkinSourceCommand.Resolution resolution =
                    command.resolve(arguments(ACME_SLUG, "nobody@mgs-acme.test"));

            assertThat(resolution)
                    .isInstanceOf(MigrateGherkinSourceCommand.Resolution.NoSuchActor.class);
            assertThat(resolution.message())
                    .contains("nobody@mgs-acme.test")
                    .contains(ACME_SLUG);
            assertThat(MigrateGherkinSourceCommand.exitCodeOf(resolution)).isEqualTo(2);
        }

        @Test
        @DisplayName("an account that exists elsewhere is refused for THIS company")
        void aNonMemberIsRefused() {
            MigrateGherkinSourceCommand.Resolution resolution =
                    command.resolve(arguments(ACME_SLUG, "stranger@mgs-elsewhere.test"));

            assertThat(resolution)
                    .isInstanceOf(MigrateGherkinSourceCommand.Resolution.NotAMember.class);
            assertThat(resolution.message())
                    .contains("stranger@mgs-elsewhere.test")
                    .contains(ACME_SLUG);
            assertThat(MigrateGherkinSourceCommand.exitCodeOf(resolution)).isEqualTo(2);
        }

        @Test
        @DisplayName("an INVITED membership is not a membership")
        void anInvitedMemberIsRefused() {
            // The gate is `status = ACTIVE`, not "a row exists" — CompanyMembershipRepository's
            // header. Attributing a version to somebody who has not accepted an invitation would
            // put a name in the history that never agreed to be there.
            MigrateGherkinSourceCommand.Resolution resolution =
                    command.resolve(arguments(ACME_SLUG, "invitee@mgs-acme.test"));

            assertThat(resolution)
                    .isInstanceOf(MigrateGherkinSourceCommand.Resolution.NotAMember.class);
        }

        @Test
        @DisplayName("an unknown slug is a different refusal from an unknown actor")
        void anUnknownSlugIsRefused() {
            // A script has to tell "you typed the company wrong" from "grant somebody the
            // membership": the first is a typo and the second is work.
            MigrateGherkinSourceCommand.Resolution resolution =
                    command.resolve(arguments("mgs-no-such-company", ACTOR_EMAIL));

            assertThat(resolution)
                    .isInstanceOf(MigrateGherkinSourceCommand.Resolution.NoSuchCompany.class);
            assertThat(MigrateGherkinSourceCommand.exitCodeOf(resolution)).isEqualTo(1);
        }

        @Test
        @DisplayName("the real actor resolves")
        void theActorResolves() {
            assertThat(command.resolve(arguments(ACME_SLUG, ACTOR_EMAIL)))
                    .isEqualTo(new MigrateGherkinSourceCommand.Resolution.Ready(ACME, ACTOR));
        }
    }

    // =====================================================================
    // helpers — driving
    // =====================================================================

    /**
     * Resolves the arguments exactly as {@code run} does and then migrates — everything the command
     * does short of {@code System.exit}, which no in-process test survives.
     */
    private GherkinBackfillReport run(boolean dryRun) {
        MigrateGherkinSourceCommand.Resolution resolution =
                command.resolve(arguments(ACME_SLUG, ACTOR_EMAIL));
        assertThat(resolution)
                .isInstanceOf(MigrateGherkinSourceCommand.Resolution.Ready.class);
        var ready = (MigrateGherkinSourceCommand.Resolution.Ready) resolution;

        try (var ignored = CompanyContext.open(ready.companyId())) {
            return backfill.run(ready.actorUserId(), dryRun);
        }
    }

    private static MigrateGherkinSourceCommand.Arguments arguments(String slug, String actor) {
        return MigrateGherkinSourceCommand.parse(new DefaultApplicationArguments(
                "migrate-gherkin-source", "--slug=" + slug, "--actor=" + actor));
    }

    private static GherkinBackfillReport.Entry entry(GherkinBackfillReport report, String caseKey) {
        return report.entries().stream()
                .filter(candidate -> candidate.caseKey().equals(caseKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no entry for " + caseKey + " in " + report.entries()));
    }

    private static List<String> outcomes(GherkinBackfillReport report) {
        return report.entries().stream()
                .map(entry -> entry.caseKey() + "=" + entry.outcome() + ":" + entry.steps())
                .toList();
    }

    // =====================================================================
    // helpers — reading what was written
    // =====================================================================

    private List<String> stepsOf(UUID companyId, UUID caseId) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("""
                        SELECT coalesce(keyword, '*') || ' ' || action FROM test_step
                         WHERE case_id = CAST(? AS uuid) ORDER BY position ASC
                        """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(resultSet.getString(1));
                }
            }
        }
        return rows;
    }

    private record Version(int versionNo, UUID createdBy, String changeComment) {
    }

    private List<Version> versionsOf(UUID companyId, UUID caseId) throws SQLException {
        List<Version> rows = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("""
                        SELECT version_no, created_by, change_comment FROM test_case_version
                         WHERE case_id = CAST(? AS uuid) ORDER BY version_no ASC
                        """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String createdBy = resultSet.getString(2);
                    rows.add(new Version(
                            resultSet.getInt(1),
                            createdBy == null ? null : UUID.fromString(createdBy),
                            resultSet.getString(3)));
                }
            }
        }
        return rows;
    }

    private String snapshotOf(UUID companyId, UUID caseId) throws SQLException {
        return single(companyId, """
                SELECT snapshot::text FROM test_case_version
                 WHERE case_id = CAST(? AS uuid) ORDER BY version_no DESC LIMIT 1
                """, caseId);
    }

    private String titleOf(UUID companyId, UUID caseId) throws SQLException {
        return single(companyId, "SELECT title FROM test_case WHERE id = CAST(? AS uuid)", caseId);
    }

    private String gherkinSourceOf(UUID companyId, UUID caseId) throws SQLException {
        return single(companyId,
                "SELECT gherkin_source FROM test_case WHERE id = CAST(? AS uuid)", caseId);
    }

    private int currentVersionNoOf(UUID companyId, UUID caseId) throws SQLException {
        return Integer.parseInt(single(companyId,
                "SELECT current_version_no::text FROM test_case WHERE id = CAST(? AS uuid)",
                caseId));
    }

    private String single(UUID companyId, String sql, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, caseId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    // =====================================================================
    // helpers — fixture
    // =====================================================================

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now,
            boolean accepted) {

        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            if (accepted) {
                membership.accept(now);
            }
            memberships.save(membership);
        }
    }

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {

        UUID id = UUID.nameUUIDFromBytes(
                ("migrate-gherkin-source:" + marker).getBytes(StandardCharsets.UTF_8));
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * The cases, rebuilt from scratch on every test.
     *
     * <p><strong>Deleted and re-inserted rather than {@code ON CONFLICT DO NOTHING}</strong>, which
     * is the opposite of what most fixtures in this suite do — and it is forced by the subject: the
     * point of this command is that it CHANGES these rows, so a test that ran before this one has
     * already migrated them and every later test would assert against the previous test's output.
     * That is §19's fixture-collision defect arriving through time rather than through an id, and
     * {@code CaseHealthRecomputeTest}'s {@code person} helper records the same shape.
     *
     * <p>Written through the ordinary app role, not the superuser: it is the same policed path
     * production writes through, so a fixture that could not be written under RLS is one that lies
     * about what the command will see.
     */
    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            reset(connection, TEMPLATE, WITH_BACKGROUND, OUTLINE, UNPARSABLE, ALREADY, STRUCTURED,
                    UNSEEN);
            suite(connection, ACME, LEDGER_SUITE, ACME_LEDGER, "MGSL-S1", 1, "Refunds");
            suite(connection, ACME, MOBILE_SUITE, ACME_MOBILE, "MGSM-S1", 2, "Payments");

            testCase(connection, ACME, TEMPLATE, ACME_LEDGER, LEDGER_SUITE, "MGSL-1", 1,
                    "A refund lands on the ledger", "GHERKIN", TEMPLATE_BLOB);
            testCase(connection, ACME, WITH_BACKGROUND, ACME_LEDGER, LEDGER_SUITE, "MGSL-2", 2,
                    // The title and the scenario name deliberately DIFFER on this one case. The
                    // blob's Scenario: name is ignored (data-model §4: the case's title is
                    // authored, the blob's is a copy of it in every template-seeded case), and a
                    // fixture where the two agree cannot tell "ignored" from "written back".
                    "Refunding the same payment twice", "GHERKIN", BACKGROUND_BLOB);
            testCase(connection, ACME, OUTLINE, ACME_LEDGER, LEDGER_SUITE, "MGSL-3", 3,
                    "Refunds of several sizes", "GHERKIN", OUTLINE_BLOB);
            testCase(connection, ACME, UNPARSABLE, ACME_LEDGER, LEDGER_SUITE, "MGSL-4", 4,
                    "Something somebody pasted", "GHERKIN", UNPARSABLE_BLOB);

            // A blob AND a row: what every case looks like after a first run, and the arm that
            // makes a replay a no-op rather than a second version on every case.
            testCase(connection, ACME, ALREADY, ACME_LEDGER, LEDGER_SUITE, "MGSL-5", 5,
                    "Already migrated", "GHERKIN", TEMPLATE_BLOB);
            step(connection, ACME, 1, ALREADY, "GIVEN", "a step somebody already wrote");

            testCase(connection, ACME, STRUCTURED, ACME_LEDGER, LEDGER_SUITE, "MGSL-6", 6,
                    "Numbered steps", "STRUCTURED", null);

            testCase(connection, ACME, UNSEEN, ACME_MOBILE, MOBILE_SUITE, "MGSM-1", 7,
                    "In a project the actor holds no grant on", "GHERKIN", TEMPLATE_BLOB);
        }
        try (Connection connection = appConnection(UMBRA)) {
            reset(connection, FOREIGN);
            suite(connection, UMBRA, UMBRA_SUITE, UMBRA_PROJECT, "MGSU-S1", 1, "Umbra");
            testCase(connection, UMBRA, FOREIGN, UMBRA_PROJECT, UMBRA_SUITE, "MGSU-1", 1,
                    "Another client's case", "GHERKIN", TEMPLATE_BLOB);
        }
    }

    /** Versions, then steps, then the case — the foreign keys' order. */
    private void reset(Connection connection, UUID... caseIds) throws SQLException {
        for (String table : List.of("test_case_version", "test_step")) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE case_id = CAST(? AS uuid)")) {
                for (UUID caseId : caseIds) {
                    statement.setString(1, caseId.toString());
                    statement.executeUpdate();
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "DELETE FROM test_case WHERE id = CAST(? AS uuid)")) {
            for (UUID caseId : caseIds) {
                statement.setString(1, caseId.toString());
                statement.executeUpdate();
            }
        }
    }

    private void suite(Connection connection, UUID companyId, UUID id, UUID projectId, String key,
            long keySeq, String name) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void testCase(Connection connection, UUID companyId, UUID id, UUID projectId,
            UUID suiteId, String key, long keySeq, String title, String stepFormat,
            String gherkinSource) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     gherkin_source, priority, lifecycle_status, automation_status,
                     current_version_no, lock_version, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, ?, ?, 'MEDIUM', 'DRAFT', 'MANUAL', 1, 0,
                        CAST('2026-01-01T00:00:00Z' AS timestamptz))
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, stepFormat);
            statement.setString(9, gherkinSource);
            statement.executeUpdate();
        }
    }

    private void step(Connection connection, UUID companyId, int marker, UUID caseId,
            String keyword, String action) throws SQLException {

        UUID id = UUID.fromString(String.format("2b500000-0000-4000-8000-%012d", marker));
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step (id, company_id, case_id, position, keyword, action)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 0, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, keyword);
            statement.setString(5, action);
            statement.executeUpdate();
        }
    }
}
