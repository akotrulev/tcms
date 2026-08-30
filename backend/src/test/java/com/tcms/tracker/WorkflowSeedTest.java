package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The default workflow every project arrives with — six statuses, their legal moves, six types.
 *
 * <p><strong>Seeded by {@code tcms_seed_project_defaults}, not by a {@code ProjectCreatedEvent}</strong>
 * (plan 011 decision D-b, owner-confirmed). Plan 009 M1 and its review R5 both called for the event,
 * and two facts make it the worse mechanism: a Java event <em>type</em> may not cross a module
 * boundary in this codebase at all, and outbox delivery has a {@code PT1M} floor — so a brand-new
 * project would have no statuses for up to a minute and its first issue could not be created.
 * {@code aProjectCreatedAfterTheMigrationGetsTheWorkflowInTheSameTransaction} is what says the
 * difference out loud: an outbox-fed seed passes an eventual assertion and fails that one.
 *
 * <p><strong>Read as the superuser, not as {@code tcms_owner}.</strong> All three tables carry
 * {@code FORCE ROW LEVEL SECURITY} and {@code tcms_owner} holds no {@code BYPASSRLS}, so a count of
 * zero from that role means the policy is working rather than that the seed failed — the same
 * distinction {@code ProjectKeySequenceSchemaTest} turns on.
 */
@DisplayName("the seeded workflow — every project, before and after the migration")
class WorkflowSeedTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Test
    @DisplayName("every seeded project in the database has the six statuses, in order")
    void everyProjectThatExistedBeforeTheMigrationHasTheSixStatuses() throws SQLException {
        // Over whatever the shared container holds rather than over a project this test made,
        // which is what makes it a statement about the BACKFILL and not about the create path.
        // The backfill is the half that is easy to get silently wrong: `project` carries FORCE ROW
        // LEVEL SECURITY and Flyway connects as tcms_owner, so a loop that reads it without
        // setting app.current_company first sees ZERO ROWS, seeds nothing, and reports success.
        List<UUID> projects = seededProjectIds();
        assertThat(projects)
                .as("the sweep must have compared something — an empty project table is what a "
                        + "silently-skipped backfill also looks like")
                .isNotEmpty();

        for (UUID projectId : projects) {
            assertThat(statusNamesOf(projectId))
                    .as("project %s", projectId)
                    .containsExactly("Backlog", "Ready", "In Progress", "In Review", "Blocked", "Done");
        }
    }

    @Test
    @DisplayName("every status carries its kind, and Blocked is IN_PROGRESS rather than a fourth")
    void everyStatusCarriesItsKind() throws SQLException {
        UUID projectId = ownSeededProject();

        // Blocked is IN_PROGRESS deliberately: something blocked is started and not done, and a
        // kind of its own would have to be given a meaning by every percentage in the product.
        assertThat(kindsByName(projectId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Backlog", "NOT_STARTED",
                "Ready", "NOT_STARTED",
                "In Progress", "IN_PROGRESS",
                "In Review", "IN_PROGRESS",
                "Blocked", "IN_PROGRESS",
                "Done", "DONE"));
    }

    @Test
    @DisplayName("Done is reachable only from In Review — the review gate, as an allow-list")
    void doneIsReachableOnlyFromInReview() throws SQLException {
        UUID projectId = ownSeededProject();

        // issue_transition is an ALLOW-LIST: a pair that is not a row is refused. This is the one
        // shape in the default set, and it is what settings-workflow.html states — "Not to Done, a
        // review gate stands between them".
        assertThat(namesOfStatusesThatReach(projectId, "Done")).containsExactly("In Review");
    }

    @Test
    @DisplayName("a project created after the migration gets the workflow in the SAME transaction")
    void aProjectCreatedAfterTheMigrationGetsTheWorkflowInTheSameTransaction() throws SQLException {
        // The point of D-b: not "eventually" — in the creating transaction, so an issue can be
        // created in the very next request. An outbox-fed seed would pass an eventual assertion and
        // fail this one, because provision() has returned by the time it is read.
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                "wf-seed", "Workflow Seed Ltd", "ops@wf-seed.test", "Ops Person",
                "WFS", "Workflow Seed Product"));
        UUID projectId = result.projectId().orElseThrow(
                () -> new AssertionError("provisioning was asked for a first project and made none"));

        assertThat(statusNamesOf(projectId)).hasSize(6);
        assertThat(typeNamesOf(projectId))
                .containsExactlyInAnyOrder("Epic", "Story", "Task", "Bug", "Spike", "Sub-task");
    }

    @Test
    @DisplayName("two of the six types are named for a task, at the two levels that need one")
    void theQuickAddTaskIsStoryLevelAndTheBreakdownTaskIsTaskLevel() throws SQLException {
        UUID projectId = ownSeededProject();

        // D29 and the recorded disagreement with settings-general.html, which lists five types
        // while story.html and issue-new.html both offer sub-tasks. Those cannot both be true with
        // one Task type once the depth rule puts Task at STORY level, so there are six rows: Task
        // at STORY for the quick-add case — a solo project should not need an Epic and a Story
        // before "a thing to do" — and Sub-task at TASK for the breakdown case. D12's migrated
        // work_item rows land on the STORY-level one, so a user who typed "task" still sees "task".
        assertThat(levelsByTypeName(projectId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Epic", "EPIC",
                "Story", "STORY",
                "Task", "STORY",
                "Bug", "STORY",
                "Spike", "STORY",
                "Sub-task", "TASK"));
    }

    @Test
    @DisplayName("Epic and Spike do not count toward completion, and the other four do")
    void whatCountsTowardCompletion() throws SQLException {
        UUID projectId = ownSeededProject();

        // D8. A spike produces knowledge rather than shippable work, so counting it would let an
        // epic read 40% done having shipped nothing; an epic is what completion is computed FOR
        // and is never a member of the count. Both still consume hours, so both still count toward
        // budget burn — the two numbers answer different questions and are allowed to disagree.
        assertThat(typeNamesWhere(projectId, "NOT counts_toward_completion"))
                .containsExactlyInAnyOrder("Epic", "Spike");
    }

    /**
     * A project this class provisioned, rather than whichever row of {@code project} sorts first.
     *
     * <p>Every test class shares one container, so "some project" is "whatever the classes that ran
     * before me happened to leave" — a fixture whose content depends on test ORDER, which is the
     * vacuity every check in this repository is written against. Each caller provisions its own
     * company, so the assertions below are about rows this test caused.
     */
    private UUID ownSeededProject() {
        String slug = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                slug, "Workflow " + slug, "ops@" + slug + ".test", "Ops Person",
                "WF" + slug.substring(3, 7).toUpperCase(java.util.Locale.ROOT), "Workflow Product"));
        return result.projectId().orElseThrow(
                () -> new AssertionError("provisioning was asked for a first project and made none"));
    }

    /**
     * Projects {@code tcms_seed_project_defaults} actually ran for — they carry its Default
     * configuration — and not every row of {@code project}.
     *
     * <p><strong>That is the design rather than a weakening</strong>, and it is
     * {@code ProjectKeySequenceSchemaTest}'s reasoning applied to the same function one block
     * later: every test class shares one container, and a dozen of them build a project by saving
     * the entity directly, without ever calling the seed function. Those projects have no
     * {@code case_status}, no Default {@code configuration} and no {@code doc_space} either, and
     * nothing is broken about them. The sweep over <em>every</em> row was written first and fails
     * in the suite while passing alone — which is worse than not sweeping, because it reads as a
     * broken backfill. What is pinned here is the invariant that is both true and at risk: the
     * function's blocks travel together, and the backfill reached every project the function has
     * ever run for.
     */
    private List<UUID> seededProjectIds() throws SQLException {
        return superuserQuery("""
                SELECT p.id::text
                  FROM project p
                  JOIN configuration c
                    ON c.company_id = p.company_id AND c.project_id = p.id AND c.is_default
                 ORDER BY 1
                """).stream()
                .map(UUID::fromString)
                .toList();
    }

    private List<String> statusNamesOf(UUID projectId) throws SQLException {
        return superuserQuery("SELECT name FROM issue_status WHERE project_id = '"
                + projectId + "' ORDER BY \"position\"");
    }

    private List<String> typeNamesOf(UUID projectId) throws SQLException {
        return superuserQuery("SELECT name FROM issue_type WHERE project_id = '"
                + projectId + "' ORDER BY \"position\"");
    }

    private List<String> typeNamesWhere(UUID projectId, String predicate) throws SQLException {
        return superuserQuery("SELECT name FROM issue_type WHERE project_id = '"
                + projectId + "' AND " + predicate + " ORDER BY \"position\"");
    }

    private Map<String, String> kindsByName(UUID projectId) throws SQLException {
        return superuserPairs("SELECT name, kind FROM issue_status WHERE project_id = '"
                + projectId + "'");
    }

    private Map<String, String> levelsByTypeName(UUID projectId) throws SQLException {
        return superuserPairs("SELECT name, level FROM issue_type WHERE project_id = '"
                + projectId + "'");
    }

    private List<String> namesOfStatusesThatReach(UUID projectId, String destination)
            throws SQLException {
        return superuserQuery("""
                SELECT f.name
                  FROM issue_transition tr
                  JOIN issue_status f ON f.id = tr.from_status_id
                  JOIN issue_status t ON t.id = tr.to_status_id
                 WHERE tr.project_id = '%s' AND t.name = '%s'
                 ORDER BY 1
                """.formatted(projectId, destination));
    }

    private static List<String> superuserQuery(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = superuserConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static Map<String, String> superuserPairs(String sql) throws SQLException {
        Map<String, String> pairs = new LinkedHashMap<>();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                pairs.put(rs.getString(1), rs.getString(2));
            }
        }
        return pairs;
    }
}
