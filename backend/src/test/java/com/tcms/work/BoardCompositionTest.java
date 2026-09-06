package com.tcms.work;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /board} composing issues with the four derived generators — D11's whole claim, and the
 * acceptance criterion 009 closes M1 on.
 *
 * <h2>The failures this class exists to catch, and why none of them throws</h2>
 *
 * <ol>
 *   <li><strong>An issue card that is marked derived, or a derived card that is not.</strong>
 *       {@code derived} is what the SPA branches on to decide what a card may do, so getting it
 *       backwards renders a projection with a row's affordances — the divergence the whole module is
 *       built to prevent, arriving through the one field that says which kind of card this is.</li>
 *   <li><strong>A done issue that stays on the board.</strong> §6l's "card leaves" rule, expressed
 *       against {@code issue_status.kind} rather than a name, because a project may rename its
 *       columns. Without it a board becomes an append-only list of everything anybody was ever
 *       assigned, which looks like data and grows for ever.</li>
 *   <li><strong>Allowed moves taken from the column set rather than from the workflow.</strong>
 *       {@code work_item} let a card go anywhere and called that the model. A board that still
 *       offers every status would let somebody drop a card where {@code POST
 *       /issues/{key}/transitions} then answers 409 — the refusal a client cannot anticipate.</li>
 *   <li><strong>Somebody else's private note on your copy of a card.</strong> Story B5's second
 *       criterion, and the failure is silent in the worst direction: two people reading one card get
 *       the same value, and the one whose note it is never knows it was published.</li>
 *   <li><strong>A personal board that is one project's.</strong> Two projects here, so an aggregate
 *       that quietly still reads a single one fails rather than passing on a fixture that cannot
 *       tell the difference.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Board composition — issues beside reviews and executions, and only one is a row")
class BoardCompositionTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("4a000000-0000-4000-8000-000000000001");
    private static final UUID STOREFRONT = UUID.fromString("4a001000-0000-4000-8000-000000000001");
    private static final UUID PORTAL = UUID.fromString("4a001000-0000-4000-8000-000000000002");

    private static final UUID DANA = UUID.fromString("4a002000-0000-4000-8000-000000000001");
    private static final UUID MIRA = UUID.fromString("4a002000-0000-4000-8000-000000000002");

    private static final UUID SUITE = UUID.fromString("4a003000-0000-4000-8000-000000000001");
    private static final UUID CASE_TO_RUN = UUID.fromString("4a003000-0000-4000-8000-000000000002");
    private static final UUID CASE_TO_REVIEW =
            UUID.fromString("4a003000-0000-4000-8000-000000000003");
    private static final UUID LIVE_RUN = UUID.fromString("4a004000-0000-4000-8000-000000000001");
    private static final UUID REVIEW = UUID.fromString("4a005000-0000-4000-8000-000000000001");

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

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
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "board-composition", "Acme", now));
            }
            activeUser(DANA, "dana@board-composition.test", "Dana Diaz", now);
            activeUser(MIRA, "mira@board-composition.test", "Mira Okafor", now);
        });

        try (var ignored = CompanyContext.open(ACME)) {
            tx.executeWithoutResult(status -> {
                seedProject(STOREFRONT, "ACME", "Acme Storefront", now);
                seedProject(PORTAL, "BERG", "Bergstrom Portal", now);
                member(DANA, "Dana Diaz", now);
                member(MIRA, "Mira Okafor", now);
                grant("dana-store", Role.TEST_LEAD_ID, DANA, STOREFRONT, now);
                grant("dana-portal", Role.TEST_LEAD_ID, DANA, PORTAL, now);
                grant("mira-store", Role.TEST_LEAD_ID, MIRA, STOREFRONT, now);
            });
        }

        try (Connection acme = appConnection(ACME)) {
            clear(acme);
            seedCompanyDefaults(acme);
            seedProjectDefaults(acme, STOREFRONT);
            seedProjectDefaults(acme, PORTAL);

            suite(acme, SUITE, STOREFRONT, "TS-1", "Root");
            testCase(acme, CASE_TO_RUN, STOREFRONT, SUITE, "TC-1", 1, DANA);
            testCase(acme, CASE_TO_REVIEW, STOREFRONT, SUITE, "TC-2", 2, DANA);
            run(acme, LIVE_RUN, STOREFRONT, "TR-1", 1);
            execution(acme, STOREFRONT, LIVE_RUN, CASE_TO_RUN, DANA);
            review(acme, REVIEW, STOREFRONT, CASE_TO_REVIEW);
            askUser(acme, REVIEW, DANA);
        }
    }

    @Test
    void myWorkMixesIssuesWithReviewsAndExecutions() throws Exception {
        // D11's whole claim, and the acceptance criterion 009 closes M1 on. Two projects, so the
        // aggregate the personal board exists for is exercised.
        givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");
        givenIssueAssignedTo(DANA, PORTAL, "Statement download times out");

        JsonNode cards = myWork(DANA);

        assertThat(kindsOf(cards)).contains("ISSUE", "EXECUTION", "REVIEW");
        assertThat(projectKeysOfKind(cards, "ISSUE")).containsExactlyInAnyOrder("ACME", "BERG");
    }

    @Test
    void anIssueCardIsTheOnlyOneThatIsARow() throws Exception {
        givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");

        JsonNode cards = myWork(DANA);

        for (JsonNode card : cards) {
            assertThat(card.get("derived").asBoolean())
                    .as("%s is derived exactly when it is not an ISSUE", card.get("kind").asText())
                    .isEqualTo(!"ISSUE".equals(card.get("kind").asText()));
        }
        assertThat(kindsOf(cards)).contains("ISSUE");
    }

    @Test
    void aDoneIssueLeavesTheBoard() throws Exception {
        String key = givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");

        walkToDone(key);

        assertThat(idsOf(myWork(DANA))).doesNotContain(key);
    }

    @Test
    void anIssueCardsAllowedTransitionsComeFromTheProjectWorkflow() throws Exception {
        // The behaviour change from work_item, where every column was legal. ~~The seeded workflow
        // permits every pair except INTO Done.~~ → V13 (021 §2.3): it is a LINE, so from In review
        // exactly three moves exist — the next step, the return path, and Blocked. The assertion is
        // written against what tcms_seed_project_defaults actually inserts rather than against a
        // mockup's right-hand panel, which illustrates the moves from In progress.
        String key = givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");
        walkTo(key, "In review");

        assertThat(transitionNamesOn(cardFor(myWork(DANA), key)))
                .containsExactlyInAnyOrder("Ready for test", "Rework", "Blocked");
    }

    @Test
    void anEpicOffersNoMovesOnTheBoardEither() throws Exception {
        // D31 from the board's side. The empty list, IssueView's empty list and TransitionService's
        // 409 are one decision expressed three times, so a board that offered the workflow here
        // would render buttons the endpoint then refuses.
        String key = givenIssue(DANA, STOREFRONT, "Epic", "Checkout hardening", DANA);

        assertThat(cardFor(myWork(DANA), key).get("allowedTransitions")).isEmpty();
    }

    @Test
    void myNoteOnACardIsNotOnSomebodyElsesCopyOfIt() throws Exception {
        String key = givenIssueAssignedTo(MIRA, STOREFRONT, "Expired cards decline without a message");
        UUID id = issueId(key);

        putAnnotation(DANA, id, "ask Mira which postcode set is authoritative");

        assertThat(cardFor(board(DANA, STOREFRONT), key).get("annotation").isNull()).isFalse();
        assertThat(cardFor(board(MIRA, STOREFRONT), key).path("annotation").isNull()).isTrue();
    }

    @Test
    void aNoteWrittenAgainstTheIssueIdIsWhatTheBoardRenders() throws Exception {
        // The migration carried work_item.id forward and relabelled derived_item_annotation's
        // source_type from 'TASK' to 'ISSUE' for exactly this, so a note written before the upgrade
        // is a row keyed (ISSUE, that id) afterwards — which is the shape this writes directly.
        //
        // Asserted from the BOARD rather than from the table, because the table being right and the
        // board not reading it is a real and separate failure. The migration's own half is
        // WorkItemMigrationTest's, on its own container at V3: work_item is dropped by V4, so the
        // shared container this class runs on has no task left to migrate.
        String key = givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");
        annotationRow(DANA, "ISSUE", issueId(key), "carried across the upgrade");

        assertThat(cardFor(board(DANA, STOREFRONT), key).get("annotation").get("note").asText())
                .isEqualTo("carried across the upgrade");
    }

    @Test
    void anAnnotationIsMergedRatherThanReplaced() throws Exception {
        // A PUT with PATCH semantics, which the contract states and which matters here: the flag and
        // the note are edited by different affordances on the card, so a flag edit that emptied the
        // note would delete something a person typed.
        String key = givenIssueAssignedTo(DANA, STOREFRONT, "Expired cards decline without a message");
        UUID id = issueId(key);

        putAnnotation(DANA, id, "ask Mira which postcode set is authoritative");
        putRaw(DANA, id, "{\"flagged\":true}");

        JsonNode annotation = cardFor(board(DANA, STOREFRONT), key).get("annotation");
        assertThat(annotation.get("flagged").asBoolean()).isTrue();
        assertThat(annotation.get("note").asText())
                .isEqualTo("ask Mira which postcode set is authoritative");
    }

    // =====================================================================
    // Acts
    // =====================================================================

    private String givenIssueAssignedTo(UUID assignee, UUID projectId, String title)
            throws Exception {

        return givenIssue(DANA, projectId, "Task", title, assignee);
    }

    private String givenIssue(UUID author, UUID projectId, String typeName, String title,
            UUID assignee) throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId.toString());
        payload.put("typeId", typeNamed(projectId, typeName).toString());
        payload.put("title", title);
        payload.put("assigneeId", assignee == null ? null : assignee.toString());

        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(created.getResponse().getContentAsString()).get("key").asText();
    }

    /** The whole line, New to Done — nine statuses, eight moves (021 §2.3). */
    private void walkToDone(String key) throws Exception {
        walkTo(key, "Done");
    }

    /**
     * Walks the line from {@code New} up to and including {@code statusName}.
     *
     * <p>The seeded workflow is a LINE now, so a fixture cannot jump: every step between where the
     * issue is and where the test needs it has to be a legal move.
     */
    private void walkTo(String key, String statusName) throws Exception {
        for (String step : List.of("Refining", "Ready to develop", "In progress", "In review",
                "Ready for test", "In test", "Ready to deploy", "Done")) {
            moveTo(key, step);
            if (step.equals(statusName)) {
                return;
            }
        }
        throw new AssertionError(statusName + " is not on the seeded line");
    }

    private void moveTo(String key, String statusName) throws Exception {
        MvcResult moved = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/issues/" + key + "/transitions")
                        .header("Authorization", "Bearer " + tokenFor(DANA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "statusId", statusNamed(STOREFRONT, statusName).toString()))))
                .andReturn();
        assertThat(moved.getResponse().getStatus())
                .as("move %s to %s: %s", key, statusName, moved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void putAnnotation(UUID userId, UUID issueId, String note) throws Exception {
        putRaw(userId, issueId, json.writeValueAsString(Map.of("note", note)));
    }

    private void putRaw(UUID userId, UUID issueId, String body) throws Exception {
        MvcResult saved = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/board/annotations/ISSUE/" + issueId)
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(saved.getResponse().getStatus())
                .as(saved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    // =====================================================================
    // Reads
    // =====================================================================

    private JsonNode myWork(UUID userId) throws Exception {
        return read(userId, "/api/v1/board?mine=true");
    }

    private JsonNode board(UUID userId, UUID projectId) throws Exception {
        return read(userId, "/api/v1/board?projectId=" + projectId);
    }

    private JsonNode read(UUID userId, String path) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID typeNamed(UUID projectId, String name) throws Exception {
        for (JsonNode type : workflow(projectId).get("types")) {
            if (type.get("name").asText().equals(name)) {
                return UUID.fromString(type.get("id").asText());
            }
        }
        throw new AssertionError("the seeded workflow has no type called " + name);
    }

    private UUID statusNamed(UUID projectId, String name) throws Exception {
        for (JsonNode status : workflow(projectId).get("statuses")) {
            if (status.get("name").asText().equals(name)) {
                return UUID.fromString(status.get("id").asText());
            }
        }
        throw new AssertionError("the seeded workflow has no status called " + name);
    }

    private JsonNode workflow(UUID projectId) throws Exception {
        return read(DANA, "/api/v1/projects/" + projectId + "/workflow");
    }

    private static JsonNode cardFor(JsonNode board, String key) {
        for (JsonNode card : board) {
            if (key.equals(card.get("id").asText())) {
                return card;
            }
        }
        throw new AssertionError("no card for " + key + " on the board");
    }

    private static List<String> kindsOf(JsonNode board) {
        List<String> kinds = new ArrayList<>();
        board.forEach(card -> kinds.add(card.get("kind").asText()));
        return kinds;
    }

    private static List<String> idsOf(JsonNode board) {
        List<String> ids = new ArrayList<>();
        board.forEach(card -> ids.add(card.get("id").asText()));
        return ids;
    }

    private static List<String> projectKeysOfKind(JsonNode board, String kind) {
        List<String> keys = new ArrayList<>();
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())) {
                keys.add(card.get("projectKey").asText());
            }
        }
        return keys;
    }

    private static List<String> transitionNamesOn(JsonNode card) {
        List<String> names = new ArrayList<>();
        card.get("allowedTransitions").forEach(status -> names.add(status.get("name").asText()));
        return names;
    }

    private String tokenFor(UUID userId) {
        return tx.execute(status -> tokens.issue(userId, ACME, 1, false).token());
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void member(UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(ACME, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(ACME, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void seedProject(UUID id, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, ACME, key, name, null, now));
        }
    }

    private void grant(String marker, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("board-composition:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnProject(id, ACME, roleId, userId, projectId, null, now));
        }
    }

    /**
     * Empties everything a test writes, in FK order.
     *
     * <p>Per method rather than per class: every test here creates issues, and a suite that let them
     * accumulate would make one method's assertion depend on which methods ran before it. Issues
     * leave in three passes because {@code issue.parent_id} is {@code ON DELETE RESTRICT} and the
     * constraint fires per row.
     */
    private void clear(Connection connection) throws SQLException {
        for (String table : List.of("derived_item_annotation", "issue_doc_link", "issue_watcher",
                "issue_link", "issue_comment", "issue_fact", "review_assignment",
                "review_participation", "review_comment", "review", "step_result",
                "test_execution", "test_run", "test_case_version", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM issue i
                     WHERE NOT EXISTS (SELECT 1 FROM issue c WHERE c.parent_id = i.id)
                    """)) {
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement("UPDATE project_key_sequence SET next_value = 1")) {
            statement.executeUpdate();
        }
    }

    private void seedCompanyDefaults(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setObject(1, ACME);
            statement.execute();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, ACME);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId, String key, String name)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 1, ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, projectId);
            statement.setString(4, key);
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long keySeq, UUID ownerId) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, owner_id, created_at,
                     updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', CAST(? AS uuid), now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, projectId);
            statement.setObject(4, suiteId);
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setObject(8, ownerId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, id);
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID projectId, String key, long keySeq)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 'IN_PROGRESS',
                        'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, projectId);
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, "Run " + key);
            statement.setObject(7, projectId);
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID projectId, UUID runId, UUID caseId,
            UUID assigneeId) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     assignee_id, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        NULL, CAST(? AS uuid), now(), now())
                """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, projectId);
            statement.setObject(3, runId);
            statement.setObject(4, caseId);
            statement.setObject(5, caseId);
            statement.setObject(6, assigneeId);
            statement.executeUpdate();
        }
    }

    private void review(Connection connection, UUID id, UUID projectId, UUID subjectId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review
                    (id, company_id, project_id, subject_type, subject_id, subject_version_no,
                     state, created_by, created_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'CASE',
                        CAST(? AS uuid), 1, 'IN_REVIEW', CAST(? AS uuid), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, projectId);
            statement.setObject(4, subjectId);
            statement.setObject(5, MIRA);
            statement.executeUpdate();
        }
    }

    private void askUser(Connection connection, UUID reviewId, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review_assignment
                    (id, company_id, review_id, assignee_user_id, assigned_by)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid))
                ON CONFLICT DO NOTHING
                """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, reviewId);
            statement.setObject(3, userId);
            statement.setObject(4, MIRA);
            statement.executeUpdate();
        }
    }

    private void annotationRow(UUID userId, String sourceType, UUID sourceId, String note)
            throws SQLException {

        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO derived_item_annotation
                            (company_id, user_id, source_type, source_id, note, flagged)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), ?, false)
                        """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, userId);
            statement.setString(3, sourceType);
            statement.setObject(4, sourceId);
            statement.setString(5, note);
            statement.executeUpdate();
        }
    }

    private UUID issueId(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM issue WHERE issue_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("issue %s exists", key).isTrue();
                return (UUID) rows.getObject(1);
            }
        }
    }
}
