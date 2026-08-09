package com.tcms.project;

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
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The classification vocabulary's writes — {@code POST}, {@code PUT} and {@code DELETE} under
 * {@code /projects/{id}/classification/values}.
 *
 * <h2>What this class is really testing</h2>
 *
 * <p>Three things, and only the first is the feature:
 *
 * <ol>
 *   <li><strong>That a Feature tree can be built at all.</strong> {@code tcms_seed_project_defaults}
 *       creates the dimension empty on purpose, so before this endpoint a provisioned project's
 *       Feature picker was empty and stayed empty (handoff §68 left it "usable and not
 *       fillable").</li>
 *   <li><strong>That the gate depends on the DIMENSION, in key and in scope.</strong> A Test Lead
 *       holds {@code project:settings} and not {@code company:vocabulary}, so the same request body
 *       against two different dimensions must answer 201 and 403. A gate written as one constant
 *       compiles, passes every Feature test here, and lets a Test Lead edit the vocabulary every
 *       one of that client's projects reports through.</li>
 *   <li><strong>That a reference this project does not offer is refused.</strong> The foreign key
 *       is satisfied by a value of another dimension and by another project's Feature value, and
 *       both store cleanly — {@code sharedStepId} (§64) and {@code classificationValueIds} (§68)
 *       are the same family, and this is the third and fourth column in it.</li>
 * </ol>
 *
 * <p>The fixture builds its tree <strong>through the endpoint</strong> rather than through SQL,
 * unlike {@code ProjectConfigurationEndpointTest}, which had no writer to use. That is deliberate:
 * a fixture that inserts rows by hand can seed a state the product cannot produce, and §68 records
 * the mock-side version of exactly that defect.
 *
 * <p>Company, user and project ids are unique to this class (prefix {@code fb…}, slug namespace
 * {@code voc-}) — handoff §19's collision has now happened five times.
 */
@AutoConfigureMockMvc
@DisplayName("Classification value writes — the gate the dimension decides")
class ClassificationValueWriteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("fb000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("fb000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD and ENGINEER. Where the tree gets built. */
    private static final UUID BANKING = UUID.fromString("fb001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody but the admin. Its Feature dimension must stay unreachable. */
    private static final UUID MOBILE = UUID.fromString("fb001000-0000-4000-8000-000000000002");
    /** The other client's project, seeded from the same function so every name collides. */
    private static final UUID FOREIGN = UUID.fromString("fb002000-0000-4000-8000-000000000001");

    /** Test Lead on BANKING: holds project:settings, does NOT hold company:vocabulary. */
    private static final UUID LEAD = UUID.fromString("fb003000-0000-4000-8000-000000000001");
    /** Company Admin in Acme: holds both, and reaches every project. */
    private static final UUID ADMIN = UUID.fromString("fb003000-0000-4000-8000-000000000002");
    /** Test Engineer on BANKING: holds neither. Reads the vocabulary, edits nothing. */
    private static final UUID ENGINEER = UUID.fromString("fb003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("fb003000-0000-4000-8000-000000000004");

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
    private TransactionTemplate tx;

    private UUID featureDimension;
    private UUID testTypeDimension;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "voc-acme", "Acme", now);
            company(UMBRA, "voc-umbra", "Umbra", now);

            activeUser(LEAD, "lead@voc-acme.test", now);
            activeUser(ADMIN, "admin@voc-acme.test", now);
            activeUser(ENGINEER, "engineer@voc-acme.test", now);
            activeUser(NO_GRANT, "nogrant@voc-acme.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "VAOLB", "Online Banking", now);
            seedProject(MOBILE, ACME, "VAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ADMIN, "A. Admin", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("voc-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grantOnProject("voc-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, BANKING, now);
            grantOnCompany("voc-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(FOREIGN, UMBRA, "VUOLB", "Online Banking", now)));

        seedVocabularies(ACME, List.of(BANKING, MOBILE));
        seedVocabularies(UMBRA, List.of(FOREIGN));

        // Every test method starts from the seeded state plus whatever earlier methods wrote, so
        // nothing here asserts a count over the whole dimension — only over what it created.
        featureDimension = dimensionId(ADMIN, ACME, BANKING, "feature");
        testTypeDimension = dimensionId(ADMIN, ACME, BANKING, "test_type");
    }

    // =====================================================================
    // The gate — the reason this endpoint is not one permission
    // =====================================================================

    @Nested
    @DisplayName("The permission is the dimension's, and so is the scope it is held at")
    class Gate {

        @Test
        @DisplayName("a Test Lead fills their own project's Feature tree")
        void projectLevelDimensionTakesProjectSettings() throws Exception {
            assertThat(create(LEAD, BANKING, featureDimension, "Payments", null).getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("the same Test Lead cannot add a Test Type — that vocabulary is the company's")
        void companyLevelDimensionRefusesProjectSettings() throws Exception {
            // The headline. Same caller, same body, same endpoint — 403 rather than 201, because
            // "Performance" has to mean the same thing in every one of this client's projects or
            // the type × level coverage matrix stops comparing anything (plan §6d). A gate written
            // as one constant passes every Feature test in this class and fails only here.
            // A name no other method uses: if the gate breaks, this create SUCCEEDS, and a shared
            // name would then redden a second test through the duplicate check rather than through
            // the gate — a mutation count that measures the fixture instead of the code.
            MockHttpServletResponse refused =
                    create(LEAD, BANKING, testTypeDimension, "Chaos monkey", null);

            assertThat(refused.getStatus()).isEqualTo(403);
            assertThat(refused.getContentAsString())
                    .as("a refusal with a sentence, since the caller can already read the value")
                    .contains("permission");
        }

        @Test
        @DisplayName("a Company Admin adds a Test Type — company:vocabulary's first consumer")
        void companyLevelDimensionTakesCompanyVocabulary() throws Exception {
            // The permission has been seeded and correctly granted since M0 and never once asked,
            // which is §62's case:approve and §64's library:manage a third time.
            assertThat(create(ADMIN, BANKING, testTypeDimension, "Chaos", null).getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a Test Engineer, who reads the vocabulary, edits neither dimension")
        void readingIsNotEditing() throws Exception {
            assertThat(create(ENGINEER, BANKING, featureDimension, "Ledger", null).getStatus())
                    .isEqualTo(403);
            assertThat(create(ENGINEER, BANKING, testTypeDimension, "Ledger", null).getStatus())
                    .isEqualTo(403);
            assertThat(statusOfClassification(ENGINEER, ACME, BANKING))
                    .as("the 403 is the write gate and not a broken route")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404, not 403 — they may not know it exists")
        void personaThreeIsUnreachableRatherThanForbidden() throws Exception {
            assertThat(create(NO_GRANT, BANKING, featureDimension, "Ledger", null).getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a dimension of a project the caller cannot see is 404, not 403")
        void anotherProjectsDimensionIsUnreachable() throws Exception {
            UUID mobileFeature = dimensionId(ADMIN, ACME, MOBILE, "feature");

            // 404 rather than 403 by construction: the permission to ask is a property of the
            // dimension row, so a caller whose dimension does not resolve cannot be told which
            // permission they failed without the server inventing one.
            assertThat(create(LEAD, BANKING, mobileFeature, "Ledger", null).getStatus())
                    .isEqualTo(404);
            assertThat(mobileFeature)
                    .as("the sibling dimension really exists — otherwise the 404 is vacuous")
                    .isNotEqualTo(featureDimension);
        }

        @Test
        @DisplayName("a dimensionId naming nothing at all is the same 404, bodiless")
        void anUnknownDimensionIsIndistinguishable() throws Exception {
            MockHttpServletResponse absent = create(LEAD, BANKING, UUID.randomUUID(), "Ledger", null);

            assertThat(absent.getStatus()).isEqualTo(404);
            assertThat(absent.getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("an anonymous caller is refused before any of it")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + BANKING + "/classification/values")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(featureDimension, "Ledger", null, null)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Creating — and the references that satisfy the foreign key anyway
    // =====================================================================

    @Nested
    @DisplayName("Creating a value")
    class Create {

        @Test
        @DisplayName("the created value is in the next GET, nested under its parent")
        void aCreatedValueIsServed() throws Exception {
            UUID parent = idOf(create(LEAD, BANKING, featureDimension, "Cards", null));
            UUID child = idOf(create(LEAD, BANKING, featureDimension, "Chargebacks", parent));

            JsonNode served = valueById(featureValues(LEAD), child);

            assertThat(served.get("name").asText()).isEqualTo("Chargebacks");
            assertThat(served.get("parentId").asText()).isEqualTo(parent.toString());
            assertThat(served.get("path").asText())
                    .as("a child's path nests under its parent's, which is what §6q's descendant "
                            + "matching will read")
                    .startsWith(valueById(featureValues(LEAD), parent).get("path").asText() + ".");
        }

        @Test
        @DisplayName("a name with no ASCII letters in it is created, and its path is valid")
        void aNonAsciiNameDoesNotBreakTheLtreePath() throws Exception {
            // tcms_ltree_label() — lower + regexp_replace + trim — answers the EMPTY STRING for
            // "支付", and an empty ltree label is not a label: a name-derived path would fail the
            // insert with a message about the path rather than about the name. A Feature value is
            // the client's own product taxonomy in the client's own language, which is exactly
            // where this arrives first. The label is derived from the id instead.
            MockHttpServletResponse created = create(LEAD, BANKING, featureDimension, "支付", null);

            assertThat(created.getStatus()).isEqualTo(201);
            assertThat(node(created).get("path").asText())
                    .as("an id-derived label: non-empty, and a legal ltree label whatever the "
                            + "name contains")
                    .matches("^v[0-9a-f]{32}$");
            assertThat(node(created).get("name").asText())
                    .as("the name is untouched — the path is not a display string")
                    .isEqualTo("支付");
        }

        @Test
        @DisplayName("a parent from another dimension is a field error, not a stored row")
        void aParentOnAnotherAxisIsRefused() throws Exception {
            UUID functional = UUID.fromString(
                    valueByName(values(ADMIN, BANKING, "test_type"), "Functional")
                            .get("id").asText());

            MockHttpServletResponse refused = create(LEAD, BANKING, featureDimension, "Ledger", functional);

            // The foreign key is satisfied — it is a real classification_value in this company —
            // and so is the company column, RLS, and every check V2's trigger makes. Unchecked it
            // is simply stored, and every later read resolves a Feature through a Test Type.
            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("parentId");
        }

        @Test
        @DisplayName("a parent from ANOTHER PROJECT's Feature tree is refused the same way")
        void aParentInASiblingProjectIsRefused() throws Exception {
            UUID mobileFeature = dimensionId(ADMIN, ACME, MOBILE, "feature");
            UUID theirs = idOf(create(ADMIN, MOBILE, mobileFeature, "Their Feature", null));

            MockHttpServletResponse refused = create(LEAD, BANKING, featureDimension, "Ledger", theirs);

            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(messagesFor(refused, "parentId"))
                    .as("the same sentence as a wrong-dimension parent: telling them apart tells "
                            + "the caller their guessed id names a real value somewhere else")
                    .containsExactly("That value is not in this dimension.");
        }

        @Test
        @DisplayName("a blank name and an oversized one are both field errors")
        void theNameIsValidated() throws Exception {
            assertThat(errorFields(create(LEAD, BANKING, featureDimension, "   ", null)))
                    .containsExactly("name");
            assertThat(errorFields(
                    create(LEAD, BANKING, featureDimension, "x".repeat(151), null)))
                    .containsExactly("name");
            assertThat(create(LEAD, BANKING, featureDimension, "x".repeat(150), null).getStatus())
                    .as("150 is the boundary and it is inclusive")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a colour that is not six hex digits is refused")
        void theColourIsValidated() throws Exception {
            assertThat(errorFields(createColoured(LEAD, featureDimension, "Tinted", "red")))
                    .containsExactly("color");
            assertThat(createColoured(LEAD, featureDimension, "Tinted ok", "#b7791f").getStatus())
                    .isEqualTo(201);
        }
    }

    // =====================================================================
    // Uniqueness — per parent, case-insensitive, live rows only
    // =====================================================================

    @Nested
    @DisplayName("One name per parent")
    class Uniqueness {

        @Test
        @DisplayName("a duplicate sibling name is refused, whatever its case")
        void duplicatesAreRefused() throws Exception {
            create(LEAD, BANKING, featureDimension, "Statements", null);

            MockHttpServletResponse refused = create(LEAD, BANKING, featureDimension, "sTaTeMeNtS", null);

            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("name");
        }

        @Test
        @DisplayName("the SAME name under a different parent is allowed — §68 turned on this")
        void aRepeatedLeafNameUnderAnotherParentIsFine() throws Exception {
            UUID payments = idOf(create(LEAD, BANKING, featureDimension, "Pay", null));
            UUID billing = idOf(create(LEAD, BANKING, featureDimension, "Bill", null));

            // "Refunds" under Payments and "Refunds" under Billing are two different things. It is
            // why the case detail had to start serving classification value IDS rather than the
            // names it was already serving, and a per-dimension unique index would make the tree
            // unable to express a taxonomy the product already renders.
            assertThat(create(LEAD, BANKING, featureDimension, "Refunds", payments).getStatus())
                    .isEqualTo(201);
            assertThat(create(LEAD, BANKING, featureDimension, "Refunds", billing).getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a retired value's name is free again")
        void retiringReleasesTheName() throws Exception {
            UUID ghost = idOf(create(LEAD, BANKING, featureDimension, "Ghost", null));
            assertThat(retire(LEAD, BANKING, ghost).getStatus()).isEqualTo(204);

            // Otherwise the only way back is to resurrect the retired row — and a resurrected
            // value silently re-attaches itself to every case that carried it before.
            assertThat(create(LEAD, BANKING, featureDimension, "ghost", null).getStatus())
                    .isEqualTo(201);
        }
    }

    // =====================================================================
    // Editing — and the two things it refuses rather than drops
    // =====================================================================

    @Nested
    @DisplayName("Editing a value")
    class Edit {

        @Test
        @DisplayName("a rename changes the name and leaves the path alone")
        void renamingDoesNotRewriteThePath() throws Exception {
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "Onboarding", null));
            String path = valueById(featureValues(LEAD), value).get("path").asText();

            MockHttpServletResponse renamed = update(LEAD, BANKING, value, featureDimension, "Sign-up", null);

            assertThat(renamed.getStatus()).isEqualTo(200);
            assertThat(node(renamed).get("name").asText()).isEqualTo("Sign-up");
            assertThat(node(renamed).get("path").asText())
                    .as("the payoff of an id-derived label: a rename is a field assignment "
                            + "rather than a subtree rewrite")
                    .isEqualTo(path);
        }

        @Test
        @DisplayName("renaming a value to the name it already has is not a duplicate of itself")
        void aValueDoesNotCollideWithItself() throws Exception {
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "Settlement", null));

            // The defect every edit-side uniqueness check has by default — and it would refuse a
            // pure recolour, which changes no name at all.
            assertThat(update(LEAD, BANKING, value, featureDimension, "Settlement", null)
                    .getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a differing parentId is refused, not silently dropped")
        void movingIsRefused() throws Exception {
            UUID parent = idOf(create(LEAD, BANKING, featureDimension, "Treasury", null));
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "FX", null));

            MockHttpServletResponse refused = update(LEAD, BANKING, value, featureDimension, "FX", parent);

            // Dropping it is the third answer: the client shows the value in its new place,
            // reloads, and finds it back — with the server having reported success.
            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("parentId");
        }

        @Test
        @DisplayName("a differing dimensionId is refused — a value cannot change axis")
        void changingDimensionIsRefused() throws Exception {
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "Reconciliation", null));

            MockHttpServletResponse refused =
                    update(LEAD, BANKING, value, testTypeDimension, "Reconciliation", null);

            assertThat(refused.getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("dimensionId");
        }

        @Test
        @DisplayName("editing a company-level value needs the company permission, as the create did")
        void theEditGateMatchesTheCreateGate() throws Exception {
            UUID value = idOf(create(ADMIN, BANKING, testTypeDimension, "Soak", null));

            assertThat(update(LEAD, BANKING, value, testTypeDimension, "Soak testing", null)
                    .getStatus())
                    .isEqualTo(403);
            assertThat(update(ADMIN, BANKING, value, testTypeDimension, "Soak testing", null)
                    .getStatus())
                    .isEqualTo(200);
        }
    }

    // =====================================================================
    // Retiring — soft, and refused while anything hangs off it
    // =====================================================================

    @Nested
    @DisplayName("Retiring a value")
    class Retire {

        @Test
        @DisplayName("a retired leaf disappears from the vocabulary")
        void retiringHidesTheValue() throws Exception {
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "Deprecated", null));

            assertThat(retire(LEAD, BANKING, value).getStatus()).isEqualTo(204);
            assertThat(idsOf(featureValues(LEAD))).doesNotContain(value.toString());
        }

        @Test
        @DisplayName("a value with live children is refused, in a sentence naming the count")
        void aParentIsNotRetiredOutFromUnderItsChildren() throws Exception {
            UUID parent = idOf(create(LEAD, BANKING, featureDimension, "Lending", null));
            create(LEAD, BANKING, featureDimension, "Mortgages", parent);
            create(LEAD, BANKING, featureDimension, "Overdrafts", parent);

            MockHttpServletResponse refused = retire(LEAD, BANKING, parent);

            // The cascade is not built: it would unclassify every case beneath the subtree in one
            // request, and each of those assignments is unrestorable through the API because a
            // retired value is served to no client (§68's bound).
            assertThat(refused.getStatus()).isEqualTo(409);
            // A sentence, not the bare "2" this asserted until §149. `Problem.errors` values are
            // printed verbatim by `@tcms/cli` and handed to a model by `@tcms/mcp`, so a datum
            // here reads as `children: 2` at the two callers that cannot see the dialog which
            // used to compose the words. The count is still in it, and still comes from the row.
            List<String> children = messagesFor(refused, "children");
            assertThat(children).hasSize(1);
            assertThat(children.get(0))
                    .startsWith("2 values sit under this one.")
                    .contains("Retire those first");
        }

        @Test
        @DisplayName("retiring the children first lets the parent go")
        void theRefusalIsAboutChildrenAndNothingElse() throws Exception {
            UUID parent = idOf(create(LEAD, BANKING, featureDimension, "Insurance", null));
            UUID child = idOf(create(LEAD, BANKING, featureDimension, "Motor", parent));

            assertThat(retire(LEAD, BANKING, child).getStatus()).isEqualTo(204);
            assertThat(retire(LEAD, BANKING, parent).getStatus())
                    .as("otherwise the 409 above proves only that retire is broken")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("retiring an already-retired value is 404, not a second success")
        void retiringTwiceIsNotIdempotentSuccess() throws Exception {
            UUID value = idOf(create(LEAD, BANKING, featureDimension, "Twice", null));

            assertThat(retire(LEAD, BANKING, value).getStatus()).isEqualTo(204);
            assertThat(retire(LEAD, BANKING, value).getStatus())
                    .as("reporting success for a delete this request did not perform is the same "
                            + "untruth as a 201 on a replayed create")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("retiring a company-level value needs the company permission")
        void theRetireGateMatchesTheCreateGate() throws Exception {
            UUID value = idOf(create(ADMIN, BANKING, testTypeDimension, "Fuzz", null));

            assertThat(retire(LEAD, BANKING, value).getStatus()).isEqualTo(403);
            assertThat(retire(ADMIN, BANKING, value).getStatus()).isEqualTo(204);
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static String body(UUID dimensionId, String name, UUID parentId, String color) {
        return "{\"dimensionId\":" + quoted(dimensionId)
                + ",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
                + ",\"parentId\":" + quoted(parentId)
                + ",\"color\":" + (color == null ? "null" : "\"" + color + "\"") + "}";
    }

    private static String quoted(UUID id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    private MockHttpServletResponse create(UUID userId, UUID projectId, UUID dimensionId, String name, UUID parent)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/classification/values")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyOf(projectId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(dimensionId, name, parent, null)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse createColoured(UUID userId, UUID dimensionId, String name, String color)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + BANKING + "/classification/values")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(dimensionId, name, null, color)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse update(
            UUID userId, UUID projectId, UUID valueId, UUID dimensionId, String name, UUID parent)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/classification/values/" + valueId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyOf(projectId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(dimensionId, name, parent, null)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse retire(UUID userId, UUID projectId, UUID valueId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/classification/values/" + valueId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyOf(projectId))))
                .andReturn().getResponse();
    }

    private static UUID companyOf(UUID projectId) {
        return FOREIGN.equals(projectId) ? UMBRA : ACME;
    }

    private int statusOfClassification(UUID userId, UUID companyId, UUID projectId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/classification")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private List<JsonNode> dimensionsOf(UUID userId, UUID companyId, UUID projectId)
            throws Exception {

        String payload = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/classification")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        List<JsonNode> dimensions = new ArrayList<>();
        json.readTree(payload).forEach(dimensions::add);
        return dimensions;
    }

    private UUID dimensionId(UUID userId, UUID companyId, UUID projectId, String key)
            throws Exception {

        return dimensionsOf(userId, companyId, projectId).stream()
                .filter(dimension -> dimension.get("key").asText().equals(key))
                .findFirst()
                .map(dimension -> UUID.fromString(dimension.get("id").asText()))
                .orElseThrow(() -> new AssertionError("no dimension keyed '" + key + "'"));
    }

    private List<JsonNode> values(UUID userId, UUID projectId, String key) throws Exception {
        List<JsonNode> values = new ArrayList<>();
        dimensionsOf(userId, companyOf(projectId), projectId).stream()
                .filter(dimension -> dimension.get("key").asText().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dimension keyed '" + key + "'"))
                .get("values")
                .forEach(values::add);
        return values;
    }

    private List<JsonNode> featureValues(UUID userId) throws Exception {
        return values(userId, BANKING, "feature");
    }

    private JsonNode node(MockHttpServletResponse result) throws Exception {
        return json.readTree(result.getContentAsString());
    }

    private UUID idOf(MockHttpServletResponse result) throws Exception {
        assertThat(result.getStatus())
                .as("the fixture's own create failed: %s", result.getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(node(result).get("id").asText());
    }

    private static JsonNode valueById(List<JsonNode> values, UUID id) {
        return values.stream()
                .filter(value -> value.get("id").asText().equals(id.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no value with id " + id));
    }

    private static JsonNode valueByName(List<JsonNode> values, String name) {
        return values.stream()
                .filter(value -> value.get("name").asText().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no value named '" + name + "'"));
    }

    private static List<String> idsOf(List<JsonNode> values) {
        return values.stream().map(value -> value.get("id").asText()).toList();
    }

    private List<String> errorFields(MockHttpServletResponse result) throws Exception {
        JsonNode errors = node(result).get("errors");
        assertThat(errors)
                .as("expected a validation problem, got %s",
                        result.getContentAsString())
                .isNotNull();
        List<String> fields = new ArrayList<>();
        errors.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private List<String> messagesFor(MockHttpServletResponse result, String field) throws Exception {
        List<String> messages = new ArrayList<>();
        node(result).get("errors").get(field).forEach(message -> messages.add(message.asText()));
        return messages;
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

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {

        UUID id = UUID.nameUUIDFromBytes(("classification-value-write:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantOnCompany(
            String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {

        UUID id = UUID.nameUUIDFromBytes(("classification-value-write:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    /** The vocabularies through the V2 seed functions — the only sanctioned writers of dimensions. */
    private void seedVocabularies(UUID companyId, List<UUID> projectIds) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
                statement.setObject(1, companyId);
                statement.execute();
            }
            for (UUID projectId : projectIds) {
                try (var statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                    statement.setObject(1, companyId);
                    statement.setObject(2, projectId);
                    statement.execute();
                }
            }
        }
    }
}
