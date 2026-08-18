package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.TokenContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail's first reader — <strong>the nullable columns, and the refusal that is not an
 * empty page.</strong>
 *
 * <p>Every act in this product has written an {@code audit_event} row since M0 and nothing has ever
 * read one, so the risk here is not that the query is wrong: it is that the <em>shape</em> of the
 * data is not what a reader assumes. Three columns are nullable and each of them is nullable for a
 * different reason — no project, no person, no token — so most of this class is one arm per
 * absence.
 *
 * <h2>Two arms exist because this slice's plan was wrong, and they say so</h2>
 *
 * <p>The plan asserted that both actor columns are never set together and that offboarding nulls
 * the actor's name. Both are false against the code, in the direction that loses information:
 * {@link AuditEvent#record} fills the token <em>beside</em> the person, and {@link
 * AdminDirectoryService#peopleById} does not filter memberships on status, so a departed
 * colleague's name still resolves. {@code aTokenDrivenActNamesBothThePersonAndTheCredential} and
 * {@code anOffboardedActorKeepsTheirName} pin the real behaviour, which is also the behaviour an
 * audit trail wants.
 *
 * <h2>Rows are written through the product's own writer, never by INSERT</h2>
 *
 * <p>{@link AuditEvent#record} reads {@link CompanyContext} and {@link TokenContext} itself, and a
 * fixture that inserted rows directly would prove the query works over data the product does not
 * actually produce — which is the whole reason the token column spent thirty-two sections looking
 * read when only a test SELECT touched it.
 *
 * <p>Ids use the {@code 0a…} prefix, which no other class in the suite uses — checked against every
 * {@code UUID.fromString} literal under {@code src/test/java} rather than assumed, because handoff
 * §19's fixture collision has happened five times and two lanes taking the same prefix merge clean.
 */
@AutoConfigureMockMvc
@DisplayName("The audit log reader — who did what, including when they have left")
class AuditLogReadTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("0a000000-0000-4000-8000-00000000000a");
    /** A second company, so "this company's trail" can be shown to be exactly one company's. */
    private static final UUID RIVAL = UUID.fromString("0a000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("0a001000-0000-4000-8000-000000000001");
    /** ACME's other project. LEAD holds nothing here, so it drives the 404 arm. */
    private static final UUID BOREAL = UUID.fromString("0a001000-0000-4000-8000-000000000002");
    private static final UUID RIVAL_PROJECT = UUID.fromString("0a001000-0000-4000-8000-000000000003");

    /** Company Admin: holds {@code audit:view} and reaches {@code project:audit} everywhere. */
    private static final UUID ADMIN = UUID.fromString("0a003000-0000-4000-8000-000000000001");
    /** Test Lead on ATLAS: holds {@code project:audit} there and {@code audit:view} nowhere. */
    private static final UUID LEAD = UUID.fromString("0a003000-0000-4000-8000-000000000002");
    /** Test Engineer on ATLAS: can open the project and holds no {@code project:audit}. */
    private static final UUID ENGINEER = UUID.fromString("0a003000-0000-4000-8000-000000000003");
    /** Offboarded after acting. Their rows must survive them, name included. */
    private static final UUID DEPARTED = UUID.fromString("0a003000-0000-4000-8000-000000000004");
    /** RIVAL's admin, for the isolation arm. */
    private static final UUID OUTSIDER = UUID.fromString("0a003000-0000-4000-8000-000000000005");
    /**
     * A real account that is a member of NOTHING — the unresolvable actor.
     *
     * <p>It has to be a real {@code app_user} because {@code audit_event.actor_user_id} carries a
     * FOREIGN KEY to it, so an invented id cannot be recorded at all. That constraint is the point
     * of the arm: the account always exists, and it is the <em>membership</em> that may not —
     * different tables at different scopes, and only the membership carries the §6u display name.
     */
    private static final UUID UNAFFILIATED = UUID.fromString("0a003000-0000-4000-8000-000000000006");

    private static final UUID CI_TOKEN = UUID.fromString("0a006000-0000-4000-8000-000000000001");
    private static final String CI_TOKEN_NAME = "nightly-pipeline";

    private static final UUID GRANT_ADMIN = UUID.fromString("0a005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_LEAD = UUID.fromString("0a005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_ENGINEER = UUID.fromString("0a005000-0000-4000-8000-000000000003");
    private static final UUID GRANT_DEPARTED = UUID.fromString("0a005000-0000-4000-8000-000000000004");
    private static final UUID GRANT_OUTSIDER = UUID.fromString("0a005000-0000-4000-8000-000000000005");

    /** A recognisable string, so its ABSENCE from another company's body is a real assertion. */
    private static final String RIVAL_ACTION = "rival.secret.act";

    private static final Instant BASE = Instant.parse("2026-08-18T09:00:00Z");

    @Autowired
    private MockMvc mvc;

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
    private ApiTokenRepository apiTokens;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(status -> {
            provisionCompany(ACME, "acme-audit", "Acme");
            provisionCompany(RIVAL, "rival-audit", "Rival");

            activeUser(ADMIN, "admin@acme-audit.test");
            activeUser(LEAD, "lead@acme-audit.test");
            activeUser(ENGINEER, "engineer@acme-audit.test");
            activeUser(DEPARTED, "departed@acme-audit.test");
            activeUser(OUTSIDER, "outsider@rival-audit.test");
            // Deliberately never passed to member(): an account with no membership anywhere.
            activeUser(UNAFFILIATED, "unaffiliated@acme-audit.test");
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "ATL", "Atlas");
            seedProject(BOREAL, ACME, "BOR", "Boreal");

            member(ACME, ADMIN, "A. Admin");
            member(ACME, LEAD, "L. Lead");
            member(ACME, ENGINEER, "E. Engineer");
            member(ACME, DEPARTED, "D. Departed");

            grantOnCompany(GRANT_ADMIN, ACME, Role.COMPANY_ADMIN_ID, ADMIN);
            grantOnProject(GRANT_LEAD, ACME, Role.TEST_LEAD_ID, LEAD, ATLAS);
            grantOnProject(GRANT_ENGINEER, ACME, Role.TEST_ENGINEER_ID, ENGINEER, ATLAS);
            grantOnProject(GRANT_DEPARTED, ACME, Role.TEST_ENGINEER_ID, DEPARTED, ATLAS);
            grantCompanyReadBaseline(ACME);

            if (apiTokens.findScopedById(CI_TOKEN).isEmpty()) {
                apiTokens.save(ApiToken.issue(
                        CI_TOKEN, ACME, CI_TOKEN_NAME, "tcms_0a", LEAD,
                        Role.TEST_ENGINEER_ID, ATLAS, null, BASE));
            }
        }));

        inCompany(RIVAL, () -> tx.executeWithoutResult(status -> {
            seedProject(RIVAL_PROJECT, RIVAL, "RIV", "Rival Portal");
            member(RIVAL, OUTSIDER, "O. Outsider");
            grantOnCompany(GRANT_OUTSIDER, RIVAL, Role.COMPANY_ADMIN_ID, OUTSIDER);
            grantCompanyReadBaseline(RIVAL);
        }));

        clearTrail();
    }

    /**
     * Empties both companies' trails before each test.
     *
     * <p>Not tidiness. Every arm below asserts on a <em>count</em> or on an <em>order</em>, and
     * {@code audit_event} is append-only with no unique key a re-seed could collide on — so without
     * this the second test in the class reads the first one's rows and "three acts, newest first"
     * starts passing or failing on JUnit's method order. §19's defect, one table over.
     */
    private void clearTrail() {
        for (UUID companyId : List.of(ACME, RIVAL)) {
            inCompany(companyId, () -> tx.executeWithoutResult(status ->
                    auditEvents.deleteAllInBatch()));
        }
    }

    // =====================================================================
    // The project trail
    // =====================================================================

    @Nested
    @DisplayName("the project trail — this project's acts, and only this project's")
    class TheProjectTrail {

        @Test
        @DisplayName("returns this project's acts newest first")
        void aProjectLogReturnsThatProjectsActsNewestFirst() throws Exception {
            recordAct(ATLAS, LEAD, "case.created", BASE);
            recordAct(ATLAS, LEAD, "case.updated", BASE.plus(1, ChronoUnit.MINUTES));
            recordAct(ATLAS, LEAD, "case.approved", BASE.plus(2, ChronoUnit.MINUTES));
            recordAct(BOREAL, LEAD, "case.created", BASE.plus(3, ChronoUnit.MINUTES));

            JsonNode page = projectTrail(ADMIN, ATLAS);

            assertThat(page.get("total").asInt()).isEqualTo(3);
            assertThat(actionsOf(page))
                    .as("newest first, so the most recent act is the first row")
                    .containsExactly("case.approved", "case.updated", "case.created");
        }

        @Test
        @DisplayName("a company-scope act is absent — its project_id is NULL, not this project")
        void aCompanyScopeActIsAbsentFromEveryProjectLog() throws Exception {
            recordAct(ATLAS, ADMIN, "case.created", BASE);
            // A member invited: an act of the company, belonging to no project.
            recordAct(null, ADMIN, "member.invited", BASE.plus(1, ChronoUnit.MINUTES));

            JsonNode page = projectTrail(ADMIN, ATLAS);

            // The defect this catches is a filter written `projectId = :id OR projectId IS NULL`,
            // which folds every invitation and vocabulary change into every project's log.
            assertThat(actionsOf(page)).containsExactly("case.created");
            assertThat(page.get("total").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("a caller without project:audit is refused with 403, not an empty page")
        void aCallerWithoutProjectAuditIsRefused() throws Exception {
            recordAct(ATLAS, LEAD, "case.created", BASE);

            MvcResult result = getProjectTrail(ENGINEER, ATLAS);

            // ENGINEER can open ATLAS and holds no project:audit. An empty page would read as
            // "nothing happened here", which about an audit trail is a lie the reader has no way
            // to detect — and there are three acts.
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("case.created");
        }

        @Test
        @DisplayName("a project the caller cannot open is 404, not 403 — visibility answers first")
        void anUnreachableProjectIsNotDistinguishableFromAMissingOne() throws Exception {
            // LEAD holds Test Lead on ATLAS and nothing on BOREAL, so BOREAL is invisible to them.
            // 403 here would confirm the project exists, which §6t exists to prevent; the
            // permission question is only asked of somebody already looking at the project.
            assertThat(getProjectTrail(LEAD, BOREAL).getResponse().getStatus()).isEqualTo(404);
            assertThat(getProjectTrail(LEAD, ATLAS).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    // The actors — three nullable shapes, one arm each
    // =====================================================================

    @Nested
    @DisplayName("the actors — a person, a person and a credential, or neither")
    class TheActors {

        @Test
        @DisplayName("a token-driven act names BOTH the person and the credential")
        void aTokenDrivenActNamesBothThePersonAndTheCredential() throws Exception {
            asToken(CI_TOKEN, ACME, LEAD, ATLAS,
                    () -> recordAct(ATLAS, LEAD, "result.recorded", BASE));

            JsonNode row = rowsOf(projectTrail(ADMIN, ATLAS)).get(0);

            // The plan for this slice said "exactly one is set". It is not: the caller identity on
            // a token-authenticated request is the token's OWNER, so both columns are filled — the
            // person answerable for the credential, and the credential. A view that rendered one
            // and hid the other would say a human did what a pipeline did.
            assertThat(row.get("actorUserId").asText()).isEqualTo(LEAD.toString());
            assertThat(row.get("actorUserName").asText()).isEqualTo("L. Lead");
            assertThat(row.get("actorTokenId").asText()).isEqualTo(CI_TOKEN.toString());
            assertThat(row.get("actorTokenName").asText()).isEqualTo(CI_TOKEN_NAME);
        }

        @Test
        @DisplayName("an unattended job has no person, and the read does not throw")
        void anUnattendedJobHasNoPerson() throws Exception {
            recordAct(ATLAS, null, "run.reaped", BASE);

            JsonNode row = rowsOf(projectTrail(ADMIN, ATLAS)).get(0);

            // The only shape in which actorUserId is genuinely null. The name must be null too and
            // NOT the string "Unknown": a placeholder is this screen inventing a fact.
            assertThat(row.get("actorUserId").isNull()).isTrue();
            assertThat(row.get("actorUserName").isNull()).isTrue();
            assertThat(row.get("actorTokenId").isNull()).isTrue();
            assertThat(row.get("action").asText()).isEqualTo("run.reaped");
        }

        @Test
        @DisplayName("an offboarded actor keeps their name — the log survives them intact")
        void anOffboardedActorKeepsTheirName() throws Exception {
            recordAct(ATLAS, DEPARTED, "case.updated", BASE);
            offboard(ACME, DEPARTED);

            JsonNode row = rowsOf(projectTrail(ADMIN, ATLAS)).get(0);

            // The plan expected the name to go. It does not, and that is the better behaviour:
            // removal sets the membership to REMOVED rather than deleting the row, and
            // peopleById does not filter on status. "Who did this" must not become unanswerable
            // the day somebody leaves — which is the one question this table exists to answer.
            assertThat(row.get("actorUserId").asText()).isEqualTo(DEPARTED.toString());
            assertThat(row.get("actorUserName").asText()).isEqualTo("D. Departed");
        }

        @Test
        @DisplayName("an actor who is no longer a member at all resolves to a null name, not a lost row")
        void anUnresolvableActorLosesOnlyTheirName() throws Exception {
            // A real account with no membership in this company. It cannot be an invented id:
            // actor_user_id is a FOREIGN KEY to app_user, which is what makes the shape precise —
            // the ACCOUNT always exists and the MEMBERSHIP is what resolves the name, so this is
            // the row an operator-level removal leaves behind. The row is still served, which is
            // the half that matters: a client treating a null name as a missing row deletes
            // history from the screen.
            recordAct(ATLAS, UNAFFILIATED, "case.deleted", BASE);

            JsonNode row = rowsOf(projectTrail(ADMIN, ATLAS)).get(0);

            assertThat(row.get("actorUserId").asText()).isEqualTo(UNAFFILIATED.toString());
            assertThat(row.get("actorUserName").isNull()).isTrue();
            assertThat(row.get("action").asText()).isEqualTo("case.deleted");
        }
    }

    // =====================================================================
    // The company trail
    // =====================================================================

    @Nested
    @DisplayName("the company trail — every act in this company, and no other company's")
    class TheCompanyTrail {

        @Test
        @DisplayName("includes company-scope acts AND project-scoped ones — it is the superset")
        void theCompanyTrailIsASupersetOfEveryProjectTrail() throws Exception {
            recordAct(ATLAS, ADMIN, "case.created", BASE);
            recordAct(BOREAL, ADMIN, "suite.created", BASE.plus(1, ChronoUnit.MINUTES));
            recordAct(null, ADMIN, "member.invited", BASE.plus(2, ChronoUnit.MINUTES));

            JsonNode page = companyTrail(ADMIN);

            assertThat(actionsOf(page))
                    .containsExactly("member.invited", "suite.created", "case.created");
            // The company-scope row is the one a project trail structurally cannot serve, so its
            // presence here is what makes this endpoint worth having rather than a convenience.
            assertThat(rowsOf(page).get(0).get("projectId").isNull()).isTrue();
        }

        @Test
        @DisplayName("projectId is a filter and not a gate — an unknown id matches nothing, no 404")
        void theProjectFilterIsNotAnExistenceOracle() throws Exception {
            recordAct(ATLAS, ADMIN, "case.created", BASE);

            JsonNode mine = companyTrail(ADMIN, "?projectId=" + ATLAS);
            assertThat(actionsOf(mine)).containsExactly("case.created");

            // An id naming no project in this company. Refusing it would let the query string
            // report which project ids exist, which is the oracle §6t closes everywhere else.
            MvcResult unknown = getCompanyTrail(ADMIN, "?projectId=" + UUID.randomUUID());
            assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(unknown.getResponse().getContentAsString())
                    .get("total").asInt()).isZero();
        }

        @Test
        @DisplayName("a Test Lead holds project:audit and not audit:view — 403 on the company trail")
        void aCallerWithoutAuditViewIsRefused() throws Exception {
            recordAct(ATLAS, LEAD, "case.created", BASE);

            // The same person reads their project's trail and is refused the company's. Stated as
            // one test because the pair is the point: the two permissions are genuinely different
            // keys, and a gate that asked the wrong one would pass one half of this.
            assertThat(getProjectTrail(LEAD, ATLAS).getResponse().getStatus()).isEqualTo(200);
            assertThat(getCompanyTrail(LEAD, "").getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("another company's acts are absent — asserted on the raw body")
        void anotherCompanysActsAreAbsent() throws Exception {
            recordAct(ATLAS, ADMIN, "case.created", BASE);
            inCompany(RIVAL, () -> tx.executeWithoutResult(status -> auditEvents.save(
                    AuditEvent.record(RIVAL_PROJECT, OUTSIDER, RIVAL_ACTION, "TestCase",
                            List.of(UUID.randomUUID()), null, BASE))));

            String body = getCompanyTrail(ADMIN, "").getResponse().getContentAsString();

            // On the raw body rather than a parsed field, because a leaked row's action string is
            // the thing a client would render and no field-by-field assertion would look for a
            // row it did not expect.
            assertThat(body)
                    .doesNotContain(RIVAL_ACTION)
                    .doesNotContain(RIVAL_PROJECT.toString())
                    .doesNotContain(OUTSIDER.toString());
            assertThat(json.readTree(body).get("total").asInt()).isEqualTo(1);
        }
    }

    // =====================================================================
    // Paging
    // =====================================================================

    @Nested
    @DisplayName("paging — the secondary sort key, and what it is for")
    class Paging {

        @Test
        @DisplayName("two pages over rows sharing one timestamp neither repeat nor skip a row")
        void theSortIsStableWhenTimestampsCollide() throws Exception {
            // Six acts at the SAME instant, which is not contrived: every writer in this product
            // stamps a transaction's rows from one Instant, so a bulk act produces exactly this.
            for (int index = 0; index < 6; index++) {
                recordAct(ATLAS, ADMIN, "case.updated", BASE);
            }

            List<String> firstPage = idsOf(projectTrail(ADMIN, ATLAS, "?page=0&size=3"));
            List<String> secondPage = idsOf(projectTrail(ADMIN, ATLAS, "?page=1&size=3"));

            assertThat(firstPage).hasSize(3);
            assertThat(secondPage).hasSize(3);
            // Without `id DESC` behind `occurred_at DESC` Postgres may order the six differently
            // for the two LIMIT/OFFSET reads, and a row is served twice or not at all. On an audit
            // trail "not at all" is the failure the table exists to prevent.
            assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);

            List<String> both = new ArrayList<>(firstPage);
            both.addAll(secondPage);
            assertThat(both)
                    .as("six distinct rows across two pages of three")
                    .doesNotHaveDuplicates()
                    .hasSize(6);
        }

        @Test
        @DisplayName("total counts the filter, not the page")
        void totalIsTheCountOfTheFilterAndNotOfThePage() throws Exception {
            for (int index = 0; index < 5; index++) {
                recordAct(ATLAS, ADMIN, "case.updated", BASE.plusSeconds(index));
            }

            JsonNode page = projectTrail(ADMIN, ATLAS, "?page=0&size=2");

            assertThat(rowsOf(page)).hasSize(2);
            // The pager reads this. A total that reported the page length would render "1 of 1"
            // over a trail with five rows in it.
            assertThat(page.get("total").asInt()).isEqualTo(5);
            assertThat(page.get("size").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("the action filter matches exactly and never as a prefix")
        void theActionFilterIsExact() throws Exception {
            recordAct(ATLAS, ADMIN, "case.created", BASE);
            recordAct(ATLAS, ADMIN, "case.created.bulk", BASE.plusSeconds(1));

            JsonNode page = projectTrail(ADMIN, ATLAS, "?action=case.created");

            // `case.` is a naming convention in this table, not a family. A prefix match would
            // promote it to one, and nothing else in the product treats it as one.
            assertThat(actionsOf(page)).containsExactly("case.created");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private JsonNode projectTrail(UUID userId, UUID projectId) throws Exception {
        return projectTrail(userId, projectId, "");
    }

    private JsonNode projectTrail(UUID userId, UUID projectId, String query) throws Exception {
        MvcResult result = getProjectTrail(userId, projectId, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult getProjectTrail(UUID userId, UUID projectId) throws Exception {
        return getProjectTrail(userId, projectId, "");
    }

    private MvcResult getProjectTrail(UUID userId, UUID projectId, String query) throws Exception {
        // The query is appended whole rather than onto a hardcoded `?size=50`, which is how the
        // first version of this helper produced `?size=50&size=2` — Spring bound the FIRST
        // occurrence, three arms read 50 rows where they asked for 2, and the failure looked like
        // a paging defect in the product.
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/audit-events" + query)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private JsonNode companyTrail(UUID userId) throws Exception {
        return companyTrail(userId, "");
    }

    private JsonNode companyTrail(UUID userId, String query) throws Exception {
        MvcResult result = getCompanyTrail(userId, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult getCompanyTrail(UUID userId, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/audit-events" + query)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private static List<JsonNode> rowsOf(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.get("items").forEach(rows::add);
        return rows;
    }

    private static List<String> actionsOf(JsonNode page) {
        return rowsOf(page).stream().map(row -> row.get("action").asText()).toList();
    }

    private static List<String> idsOf(JsonNode page) {
        return rowsOf(page).stream().map(row -> row.get("id").asText()).toList();
    }

    /**
     * Records one act through the product's own writer, in ACME's context.
     *
     * <p>Trailing underscore because {@code record} is a contextual keyword and a method called
     * {@code record} beside {@code AuditEvent.record} reads as the same thing at the call site.
     */
    private void recordAct(UUID projectId, UUID actorUserId, String action, Instant at) {
        inCompany(ACME, () -> tx.executeWithoutResult(status -> auditEvents.save(
                AuditEvent.record(
                        projectId, actorUserId, action, "TestCase",
                        List.of(UUID.randomUUID()), null, at))));
    }

    /** Runs {@code action} as though the request had arrived on an API token. */
    private static void asToken(
            UUID tokenId, UUID companyId, UUID ownerId, UUID projectId, Runnable action) {

        try (var ignored = TokenContext.open(new TokenContext.ApiTokenPrincipal(
                tokenId, companyId, ownerId, Role.TEST_ENGINEER_ID, projectId))) {
            action.run();
        }
    }

    private void offboard(UUID companyId, UUID userId) {
        inCompany(companyId, () -> tx.executeWithoutResult(status ->
                memberships.findByCompanyIdAndUserId(companyId, userId).ifPresent(membership -> {
                    membership.remove();
                    memberships.save(membership);
                })));
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private void provisionCompany(UUID id, String slug, String name) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, BASE));
        }
    }

    private void activeUser(UUID id, String email) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, BASE);
            user.activate("hash", BASE);
            users.save(user);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, BASE));
        }
    }

    /**
     * An accepted membership — re-accepted on every run, because {@code anOffboardedActorKeepsTheirName}
     * leaves DEPARTED removed and the next test needs them back.
     */
    private void member(UUID companyId, UUID userId, String displayName) {
        CompanyMembership membership = memberships.findByCompanyIdAndUserId(companyId, userId)
                .orElseGet(() -> CompanyMembership.invite(companyId, userId, displayName, null, BASE));
        membership.accept(BASE);
        memberships.save(membership);
    }

    private void grantOnCompany(UUID id, UUID companyId, UUID roleId, UUID userId) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, BASE));
        }
    }

    private void grantOnProject(UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, BASE));
        }
    }

    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }
}
