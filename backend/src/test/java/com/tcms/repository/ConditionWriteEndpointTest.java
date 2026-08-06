package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST} and {@code PUT /projects/{projectId}/conditions} — the writers §95 gave the library.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>An {@code If-Match} that cannot fail.</strong> {@code condition.lock_version} has
 *       existed since {@code V1} and was never mapped; §74 found the sibling spelling — mapped
 *       <em>without</em> {@code @Version} — on three other entities, where the column reads
 *       {@code 0} for ever and a stale precondition is therefore indistinguishable from a current
 *       one. An endpoint shipping that is one whose concurrency check is decorative, and every
 *       happy-path test passes. {@link Editing#theVersionMovesOnEveryEdit} and
 *       {@link Editing#aStaleIfMatchIsRefused} are a <strong>pair</strong>: the first makes the
 *       second able to fail, because a version that never moves makes every {@code If-Match} match.
 *   </li>
 *   <li><strong>An edit that does not propagate.</strong> The entire reason this object exists is
 *       that an attachment is a reference — {@link Editing#theEditReachesEveryAttachedCase} reads
 *       the change back through a <em>case</em>, not through the library, because a library read
 *       proves only that the row was written.</li>
 *   <li><strong>A writer resolving by id alone.</strong> {@code findById} is RLS-scoped to the
 *       company, so it accepts a sibling project's row — §64/§68/§69's family. Here that would let
 *       a caller edit a library they hold no grant on, and the edit would land on every case in the
 *       other project.</li>
 *   <li><strong>Validation relaxed on the edit.</strong> A rule enforced on create and skipped on
 *       update lets an entry reach, across hundreds of attached cases, a state the create refused.
 *   </li>
 * </ol>
 *
 * <h2>Personas</h2>
 *
 * <p>{@code LEAD} holds {@code library:manage} through Test Lead; {@code VIEWER} is granted on the
 * same project and holds no {@code library:manage}, which is what makes the 403 assertions
 * non-vacuous — a persona with no grant at all would 404 one gate earlier and prove nothing about
 * the permission. {@code OUTSIDER} is in another company entirely.
 *
 * <p>Company and user ids are unique to this class (prefix {@code c9}, computed against the whole
 * test tree as free). Sharing them with another class is how handoff §19's defect happened — green
 * alone, red in the full suite — and it has recurred five times since.
 */
@AutoConfigureMockMvc
@DisplayName("Condition writers — library:manage, and an If-Match that can actually fail")
class ConditionWriteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c9000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("c9000000-0000-4000-8000-00000000000b");

    private static final UUID BANKING = UUID.fromString("c9001000-0000-4000-8000-000000000001");
    /** Same company, LEAD holds no grant — the project a write must not reach. */
    private static final UUID MOBILE = UUID.fromString("c9001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("c9002000-0000-4000-8000-000000000001");

    /** Test Lead on BANKING. Holds library:manage. */
    private static final UUID LEAD = UUID.fromString("c9003000-0000-4000-8000-000000000001");
    /** Viewer on BANKING. Reaches the project, holds no library:manage — the 403 persona. */
    private static final UUID VIEWER = UUID.fromString("c9003000-0000-4000-8000-000000000002");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("c9003000-0000-4000-8000-000000000003");

    /** Attached to a case, so an edit to it is observable from outside the library. */
    private static final UUID ATTACHED = UUID.fromString("c9004000-0000-4000-8000-000000000001");
    /** Retired — editable only if the writer forgets {@code deleted_at}. */
    private static final UUID RETIRED = UUID.fromString("c9004000-0000-4000-8000-000000000002");
    /** In MOBILE, which LEAD cannot reach. The sibling-project row. */
    private static final UUID FOREIGN = UUID.fromString("c9004000-0000-4000-8000-000000000003");

    /** A second live entry, so ORDER is observable rather than a list of one. */
    private static final UUID SECOND = UUID.fromString("c9004000-0000-4000-8000-000000000004");

    private static final UUID SUITE = UUID.fromString("c9005000-0000-4000-8000-000000000001");
    private static final UUID CASE = UUID.fromString("c9006000-0000-4000-8000-000000000001");

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

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "cw-acme", "Acme", now);
            company(UMBRA, "cw-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cw-acme.test", now);
            activeUser(VIEWER, "viewer@cw-acme.test", now);
            activeUser(OUTSIDER, "engineer@cw-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "CWOLB", "Online Banking", now);
            seedProject(MOBILE, ACME, "CWMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);

            grantOnProject("cw-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grantOnProject("cw-viewer", ACME, Role.VIEWER_ID, VIEWER, BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CWUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("cw-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedLibrary();
    }

    // =====================================================================
    // create
    // =====================================================================

    @Nested
    @DisplayName("Creating — library:manage, and both counts start at zero")
    class Creating {

        @Test
        @DisplayName("a valid create answers 201 with the entry")
        void createsAnEntry() throws Exception {
            JsonNode created = created(post(LEAD, BANKING, body(
                    "Seed a captured payment", "PRE", List.of("Insert the payment", "Settle it"))));

            assertThat(created.get("title").asText()).isEqualTo("Seed a captured payment");
            assertThat(created.get("type").asText()).isEqualTo("PRE");
            assertThat(stringsIn(created.get("body")))
                    .containsExactly("Insert the payment", "Settle it");
        }

        @Test
        @DisplayName("a new entry is attached by nothing, so both counts are zero and lockVersion is 0")
        void startsUnattached() throws Exception {
            JsonNode created = created(post(LEAD, BANKING, body(
                    "Open the console", "PRE", List.of("Navigate to /admin"))));

            // Passed as literals by the service rather than re-queried, since nothing can attach an
            // entry created in the same transaction. Asserted anyway because "known to be zero" and
            // "happens to be zero" are the same value and different code.
            assertThat(created.get("usageCount").asInt()).isZero();
            assertThat(created.get("suiteUsageCount").asInt()).isZero();
            assertThat(created.get("lockVersion").asLong()).isZero();
        }

        @Test
        @DisplayName("the entry is in the library afterwards, in the project it was created in")
        void landsInTheLibrary() throws Exception {
            JsonNode created = created(post(LEAD, BANKING, body(
                    "Restore the merchant balance", "BOTH", List.of("Run the restore job"))));

            assertThat(titlesOf(libraryAs(LEAD, BANKING)))
                    .contains("Restore the merchant balance");
            assertThat(idsOf(libraryAs(LEAD, BANKING))).contains(created.get("id").asText());
        }

        @Test
        @DisplayName("blank body lines are dropped, and the surviving order is kept")
        void dropsBlankBodyLines() throws Exception {
            // The editor appends an empty row as you type, so a trailing blank arrives on almost
            // every submit. Storing it puts an empty line into every case attaching the entry.
            JsonNode created = created(post(LEAD, BANKING, body(
                    "Trailing blanks", "PRE", List.of("First", "   ", "Second", ""))));

            assertThat(stringsIn(created.get("body"))).containsExactly("First", "Second");
        }

        @Test
        @DisplayName("a body that is ONLY blank lines is refused, not stored empty")
        void refusesAnAllBlankBody() throws Exception {
            // The other half of the rule above, and it must be the other half: dropping these
            // silently would store an empty block, and an empty condition is a picker row that
            // attaches nothing and cannot be told apart from one that failed to load.
            mvc.perform(post(LEAD, BANKING, body("Nothing in it", "PRE", List.of("  ", ""))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.body").exists());
        }

        @Test
        @DisplayName("a missing title and a missing type are field errors, not defaults")
        void refusesMissingFields() throws Exception {
            // type in particular is NOT defaulted to BOTH: an entry typed BOTH appears in both
            // pickers, so a caller who forgot the field would get an entry offered as a
            // postcondition it was never written to be.
            String payload = json.writeValueAsString(Map.of("body", List.of("Something")));

            mvc.perform(post(LEAD, BANKING, payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.title").exists())
                    .andExpect(jsonPath("$.errors.type").exists());
        }

        @Test
        @DisplayName("a reader without library:manage is refused 403, having reached the project")
        void refusesAViewer() throws Exception {
            // VIEWER can read this project, so the refusal is about the permission and nothing
            // else. A persona with no grant would 404 one gate earlier and prove nothing here.
            mvc.perform(post(VIEWER, BANKING, body("Anything", "PRE", List.of("Do it"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unreachable project is 404, never 403 — the gate discloses nothing")
        void unreachableProjectIsNotFound() throws Exception {
            mvc.perform(post(LEAD, MOBILE, body("Anything", "PRE", List.of("Do it"))))
                    .andExpect(status().isNotFound());
            mvc.perform(post(OUTSIDER, BANKING, body("Anything", "PRE", List.of("Do it"))))
                    .andExpect(status().isNotFound());
        }
    }

    // =====================================================================
    // edit
    // =====================================================================

    @Nested
    @DisplayName("Editing — the change propagates, and the precondition can fail")
    class Editing {

        @Test
        @DisplayName("a valid edit answers 200 with the new content")
        void editsAnEntry() throws Exception {
            JsonNode updated = ok(put(LEAD, BANKING, ATTACHED, 0L, body(
                    "Log in as an auditor", "PRE", List.of("Open the console", "Use the audit role"))));

            assertThat(updated.get("title").asText()).isEqualTo("Log in as an auditor");
            assertThat(stringsIn(updated.get("body")))
                    .containsExactly("Open the console", "Use the audit role");
        }

        @Test
        @DisplayName("the version moves on every edit — without which no If-Match can ever fail")
        void theVersionMovesOnEveryEdit() throws Exception {
            // The half that makes aStaleIfMatchIsRefused able to fail. lock_version was mapped for
            // the first time in §95, WITH @Version: §74 found the plain-column spelling on three
            // entities, where this assertion is what would have caught it.
            JsonNode first = ok(put(LEAD, BANKING, ATTACHED, 0L, body(
                    "First edit", "PRE", List.of("Step"))));
            assertThat(first.get("lockVersion").asLong()).isEqualTo(1L);

            JsonNode second = ok(put(LEAD, BANKING, ATTACHED, 1L, body(
                    "Second edit", "PRE", List.of("Step"))));
            assertThat(second.get("lockVersion").asLong()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a stale If-Match is refused with 409, and the entry is left alone")
        void aStaleIfMatchIsRefused() throws Exception {
            ok(put(LEAD, BANKING, ATTACHED, 0L, body("Moved on", "PRE", List.of("Step"))));

            mvc.perform(put(LEAD, BANKING, ATTACHED, 0L, body(
                            "Written over the top", "PRE", List.of("Step"))))
                    .andExpect(status().isConflict());

            // The refusal has to be a refusal, not a 409 issued after the write landed.
            assertThat(byId(libraryAs(LEAD, BANKING), ATTACHED).get("title").asText())
                    .isEqualTo("Moved on");
        }

        @Test
        @DisplayName("an absent If-Match is last-write-wins, deliberately")
        void anAbsentIfMatchSkipsTheCheck() throws Exception {
            // Correct for a caller with no prior read (an importer) and wrong for an editor, which
            // is why the SPA always sends it. Asserted so that "the header is optional" stays a
            // decision rather than becoming an accident of binding.
            ok(put(LEAD, BANKING, ATTACHED, 0L, body("Moved on", "PRE", List.of("Step"))));

            JsonNode updated = ok(put(LEAD, BANKING, ATTACHED, null, body(
                    "Overwritten knowingly", "PRE", List.of("Step"))));

            assertThat(updated.get("title").asText()).isEqualTo("Overwritten knowingly");
        }

        @Test
        @DisplayName("the edit reaches every case attached to it — read back through the CASE")
        void theEditReachesEveryAttachedCase() throws Exception {
            // The property the whole object exists for. Read through the case detail rather than
            // through the library, because a library read proves only that the row was written —
            // it cannot tell a reference from a copy, which is the one distinction that matters.
            ok(put(LEAD, BANKING, ATTACHED, 0L, body(
                    "Log in as an auditor", "PRE", List.of("Open the console"))));

            JsonNode detail = caseDetail(LEAD, BANKING, CASE);
            JsonNode precondition = detail.get("preconditions").get(0);

            assertThat(precondition.get("title").asText()).isEqualTo("Log in as an auditor");
            assertThat(stringsIn(precondition.get("body"))).containsExactly("Open the console");
        }

        @Test
        @DisplayName("the served counts reflect the attachments, not the stored column")
        void servesTheDerivedCounts() throws Exception {
            JsonNode updated = ok(put(LEAD, BANKING, ATTACHED, 0L, body(
                    "Still attached", "PRE", List.of("Step"))));

            // The fixture stores usage_count = 99 on this row and attaches exactly one live case.
            assertThat(updated.get("usageCount").asInt()).isEqualTo(1);
            assertThat(updated.get("suiteUsageCount").asInt()).isZero();
        }

        @Test
        @DisplayName("validation is the SAME on the edit as on the create")
        void validatesIdenticallyToCreate() throws Exception {
            // A rule enforced on create and relaxed here would let an entry reach — across every
            // case attaching it — a state the create refused.
            mvc.perform(put(LEAD, BANKING, ATTACHED, 0L, body("Empty now", "PRE", List.of(" "))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.body").exists());
        }

        @Test
        @DisplayName("a retired entry is 404, not an editable row")
        void refusesARetiredEntry() throws Exception {
            mvc.perform(put(LEAD, BANKING, RETIRED, 0L, body("Back from the dead", "PRE",
                            List.of("Step"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a sibling project's entry is 404 — findById would have accepted it")
        void refusesASiblingProjectsEntry() throws Exception {
            // FOREIGN lives in MOBILE, in the SAME company, so the Hibernate company filter and RLS
            // both pass it. Only the projectId predicate on findLiveInProject refuses it. Resolving
            // by id alone here would let LEAD edit a library they hold no grant on, and the edit
            // would land on every case in that project.
            mvc.perform(put(LEAD, BANKING, FOREIGN, 0L, body("Reached across", "PRE",
                            List.of("Step"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an unknown id is 404 and looks exactly like the two refusals above")
        void unknownIdIsNotFound() throws Exception {
            mvc.perform(put(LEAD, BANKING, UUID.randomUUID(), 0L, body("Ghost", "PRE",
                            List.of("Step"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a reader without library:manage is refused 403")
        void refusesAViewer() throws Exception {
            mvc.perform(put(VIEWER, BANKING, ATTACHED, 0L, body("Anything", "PRE", List.of("Step"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("permission is checked before existence — a Viewer is not told what exists")
        void permissionOutranksExistence() throws Exception {
            // A Viewer naming a nonexistent id must get the SAME answer as a Viewer naming a real
            // one, or the refusal becomes an existence oracle for a library they may not manage.
            mvc.perform(put(VIEWER, BANKING, UUID.randomUUID(), 0L, body("Ghost", "PRE",
                            List.of("Step"))))
                    .andExpect(status().isForbidden());
        }
    }


    // =====================================================================
    // attaching — §95 phase 2
    // =====================================================================

    @Nested
    @DisplayName("Attaching — the case editor's half, and the field that used to 400")
    class Attaching {

        @Test
        @DisplayName("preconditions are accepted where they used to be refused with a 400")
        void acceptsPreconditions() throws Exception {
            JsonNode saved = patchCase(caseBody(List.of(ATTACHED), List.of()));

            assertThat(refTitles(saved.get("preconditions")))
                    .containsExactly("Log in as standard user");
        }

        @Test
        @DisplayName("payload order is the stored order, and re-sending it reversed reorders them")
        void payloadOrderIsTheOrder() throws Exception {
            // Position is never sent — it is assigned from the array's order — so this is the only
            // way an author can express a procedure, and the only thing that can prove it works.
            patchCase(caseBody(List.of(ATTACHED, SECOND), List.of()));
            assertThat(refTitles(caseDetail(LEAD, BANKING, CASE).get("preconditions")))
                    .containsExactly("Log in as standard user", "Navigate to Billing");

            patchCase(caseBody(List.of(SECOND, ATTACHED), List.of()));
            assertThat(refTitles(caseDetail(LEAD, BANKING, CASE).get("preconditions")))
                    .containsExactly("Navigate to Billing", "Log in as standard user");
        }

        @Test
        @DisplayName("an empty list detaches everything; the field being ABSENT leaves it alone")
        void absentAndEmptyAreDifferentInstructions() throws Exception {
            patchCase(caseBody(List.of(ATTACHED), List.of()));

            // Absent — the bulk actions' shape, and the one that must not be read as "detach".
            JsonNode untouched = patchCase(caseBody(null, null));
            assertThat(refTitles(untouched.get("preconditions")))
                    .containsExactly("Log in as standard user");

            // Empty — an author removing the last row.
            JsonNode cleared = patchCase(caseBody(List.of(), List.of()));
            assertThat(cleared.get("preconditions")).isEmpty();
        }

        @Test
        @DisplayName("the same entry may be BOTH a pre- and a postcondition")
        void theSameEntryMayBeBothKinds() throws Exception {
            // Setup that is also cleanup — "reset the payment data" is the canonical case. The two
            // lists are normalized separately and never checked against each other.
            JsonNode saved = patchCase(caseBody(List.of(ATTACHED), List.of(ATTACHED)));

            assertThat(refTitles(saved.get("preconditions")))
                    .containsExactly("Log in as standard user");
            assertThat(refTitles(saved.get("postconditions")))
                    .containsExactly("Log in as standard user");
        }

        @Test
        @DisplayName("a repeat WITHIN one list is collapsed, not refused")
        void repeatsWithinOneListCollapse() throws Exception {
            JsonNode saved = patchCase(caseBody(List.of(ATTACHED, ATTACHED), List.of()));

            assertThat(refTitles(saved.get("preconditions")))
                    .containsExactly("Log in as standard user");
        }

        @Test
        @DisplayName("a sibling project's condition is a field error — the FK would have taken it")
        void refusesASiblingProjectsCondition() throws Exception {
            // FOREIGN is in MOBILE, same company, so condition_id's foreign key is satisfied and
            // nothing below the validator would have caught it. Stored, every later read of this
            // case would resolve a condition through a project the reader holds no grant on.
            mvc.perform(patch(caseBody(List.of(FOREIGN), List.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preconditions").exists());
        }

        @Test
        @DisplayName("a retired condition cannot be newly attached")
        void refusesARetiredCondition() throws Exception {
            mvc.perform(patch(caseBody(List.of(RETIRED), List.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preconditions").exists());
        }

        @Test
        @DisplayName("an inline body is refused rather than stored where nothing can render it")
        void refusesAnInlineBody() throws Exception {
            Map<String, Object> inline = new LinkedHashMap<>();
            inline.put("inlineBody", List.of("Do it by hand"));

            mvc.perform(patch(caseBodyRaw(List.of(inline), List.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preconditions").exists());
        }

        @Test
        @DisplayName("attaching then editing the entry propagates — the two halves of §95 together")
        void attachThenEditPropagates() throws Exception {
            patchCase(caseBody(List.of(ATTACHED), List.of()));

            ok(put(LEAD, BANKING, ATTACHED, 0L, body(
                    "Log in as an auditor", "PRE", List.of("Open the console"))));

            assertThat(refTitles(caseDetail(LEAD, BANKING, CASE).get("preconditions")))
                    .containsExactly("Log in as an auditor");
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private String body(String title, String type, List<String> lines) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("type", type);
        payload.put("body", lines);
        return json.writeValueAsString(payload);
    }

    private MockHttpServletRequestBuilder post(UUID userId, UUID projectId, String payload) {
        return MockMvcRequestBuilders
                .post("/api/v1/projects/{projectId}/conditions", projectId)
                .header("Authorization", "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    private MockHttpServletRequestBuilder put(
            UUID userId, UUID projectId, UUID conditionId, Long ifMatch, String payload) {

        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .put("/api/v1/projects/{projectId}/conditions/{conditionId}", projectId, conditionId)
                .header("Authorization", "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);

        return ifMatch == null ? request : request.header("If-Match", ifMatch.toString());
    }

    private JsonNode created(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode libraryAs(UUID userId, UUID projectId) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/{projectId}/conditions", projectId)
                        .header("Authorization", "Bearer " + token(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode caseDetail(UUID userId, UUID projectId, UUID caseId) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/{projectId}/cases/{caseId}", projectId, caseId)
                        .header("Authorization", "Bearer " + token(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /**
     * A session for the persona, in their own company.
     *
     * <p>The company comes from the token and never from the request, which is the whole of ERD
     * §0.1 point 2 — so OUTSIDER's session carries Umbra and cannot be pointed at Acme by naming an
     * Acme project in the path. That is what makes the cross-company 404 a real assertion rather
     * than a check that a UUID did not happen to match.
     */

    /** A full editable case, since the endpoint requires the whole set on every save. */
    private String caseBody(List<UUID> preconditions, List<UUID> postconditions) throws Exception {
        return caseBodyRaw(refs(preconditions), refs(postconditions));
    }

    private static List<Map<String, Object>> refs(List<UUID> ids) {
        if (ids == null) {
            return null;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (UUID id : ids) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("conditionId", id.toString());
            entries.add(entry);
        }
        return entries;
    }

    private String caseBodyRaw(Object preconditions, Object postconditions) throws Exception {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "Do the thing");
        step.put("expectedResult", "It happened");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Refund a settled payment");
        payload.put("suiteId", SUITE.toString());
        payload.put("stepFormat", "STRUCTURED");
        // The endpoint requires the whole editable set on every save — absent and null are the same
        // Java value here, so it cannot tell "leave it" from "clear it" and asks for all of them.
        payload.put("priority", "MEDIUM");
        payload.put("automationStatus", "MANUAL");
        payload.put("steps", List.of(step));
        if (preconditions != null) {
            payload.put("preconditions", preconditions);
        }
        if (postconditions != null) {
            payload.put("postconditions", postconditions);
        }
        return json.writeValueAsString(payload);
    }

    private MockHttpServletRequestBuilder patch(String payload) {
        return MockMvcRequestBuilders
                .patch("/api/v1/projects/{projectId}/cases/{caseId}", BANKING, CASE)
                .header("Authorization", "Bearer " + token(LEAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    /** Saves and returns the case, failing loudly rather than letting a 400 read as an empty list. */
    private JsonNode patchCase(String payload) throws Exception {
        return json.readTree(mvc.perform(patch(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> refTitles(JsonNode array) {
        List<String> titles = new ArrayList<>();
        array.forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    private String token(UUID userId) {
        UUID companyId = userId.equals(OUTSIDER) ? UMBRA : ACME;
        return tx.execute(ignored -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static List<String> titlesOf(JsonNode array) {
        return valuesOf(array, "title");
    }

    private static List<String> idsOf(JsonNode array) {
        return valuesOf(array, "id");
    }

    private static List<String> valuesOf(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }

    private static JsonNode byId(JsonNode array, UUID id) {
        for (JsonNode node : array) {
            if (node.get("id").asText().equals(id.toString())) {
                return node;
            }
        }
        throw new AssertionError("no entry with id " + id);
    }

    private static List<String> stringsIn(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
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
        UUID id = UUID.nameUUIDFromBytes(("condition-write:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * The rows the writers act on, written as raw SQL through the ordinary app role — the same
     * policed path production reads through, so a fixture that could not be written under RLS is a
     * fixture that is lying about what the endpoint will see.
     *
     * <p>{@code ATTACHED} carries a stored {@code usage_count} of 99 against exactly one live
     * attachment, so {@link Editing#servesTheDerivedCounts} separates the derived answer from the
     * column in one assertion.
     */
    private void seedLibrary() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, ATTACHED, BANKING, ACME, "Log in as standard user", "PRE",
                    "[\"Open the application\"]", 99, false);
            condition(connection, RETIRED, BANKING, ACME, "Log in as the old admin", "PRE",
                    "[\"Open the legacy console\"]", 0, true);
            condition(connection, FOREIGN, MOBILE, ACME, "A sibling project's entry", "PRE",
                    "[\"Do something else\"]", 0, false);
            condition(connection, SECOND, BANKING, ACME, "Navigate to Billing", "PRE",
                    "[\"Open the account menu\"]", 0, false);

            suite(connection, SUITE, BANKING);
            testCase(connection, CASE, BANKING);
            caseCondition(connection, "c9007000-0000-4000-8000-000000000001", CASE, ATTACHED);
        }
    }

    private void condition(
            Connection connection, UUID id, UUID projectId, UUID companyId, String title,
            String type, String body, int usageCount, boolean retired) throws SQLException {

        // DO UPDATE, not DO NOTHING — and that is the difference between a deterministic fixture
        // and one that depends on test order. These tests WRITE, the rows outlive the method
        // (nothing rolls back a MockMvc request), and lock_version therefore climbs across the
        // class: the first edit leaves ATTACHED at version 1, so every later `If-Match: 0` is a
        // legitimate 409 and six tests fail for a reason that has nothing to do with what they
        // assert. Resetting the whole row — content AND version — is what makes each test start
        // from the state its assertions are written against.
        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, usage_count, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS jsonb), ?, CAST(? AS timestamptz))
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    type = EXCLUDED.type,
                    body = EXCLUDED.body,
                    usage_count = EXCLUDED.usage_count,
                    deleted_at = EXCLUDED.deleted_at,
                    lock_version = 0
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, body);
            statement.setInt(7, usageCount);
            statement.setString(8, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, "CWOLB-S1");
            statement.setLong(5, 1L);
            // ltree labels allow only [A-Za-z0-9_], so the hyphens have to come out.
            statement.setString(6, "Payments");
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, SUITE.toString());
            statement.setString(5, "CWOLB-1");
            statement.setLong(6, 1L);
            statement.setString(7, "Refund a settled payment");
            statement.executeUpdate();
        }
    }

    private void caseCondition(Connection connection, String id, UUID caseId, UUID conditionId)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_condition
                    (id, company_id, case_id, kind, position, condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id);
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, "PRE");
            statement.setInt(5, 0);
            statement.setString(6, conditionId.toString());
            statement.executeUpdate();
        }
    }
}
