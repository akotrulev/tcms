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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A test plan's document, and the links a block can carry — §93 end to end, and §97's adoption.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A non-partial unique index on the subject.</strong> {@code uq_doc_page_subject} is
 *       {@code WHERE deleted_at IS NULL}, and a page's delete is soft. Drop the predicate and
 *       everything still passes except {@link Adoption#deletedDocumentReleasesTheSlot} — a plan
 *       whose document was deleted could never have another, surfacing as a duplicate-key 500
 *       naming a row nobody can see.</li>
 *   <li><strong>A subject check scoped to the company rather than the project.</strong> RLS scopes
 *       every statement to the company, so a sibling project's plan is <em>visible</em> and only
 *       {@link PlanFacts}' own predicate refuses it. Remove that predicate and a page is adopted by
 *       another project's plan — filed where neither project's screens look.</li>
 *   <li><strong>The two 409s answering as one.</strong> A plan that already has a document and a
 *       page that is already taken are different refusals resolved in different places, and with a
 *       single plan in the fixture they are indistinguishable — hence {@link #OTHER_PLAN}. The
 *       write-side half is asserted from the database ({@code subjectOf}), because a refusal that
 *       reassigned the page first passes every status assertion.</li>
 *   <li><strong>Adoption implemented as a copy.</strong> The whole reason the template was retired
 *       is that a page keeps its key, its history, its links and its place in the search index —
 *       so {@link Adoption#linkingAdoptsAnExistingPage} asserts the id, the key, the title and the
 *       version number are the ones the page already had.</li>
 *   <li><strong>A link write that is not idempotent.</strong> §79's re-stamp defect in the one place
 *       it recurs. There is no unique constraint behind it, so the read is the whole protection and
 *       a second row would double every count drawn off the table.</li>
 *   <li><strong>`level` collapsing back to two arms.</strong> The outline is the product of the
 *       middle one. A server that silently dropped a list bullet's depth would render every plan
 *       flat, with nothing failing anywhere.</li>
 *   <li><strong>The two traceability 409s.</strong> They have existed since §55 and could not fire
 *       on any real request until this slice; {@link Traceability} is the first thing that drives
 *       them through the API rather than through hand-inserted rows.</li>
 * </ol>
 *
 * <p>Company and user ids use the {@code d1…} prefix, unique to this class — the collision that has
 * happened five times (§19 … §62) and has no check behind it.
 */
@AutoConfigureMockMvc
@DisplayName("The plan document — the narrative half, and the links a block carries")
class PlanDocumentEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("d1000000-0000-4000-8000-00000000000b");

    private static final UUID BANKING = UUID.fromString("d1001000-0000-4000-8000-000000000001");

    /**
     * A second project of the SAME company.
     *
     * <p>The fixture that makes the project predicate testable at all. Another <em>company's</em>
     * project is refused by RLS one layer down, so it would pass with the predicate removed — which
     * is exactly how a company-scoped check gets mistaken for a project-scoped one.
     */
    private static final UUID SIBLING = UUID.fromString("d1001000-0000-4000-8000-000000000002");

    private static final UUID UMBRA_BANKING = UUID.fromString("d1002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("d1003000-0000-4000-8000-000000000001");

    /** Holds {@code doc:edit}; the writer of everything here. */
    private static final UUID ENGINEER = UUID.fromString("d1003000-0000-4000-8000-000000000002");

    /** Holds {@code doc:read} and not {@code doc:edit} — the 403 half of every gate below. */
    private static final UUID VIEWER = UUID.fromString("d1003000-0000-4000-8000-000000000003");

    private static final UUID PLAN = UUID.fromString("d1004000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_PLAN = UUID.fromString("d1004000-0000-4000-8000-000000000002");

    /**
     * A second plan of the SAME project.
     *
     * <p>The fixture that makes "this page is already another plan's document" testable at all.
     * With one plan the two 409s are indistinguishable, and an implementation that answered the
     * wrong one — or checked only one end — would pass everything.
     */
    private static final UUID OTHER_PLAN = UUID.fromString("d1004000-0000-4000-8000-000000000003");

    /** A page as somebody actually writes one, before any plan has adopted it. */
    private static final String SPECIFICATION = """
            {"title":"Release 24.1 — requirements","blocks":[
              {"blockType":"heading","level":1,"text":"Business requirements"},
              {"blockType":"list","level":1,"text":"A capability the product offers."},
              {"blockType":"list","level":2,"text":"A variation of the capability above."},
              {"blockType":"list","level":3,"text":"A specific condition within that variation."}]}
            """;

    private static final UUID SUITE = UUID.fromString("d1005000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_SUITE = UUID.fromString("d1005000-0000-4000-8000-000000000002");

    private static final UUID CASE = UUID.fromString("d1006000-0000-4000-8000-000000000001");

    /** A case of {@link #SIBLING}. Reachable under RLS and refused only by the project predicate. */
    private static final UUID SIBLING_CASE = UUID.fromString("d1006000-0000-4000-8000-000000000002");

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
            company(ACME, "d1-acme", "Acme", now);
            company(UMBRA, "d1-umbra", "Umbra", now);

            activeUser(LEAD, "lead@d1-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@d1-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@d1-acme.test", "V. Viewer", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "D1OLB", "Online Banking", now);
            seedProject(SIBLING, ACME, "D1SIB", "Payments", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);

            grantOnProject("d1-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grantOnProject("d1-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, BANKING, now);
            grantOnProject("d1-view", ACME, Role.VIEWER_ID, VIEWER, BANKING, now);
            // On the SIBLING project too, so a refusal there is about the row and never about
            // reachability — otherwise the project-predicate tests would pass on a 404 the gate
            // produced one step earlier.
            grantOnProject("d1-eng-sib", ACME, Role.TEST_ENGINEER_ID, ENGINEER, SIBLING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_BANKING, UMBRA, "D1UMB", "Online Banking", now)));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedProjectDefaults();
        seedCatalogue();
    }

    // =====================================================================
    // Adopting a page, and letting it go
    // =====================================================================

    @Nested
    @DisplayName("Adoption — the page a plan takes on, and the two ends that can already be taken")
    class Adoption {

        @Test
        @DisplayName("a plan with no document answers 404, which is the ordinary first state")
        void noDocumentYet() throws Exception {
            assertThat(document(ENGINEER, BANKING, PLAN).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("linking adopts the page as it is — same id, same key, same history")
        void linkingAdoptsAnExistingPage() throws Exception {
            JsonNode written = body(create(ENGINEER, BANKING, SPECIFICATION));
            UUID pageId = idOfPage(written);

            MvcResult linked = linkPage(
                    ENGINEER, BANKING, PLAN, "{\"pageId\":\"" + pageId + "\"}");

            // 200, not 201: nothing was created, and a client that treats this as a create would
            // look for a Location header naming a page it already has.
            assertThat(linked.getResponse().getStatus()).isEqualTo(200);

            JsonNode page = body(linked);
            assertThat(page.get("subjectType").asText()).isEqualTo("TEST_PLAN");
            assertThat(page.get("subjectId").asText()).isEqualTo(PLAN.toString());

            // THE point of adoption over the retired template: the page keeps everything it had.
            // A copy-into-a-fresh-page implementation passes the subject assertions above and
            // fails every one of these.
            assertThat(idOfPage(page)).isEqualTo(pageId);
            assertThat(page.get("key").asText()).isEqualTo(written.get("key").asText());
            assertThat(page.get("title").asText()).isEqualTo("Release 24.1 — requirements");
            // No version was authored — adopting a page changes nothing about what it says.
            assertThat(page.get("currentVersionNo").asInt())
                    .isEqualTo(written.get("currentVersionNo").asInt());

            // `level`'s middle arm, which the retired template used to be this class's only cover
            // for: three distinct bullet depths survived the round trip. A server that dropped a
            // list's level renders every requirements page flat with nothing else failing.
            assertThat(bulletDepths(page)).contains(1, 2, 3);

            // And it is the plan's document from the other direction too.
            assertThat(idOfPage(body(document(ENGINEER, BANKING, PLAN)))).isEqualTo(pageId);
            assertThat(livePagesFor(PLAN)).isEqualTo(1);
        }

        @Test
        @DisplayName("a second link is a 409 naming the plan's document, and writes nothing")
        void onlyOneDocument() throws Exception {
            start(ENGINEER, BANKING, PLAN);
            UUID second = idOf(create(ENGINEER, BANKING, SPECIFICATION));

            MvcResult again = linkPage(
                    ENGINEER, BANKING, PLAN, "{\"pageId\":\"" + second + "\"}");

            assertThat(again.getResponse().getStatus()).isEqualTo(409);
            // The KEY, because that is what the reader will see in the tree they are being sent to.
            assertThat(again.getResponse().getContentAsString()).contains("DOC-1");
            assertThat(livePagesFor(PLAN)).isEqualTo(1);
            assertThat(subjectOf(second)).isNull();
        }

        @Test
        @DisplayName("a page that is already another plan's document is a 409 naming that plan")
        void pageMayNotBeTwoPlansDocument() throws Exception {
            UUID pageId = idOf(start(ENGINEER, BANKING, PLAN));

            MvcResult refused = linkPage(
                    ENGINEER, BANKING, OTHER_PLAN, "{\"pageId\":\"" + pageId + "\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            // The other PLAN's key, not the page's — the two 409s say different things and an
            // implementation that answered "this plan already has a document" here would be
            // telling the caller to look at a plan that has none.
            assertThat(refused.getResponse().getContentAsString()).contains("TP-1");
            // The row did not move. Without this, an unconditional write passes the status
            // assertion above by refusing after having already reassigned the page.
            assertThat(subjectOf(pageId)).isEqualTo(PLAN);
            assertThat(livePagesFor(OTHER_PLAN)).isZero();
        }

        @Test
        @DisplayName("unlinking then linking is how a page moves — two requests, never one")
        void relinkingIsTwoSteps() throws Exception {
            UUID pageId = idOf(start(ENGINEER, BANKING, PLAN));

            assertThat(unlinkPage(ENGINEER, BANKING, PLAN).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(linkPage(ENGINEER, BANKING, OTHER_PLAN, "{\"pageId\":\"" + pageId + "\"}")
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(subjectOf(pageId)).isEqualTo(OTHER_PLAN);
            // The page is one page throughout: the release did not delete it and the second link
            // did not copy it.
            assertThat(document(ENGINEER, BANKING, PLAN).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("unlinking keeps the page; unlinking twice is a 404, not a second 204")
        void unlinkingKeepsThePage() throws Exception {
            UUID pageId = idOf(start(ENGINEER, BANKING, PLAN));

            assertThat(unlinkPage(ENGINEER, BANKING, PLAN).getResponse().getStatus())
                    .isEqualTo(204);

            // This unlinks documentation; it does not delete it. The page is still there, still
            // readable, still carrying its blocks.
            MvcResult asPage = readPage(ENGINEER, BANKING, pageId);
            assertThat(asPage.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(asPage).get("blocks")).hasSize(4);
            assertThat(body(asPage).get("subjectType").isNull()).isTrue();

            // A 204 here would tell a caller pointed at the wrong plan that their unlink worked.
            assertThat(unlinkPage(ENGINEER, BANKING, PLAN).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("deleting the page releases the slot — the index is partial for this")
        void deletedDocumentReleasesTheSlot() throws Exception {
            UUID pageId = idOf(start(ENGINEER, BANKING, PLAN));

            assertThat(deletePage(ENGINEER, BANKING, pageId).getResponse().getStatus())
                    .isEqualTo(204);

            // The whole point of `WHERE deleted_at IS NULL`. With a plain unique index this is a
            // duplicate-key 500 about a row the user cannot see, and the plan is documentless for
            // ever with no repair anybody can perform through the product.
            assertThat(start(ENGINEER, BANKING, PLAN).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a sibling project's plan is a 404 — RLS scopes to the COMPANY, not the project")
        void planMustBeInThisProject() throws Exception {
            UUID pageId = idOf(create(ENGINEER, BANKING, SPECIFICATION));

            // The caller holds doc:edit on BOTH projects, so this cannot be the gate answering.
            assertThat(linkPage(ENGINEER, BANKING, SIBLING_PLAN, "{\"pageId\":\"" + pageId + "\"}")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(livePagesFor(SIBLING_PLAN)).isZero();
            assertThat(subjectOf(pageId)).isNull();
        }

        @Test
        @DisplayName("a sibling project's page is a 404, never a 409 or a 400")
        void pageMustBeInThisProject() throws Exception {
            // Created in the SIBLING project, by a caller who may write documentation in both — so
            // this is the project predicate answering and nothing else.
            UUID foreign = idOf(create(ENGINEER, SIBLING, SPECIFICATION));

            assertThat(linkPage(ENGINEER, BANKING, PLAN, "{\"pageId\":\"" + foreign + "\"}")
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(subjectOf(foreign)).isNull();
        }

        @Test
        @DisplayName("a body with no pageId is a field error, not a 500 three statements later")
        void pageIdIsRequired() throws Exception {
            MvcResult refused = linkPage(ENGINEER, BANKING, PLAN, "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("pageId");
        }

        @Test
        @DisplayName("a Viewer may read the document and may neither link nor unlink one")
        void gate() throws Exception {
            UUID pageId = idOf(create(ENGINEER, BANKING, SPECIFICATION));

            assertThat(linkPage(VIEWER, BANKING, PLAN, "{\"pageId\":\"" + pageId + "\"}")
                    .getResponse().getStatus()).isEqualTo(403);

            start(ENGINEER, BANKING, PLAN);
            // Both directions in one test: the refusal above proves the gate exists, and the 200
            // proves it is not refusing everybody — a read gated on doc:edit passes the first
            // assertion on its own.
            assertThat(document(VIEWER, BANKING, PLAN).getResponse().getStatus()).isEqualTo(200);
            assertThat(unlinkPage(VIEWER, BANKING, PLAN).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("both writes are audited, and the row names the plan")
        void bothWritesAreAudited() throws Exception {
            start(ENGINEER, BANKING, PLAN);
            unlinkPage(ENGINEER, BANKING, PLAN);

            // The contract's argument for making relinking two steps is that a swap must be in the
            // history. A row per write that did not say WHICH plan would leave a link and an
            // unlink of two different plans indistinguishable, which is the same as not recording
            // them.
            assertThat(auditDetails("doc_page.subject_linked")).contains(PLAN.toString());
            assertThat(auditDetails("doc_page.subject_unlinked")).contains(PLAN.toString());
        }

        @Test
        @DisplayName("the document is an ordinary page — same id, reachable at /doc-pages")
        void itIsJustAPage() throws Exception {
            UUID pageId = idOf(start(ENGINEER, BANKING, PLAN));

            MvcResult asPage = readPage(ENGINEER, BANKING, pageId);

            assertThat(asPage.getResponse().getStatus()).isEqualTo(200);
            // One page, two routes. If these ever diverge, the plan tab and the docs space are
            // showing two different documents to two people who think they are collaborating.
            assertThat(body(asPage).get("subjectId").asText()).isEqualTo(PLAN.toString());
        }
    }

    // =====================================================================
    // Links
    // =====================================================================

    @Nested
    @DisplayName("Links — doc_link's first writer, and the reads that were waiting for it")
    class Links {

        @Test
        @DisplayName("a linked case appears on the block, with its key and title")
        void linkIsServedOnTheBlock() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID blockId = firstBulletId(page);

            assertThat(link(ENGINEER, BANKING, idOfPage(page), blockId, CASE)
                    .getResponse().getStatus()).isEqualTo(201);

            JsonNode reread = body(document(ENGINEER, BANKING, PLAN));
            JsonNode links = blockById(reread, blockId).get("links");
            assertThat(links).hasSize(1);
            assertThat(links.get(0).get("sourceKey").asText()).isEqualTo("D1OLB-C1");
            assertThat(links.get(0).get("sourceTitle").asText()).isEqualTo("Sign in with a valid password");
            assertThat(links.get(0).get("relationship").asText()).isEqualTo("VERIFIES");
        }

        @Test
        @DisplayName("linking twice writes nothing and answers 200 — a recorded fact is not an error")
        void reLinkingIsIdempotent() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID pageId = idOfPage(page);
            UUID blockId = firstBulletId(page);

            assertThat(link(ENGINEER, BANKING, pageId, blockId, CASE).getResponse().getStatus())
                    .isEqualTo(201);
            MvcResult again = link(ENGINEER, BANKING, pageId, blockId, CASE);

            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            // The row count is the assertion. There is no unique constraint behind this — the read
            // is the whole protection — so a second row is what an unconditional insert produces,
            // and it doubles every count drawn off the table for ever.
            assertThat(linkCount(blockId)).isEqualTo(1);
        }

        @Test
        @DisplayName("a sibling project's case is a 404 and writes no row")
        void sourceMustBeInThisProject() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID blockId = firstBulletId(page);

            assertThat(link(ENGINEER, BANKING, idOfPage(page), blockId, SIBLING_CASE)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(linkCount(blockId)).isZero();
        }

        @Test
        @DisplayName("unlinking removes the row; unlinking twice is a 404, not a second 204")
        void unlinkingRemovesTheRow() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID pageId = idOfPage(page);
            UUID blockId = firstBulletId(page);

            UUID linkId = UUID.fromString(
                    body(link(ENGINEER, BANKING, pageId, blockId, CASE)).get("id").asText());

            assertThat(unlink(ENGINEER, BANKING, pageId, linkId).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(linkCount(blockId)).isZero();

            // Unlike the create, there is no fact here that is already true: the caller named a row
            // and the row is not there.
            assertThat(unlink(ENGINEER, BANKING, pageId, linkId).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a Viewer may not link")
        void gate() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            assertThat(link(VIEWER, BANKING, idOfPage(page), firstBulletId(page), CASE)
                    .getResponse().getStatus()).isEqualTo(403);
        }
    }

    // =====================================================================
    // The 409s that could not fire before
    // =====================================================================

    @Nested
    @DisplayName("Traceability — the two refusals that had no reachable cause until now")
    class Traceability {

        @Test
        @DisplayName("removing a linked block is refused, and nothing else on the page is rewritten")
        void editRefusesToBreakALink() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID pageId = idOfPage(page);
            UUID blockId = firstBulletId(page);
            link(ENGINEER, BANKING, pageId, blockId, CASE);

            // Every block EXCEPT the linked one — the shape a client produces by deleting a line.
            StringBuilder blocks = new StringBuilder();
            for (JsonNode block : body(document(ENGINEER, BANKING, PLAN)).get("blocks")) {
                if (block.get("id").asText().equals(blockId.toString())) {
                    continue;
                }
                if (blocks.length() > 0) {
                    blocks.append(',');
                }
                blocks.append(blockJson(block));
            }

            MvcResult refused = editPage(ENGINEER, BANKING, pageId,
                    "{\"title\":\"Renamed\",\"blocks\":[" + blocks + "]}", null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString()).contains("1 link points");
            // The refusal happens BEFORE any write, so the retitle did not land either. Asserting
            // only the status would pass on an implementation that flushed the rewrites first and
            // relied on a rollback the reader has to take on trust.
            assertThat(body(document(ENGINEER, BANKING, PLAN)).get("title").asText())
                    .isEqualTo("Release 24.1 — requirements");
        }

        @Test
        @DisplayName("deleting a page a case links to is refused")
        void deleteRefusesToBreakALink() throws Exception {
            JsonNode page = body(start(ENGINEER, BANKING, PLAN));
            UUID pageId = idOfPage(page);
            link(ENGINEER, BANKING, pageId, firstBulletId(page), CASE);

            MvcResult refused = deletePage(ENGINEER, BANKING, pageId);
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString()).contains("archive the page");
        }
    }

    // =====================================================================
    // level's three arms
    // =====================================================================

    @Nested
    @DisplayName("`level` — required on a heading, optional on a bullet, refused on the rest")
    class Depth {

        @Test
        @DisplayName("a list bullet keeps the depth it was sent")
        void bulletDepthRoundTrips() throws Exception {
            UUID pageId = idOf(create(ENGINEER, BANKING,
                    "{\"title\":\"Outline\",\"blocks\":["
                            + "{\"blockType\":\"list\",\"level\":3,\"text\":\"Deep\"}]}"));

            JsonNode block = body(readPage(ENGINEER, BANKING, pageId)).get("blocks").get(0);
            assertThat(block.get("level").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("a list bullet with no depth is accepted — an unindented bullet is ordinary")
        void bulletDepthIsOptional() throws Exception {
            assertThat(create(ENGINEER, BANKING,
                    "{\"title\":\"Outline\",\"blocks\":["
                            + "{\"blockType\":\"list\",\"text\":\"Flat\"}]}")
                    .getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a paragraph with a depth is still refused, and the message names both types")
        void paragraphStillRefusesADepth() throws Exception {
            MvcResult refused = create(ENGINEER, BANKING,
                    "{\"title\":\"Outline\",\"blocks\":["
                            + "{\"blockType\":\"paragraph\",\"level\":2,\"text\":\"No\"}]}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("Only a heading or a list bullet has a depth.");
        }

        @Test
        @DisplayName("a bullet depth out of range is a field error, not a silently clamped value")
        void bulletDepthIsBounded() throws Exception {
            MvcResult refused = create(ENGINEER, BANKING,
                    "{\"title\":\"Outline\",\"blocks\":["
                            + "{\"blockType\":\"list\",\"level\":40,\"text\":\"Way out\"}]}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("Indent depth is between 1 and 6.");
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult document(UUID userId, UUID projectId, UUID planId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/plans/" + planId + "/document")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    /**
     * A page of this project, written the way anybody writes one, then adopted by the plan.
     *
     * <p>Two requests, because that is now the only way a plan gets a document — and the reason
     * every test below still has a page with blocks to link cases to. The blocks are a heading and
     * three bullets at three depths so {@code level}'s middle arm stays covered now that the
     * template that used to cover it is gone.
     */
    private MvcResult start(UUID userId, UUID projectId, UUID planId) throws Exception {
        UUID pageId = idOf(create(userId, projectId, SPECIFICATION));
        return linkPage(userId, projectId, planId, "{\"pageId\":\"" + pageId + "\"}");
    }

    private MvcResult linkPage(UUID userId, UUID projectId, UUID planId, String payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/plans/" + planId + "/document")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private MvcResult unlinkPage(UUID userId, UUID projectId, UUID planId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/plans/" + planId + "/document")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult create(UUID userId, UUID projectId, String payload) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private MvcResult readPage(UUID userId, UUID projectId, UUID pageId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private MvcResult editPage(
            UUID userId, UUID projectId, UUID pageId, String payload, Long ifMatch)
            throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, String.valueOf(ifMatch));
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult deletePage(UUID userId, UUID projectId, UUID pageId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private MvcResult link(UUID userId, UUID projectId, UUID pageId, UUID blockId, UUID caseId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/blocks/" + blockId + "/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"TEST_CASE\",\"sourceId\":\"" + caseId + "\"}"))
                .andReturn();
    }

    private MvcResult unlink(UUID userId, UUID projectId, UUID pageId, UUID linkId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/links/" + linkId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    // =====================================================================
    // helpers — reading a response
    // =====================================================================

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }

    private static UUID idOfPage(JsonNode page) {
        return UUID.fromString(page.get("id").asText());
    }


    private static List<Integer> bulletDepths(JsonNode page) {
        return java.util.stream.StreamSupport
                .stream(page.get("blocks").spliterator(), false)
                .filter(block -> "list".equals(block.get("blockType").asText()))
                .map(block -> block.get("level").asInt())
                .toList();
    }

    /** The first bullet of the template — the one a case gets linked to. */
    private static UUID firstBulletId(JsonNode page) {
        for (JsonNode block : page.get("blocks")) {
            if ("list".equals(block.get("blockType").asText())) {
                return UUID.fromString(block.get("id").asText());
            }
        }
        throw new IllegalStateException("The template has no list block");
    }

    private static JsonNode blockById(JsonNode page, UUID blockId) {
        for (JsonNode block : page.get("blocks")) {
            if (block.get("id").asText().equals(blockId.toString())) {
                return block;
            }
        }
        throw new IllegalStateException("No block " + blockId);
    }

    /** One served block, as the edit endpoint accepts it back — ids included, which is the point. */
    private static String blockJson(JsonNode block) {
        StringBuilder out = new StringBuilder("{\"id\":\"")
                .append(block.get("id").asText())
                .append("\",\"blockType\":\"")
                .append(block.get("blockType").asText())
                .append('"');
        if (!block.get("level").isNull()) {
            out.append(",\"level\":").append(block.get("level").asInt());
        }
        out.append(",\"text\":").append(quote(block.get("text").asText())).append('}');
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char each : value.toCharArray()) {
            switch (each) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(each);
            }
        }
        return out.append('"').toString();
    }

    // =====================================================================
    // helpers — reading the database
    // =====================================================================

    private long livePagesFor(UUID planId) throws SQLException {
        return scalarLong(ACME, """
                SELECT COUNT(*) FROM doc_page
                 WHERE subject_type = 'TEST_PLAN'
                   AND subject_id = CAST(? AS uuid)
                   AND deleted_at IS NULL
                """, planId.toString());
    }

    /**
     * What the page row itself says it documents, or null.
     *
     * <p>Read from the database rather than from a response, because the assertions that need it
     * are about a write that must <em>not</em> have happened: a refusal asserted only on its status
     * code passes against an implementation that reassigned the page and then answered 409.
     */
    private UUID subjectOf(UUID pageId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT subject_id FROM doc_page WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, pageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                String value = rows.getString(1);
                return value == null ? null : UUID.fromString(value);
            }
        }
    }

    /** Every audit row's `changes` for one action, concatenated — the assertions ask "contains". */
    private String auditDetails(String action) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT changes FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.append(rows.getString(1));
                }
            }
        }
        return out.toString();
    }

    private long linkCount(UUID blockId) throws SQLException {
        return scalarLong(ACME,
                "SELECT COUNT(*) FROM doc_link WHERE target_block_id = CAST(? AS uuid)",
                blockId.toString());
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

    // =====================================================================
    // helpers — fixture
    // =====================================================================

    /**
     * The plans, suites and cases the links point at.
     *
     * <p>Written as SQL rather than through the API, deliberately: this class is about the
     * documentation module, and driving four {@code execution} and {@code repository} endpoints to
     * arrange its fixture makes every test here fail when one of those changes.
     */
    private void seedCatalogue() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            plan(connection, PLAN, BANKING, "TP-1", 1, "Release 24.1");
            plan(connection, SIBLING_PLAN, SIBLING, "TP-2", 2, "Payments 24.1");
            plan(connection, OTHER_PLAN, BANKING, "TP-3", 3, "Release 24.2");

            suite(connection, SUITE, BANKING, "D1OLB-S1", 1, "Sign in");
            suite(connection, SIBLING_SUITE, SIBLING, "D1SIB-S1", 1, "Cards");

            testCase(connection, CASE, BANKING, SUITE, "D1OLB-C1", 1,
                    "Sign in with a valid password");
            testCase(connection, SIBLING_CASE, SIBLING, SIBLING_SUITE, "D1SIB-C1", 1,
                    "Pay with a valid card");
        }
    }

    private void plan(Connection connection, UUID id, UUID projectId, String key, long seq,
            String name) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_plan (id, company_id, project_id, key, key_seq, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, seq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId, String key, long seq,
            String name) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, seq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long seq, String title) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, seq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
    }

    /**
     * Puts back everything a test can write, counters included.
     *
     * <p>Ordered by reference: {@code doc_link} before {@code doc_block}, and the catalogue after
     * the pages that link to it. The counter reset is what keeps every {@code DOC-1} assertion from
     * depending on the order the class ran in — §19's defect, which is green alone and red in the
     * suite.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM doc_link");
                execute(connection, "DELETE FROM doc_page_version");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, BANKING);
            seedProjectDefaults(connection, ACME, SIBLING);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

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

        UUID id = UUID.nameUUIDFromBytes(("plan-document-endpoint:" + marker).getBytes());
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
