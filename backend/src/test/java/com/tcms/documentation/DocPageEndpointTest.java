package com.tcms.documentation;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code documentation} module end to end — the page tree, one page, and the four writes.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>An edit that re-issues block ids.</strong> The one that matters. Delete-and-recreate
 *       is the obvious implementation of "replace the blocks" and it passes every assertion about
 *       text, order and type — while voiding every {@code doc_link} on the page, silently, because
 *       the links cascade away rather than dangling. {@link BlockIdentity} is written so that
 *       exactly this mutation goes red and little else does.</li>
 *   <li><strong>A removal that takes links with it.</strong> The 409 has to happen <em>before</em>
 *       anything is written, so the test asserts the refusal <em>and</em> that the other blocks were
 *       not rewritten.</li>
 *   <li><strong>The two permissions collapsed into one.</strong> {@code doc:edit} is held by Test
 *       Engineer and {@code doc:publish} is not. Gate publishing on {@code doc:edit} and only
 *       {@link Gates#engineerCannotPublish} goes red — every other assertion about publishing still
 *       passes, because a lead can still do it.</li>
 *   <li><strong>A path or slug derived from the title.</strong> Both are set at creation from the
 *       key sequence and must survive a retitle; a title-derived path breaks subtree matching on the
 *       first rename, and does it quietly.</li>
 *   <li><strong>A publish that writes a version.</strong> Nobody authored anything, and a history
 *       whose numbers count publications no longer counts edits.</li>
 *   <li><strong>A created page with no v1</strong> — §29's defect on cases, one entity over.</li>
 *   <li><strong>{@code coverage} serialized as zeros.</strong> "Not tested" is a specific and
 *       alarming claim to make about every paragraph in the product on the strength of a question
 *       nobody has asked.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code dc…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 and §54 each hit it again.
 */
@AutoConfigureMockMvc
@DisplayName("Documentation endpoints — the page tree, the page, and the writes that fill it")
class DocPageEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("dc000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("dc000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. Carries the seeded pages. */
    private static final UUID ACME_BANKING = UUID.fromString("dc001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the 404-not-403 fixture. */
    private static final UUID ACME_LOCKED = UUID.fromString("dc001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("dc002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds {@code doc:edit} AND {@code doc:publish}. */
    private static final UUID LEAD = UUID.fromString("dc003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. Holds {@code doc:edit} and <strong>not</strong> {@code doc:publish} — the one
     * persona that separates the two permissions, and the reason this class has two writers.
     */
    private static final UUID ENGINEER = UUID.fromString("dc003000-0000-4000-8000-000000000002");
    /** Viewer. Holds {@code doc:read} and neither write permission. */
    private static final UUID VIEWER = UUID.fromString("dc003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("dc003000-0000-4000-8000-000000000004");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("dc003000-0000-4000-8000-000000000005");

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
            company(ACME, "dc-acme", "Acme", now);
            company(UMBRA, "dc-umbra", "Umbra", now);

            activeUser(LEAD, "lead@dc-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@dc-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@dc-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@dc-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@dc-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "DCOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "DCLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("dc-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("dc-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("dc-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "DCUMB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("dc-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedProjectDefaults();
    }

    /**
     * Puts back everything a test can write, counters included.
     *
     * <p>The counter reset is the part that surprises: {@code tcms_seed_company_defaults} is
     * {@code ON CONFLICT DO NOTHING}, correct for provisioning and therefore no reset. Without this
     * every {@code DOC-n} assertion would depend on the order the class ran in — green alone and red
     * in the suite, which is handoff §19's defect again.
     *
     * <p>{@code doc_link} is deleted first: it references {@code doc_block}, and the tests that
     * write one leave it behind for the next.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // Through each company's OWN context: RLS means the other's connection could not
                // see these rows to delete them, which is isolation working rather than a leftover.
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM doc_link");
                execute(connection, "DELETE FROM doc_page_version");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    /**
     * Runs the seed function, which since {@code V18} is what creates the {@code doc_space}.
     *
     * <p>Every create in this class depends on it, so a failure here would surface as
     * "documentation is broken" rather than "the space is missing" — which is exactly why
     * {@link DocSpaceRepository#requireForProject} throws a sentence naming the repair.
     */
    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }
    }

    // =====================================================================
    // V18 — the row that had no writer
    // =====================================================================

    @Nested
    @DisplayName("V18 — the documentation space")
    class Space {

        @Test
        @DisplayName("seeding a project creates exactly one space, and re-seeding creates no second")
        void theSeedIsIdempotent() throws SQLException {
            assertThat(spaceCount(ACME, ACME_BANKING)).isEqualTo(1);

            try (Connection connection = appConnection(ACME)) {
                seedProjectDefaults(connection, ACME, ACME_BANKING);
                seedProjectDefaults(connection, ACME, ACME_BANKING);
            }

            // provision-company is idempotent and is the supported way to top a company up, so the
            // function runs repeatedly against projects that already have everything. A second
            // space would put a project's pages in two containers with nothing choosing between
            // them.
            assertThat(spaceCount(ACME, ACME_BANKING)).isEqualTo(1);
        }

        @Test
        @DisplayName("a page can be created straight after provisioning, with no hand-written SQL")
        void aProvisionedProjectIsImmediatelyUsable() throws Exception {
            // The whole point of the migration. Before it, doc_page.space_id had no value to take
            // and the first create was a foreign-key violation on a table nobody had noticed.
            assertThat(create(LEAD, ACME_BANKING, page("First", null)).getResponse().getStatus())
                    .isEqualTo(201);
        }
    }

    // =====================================================================
    // The gates
    // =====================================================================

    @Nested
    @DisplayName("The gates — doc:edit to write, doc:publish to publish")
    class Gates {

        @Test
        @DisplayName("a Test Lead creates a page and gets 201 with the whole page")
        void leadCanCreate() throws Exception {
            MvcResult result = create(LEAD, ACME_BANKING, page("Refund policy", null));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("title").asText()).isEqualTo("Refund policy");
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/doc-pages/"
                            + body(result).get("id").asText());
        }

        @Test
        @DisplayName("a Test Engineer may create and edit — writing specifications is not restricted")
        void engineerCanEdit() throws Exception {
            MvcResult created = create(ENGINEER, ACME_BANKING, page("Engineer's", null));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);

            UUID id = idOf(created);
            assertThat(edit(ENGINEER, ACME_BANKING, id, page("Retitled", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Engineer may NOT publish — the two permissions are genuinely two")
        void engineerCannotPublish() throws Exception {
            UUID id = idOf(create(ENGINEER, ACME_BANKING, page("Draft", null)));

            // THE assertion of this nest. Gate publishing on doc:edit and this is the only test in
            // the suite that goes red.
            assertThat(setStatus(ENGINEER, ACME_BANKING, id, "PUBLISHED").getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(statusOf(ACME, id)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("the same Engineer, refused the publish, DOES reach the edit")
        void theRefusalIsThePermissionAndNotABrokenRoute() throws Exception {
            // Without this the test above passes just as well against a route that does not exist,
            // or a caller whose token is wrong.
            UUID id = idOf(create(ENGINEER, ACME_BANKING, page("Draft", null)));
            assertThat(edit(ENGINEER, ACME_BANKING, id, page("Still writable", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Lead may publish")
        void leadCanPublish() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Ready", null)));

            assertThat(setStatus(LEAD, ACME_BANKING, id, "PUBLISHED").getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(statusOf(ACME, id)).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("a Viewer may read the tree and a page, and write nothing")
        void viewerIsRefusedEveryWrite() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Readable", null)));

            assertThat(tree(VIEWER, ACME_BANKING).getResponse().getStatus())
                    .as("the read gate every role passes — otherwise the 403s below prove nothing")
                    .isEqualTo(200);
            assertThat(read(VIEWER, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(200);

            assertThat(create(VIEWER, ACME_BANKING, page("Nope", null))
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(edit(VIEWER, ACME_BANKING, id, page("Nope", null), null)
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(setStatus(VIEWER, ACME_BANKING, id, "PUBLISHED")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(delete(VIEWER, ACME_BANKING, id)
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = create(VIEWER, ACME_BANKING, page("Nope", null))
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves, "
                            + "and naming it publishes the catalogue to exactly that caller (§32)")
                    .doesNotContain("doc:edit")
                    .doesNotContain("doc:publish");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404 on every verb, never 403")
        void membershipConveysNoAccess() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Hidden", null)));

            // The rows are in this caller's own company, so no cross-company assertion reaches
            // them. A 403 here would confirm the project id names something real.
            assertThat(tree(NO_GRANT, ACME_BANKING).getResponse().getStatus()).isEqualTo(404);
            assertThat(read(NO_GRANT, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(404);
            assertThat(create(NO_GRANT, ACME_BANKING, page("Nope", null))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(edit(NO_GRANT, ACME_BANKING, id, page("Nope", null), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(setStatus(NO_GRANT, ACME_BANKING, id, "PUBLISHED")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(delete(NO_GRANT, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent projects answer 404 with no body")
        void theThreeUnreachableCasesAnswerIdentically() throws Exception {
            UUID nowhere = UUID.fromString("dc009000-0000-4000-8000-000000000099");

            for (UUID projectId : List.of(ACME_LOCKED, UMBRA_BANKING, nowhere)) {
                MvcResult result = tree(LEAD, projectId);
                assertThat(result.getResponse().getStatus())
                        .as("project %s", projectId).isEqualTo(404);
                assertThat(result.getResponse().getContentAsString()).isEmpty();
            }
        }
    }

    // =====================================================================
    // Block identity — the decision the module is built around
    // =====================================================================

    @Nested
    @DisplayName("Block identity — an edited block keeps its id (plan §6i/§6m)")
    class BlockIdentity {

        @Test
        @DisplayName("fixing a typo in a heading does not re-issue the block's id")
        void anEditedBlockKeepsItsId() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Refunds",
                    block(null, "heading", 1, "Full refudns"),
                    block(null, "paragraph", null, "A refund is issued within 5 days.")));

            List<JsonNode> before = blocksOf(created);
            UUID headingId = UUID.fromString(before.get(0).get("id").asText());
            UUID paragraphId = UUID.fromString(before.get(1).get("id").asText());

            MvcResult edited = edit(LEAD, ACME_BANKING, idOf(created), pageWithBlocks("Refunds",
                    block(headingId, "heading", 1, "Full refunds"),
                    block(paragraphId, "paragraph", null,
                            "A refund is issued within 5 days.")), null);

            List<JsonNode> after = blocksOf(edited);

            // THE assertion. Delete-and-recreate passes every other check in this class: the text is
            // right, the order is right, the type is right. It fails here, and only here.
            assertThat(after.get(0).get("id").asText()).isEqualTo(headingId.toString());
            assertThat(after.get(0).get("text").asText()).isEqualTo("Full refunds");
            assertThat(after.get(1).get("id").asText()).isEqualTo(paragraphId.toString());
        }

        @Test
        @DisplayName("a block whose TYPE changed also keeps its id")
        void promotingAParagraphToAHeadingKeepsTheLink() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Refunds")));
            UUID blockId = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            MvcResult edited = edit(LEAD, ACME_BANKING, idOf(created),
                    pageWithBlocks("Doc", block(blockId, "heading", 2, "Refunds")), null);

            // Turning a paragraph into a heading is a formatting change somebody makes while
            // writing. Forcing it through delete-and-recreate would drop every link for it.
            assertThat(blocksOf(edited).get(0).get("id").asText()).isEqualTo(blockId.toString());
            assertThat(blocksOf(edited).get(0).get("blockType").asText()).isEqualTo("heading");
            assertThat(blocksOf(edited).get(0).get("level").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("a new block is added without disturbing the ids of the ones around it")
        void insertingInTheMiddleRenumbersPositionsAndNothingElse() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    block(null, "paragraph", null, "First"),
                    block(null, "paragraph", null, "Third")));
            UUID first = UUID.fromString(blocksOf(created).get(0).get("id").asText());
            UUID third = UUID.fromString(blocksOf(created).get(1).get("id").asText());

            MvcResult edited = edit(LEAD, ACME_BANKING, idOf(created), pageWithBlocks("Doc",
                    block(first, "paragraph", null, "First"),
                    block(null, "paragraph", null, "Second"),
                    block(third, "paragraph", null, "Third")), null);

            List<JsonNode> after = blocksOf(edited);
            assertThat(after).hasSize(3);
            assertThat(after.get(0).get("id").asText()).isEqualTo(first.toString());
            assertThat(after.get(2).get("id").asText()).isEqualTo(third.toString());
            assertThat(after.stream().map(node -> node.get("position").asInt()).toList())
                    .as("position is assigned from array order, never sent by the client")
                    .containsExactly(0, 1, 2);
            assertThat(after.get(1).get("id").asText())
                    .isNotEqualTo(first.toString())
                    .isNotEqualTo(third.toString());
        }

        @Test
        @DisplayName("an id that is not on this page is a 400, and creates nothing")
        void anUnknownIdIsRefusedRatherThanCreated() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Only")));
            UUID stranger = UUID.fromString("dc00b10c-0000-4000-8000-000000000001");

            MvcResult refused = edit(LEAD, ACME_BANKING, idOf(created),
                    pageWithBlocks("Doc", block(stranger, "paragraph", null, "Only")), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("blocks[0].id");

            // Creating under the submitted id instead would mean a client that believes it edited
            // block X has created block Y — and a link pointing at X now resolves to a block nobody
            // is editing.
            assertThat(blockIdsOf(ACME, idOf(created))).doesNotContain(stranger);
        }

        @Test
        @DisplayName("the same id twice in one payload is a 400")
        void aDuplicateIdIsRefused() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Only")));
            UUID blockId = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            MvcResult refused = edit(LEAD, ACME_BANKING, idOf(created), pageWithBlocks("Doc",
                    block(blockId, "paragraph", null, "One"),
                    block(blockId, "paragraph", null, "Two")), null);

            // Reconciled without this check, the second silently wins and one submitted block
            // vanishes from a page the client believes it just saved.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("a create refuses block ids outright")
        void aCreateIssuesEveryId() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    block(UUID.fromString("dc00b10c-0000-4000-8000-000000000002"),
                            "paragraph", null, "Mine")));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        }
    }

    // =====================================================================
    // Traceability — the refusal that protects a link
    // =====================================================================

    @Nested
    @DisplayName("Traceability — a linked block is not collateral")
    class Traceability {

        @Test
        @DisplayName("removing a linked block is a 409, and the page is left exactly as it was")
        void anEditThatWouldBreakALinkIsRefusedBeforeAnythingIsWritten() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Refunds",
                    block(null, "paragraph", null, "Refunds are issued within 5 days."),
                    block(null, "paragraph", null, "Partial refunds are pro-rated.")));
            UUID pageId = idOf(created);
            UUID linked = UUID.fromString(blocksOf(created).get(0).get("id").asText());
            UUID other = UUID.fromString(blocksOf(created).get(1).get("id").asText());

            link(ACME, linked);

            // Drops the linked block and rewrites the other one in the same request.
            MvcResult refused = edit(LEAD, ACME_BANKING, pageId, pageWithBlocks("Refunds",
                    block(other, "paragraph", null, "Rewritten.")), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString()).contains("links");

            // The check runs before any write, so the rewrite that shared the request did not
            // happen either. Asserted rather than trusted to the rollback: a refusal whose
            // correctness depends on the transaction unwinding is one nobody can reason about
            // locally.
            assertThat(blockIdsOf(ACME, pageId)).contains(linked, other);
            assertThat(textOf(ACME, other)).isEqualTo("Partial refunds are pro-rated.");
        }

        @Test
        @DisplayName("editing a linked block in place is allowed — that is the whole point")
        void aLinkedBlockIsStillEditable() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Refunds",
                    block(null, "paragraph", null, "Refunds in 5 days.")));
            UUID linked = UUID.fromString(blocksOf(created).get(0).get("id").asText());
            link(ACME, linked);

            MvcResult edited = edit(LEAD, ACME_BANKING, idOf(created), pageWithBlocks("Refunds",
                    block(linked, "paragraph", null, "Refunds in 3 days.")), null);

            // The refusal must be about *removal*, not about touching a linked block at all. A rule
            // that froze linked content would make traceability a reason not to link.
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat(textOf(ACME, linked)).isEqualTo("Refunds in 3 days.");
        }

        @Test
        @DisplayName("deleting a page whose block is linked is a 409, and the page survives")
        void aDeleteThatWouldBreakALinkIsRefused() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Refunds", block(null, "paragraph", null, "Refunds.")));
            UUID pageId = idOf(created);
            link(ACME, UUID.fromString(blocksOf(created).get(0).get("id").asText()));

            MvcResult refused = delete(LEAD, ACME_BANKING, pageId);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString())
                    .as("the message must offer the action that does work")
                    .contains("archive");
            assertThat(deletedAtOf(ACME, pageId)).isNull();
        }

        @Test
        @DisplayName("an unlinked block is removed normally")
        void removingAnUnlinkedBlockIsNotRefused() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    block(null, "paragraph", null, "Keep"),
                    block(null, "paragraph", null, "Drop")));
            UUID keep = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            MvcResult edited = edit(LEAD, ACME_BANKING, idOf(created),
                    pageWithBlocks("Doc", block(keep, "paragraph", null, "Keep")), null);

            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat(blockIdsOf(ACME, idOf(created))).containsExactly(keep);
        }
    }

    // =====================================================================
    // Keys, paths and versions
    // =====================================================================

    @Nested
    @DisplayName("Keys, paths and versions")
    class Identity {

        @Test
        @DisplayName("keys are allocated DOC-1, DOC-2 from the company counter")
        void keysComeFromTheCounter() throws Exception {
            assertThat(body(create(LEAD, ACME_BANKING, page("One", null))).get("key").asText())
                    .isEqualTo("DOC-1");
            assertThat(body(create(LEAD, ACME_BANKING, page("Two", null))).get("key").asText())
                    .isEqualTo("DOC-2");
        }

        @Test
        @DisplayName("a rejected payload burns no key")
        void validationRunsBeforeAllocation() throws Exception {
            assertThat(create(LEAD, ACME_BANKING, page("", null)).getResponse().getStatus())
                    .isEqualTo(400);

            // The counter only moves forward, so a burned number is a permanent hole in the
            // company's DOC- series — one per failed attempt (§29's rule).
            assertThat(body(create(LEAD, ACME_BANKING, page("First real", null)))
                    .get("key").asText())
                    .isEqualTo("DOC-1");
        }

        @Test
        @DisplayName("the path and slug are derived from the key, and survive a retitle")
        void neitherIdentifierFollowsTheTitle() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, page("Original title", null));
            UUID id = idOf(created);
            String path = pathOf(ACME, id);
            String slug = body(created).get("slug").asText();

            assertThat(path).isEqualTo("d1");
            assertThat(slug).isEqualTo("original-title-1");

            edit(LEAD, ACME_BANKING, id, page("Completely different", null), null);

            // A title-derived path breaks subtree matching on the first rename, and does it
            // quietly: the tree keeps rendering while every descendant's path names a page that no
            // longer exists.
            assertThat(pathOf(ACME, id)).isEqualTo(path);
            assertThat(body(read(LEAD, ACME_BANKING, id)).get("slug").asText()).isEqualTo(slug);
        }

        @Test
        @DisplayName("a child page's path nests under its parent's")
        void theTreeIsMaterialised() throws Exception {
            UUID parent = idOf(create(LEAD, ACME_BANKING, page("Parent", null)));
            UUID child = idOf(create(LEAD, ACME_BANKING, page("Child", parent)));

            assertThat(pathOf(ACME, child)).isEqualTo(pathOf(ACME, parent) + ".d2");
        }

        @Test
        @DisplayName("a parent in another project is a field error, not a 404 and not a silent nest")
        void anUnreachableParentIsRefused() throws Exception {
            UUID foreign = UUID.fromString("dc00fa11-0000-4000-8000-000000000001");

            MvcResult refused = create(LEAD, ACME_BANKING, page("Orphan", foreign));

            // Accepted, the page would nest under a page in no reachable tree — created
            // successfully and immediately invisible, because every read is scoped by project.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("parentId");
        }

        @Test
        @DisplayName("a create writes v1, so a created page has no history gap")
        void creationWritesTheFirstVersion() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Body"))));

            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
            assertThat(body(read(LEAD, ACME_BANKING, id)).get("currentVersionNo").asInt())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a save that changed nothing writes no version")
        void theHashDecides() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Body")));
            UUID id = idOf(created);
            UUID blockId = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            edit(LEAD, ACME_BANKING, id,
                    pageWithBlocks("Doc", block(blockId, "paragraph", null, "Body")), null);

            // Opening a page and pressing Save must not manufacture a version (plan §6j).
            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
        }

        @Test
        @DisplayName("a retitle DOES write a version — a title is content here, unlike a suite's name")
        void aRetitleAuthorsAVersion() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Before", null)));

            edit(LEAD, ACME_BANKING, id, page("After", null), null);

            // The opposite of SuiteVersions, deliberately: a suite version is defined as
            // composition, so a folder's name is outside it — a page version is the page, and its
            // title is the first line a reader sees.
            assertThat(versionNumbersOf(ACME, id)).containsExactly(1, 2);
            assertThat(body(read(LEAD, ACME_BANKING, id)).get("currentVersionNo").asInt())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("publishing writes no version and does not move the number")
        void publicationIsNotAuthorship() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Doc", null)));

            setStatus(LEAD, ACME_BANKING, id, "PUBLISHED");
            setStatus(LEAD, ACME_BANKING, id, "DRAFT");

            // A history reading "v2 — published, v3 — unpublished" is a history whose numbers no
            // longer count edits. The decision belongs to audit_event.
            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
            assertThat(auditActions(ACME)).contains("doc_page.status_changed");
        }

        @Test
        @DisplayName("setting the status a page already has is a 200 and audits nothing new")
        void theStatusEndpointIsIdempotent() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Doc", null)));

            assertThat(setStatus(LEAD, ACME_BANKING, id, "DRAFT").getResponse().getStatus())
                    .isEqualTo(200);

            // Two administrators publishing the same page is agreement, not a conflict — and an
            // audit row per repeated click buries the change that did happen.
            assertThat(auditActions(ACME)).doesNotContain("doc_page.status_changed");
        }
    }

    // =====================================================================
    // The payload rules
    // =====================================================================

    @Nested
    @DisplayName("The payload — what is refused, and what is left alone")
    class Payload {

        @Test
        @DisplayName("omitting `blocks` is a retitle and leaves the content alone")
        void nullBlocksMeansLeaveThemAlone() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Body")));
            UUID id = idOf(created);
            UUID blockId = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            MvcResult edited = edit(LEAD, ACME_BANKING, id, page("Retitled", null), null);

            // Collapsed with `[]`, forgetting a field would be the way to empty somebody's
            // specification — with a 200 reporting success.
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat(blockIdsOf(ACME, id)).containsExactly(blockId);
        }

        @Test
        @DisplayName("an empty `blocks` array does empty the page")
        void emptyBlocksMeansRemoveThem() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Body"))));

            MvcResult edited = edit(LEAD, ACME_BANKING, id, pageWithBlocks("Doc"), null);

            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat(blockIdsOf(ACME, id)).isEmpty();
        }

        @Test
        @DisplayName("a heading with no depth, and a paragraph with one, are both 400s")
        void theLevelRuleGoesBothWays() throws Exception {
            assertThat(create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "heading", null, "No depth")))
                    .getResponse().getStatus())
                    .isEqualTo(400);

            // Refused rather than dropped: a level on a paragraph means the client believes it is
            // sending a heading, and honouring the type while discarding the depth renders that
            // belief as a paragraph with nothing reported.
            assertThat(create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", 2, "Not a heading")))
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an unknown block type is a field error naming the six, not a parse failure")
        void anUnknownTypeLandsOnTheField() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "hedaing", null, "Typo")));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].blockType")
                    .contains("heading");
        }

        @Test
        @DisplayName("a stale If-Match is a 409 and writes nothing")
        void theLostUpdateIsRefused() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Original", null)));

            MvcResult refused = edit(LEAD, ACME_BANKING, id, page("Racing", null), 99L);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(read(LEAD, ACME_BANKING, id)).get("title").asText())
                    .isEqualTo("Original");
        }

        @Test
        @DisplayName("a malformed If-Match is a 400, not a 409 nobody can escape")
        void aBadHeaderIsNotAConflict() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Doc", null)));

            MvcResult refused = mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/projects/" + ACME_BANKING + "/doc-pages/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .header(HttpHeaders.IF_MATCH, "not-a-number")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(page("Doc", null)))
                    .andReturn();

            // A 409 would send a client with a malformed header into a reload-and-retry loop that
            // cannot succeed.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("a correct If-Match saves")
        void theHeaderIsHonouredWhenItMatches() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, page("Doc", null));
            long lockVersion = body(created).get("lockVersion").asLong();

            assertThat(edit(LEAD, ACME_BANKING, idOf(created), page("Saved", null), lockVersion)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    // =====================================================================
    // Reads
    // =====================================================================

    @Nested
    @DisplayName("Reads — the tree, the page, and the field that is deliberately absent")
    class Reads {

        @Test
        @DisplayName("a fresh project's tree is [] — nothing seeds a first page")
        void anEmptyProjectIsEmptyRatherThanSeeded() throws Exception {
            MvcResult result = tree(LEAD, ACME_BANKING);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).isEmpty();
        }

        @Test
        @DisplayName("the tree lists parents before children")
        void theTreeIsConstructibleInOnePass() throws Exception {
            UUID parent = idOf(create(LEAD, ACME_BANKING, page("Parent", null)));
            UUID child = idOf(create(LEAD, ACME_BANKING, page("Child", parent)));

            List<String> ids = new ArrayList<>();
            body(tree(LEAD, ACME_BANKING)).forEach(node -> ids.add(node.get("id").asText()));

            assertThat(ids).containsExactly(parent.toString(), child.toString());
        }

        @Test
        @DisplayName("an archived page stays in the tree; a deleted one does not")
        void archivingIsNotDeleting() throws Exception {
            UUID archived = idOf(create(LEAD, ACME_BANKING, page("Archived", null)));
            UUID deleted = idOf(create(LEAD, ACME_BANKING, page("Deleted", null)));

            setStatus(LEAD, ACME_BANKING, archived, "ARCHIVED");
            assertThat(delete(LEAD, ACME_BANKING, deleted).getResponse().getStatus())
                    .isEqualTo(204);

            List<String> ids = new ArrayList<>();
            body(tree(LEAD, ACME_BANKING)).forEach(node -> ids.add(node.get("id").asText()));

            // Filtering ARCHIVED out here would make an archived page unreachable from the only
            // screen that could un-archive it.
            assertThat(ids).containsExactly(archived.toString());
            assertThat(read(LEAD, ACME_BANKING, deleted).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("no block carries a `coverage` marker, because nothing can create a link yet")
        void coverageIsAbsentRatherThanZeroed() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Refunds.")));

            // A zeroed marker does not read as "unknown" — the SPA renders it as "Not tested",
            // which is a specific and alarming claim about every paragraph in the product on the
            // strength of a question nobody has asked. Same call as TestCase.relatedDocuments.
            assertThat(blocksOf(created).get(0).has("coverage")).isFalse();
        }

        @Test
        @DisplayName("a second delete of an already-deleted page is a 404, not a second 204")
        void deletingTwiceIsNotIdempotentAndShouldNotBe() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Doc", null)));

            assertThat(delete(LEAD, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(204);

            // Reporting success for a delete this request did not perform is the same untruth as a
            // 201 on a replayed create.
            assertThat(delete(LEAD, ACME_BANKING, id).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("a page with children cannot be deleted")
        void theSubtreeIsNotCascaded() throws Exception {
            UUID parent = idOf(create(LEAD, ACME_BANKING, page("Parent", null)));
            create(LEAD, ACME_BANKING, page("Child", parent));

            MvcResult refused = delete(LEAD, ACME_BANKING, parent);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString()).contains("child page");
            assertThat(deletedAtOf(ACME, parent)).isNull();
        }
    }

    // =====================================================================
    // Table blocks
    // =====================================================================

    @Nested
    @DisplayName("Table blocks — the cells, and the shape rules that keep them honest (#48)")
    class TableBlocks {

        @Test
        @DisplayName("a table's rows round-trip, and the caption stays on `text`")
        void aTableKeepsItsCells() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Limits",
                    tableBlock(null, "Transfer limits by tier",
                            new String[][] {{"Tier", "Daily limit"}, {"Basic", "1000"}})));

            assertThat(created.getResponse().getStatus()).isEqualTo(201);

            JsonNode block = blocksOf(created).get(0);
            assertThat(block.get("blockType").asText()).isEqualTo("table");
            assertThat(block.get("text").asText()).isEqualTo("Transfer limits by tier");
            assertThat(block.get("rows").get(0).get(1).asText()).isEqualTo("Daily limit");
            assertThat(block.get("rows").get(1).get(0).asText()).isEqualTo("Basic");

            // Read back through the GET rather than trusting the write's own response: the two are
            // assembled by different code paths, and a field the create echoes from its input and
            // never persists reads perfectly here and is gone on the next page load.
            JsonNode reread = blocksOf(read(LEAD, ACME_BANKING, idOf(created))).get(0);
            assertThat(reread.get("rows").get(1).get(1).asText()).isEqualTo("1000");
        }

        @Test
        @DisplayName("rows on a paragraph, and a table with none, are both 400s")
        void theRowsRuleGoesBothWays() throws Exception {
            MvcResult onAParagraph = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    tableBlock(null, "Not a table", new String[][] {{"a"}})
                            .replace("\"table\"", "\"paragraph\"")));
            assertThat(onAParagraph.getResponse().getStatus()).isEqualTo(400);
            assertThat(onAParagraph.getResponse().getContentAsString())
                    .contains("blocks[0].rows");

            MvcResult empty = create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", tableBlock(null, "Empty", new String[][] {})));
            assertThat(empty.getResponse().getStatus()).isEqualTo(400);
            assertThat(empty.getResponse().getContentAsString()).contains("blocks[0].rows");
        }

        @Test
        @DisplayName("a ragged row is refused naming its own index, and nothing is padded")
        void aShortRowIsRefusedRatherThanFilledIn() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    tableBlock(null, "Limits", new String[][] {
                        {"Tier", "Daily limit", "Monthly limit"},
                        {"Basic", "1000"}})));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);

            // The row, not the block. Padding would render cleanly and be wrong in a way nothing
            // can point at: an empty cell the author typed and a cell the server invented look
            // identical, and in a specification they mean opposite things.
            assertThat(refused.getResponse().getContentAsString()).contains("blocks[0].rows[1]");
        }

        @Test
        @DisplayName("a cell over the cap is refused, and the message says why a cell is not a paragraph")
        void aCellIsNotAParagraph() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    tableBlock(null, "Limits", new String[][] {
                        {"Tier", "Note"},
                        {"Basic", "x".repeat(501)}})));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].rows[1][1]")
                    .contains("cannot link to a cell");
        }

        @Test
        @DisplayName("more columns than the header names is the same refusal as fewer")
        void aWideRowIsRaggedToo() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pageWithBlocks("Doc",
                    tableBlock(null, "Limits", new String[][] {
                        {"Tier", "Daily limit"},
                        {"Basic", "1000", "and a stray cell"}})));

            // The HEADER decides the width, not the widest row: taking the maximum instead would
            // let a stray cell silently widen the table and leave the header a column short.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("blocks[0].rows[1]");
        }

        @Test
        @DisplayName("EDITING ONE CELL WRITES A VERSION — the snapshot has to carry the rows")
        void aCellEditIsAuthorship() throws Exception {
            MvcResult created = create(LEAD, ACME_BANKING, pageWithBlocks("Limits",
                    tableBlock(null, "Transfer limits", new String[][] {
                        {"Tier", "Daily limit"},
                        {"Basic", "1000"}})));
            UUID id = idOf(created);
            UUID blockId = UUID.fromString(blocksOf(created).get(0).get("id").asText());

            edit(LEAD, ACME_BANKING, id, pageWithBlocks("Limits",
                    tableBlock(blockId, "Transfer limits", new String[][] {
                        {"Tier", "Daily limit"},
                        {"Basic", "2500"}})), null);

            /*
              THE regression guard for this slice, and the failure it catches is silent in the
              direction that looks like success. `PageVersions` writes a version only when the
              snapshot's hash moved, and the snapshot is built field by field — so a content field
              it does not carry is a field somebody can rewrite all day while the history says
              nothing happened. Every other assertion about tables passes with `rows` missing from
              `SnapshotBlock`: the page saves, reads back correct, and quietly loses its history.
            */
            assertThat(versionNumbersOf(ACME, id)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("a paragraph's stored snapshot carries no `rows` key — the old hashes stand")
        void theSnapshotStaysByteIdenticalForBlocksWithNoPayload() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING,
                    pageWithBlocks("Doc", block(null, "paragraph", null, "Body"))));

            /*
              `@JsonInclude(NON_NULL)` on the two newest snapshot components, asserted from outside
              rather than trusted. Without it every stored `content_hash` in every deployment
              changes the day this ships, and the first save of every untouched page writes a
              version whose diff is empty — the exact history-noise `PageVersions` exists to
              suppress, delivered once per page as a migration artefact. Nothing else in the suite
              can see that, because every hash in a test run is computed by the same build.
            */
            // Read back through `CAST(snapshot AS text)`, so Postgres has re-rendered the document
            // — keys reordered, one space after each colon. That normalisation is why the
            // assertion is about a key being ABSENT rather than about the byte string: a
            // `"rows": null` written by Jackson would survive into the column as a key, and it is
            // the key's presence that moves the hash.
            assertThat(snapshotOf(ACME, id))
                    .contains("\"text\": \"Body\"")
                    .doesNotContain("rows")
                    .doesNotContain("attachmentId");
        }
    }

    // =====================================================================
    // Image blocks
    // =====================================================================

    @Nested
    @DisplayName("Image blocks — a block names a file the page already holds (#48)")
    class ImageBlocks {

        @Test
        @DisplayName("an image block round-trips its attachment id, with the alt text on `text`")
        void anImageNamesOneOfThePagesFiles() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Checkout", null)));
            UUID fileId = attachTo(id, "wireframe.png");

            MvcResult edited = edit(LEAD, ACME_BANKING, id, pageWithBlocks("Checkout",
                    imageBlock(null, "The checkout screen, with the totals panel on the right",
                            fileId)), null);

            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            JsonNode block = blocksOf(edited).get(0);
            assertThat(block.get("blockType").asText()).isEqualTo("image");
            assertThat(block.get("attachmentId").asText()).isEqualTo(fileId.toString());
            assertThat(block.get("text").asText()).startsWith("The checkout screen");

            // And the file list is still on the response. An edit does not touch the attachments,
            // and a save that appeared to delete every file on the page until the next GET is the
            // regression the write service carries them for.
            assertThat(body(edited).get("attachments")).hasSize(1);
        }

        @Test
        @DisplayName("an image on a CREATE is refused, and the message says what to do instead")
        void aNewPageHasNoFilesToReference() throws Exception {
            MvcResult refused = create(LEAD, ACME_BANKING, pageWithBlocks("Checkout",
                    imageBlock(null, "A wireframe", UUID.randomUUID())));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);

            // Not "that file is not attached to this page", which would be true and useless: on a
            // create no id can pass, so a perfectly valid attachment id would be reported as
            // unknown and send the author hunting a mistake they did not make.
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].attachmentId")
                    .contains("Create the page");
        }

        @Test
        @DisplayName("an image naming ANOTHER page's file is a 400, not a broken block")
        void aForeignAttachmentIsRefused() throws Exception {
            UUID mine = idOf(create(LEAD, ACME_BANKING, page("Mine", null)));
            UUID theirs = idOf(create(LEAD, ACME_BANKING, page("Theirs", null)));
            UUID theirFile = attachTo(theirs, "diagram.png");

            MvcResult refused = edit(LEAD, ACME_BANKING, mine,
                    pageWithBlocks("Mine", imageBlock(null, "Their diagram", theirFile)), null);

            // `parentId`'s rule one field over: an unchecked id is a block saved successfully and
            // immediately broken, because the read that resolves it is scoped to this page.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].attachmentId");
        }

        @Test
        @DisplayName("blank alt text is a 400 — an image in a specification is never decorative")
        void anImageMustBeDescribed() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Checkout", null)));
            UUID fileId = attachTo(id, "wireframe.png");

            MvcResult refused = edit(LEAD, ACME_BANKING, id,
                    pageWithBlocks("Checkout", imageBlock(null, "   ", fileId)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("blocks[0].text");

            // The one type where empty text is refused. An empty PARAGRAPH is a spacer somebody
            // typed and stays legal — the two rules live one `else if` apart and it would be easy
            // to make them agree.
            assertThat(edit(LEAD, ACME_BANKING, id,
                    pageWithBlocks("Checkout", block(null, "paragraph", null, "")), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("an attachment id on a paragraph is refused, like a level and like rows")
        void onlyAnImageNamesAFile() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Checkout", null)));
            UUID fileId = attachTo(id, "wireframe.png");

            MvcResult refused = edit(LEAD, ACME_BANKING, id, pageWithBlocks("Checkout",
                    imageBlock(null, "Body", fileId).replace("\"image\"", "\"paragraph\"")), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].attachmentId");
        }

        @Test
        @DisplayName("an image block with no file at all is a 400")
        void anImageWithoutAFileIsNotAnImage() throws Exception {
            UUID id = idOf(create(LEAD, ACME_BANKING, page("Checkout", null)));

            MvcResult refused = edit(LEAD, ACME_BANKING, id,
                    pageWithBlocks("Checkout", imageBlock(null, "A wireframe", null)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("blocks[0].attachmentId");
        }

        /** Attaches a one-pixel file to a page and returns its id. */
        private UUID attachTo(UUID pageId, String filename) throws Exception {
            MvcResult attached = mvc.perform(MockMvcRequestBuilders
                            .multipart("/api/v1/projects/" + ACME_BANKING + "/doc-pages/" + pageId
                                    + "/attachments")
                            .file(new MockMultipartFile(
                                    "file", filename, "image/png", new byte[] {1, 2, 3}))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME)))
                    .andReturn();

            assertThat(attached.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(body(attached).get("id").asText());
        }
    }

    // =====================================================================
    // Idempotency-Key (V42)
    //
    // The header this endpoint refused until V42, on a reason copied from the suite endpoint that
    // was weakest here: a page is a LINK TARGET, so a duplicate splits traceability across two
    // sets of block ids and deleting the loser destroys its links rather than merging them.
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — a retry must not create a second page")
    class Idempotency {

        @Test
        @DisplayName("a repeated key answers 200 with the SAME page, and creates nothing")
        void aRetryReplaysTheOriginal() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            MvcResult first = create(LEAD, ACME_BANKING, page("Once", null), key);
            MvcResult retry = create(LEAD, ACME_BANKING, page("Once", null), key);

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(idOf(retry)).isEqualTo(idOf(first));
            assertThat(livePageCount(ACME, ACME_BANKING)).isEqualTo(1);
        }

        @Test
        @DisplayName("a replay serves the page's blocks, so it matches what the create answered")
        void aReplayCarriesTheBlocks() throws Exception {
            // The replay renders from the live row rather than from a stored response — the whole
            // reason the column form was chosen over a general idempotent-request table (V17).
            String key = "intent-" + UUID.randomUUID();
            String payload = pageWithBlocks("Spec",
                    block(null, "paragraph", null, "The statement"));

            create(LEAD, ACME_BANKING, payload, key);
            MvcResult retry = create(LEAD, ACME_BANKING, payload, key);

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(blocksOf(retry)).hasSize(1);
            assertThat(blocksOf(retry).get(0).get("text").asText()).isEqualTo("The statement");
        }

        @Test
        @DisplayName("a replay carries no Location — nothing was created by that request")
        void aReplayCarriesNoLocation() throws Exception {
            String key = "intent-" + UUID.randomUUID();
            create(LEAD, ACME_BANKING, page("Located", null), key);

            MvcResult retry = create(LEAD, ACME_BANKING, page("Located", null), key);

            assertThat(retry.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        }

        @Test
        @DisplayName("the counter does not move on a replay, so the next real create is DOC-2")
        void aReplayBurnsNoKey() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            assertThat(body(create(LEAD, ACME_BANKING, page("First", null), key))
                    .get("key").asText()).isEqualTo("DOC-1");
            create(LEAD, ACME_BANKING, page("First", null), key);

            assertThat(body(create(LEAD, ACME_BANKING, page("Second", null)))
                    .get("key").asText()).isEqualTo("DOC-2");
        }

        @Test
        @DisplayName("a retry whose payload has drifted still returns the original, not a 400")
        void aRetryIgnoresADriftedPayload() throws Exception {
            // The key is checked BEFORE validation: the client is asking "did my intent land", and
            // a 400 would send it to fix fields on a page that already exists.
            String key = "intent-" + UUID.randomUUID();
            create(LEAD, ACME_BANKING, page("Original", null), key);

            MvcResult retry = create(LEAD, ACME_BANKING, page("   ", null), key);

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("title").asText()).isEqualTo("Original");
        }

        @Test
        @DisplayName("no key at all creates every time — the header stays optional")
        void withoutAKeyEveryCallCreates() throws Exception {
            create(LEAD, ACME_BANKING, page("One", null));
            create(LEAD, ACME_BANKING, page("One", null));

            assertThat(livePageCount(ACME, ACME_BANKING)).isEqualTo(2);
        }

        @Test
        @DisplayName("a blank key is treated as absent, not as a key everyone shares")
        void aBlankKeyIsAbsent() throws Exception {
            // Stored as-is, a blank key becomes ONE key every misconfigured client replays against,
            // and the project accepts exactly one page from all of them, forever. The SAME blank
            // value has to be sent twice: two different blanks would not collide even without the
            // trim.
            create(LEAD, ACME_BANKING, page("One", null), "   ");
            create(LEAD, ACME_BANKING, page("Two", null), "   ");
            create(LEAD, ACME_BANKING, page("Three", null), "");
            create(LEAD, ACME_BANKING, page("Four", null), "");

            assertThat(livePageCount(ACME, ACME_BANKING)).isEqualTo(4);
        }

        @Test
        @DisplayName("the same key in another COMPANY's project creates a page")
        void keysDoNotCrossCompanies() throws Exception {
            // The index leads with company_id and the lookup runs under RLS, so Umbra's key cannot
            // resolve to Acme's page even when the string is identical.
            String key = "intent-shared";

            MvcResult acme = create(LEAD, ACME_BANKING, page("Acme's", null), key);
            MvcResult umbra = create(OUTSIDER, UMBRA_BANKING, page("Umbra's", null), key);

            assertThat(acme.getResponse().getStatus()).isEqualTo(201);
            assertThat(umbra.getResponse().getStatus()).isEqualTo(201);
            assertThat(idOf(umbra)).isNotEqualTo(idOf(acme));
        }

        @Test
        @DisplayName("an absurdly long key is refused rather than stored")
        void anOverlongKeyIsRefused() throws Exception {
            MvcResult result =
                    create(LEAD, ACME_BANKING, page("Long key", null), "k".repeat(201));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("Idempotency-Key")).isTrue();
            assertThat(livePageCount(ACME, ACME_BANKING)).isZero();
        }
    }

    // =====================================================================
    // Isolation
    // =====================================================================

    @Nested
    @DisplayName("Isolation — two clients, both numbered DOC-1")
    class Isolation {

        @Test
        @DisplayName("each company's counter is its own, and neither sees the other's page")
        void keysCollideAcrossCompaniesAndNothingLeaks() throws Exception {
            MvcResult acme = create(LEAD, ACME_BANKING, page("Acme's spec", null));
            MvcResult umbra = create(OUTSIDER, UMBRA_BANKING, page("Umbra's spec", null));

            // Keys are numbered per COMPANY, so the collision is the design working (§6t).
            assertThat(body(acme).get("key").asText()).isEqualTo("DOC-1");
            assertThat(body(umbra).get("key").asText()).isEqualTo("DOC-1");

            // Umbra's lead, given Acme's page id on Acme's project: 404 on both counts, and no
            // title of Acme's anywhere in the response.
            MvcResult crossed = read(OUTSIDER, ACME_BANKING, idOf(acme));
            assertThat(crossed.getResponse().getStatus()).isEqualTo(404);
            assertThat(crossed.getResponse().getContentAsString()).doesNotContain("Acme's spec");
        }

        @Test
        @DisplayName("a page id from another company is a 404 on one's own project")
        void aForeignPageIdResolvesToNothing() throws Exception {
            UUID umbraPage = idOf(create(OUTSIDER, UMBRA_BANKING, page("Umbra's spec", null)));

            assertThat(read(LEAD, ACME_BANKING, umbraPage).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(edit(LEAD, ACME_BANKING, umbraPage, page("Mine now", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult tree(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/doc-pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId))))
                .andReturn();
    }

    private MvcResult read(UUID userId, UUID projectId, UUID pageId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId))))
                .andReturn();
    }

    private MvcResult create(UUID userId, UUID projectId, String payload) throws Exception {
        return create(userId, projectId, payload, null);
    }

    private MvcResult create(
            UUID userId, UUID projectId, String payload, String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/doc-pages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult edit(UUID userId, UUID projectId, UUID pageId, String payload, Long ifMatch)
            throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, String.valueOf(ifMatch));
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult setStatus(UUID userId, UUID projectId, UUID pageId, String status)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/doc-pages/" + pageId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn();
    }

    private MvcResult delete(UUID userId, UUID projectId, UUID pageId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyOf(userId))))
                .andReturn();
    }

    /** The company each persona signs in to. OUTSIDER is the only one in Umbra. */
    private static UUID companyOf(UUID userId) {
        return userId.equals(OUTSIDER) ? UMBRA : ACME;
    }

    // =====================================================================
    // helpers — payloads
    // =====================================================================

    private String page(String title, UUID parentId) {
        return "{\"title\":" + quote(title)
                + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"") + "}";
    }

    private String pageWithBlocks(String title, String... blocks) {
        return "{\"title\":" + quote(title) + ",\"blocks\":[" + String.join(",", blocks) + "]}";
    }

    private String block(UUID id, String type, Integer level, String text) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",")
                + "\"blockType\":" + quote(type)
                + (level == null ? "" : ",\"level\":" + level)
                + ",\"text\":" + quote(text) + "}";
    }

    /**
     * A {@code table} block. The caption goes on {@code text}, which is where the coverage matrix
     * reads a block's statement from.
     */
    private String tableBlock(UUID id, String caption, String[][] rows) {
        List<String> serialized = new ArrayList<>();
        for (String[] row : rows) {
            List<String> cells = new ArrayList<>();
            for (String cell : row) {
                cells.add(quote(cell));
            }
            serialized.add("[" + String.join(",", cells) + "]");
        }
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",")
                + "\"blockType\":\"table\",\"text\":" + quote(caption)
                + ",\"rows\":[" + String.join(",", serialized) + "]}";
    }

    /** An {@code image} block. {@code text} is the alt text and a null file is sent as JSON null. */
    private String imageBlock(UUID id, String alt, UUID attachmentId) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",")
                + "\"blockType\":\"image\",\"text\":" + quote(alt)
                + ",\"attachmentId\":"
                + (attachmentId == null ? "null" : "\"" + attachmentId + "\"") + "}";
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }

    private List<JsonNode> blocksOf(MvcResult result) throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        body(result).get("blocks").forEach(blocks::add);
        return blocks;
    }

    // =====================================================================
    // helpers — reading the database
    // =====================================================================

    private long livePageCount(UUID companyId, UUID projectId) throws SQLException {
        return scalarLong(companyId,
                "SELECT COUNT(*) FROM doc_page WHERE project_id = CAST(? AS uuid) "
                        + "AND deleted_at IS NULL",
                projectId.toString());
    }

    private long spaceCount(UUID companyId, UUID projectId) throws SQLException {
        return scalarLong(companyId,
                "SELECT COUNT(*) FROM doc_space WHERE project_id = CAST(? AS uuid)",
                projectId.toString());
    }

    private String statusOf(UUID companyId, UUID pageId) throws SQLException {
        return scalarString(companyId,
                "SELECT status FROM doc_page WHERE id = CAST(? AS uuid)", pageId.toString());
    }

    private String pathOf(UUID companyId, UUID pageId) throws SQLException {
        return scalarString(companyId,
                "SELECT CAST(path AS text) FROM doc_page WHERE id = CAST(? AS uuid)",
                pageId.toString());
    }

    private String deletedAtOf(UUID companyId, UUID pageId) throws SQLException {
        return scalarString(companyId,
                "SELECT CAST(deleted_at AS text) FROM doc_page WHERE id = CAST(? AS uuid)",
                pageId.toString());
    }

    private String textOf(UUID companyId, UUID blockId) throws SQLException {
        return scalarString(companyId,
                "SELECT content ->> 'text' FROM doc_block WHERE id = CAST(? AS uuid)",
                blockId.toString());
    }

    private List<UUID> blockIdsOf(UUID companyId, UUID pageId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT id FROM doc_block WHERE page_id = CAST(? AS uuid) ORDER BY position")) {
            statement.setString(1, pageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    /** The latest version's stored snapshot, as raw JSON — the text the hash is computed over. */
    private String snapshotOf(UUID companyId, UUID pageId) throws SQLException {
        return scalarString(companyId,
                "SELECT CAST(snapshot AS text) FROM doc_page_version "
                        + "WHERE page_id = CAST(? AS uuid) ORDER BY version_no DESC LIMIT 1",
                pageId.toString());
    }

    private List<Integer> versionNumbersOf(UUID companyId, UUID pageId) throws SQLException {
        List<Integer> numbers = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT version_no FROM doc_page_version WHERE page_id = CAST(? AS uuid) "
                             + "ORDER BY version_no")) {
            statement.setString(1, pageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    numbers.add(rows.getInt(1));
                }
            }
        }
        return numbers;
    }

    private List<String> auditActions(UUID companyId) throws SQLException {
        List<String> actions = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action FROM audit_event ORDER BY occurred_at")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
            }
        }
        return actions;
    }

    /**
     * Writes a {@code doc_link} by hand — the only way one exists, since nothing creates them yet.
     *
     * <p>This is what makes the traceability refusals drivable at all. Without it the guard would be
     * a branch no test can take, which is the state {@code DocBlockRepository#countLinksTargeting}
     * would otherwise be in: always returning 0, always allowing, and nobody able to tell whether it
     * works.
     */
    private void link(UUID companyId, UUID blockId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                     INSERT INTO doc_link
                         (id, company_id, source_type, source_id, target_kind, target_block_id,
                          relationship)
                     VALUES (gen_random_uuid(), CAST(? AS uuid), 'TEST_CASE', gen_random_uuid(),
                             'DOC_BLOCK', CAST(? AS uuid), 'VERIFIES')
                     """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, blockId.toString());
            statement.executeUpdate();
        }
    }

    private long scalarLong(UUID companyId, String sql, String parameter) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private String scalarString(UUID companyId, String sql, String parameter) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no row for %s", parameter).isTrue();
                return rows.getString(1);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
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

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
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
        UUID id = UUID.nameUUIDFromBytes(("doc-page-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {

        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }
}
