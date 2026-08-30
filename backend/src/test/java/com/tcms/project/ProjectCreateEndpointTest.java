package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InstanceRoleAssignment;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /projects} — the write that ends "a provisioned company plus hand-written SQL".
 *
 * <p>Three things here are not covered anywhere else in the suite and each is the reason a
 * particular defect would otherwise ship:
 *
 * <ol>
 *   <li><strong>A COMPANY-scope gate over HTTP.</strong> Every other write's 403 comes from a
 *       permission held on the target project. This one has no target, so the gate is a different
 *       code path — and the failure it hides is a Test Lead, who holds {@code project:settings} on
 *       the project they run, being able to start new ones.</li>
 *   <li><strong>The creator can see what they created.</strong> The endpoint can be perfectly
 *       correct and the screen still show an empty list a moment later, because visibility is a
 *       grant question and creation does not mint a grant. Asserted over the wire, on the very next
 *       request, rather than argued in a comment.</li>
 *   <li><strong>The project is seeded.</strong> A project with no statuses and no Default
 *       configuration looks fine at create and fails three screens later, in another module.</li>
 * </ol>
 *
 * <p>Ids use the {@code 9c…} prefix, which no other class in the suite uses. Sharing a prefix is
 * how handoff §19's first defect happened and how §29 lost an afternoon: one class's no-grant
 * persona becomes another's granted engineer, and the symptom reads like a broken privilege split.
 */
@AutoConfigureMockMvc
@DisplayName("POST /projects — the company-scope gate, the seed, and what the creator sees next")
class ProjectCreateEndpointTest extends IntegrationTestBase {

    private static final UUID WEST = UUID.fromString("9c000000-0000-4000-8000-00000000000a");
    private static final UUID EAST = UUID.fromString("9c000000-0000-4000-8000-00000000000b");

    /** Already exists in WEST, keyed OLB — so a create can collide with something real. */
    private static final UUID WEST_EXISTING = UUID.fromString("9c001000-0000-4000-8000-000000000001");
    /** EAST's own project, keyed OLB as well: uniqueness is per company, not global. */
    private static final UUID EAST_EXISTING = UUID.fromString("9c002000-0000-4000-8000-000000000001");

    /** Company Admin in WEST — a COMPANY-scope grant, which is what {@code project:create} needs. */
    private static final UUID ADMIN = UUID.fromString("9c003000-0000-4000-8000-000000000001");
    /**
     * Test Lead on WEST_EXISTING. The interesting refusal: they hold {@code project:settings},
     * {@code project:members} and {@code suite:delete} on the project they run, and creating another
     * one is not theirs (roles spec §A4 — "same powers, wider target" is what separates the tiers).
     */
    private static final UUID LEAD = UUID.fromString("9c003000-0000-4000-8000-000000000002");
    /** ACTIVE member of WEST holding nothing at all. */
    private static final UUID NO_GRANT = UUID.fromString("9c003000-0000-4000-8000-000000000003");
    /**
     * A COMPANY-scope grant of a role that is <em>not</em> Company Admin — Viewer over the whole
     * company, which is how a client's stakeholder is given read access to everything.
     *
     * <p>The persona the gate needs and the other five do not: {@code LEAD} and {@code NO_GRANT}
     * hold nothing company-wide at all, so they answer 403 for <em>any</em> company-scope permission
     * and cannot tell {@code project:create} apart from {@code company:read}. This one holds
     * {@code company:read} and {@code project:read} company-wide and not {@code project:create}, so
     * it is the only fixture that fails when the gate names the wrong key. Found by mutating it.
     */
    private static final UUID COMPANY_VIEWER = UUID.fromString("9c003000-0000-4000-8000-000000000006");
    /** Company Admin in EAST, used from the other side of the boundary. */
    private static final UUID EAST_ADMIN = UUID.fromString("9c003000-0000-4000-8000-000000000004");
    /** Instance Admin — SYSTEM scope, no membership row anywhere. */
    private static final UUID OPERATOR = UUID.fromString("9c003000-0000-4000-8000-000000000005");

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
    private InstanceRoleAssignmentRepository instanceRoleAssignments;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(WEST, "west-create", "Westwind", now);
            provisionCompany(EAST, "east-create", "Eastwind", now);

            activeUser(ADMIN, "admin@west-create.test", now);
            activeUser(LEAD, "lead@west-create.test", now);
            activeUser(NO_GRANT, "nobody@west-create.test", now);
            activeUser(COMPANY_VIEWER, "stakeholder@west-create.test", now);
            activeUser(EAST_ADMIN, "admin@east-create.test", now);
            activeUser(OPERATOR, "operator@instance-create.test", now);
        });

        // The operator holds no membership anywhere on purpose: the SYSTEM term is deliberately not
        // behind the company gate (PermissionEvaluator's header), and a fixture that quietly gave
        // them a membership would let this class pass whether that is true or not.
        tx.executeWithoutResult(status -> grantInstanceAdmin(OPERATOR, now));

        inCompany(WEST, () -> tx.executeWithoutResult(status -> {
            seedProject(WEST_EXISTING, WEST, "OLB", "Online Banking", now);
            member(WEST, ADMIN, "A. Admin", now);
            member(WEST, LEAD, "L. Lead", now);
            member(WEST, NO_GRANT, "N. Grant", now);
            member(WEST, COMPANY_VIEWER, "S. Stakeholder", now);

            grantOnCompany("w-admin", WEST, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject("w-lead", WEST, Role.TEST_LEAD_ID, LEAD, WEST_EXISTING, now);
            // Viewer at COMPANY scope: every project in the company, read-only. The role's scope
            // column says PROJECT, and role_assignment does not enforce it — which is precisely the
            // shape a future custom role takes, and the reason this persona is worth having.
            grantOnCompany("w-stakeholder", WEST, Role.VIEWER_ID, COMPANY_VIEWER, now);
        }));

        inCompany(EAST, () -> tx.executeWithoutResult(status -> {
            seedProject(EAST_EXISTING, EAST, "OLB", "Online Banking", now);
            member(EAST, EAST_ADMIN, "E. Admin", now);
            grantOnCompany("e-admin", EAST, Role.COMPANY_ADMIN_ID, EAST_ADMIN, now);
        }));

        seedCompanyDefaults(WEST);
        seedCompanyDefaults(EAST);
        resetMutableRows();
    }

    /**
     * Puts back everything a test can write — <strong>every project except the seeded one</strong>,
     * and everything {@code tcms_seed_project_defaults} hung off it.
     *
     * <p>This class is the only one in the suite whose subject is creating rows in {@code project}
     * itself, so without a reset the second test in the class runs against the first one's leftovers:
     * "another project already uses that key" would start firing on a key nothing had used when the
     * class was written, and every count assertion would depend on JUnit's method order. Green alone
     * and red in the suite is handoff §19's defect, and it is cheaper to prevent than to diagnose.
     *
     * <p>Deleted through each company's <em>own</em> app-role connection, so RLS is in force while
     * the cleanup runs: a delete that reached across the boundary here would be the very leak the
     * isolation assertions elsewhere in this class claim cannot happen.
     */
    private void resetMutableRows() throws SQLException {
        reset(WEST, WEST_EXISTING);
        reset(EAST, EAST_EXISTING);
    }

    private void reset(UUID companyId, UUID keptProjectId) throws SQLException {
        try (var connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
            // Children first: Postgres checks the foreign keys immediately, and the project row
            // cannot go while a status or a configuration still points at it.
            execute(connection, "DELETE FROM configuration WHERE project_id <> '" + keptProjectId + "'");
            execute(connection, "DELETE FROM case_status WHERE project_id <> '" + keptProjectId + "'");
            execute(connection, """
                    DELETE FROM classification_value
                     WHERE dimension_id IN (
                        SELECT id FROM classification_dimension
                         WHERE project_id IS NOT NULL AND project_id <> '%s')
                    """.formatted(keptProjectId));
            // project_id IS NOT NULL matters: the company-level vocabularies (Test Type, Test Level,
            // Execution Phase) carry a null project and must survive, or the classification
            // assertion below would be asserting against a company nobody had seeded.
            execute(connection, "DELETE FROM classification_dimension"
                    + " WHERE project_id IS NOT NULL AND project_id <> '" + keptProjectId + "'");
            execute(connection, "DELETE FROM project WHERE id <> '" + keptProjectId + "'");
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate — COMPANY scope, and nothing below it
    // =====================================================================

    @Nested
    @DisplayName("The gate is project:create at COMPANY scope")
    class Gate {

        @Test
        @DisplayName("a Company Admin creates one — 201, with Location")
        void companyAdminMayCreate() throws Exception {
            MvcResult result = post(ADMIN, WEST, body("PAY", "Payments Platform", "Cards and SEPA"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode created = json.readTree(result.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/projects/" + created.get("id").asText());
        }

        @Test
        @DisplayName("an Instance Admin creates one — the SYSTEM term, with no membership row")
        void instanceAdminMayCreate() throws Exception {
            // Not a formality: the operator provisions companies and must be able to work in one
            // before anybody has been made a member of it. If the company gate covered SYSTEM, this
            // would be a 403 and the instance would have a company nobody could set up.
            assertThat(statusOf(post(OPERATOR, WEST, body("OPS", "Operator Project", null))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a Test Lead may NOT — running a project is not a licence to start another")
        void projectScopedPowerDoesNotReachCompanyScope() throws Exception {
            // The mutation this kills: gating on canDo(userId, someProject, "project:create"), or on
            // any project-scoped question at all. A Test Lead holds project:settings, project:members
            // and suite:delete on WEST_EXISTING, so every "does this caller look powerful" heuristic
            // says yes here.
            assertThat(statusOf(post(LEAD, WEST, body("NEW", "Lead's Project", null)))).isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant may not either")
        void plainMemberMayNotCreate() throws Exception {
            assertThat(statusOf(post(NO_GRANT, WEST, body("NEW", "Nobody's Project", null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("the refusal names no permission key and writes nothing")
        void refusalIsQuietAndLeavesNoTrace() throws Exception {
            long before = liveProjectCount(WEST);
            MvcResult refused = post(LEAD, WEST, body("NEW", "Lead's Project", null));

            String detail = json.readTree(refused.getResponse().getContentAsString())
                    .get("detail").asText();
            assertThat(detail)
                    .as("naming the key publishes the catalogue to the one caller not entitled to it")
                    .doesNotContain("project:create")
                    .isEqualTo("You do not have permission to create projects in this company.");
            assertThat(liveProjectCount(WEST)).isEqualTo(before);
            assertThat(auditActions(WEST)).isEmpty();
        }

        @Test
        @DisplayName("a company-wide VIEWER may not create either — the gate names project:create")
        void aCompanyWideReadGrantIsNotACreateGrant() throws Exception {
            // The mutation this kills, and the only test that does: gating on company:read, or on
            // project:read, or on any other COMPANY-scope key. This caller holds both of those over
            // the whole company and must still be refused — read access to everything a client owns
            // is exactly what a stakeholder is given, and it is not permission to start products.
            assertThat(statusOf(post(COMPANY_VIEWER, WEST, body("NEW", "Stakeholder's Project", null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a refused caller is refused BEFORE the payload is looked at")
        void thePermissionIsCheckedFirst() throws Exception {
            // A Test Lead submitting a key their company already uses gets 403, not 400. Validate
            // first and the endpoint answers "that key is taken" to a caller who may not create
            // anything — a probe for which keys the company holds, handed to exactly the person the
            // gate just refused. The two statuses are the only thing that distinguishes the orders.
            assertThat(statusOf(post(LEAD, WEST, body("OLB", "Second Banking", null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("an anonymous caller is 401, before any handler runs")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("ANY", "Anything", null)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }

        /**
         * The invariant the "no automatic grant" decision rests on, computed from the seeded
         * catalogue rather than read off the roles table by eye.
         *
         * <p>{@link ProjectCreateService} does not mint a project grant for the creator because every
         * role that can reach the endpoint already sees every project in the company. That is true of
         * the built-in roles and nothing enforces it — so if a later migration seeds a role with
         * {@code project:create} and not {@code project:read}, this fails and whoever wrote it is
         * told that their new role would create projects it cannot see. Handoff §6 lesson 9: a
         * catalogue-wide invariant needs a computed check.
         */
        @Test
        @DisplayName("no role may create projects without also being able to read them")
        void everyRoleThatMayCreateMayAlsoRead() throws Exception {
            List<String> createWithoutRead = new ArrayList<>();
            try (var connection = superuserConnection();
                    var statement = connection.prepareStatement("""
                            SELECT r.name
                              FROM role r
                              JOIN role_permission rp ON rp.role_id = r.id
                             WHERE rp.permission_key = 'project:create'
                               AND NOT EXISTS (
                                   SELECT 1 FROM role_permission rp2
                                    WHERE rp2.role_id = r.id
                                      AND rp2.permission_key = 'project:read')
                            """);
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    createWithoutRead.add(rows.getString(1));
                }
            }

            assertThat(createWithoutRead)
                    .as("""
                            These roles may create a project and may not read one, so a project \
                            created through POST /projects would be invisible to its own author. \
                            Either grant the role project:read, or ProjectCreateService has to start \
                            minting a project grant for the creator — which handoff §22 says makes \
                            myRole claim a per-project role the caller does not hold.""")
                    .isEmpty();
        }
    }

    // =====================================================================
    // What the creator sees next
    // =====================================================================

    @Nested
    @DisplayName("The creator can see what they just created")
    class Visibility {

        @Test
        @DisplayName("the new project is in GET /projects on the very next request")
        void theCreatorSeesItImmediately() throws Exception {
            // The defect this exists for: creation does not mint a grant, and visibility is a grant
            // question — so a correct 201 can be followed by a project picker that does not list it.
            // Nothing about the create response would reveal that.
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            assertThat(idsOf(listAs(ADMIN, WEST))).contains(created);
        }

        @Test
        @DisplayName("and GET /projects/{id} answers 200 for the creator")
        void theDetailIsReachableToo() throws Exception {
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            assertThat(statusOfDetail(ADMIN, WEST, created)).isEqualTo(200);
        }

        @Test
        @DisplayName("myRole is null — a COMPANY-scope grant labels no single project")
        void noProjectGrantWasMinted() throws Exception {
            // If this ever reads "Company Admin", something started minting a PROJECT-scope
            // assignment for the creator: handoff §22 settled that a Company Admin's myRole is null,
            // because their grant names no project and labelling one would state a fact about the
            // company as though it were about that project.
            JsonNode created = json.readTree(
                    post(ADMIN, WEST, body("PAY", "Payments Platform", null))
                            .getResponse().getContentAsString());

            assertThat(created.get("myRole").isNull()).isTrue();
        }

        @Test
        @DisplayName("a Test Lead who could not create it cannot see it either")
        void creationDoesNotWidenAnybodyElsesVisibility() throws Exception {
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            assertThat(idsOf(listAs(LEAD, WEST))).doesNotContain(created);
            assertThat(statusOfDetail(LEAD, WEST, created))
                    .as("§6t: unreachable is 404, never 403")
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // Isolation
    // =====================================================================

    @Nested
    @DisplayName("The company boundary — a new project belongs to the session's company only")
    class Isolation {

        @Test
        @DisplayName("EAST's admin cannot see or address a project created in WEST")
        void aNewProjectIsInvisibleToTheOtherCompany() throws Exception {
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            assertThat(idsOf(listAs(EAST_ADMIN, EAST))).doesNotContain(created);
            assertThat(statusOfDetail(EAST_ADMIN, EAST, created))
                    .as("a Company Admin sees every project IN THEIR COMPANY, which is the point")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("both companies may key a project PAY — uniqueness is per company")
        void theKeyCheckIsCompanyScoped() throws Exception {
            assertThat(statusOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null))))
                    .isEqualTo(201);
            // A global uniqueness check would refuse this — and would tell Eastwind that somebody
            // else uses the key, which is a fact about another client.
            assertThat(statusOf(post(EAST_ADMIN, EAST, body("PAY", "Payments", null))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a companyId in the body is not the company it lands in")
        void theBodyCannotChooseTheCompany() throws Exception {
            // ERD §0.1 point 2. The field is not on ProjectWriteRequest at all, so this asserts the
            // property rather than a rejection: sending it changes nothing, and the row lands in the
            // company the session opened.
            String smuggled =
                    "{\"key\":\"SMG\",\"name\":\"Smuggled\",\"companyId\":\"" + EAST + "\"}";
            UUID created = idOf(post(ADMIN, WEST, smuggled));

            assertThat(idsOf(listAs(ADMIN, WEST))).contains(created);
            assertThat(idsOf(listAs(EAST_ADMIN, EAST))).doesNotContain(created);
        }
    }

    // =====================================================================
    // The payload
    // =====================================================================

    @Nested
    @DisplayName("Validation — every message lands on the field that caused it")
    class Validation {

        @Test
        @DisplayName("a key this company already uses is a 400 on `key`, not a 409")
        void duplicateKeyIsAFieldError() throws Exception {
            MvcResult refused = post(ADMIN, WEST, body("OLB", "Second Banking", null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "key"))
                    .containsExactly("Another project in this company already uses that key.");
        }

        @Test
        @DisplayName("a lower-case key is refused, not silently upper-cased")
        void keyCaseIsNotCorrectedForYou() throws Exception {
            // Storing "OLB" for a caller who typed "olb" is a save that returns something other than
            // what was typed, on the very screen that shows the key back.
            MvcResult refused = post(ADMIN, WEST, body("olb", "Lowercase", null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "key"))
                    .containsExactly("Use 2–10 upper-case letters or digits, like OLB.");
            assertThat(liveProjectCount(WEST)).isEqualTo(1);
        }

        @Test
        @DisplayName("one character, eleven characters, punctuation and blank are all refused")
        void theKeyFormatIsTheSchemasOwn() throws Exception {
            // ck_project_key_format in V1. Restated in ProjectEdit so the caller is told which field
            // is wrong instead of receiving a driver exception naming a constraint.
            for (String key : List.of("A", "ELEVENCHARS", "PA-Y", "PA Y", "  ")) {
                assertThat(statusOf(post(ADMIN, WEST, body(key, "Whatever", null))))
                        .as("key %s must be refused", key)
                        .isEqualTo(400);
            }
        }

        @Test
        @DisplayName("a missing name is a 400 on `name`")
        void nameIsRequired() throws Exception {
            MvcResult refused = post(ADMIN, WEST, "{\"key\":\"PAY\",\"name\":\"   \"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOn(refused, "name")).containsExactly("Enter a project name.");
        }

        @Test
        @DisplayName("both fields can be wrong at once, and both are reported")
        void everyFailingFieldIsNamed() throws Exception {
            // A form that highlights one input at a time makes the user submit three times to learn
            // three things. Problem.errors is a map for this reason.
            MvcResult refused = post(ADMIN, WEST, body("bad key", "", null));

            assertThat(errorsOn(refused, "key")).isNotEmpty();
            assertThat(errorsOn(refused, "name")).isNotEmpty();
        }

        @Test
        @DisplayName("an over-long name is refused rather than truncated")
        void nameHasACeiling() throws Exception {
            String tooLong = "N".repeat(ProjectEdit.MAX_NAME + 1);

            assertThat(statusOf(post(ADMIN, WEST, body("PAY", tooLong, null)))).isEqualTo(400);
        }

        @Test
        @DisplayName("a blank description is stored as null, not as whitespace")
        void blankDescriptionIsAbsence() throws Exception {
            JsonNode created = json.readTree(post(ADMIN, WEST, body("PAY", "Payments", "   "))
                    .getResponse().getContentAsString());

            assertThat(created.has("description")).isTrue();
            assertThat(created.get("description").isNull()).isTrue();
        }

        @Test
        @DisplayName("a refused create leaves no project and no audit row")
        void nothingIsWrittenOnARefusal() throws Exception {
            long before = liveProjectCount(WEST);

            assertThat(statusOf(post(ADMIN, WEST, body("olb", "Lowercase", null)))).isEqualTo(400);

            assertThat(liveProjectCount(WEST)).isEqualTo(before);
            assertThat(auditActions(WEST)).isEmpty();
        }
    }

    // =====================================================================
    // What a created project actually is
    // =====================================================================

    @Nested
    @DisplayName("The created project is a usable one")
    class Seeding {

        @Test
        @DisplayName("its seven execution statuses are there — the same ones provision-company seeds")
        void statusesAreSeeded() throws Exception {
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            List<String> names = new ArrayList<>();
            fetch("/api/v1/projects/" + created + "/statuses", ADMIN, WEST)
                    .forEach(status -> names.add(status.get("name").asText()));

            assertThat(names)
                    .containsExactly("Passed", "Failed", "Blocked", "Skipped", "Retest",
                            "Untested", "WIP");
        }

        @Test
        @DisplayName("its Feature dimension is there, so cases can be classified")
        void classificationIsSeeded() throws Exception {
            UUID created = idOf(post(ADMIN, WEST, body("PAY", "Payments Platform", null)));

            List<String> dimensionKeys = new ArrayList<>();
            fetch("/api/v1/projects/" + created + "/classification", ADMIN, WEST)
                    .forEach(dimension -> dimensionKeys.add(dimension.get("key").asText()));

            assertThat(dimensionKeys)
                    .as("the company vocabularies plus the project's own Feature tree (plan §6d)")
                    .contains("feature", "test_type", "test_level", "execution_phase");
        }

        @Test
        @DisplayName("the wire shape is the contract's Project, and companyId is not in it")
        void theResponseIsAProject() throws Exception {
            JsonNode created = json.readTree(
                    post(ADMIN, WEST, body("PAY", "Payments Platform", "Cards and SEPA"))
                            .getResponse().getContentAsString());

            assertThat(created.get("key").asText()).isEqualTo("PAY");
            assertThat(created.get("name").asText()).isEqualTo("Payments Platform");
            assertThat(created.get("description").asText()).isEqualTo("Cards and SEPA");
            assertThat(created.get("archived").asBoolean())
                    .as("archived is server-owned; a project must not be born retired")
                    .isFalse();
            assertThat(created.get("caseCount").asLong()).isZero();
            assertThat(created.has("companyId"))
                    .as("echoing it back invites the next author to accept it on the way in")
                    .isFalse();
        }

        @Test
        @DisplayName("an audit row is written, with a name that would break naive JSON")
        void theCreateIsAudited() throws Exception {
            String awkward = "Acme \"Retail\" \\ Wealth";
            UUID created = idOf(post(ADMIN, WEST, body("PAY", awkward, null)));

            List<AuditEvent> recorded = inCompanyRead(
                    WEST, () -> auditEvents.findByActionOrderByOccurredAtDesc("project.created"));

            assertThat(recorded).hasSize(1);
            AuditEvent event = recorded.getFirst();
            assertThat(event.getTargetIds()).containsExactly(created);
            assertThat(event.getTargetType()).isEqualTo("project");
            assertThat(event.getActorUserId()).isEqualTo(ADMIN);
            assertThat(event.getProjectId()).isEqualTo(created);
            // The escape is the assertion: concatenated raw, this name produces a malformed JSONB
            // document, the insert fails, and the whole create rolls back with an error naming the
            // audit table rather than the name.
            assertThat(json.readTree(event.getChanges()).get("name").asText()).isEqualTo(awkward);
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult post(UUID userId, UUID companyId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String body(String key, String name, String description) {
        StringBuilder json = new StringBuilder("{\"key\":\"").append(key)
                .append("\",\"name\":\"").append(name.replace("\\", "\\\\").replace("\"", "\\\""))
                .append('"');
        if (description != null) {
            json.append(",\"description\":\"").append(description).append('"');
        }
        return json.append('}').toString();
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private UUID idOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a created project, got: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private List<String> errorsOn(MvcResult result, String field) throws Exception {
        JsonNode errors = json.readTree(result.getResponse().getContentAsString()).get("errors");
        if (errors == null || errors.get(field) == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        errors.get(field).forEach(message -> messages.add(message.asText()));
        return messages;
    }

    private List<JsonNode> fetch(String path, UUID userId, UUID companyId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("%s must answer an array: %s", path, body).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private List<JsonNode> listAs(UUID userId, UUID companyId) throws Exception {
        return fetch("/api/v1/projects", userId, companyId);
    }

    private int statusOfDetail(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private static List<UUID> idsOf(List<JsonNode> projects) {
        return projects.stream().map(node -> UUID.fromString(node.get("id").asText())).toList();
    }

    private long liveProjectCount(UUID companyId) {
        return inCompanyRead(companyId, () -> (long) projects.findAllByOrderByNameAsc().size());
    }

    private List<String> auditActions(UUID companyId) {
        return inCompanyRead(companyId,
                () -> auditEvents.findByActionOrderByOccurredAtDesc("project.created").stream()
                        .map(AuditEvent::getAction)
                        .toList());
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    /**
     * A read inside a company context <em>and</em> a transaction — both are required. A repository
     * query method with no transaction runs with no {@code app.current_company}, and under RLS that
     * means zero rows: the assertion would pass for the wrong reason.
     */
    private <T> T inCompanyRead(UUID companyId, Supplier<T> read) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> read.get());
        }
    }

    private void provisionCompany(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    /**
     * The vocabularies and the five key counters, through the same function
     * {@code provision-company} calls.
     *
     * <p>Needed because {@code tcms_seed_project_defaults} adds the project's Feature dimension
     * <em>beside</em> the company-level ones the classification endpoint also returns — a company
     * that never ran this would let the seeding assertion pass against a nearly empty list.
     */
    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (var connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
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

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void grantOnCompany(String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantInstanceAdmin(UUID userId, Instant now) {
        UUID id = assignmentId("operator");
        if (instanceRoleAssignments.findById(id).isEmpty()) {
            instanceRoleAssignments.save(
                    InstanceRoleAssignment.forUser(id, Role.INSTANCE_ADMIN_ID, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("project-create:" + marker).getBytes());
    }
}
