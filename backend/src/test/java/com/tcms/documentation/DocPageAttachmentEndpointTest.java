package com.tcms.documentation;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import com.tcms.storage.AttachmentStore;
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
 * {@code /projects/{projectId}/doc-pages/{pageId}/attachments} — #49's phase 2 over HTTP.
 *
 * <p>{@code attachment.owner_type = 'DOC_PAGE'} was declared by {@code V1} and had no writer for the
 * whole life of the product; this is the endpoint family that gives it one.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The gate drifting to {@code doc:publish}.</strong>
 *       {@link Gates#anEngineerWithoutPublishCanAttach()} is the assertion that pins the decision.
 *       This module's whole shape is that {@code doc:edit} writes content and {@code doc:publish}
 *       moves the publication state, and an attachment is content — the other choice leaves a Test
 *       Engineer able to author a specification they cannot illustrate, which nothing else in the
 *       suite would notice because every other doc write they make would keep working.</li>
 *   <li><strong>An authorship arm creeping in on the delete.</strong>
 *       {@link Gates#anEngineerRemovesSomebodyElsesFile()}, for the reason
 *       {@code CaseAttachmentEndpointTest} states one module over: copying {@code evidence:delete}'s
 *       {@code OWN} qualifier reads as safer and would make the image stricter than the paragraph
 *       referencing it, in a module where {@code doc:edit} already deletes a lead's paragraph.</li>
 *   <li><strong>A write that reaches across the two path segments.</strong> The project gate is not
 *       the page gate, and the page gate is not the attachment gate — {@link Scoping} drives all
 *       three.</li>
 *   <li><strong>An edit swallowing the file list.</strong>
 *       {@link Detail#anEditDoesNotEmptyTheList()}: {@code PATCH} answers with the whole page, and
 *       the SPA renders that answer, so a response that omits the attachments makes a save appear to
 *       delete every file until the next {@code GET} puts them back. Nothing throws.</li>
 *   <li><strong>The bytes outliving the row.</strong> A delete that removes the metadata and leaves
 *       the blob is a deletion that did not delete.</li>
 *   <li><strong>{@code uploadedBy} answering null on a successful upload.</strong> §121's exact
 *       finding: the name is resolved through the tenancy filter, so resolving it after the
 *       transaction closed fails <em>closed</em> and nothing throws.</li>
 * </ol>
 *
 * <p>Company and user ids are {@code df…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and its five repeats.
 */
@AutoConfigureMockMvc
@DisplayName("Doc page attachments — content, so doc:edit and not doc:publish")
class DocPageAttachmentEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("df000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("df000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("df001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("df001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("df002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code doc:edit} and {@code doc:publish}. */
    private static final UUID LEAD = UUID.fromString("df003000-0000-4000-8000-000000000001");
    /**
     * <strong>Test Engineer</strong> — the persona this class is really about. Holds
     * {@code doc:edit} and deliberately <em>not</em> {@code doc:publish} (roles spec §A2).
     */
    private static final UUID ENGINEER = UUID.fromString("df003000-0000-4000-8000-000000000002");
    /** Viewer — reads the project and writes nothing. */
    private static final UUID VIEWER = UUID.fromString("df003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding no grant here. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("df003000-0000-4000-8000-000000000004");
    /**
     * A colleague who works on the <em>other</em> Acme project and only that one.
     *
     * <p>They exist so {@link #lockedPage} can be authored at all: a page in a project nobody holds
     * cannot be created through the endpoint, and seeding one by hand would be a page shaped unlike
     * every real one (no display key, no path, no space). This is also the honest fixture — the
     * sibling project a caller is refused is somebody else's project, not an empty one.
     */
    private static final UUID OTHER_LEAD = UUID.fromString("df003000-0000-4000-8000-000000000005");

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

    /**
     * The blob store itself, so the bytes can be asserted directly.
     *
     * <p>Read through the port rather than through a path rebuilt here: the root is a property, and
     * a test that recomputed it would pass against an adapter that had stopped using it.
     */
    @Autowired
    private AttachmentStore store;

    /** The page most tests act on, created through the API in {@link #seed()}. */
    private UUID page;
    /**
     * {@link #page}'s display key, read off the create response.
     *
     * <p>NOT asserted as {@code DOC-1}: the counter is per <em>company</em> and every page any test
     * in this class creates advances it, so the literal was right on the first run and wrong on the
     * second. That is the shape §29's key allocator has, working as designed.
     */
    private String pageKey;
    /** A second, so the per-owner scoping has something to be wrong about. */
    private UUID otherPage;
    /** In the sibling project nobody here is granted. */
    private UUID lockedPage;
    /** In the other client. */
    private UUID umbraPage;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "df-acme", "Acme", now);
            company(UMBRA, "df-umbra", "Umbra", now);

            activeUser(LEAD, "lead@df-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@df-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@df-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@df-acme.test", "N. Grant", now);
            activeUser(OTHER_LEAD, "other@df-acme.test", "O. Other", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "DFOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "DFLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, OTHER_LEAD, "O. Other", now);

            grantOnProject("df-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("df-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("df-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            // OTHER_LEAD holds ACME_LOCKED and nobody else does, which is what makes the
            // sibling-project 404 a real refusal rather than a typo in a project id.
            grantOnProject("df-other", ACME, Role.TEST_LEAD_ID, OTHER_LEAD, ACME_LOCKED, now);
        }));

        // Umbra's rows are written under UMBRA's context, and that is not tidiness: role_assignment
        // is under FORCE ROW LEVEL SECURITY, so an INSERT of an UMBRA-owned grant inside the ACME
        // block is refused by the policy rather than silently misfiled. It was written that way
        // first and all 22 tests failed on it.
        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "DFUOL", "Online Banking", now);
            member(UMBRA, LEAD, "L. Lead", now);
            // The same person, genuinely granted in the other company — so the cross-company 404
            // below is the tenancy boundary refusing, not an absent grant.
            grantOnProject("df-lead-umbra", UMBRA, Role.TEST_LEAD_ID, LEAD, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        seedProjectDefaults(ACME, ACME_BANKING);
        seedProjectDefaults(ACME, ACME_LOCKED);
        seedProjectDefaults(UMBRA, UMBRA_BANKING);

        resetMutableRows();

        // Pages are created through the endpoint rather than by SQL, which is DocPageEndpointTest's
        // convention: the display key, the path and the space lookup are all server-owned, and a
        // hand-written row would be a page shaped unlike every real one.
        page = createPage(LEAD, ACME, ACME_BANKING, "Checkout specification");
        pageKey = keyOf(ACME, page);
        otherPage = createPage(LEAD, ACME, ACME_BANKING, "Refunds specification");
        lockedPage = createPage(OTHER_LEAD, ACME, ACME_LOCKED, "Internal notes");
        umbraPage = createPage(LEAD, UMBRA, UMBRA_BANKING, "Checkout specification");

        // AFTER the pages exist: creating them writes audit rows, and the trail assertions below
        // count actions rather than filtering them.
        clearAuditRows();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>{@code attachment} is joined to {@code doc_page} only by a polymorphic {@code owner_id}
     * with no foreign key, so nothing cascades and a left-behind row is an orphan the NEXT test
     * finds attached to a page it just created. Green alone, red in the suite — §19's defect again.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM attachment");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page_version");
                execute(connection, "DELETE FROM doc_page");
            }
        }
    }

    private void clearAuditRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — doc:edit on both writes, no publish key and no OWN arm")
    class Gates {

        @Test
        @DisplayName("A TEST ENGINEER WITHOUT doc:publish CAN ATTACH — an attachment is content")
        void anEngineerWithoutPublishCanAttach() throws Exception {
            // THE assertion of this phase. Test Engineer holds doc:edit and not doc:publish, and
            // gating the file on the publish key would leave them able to author a specification
            // they could not illustrate. Every other doc write they make would keep working, so
            // nothing else in the suite would notice the drift.
            MvcResult result = attach(ENGINEER, ACME, ACME_BANKING, page,
                    "checkout-wireframe.png", "image/png", "PNG!".getBytes(UTF_8));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);

            JsonNode file = json.readTree(result.getResponse().getContentAsString());
            assertThat(file.get("filename").asText()).isEqualTo("checkout-wireframe.png");
            assertThat(file.get("contentType").asText()).isEqualTo("image/png");
            assertThat(file.get("sizeBytes").asLong()).isEqualTo(4);

            // §121's finding: the uploader is resolved through the tenancy filter, so resolving it
            // OUTSIDE the service's transaction runs with no app.current_company and names nobody.
            // That failure is silent — the field is simply null on every successful upload — so it
            // has to be asserted here or not at all.
            assertThat(file.get("uploadedBy").get("id").asText()).isEqualTo(ENGINEER.toString());
            assertThat(file.get("uploadedBy").get("displayName").asText())
                    .isEqualTo("E. Engineer");

            // The Location header names something that actually serves bytes. POST /ingestions
            // answered 201 with a Location that 404'd from the day it shipped until §119.
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo(file.get("url").asText());
            assertThat(download(ENGINEER, ACME, ACME_BANKING, page,
                            UUID.fromString(file.get("id").asText()))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("and that engineer genuinely cannot publish — the split is real, not assumed")
        void theEngineerStillCannotPublish() throws Exception {
            // Without this, the test above is consistent with a fixture that accidentally granted
            // doc:publish, and the decision it claims to pin would be unpinned.
            assertThat(setStatus(ENGINEER, ACME, ACME_BANKING, page, "PUBLISHED")
                    .getResponse().getStatus())
                    .as("Test Engineer holds doc:edit and not doc:publish (roles spec §A2)")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a Viewer cannot attach, and nothing is written when refused")
        void viewerCannotAttach() throws Exception {
            MvcResult refused = attach(VIEWER, ACME, ACME_BANKING, page, "notes.txt",
                    "text/plain", "x".getBytes(UTF_8));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(attachmentRows(ACME, page))
                    .as("a 403 that wrote the row anyway is worse than either answer alone")
                    .isZero();
        }

        @Test
        @DisplayName("A TEST ENGINEER REMOVES A FILE A LEAD ATTACHED — there is no OWN arm here")
        void anEngineerRemovesSomebodyElsesFile() throws Exception {
            // The one most likely to be "fixed" into failing. evidence:delete is OWN-qualified and
            // copying that here reads as obviously safer; it is incoherent, because doc:edit
            // already lets this same person delete the lead's paragraph beside the image.
            UUID leadsFile = attached(LEAD, ACME_BANKING, page, "lead-signoff.pdf");

            assertThat(remove(ENGINEER, ACME, ACME_BANKING, page, leadsFile)
                    .getResponse().getStatus()).isEqualTo(204);
            assertThat(attachmentRows(ACME, page)).isZero();
        }

        @Test
        @DisplayName("a Viewer cannot remove, and the file survives the refusal")
        void viewerCannotRemove() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "spec.pdf");

            assertThat(remove(VIEWER, ACME, ACME_BANKING, page, file)
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(attachmentRows(ACME, page)).isEqualTo(1);
        }

        @Test
        @DisplayName("a Viewer CAN download — there is no attachment:read and there must not be")
        void viewerCanDownload() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "spec.pdf");

            // Anyone who may read the page may read what is attached to it. Inventing a read key
            // would be a permission no role is granted, which is a 403 nobody can fix.
            MvcResult served = download(VIEWER, ACME, ACME_BANKING, page, file);

            assertThat(served.getResponse().getStatus()).isEqualTo(200);
            assertThat(served.getResponse().getContentAsString()).isEqualTo("hello");
            assertThat(served.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("inline; filename=\"spec.pdf\"");
        }

        @Test
        @DisplayName("a member with no project grant gets 404, never 403")
        void noGrantIsNotFound() throws Exception {
            // §6t: a 403 would confirm the project exists to somebody who was never given it.
            assertThat(attach(NO_GRANT, ACME, ACME_BANKING, page, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("AN ARCHIVED PAGE STILL ACCEPTS FILES — there is deliberately no status arm")
        void archivedPagesStillAcceptFiles() throws Exception {
            assertThat(setStatus(LEAD, ACME, ACME_BANKING, page, "ARCHIVED")
                    .getResponse().getStatus()).isEqualTo(200);

            // ARCHIVED takes a page out of circulation while keeping its links resolvable. Making
            // it a retention control is a different decision, and the page's own PATCH has no
            // status arm either — an arm here would make the image stricter than the paragraph for
            // the second time in one class.
            assertThat(attach(LEAD, ACME, ACME_BANKING, page, "late-addition.pdf",
                            "application/pdf", "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("Scoping — three path segments, three separate gates")
    class Scoping {

        @Test
        @DisplayName("a page in a project the caller was not granted is 404, not a write")
        void siblingProjectIsRefused() throws Exception {
            assertThat(attach(LEAD, ACME, ACME_LOCKED, lockedPage, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(attachmentRows(ACME, lockedPage)).isZero();
        }

        @Test
        @DisplayName("a granted project plus a page that is not in it is 404")
        void pageMustBeInThatProject() throws Exception {
            // Without the second lookup, /projects/{granted}/doc-pages/{ungranted} attaches to a
            // page the caller was never given.
            assertThat(attach(LEAD, ACME, ACME_BANKING, lockedPage, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("AN ATTACHMENT OF ANOTHER PAGE CANNOT BE REACHED THROUGH THIS ONE")
        void attachmentIsScopedToItsOwner() throws Exception {
            UUID onOther = attached(LEAD, ACME_BANKING, otherPage, "other.txt");

            // The id is real, the page is real, the caller may edit both — and the pair is still
            // wrong. An id-only lookup would serve and delete it happily.
            assertThat(download(LEAD, ACME, ACME_BANKING, page, onOther)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(remove(LEAD, ACME, ACME_BANKING, page, onOther)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(attachmentRows(ACME, otherPage))
                    .as("the refused delete must not have removed it from its real owner")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("another company's page is 404 even with a valid session here")
        void crossCompanyIsRefused() throws Exception {
            // A session for ACME against a page that exists, in a project this same person is
            // genuinely granted — in the other company. RLS and the context are what refuse it.
            assertThat(attach(LEAD, ACME, UMBRA_BANKING, umbraPage, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(attachmentRows(UMBRA, umbraPage)).isZero();
        }
    }

    @Nested
    @DisplayName("The detail — where the list is served, and the shape of nothing")
    class Detail {

        @Test
        @DisplayName("an unattached page serves [] and never null")
        void emptyIsAList() throws Exception {
            JsonNode attachments = detail(LEAD, ACME, ACME_BANKING, page).get("attachments");

            // A page with no files and a page nobody has attached to are the same page, so unlike
            // the `flake` on a case there is no third state and a nullable list would hand the SPA
            // two spellings of nothing — §121 phase 4's finding, avoided rather than repeated.
            assertThat(attachments).isNotNull();
            assertThat(attachments.isArray()).isTrue();
            assertThat(attachments).isEmpty();
        }

        @Test
        @DisplayName("files render on the page, oldest first")
        void filesRenderInUploadOrder() throws Exception {
            attached(LEAD, ACME_BANKING, page, "first.txt");
            attached(ENGINEER, ACME_BANKING, page, "second.txt");

            JsonNode attachments = detail(VIEWER, ACME, ACME_BANKING, page).get("attachments");

            // created_at order, not filename order: the panel reads as a history of what somebody
            // added, and re-sorting would land the newest upload in the middle of the list.
            assertThat(attachments).hasSize(2);
            assertThat(attachments.get(0).get("filename").asText()).isEqualTo("first.txt");
            assertThat(attachments.get(1).get("filename").asText()).isEqualTo("second.txt");
            assertThat(attachments.get(1).get("uploadedBy").get("displayName").asText())
                    .isEqualTo("E. Engineer");
        }

        @Test
        @DisplayName("A FILE ON ONE PAGE DOES NOT RENDER ON ANOTHER")
        void listIsScopedToItsPage() throws Exception {
            attached(LEAD, ACME_BANKING, page, "a.txt");

            assertThat(detail(LEAD, ACME, ACME_BANKING, otherPage).get("attachments")).isEmpty();
        }

        @Test
        @DisplayName("AN EDIT DOES NOT EMPTY THE LIST — the PATCH response is what the SPA renders")
        void anEditDoesNotEmptyTheList() throws Exception {
            attached(LEAD, ACME_BANKING, page, "wireframe.png");

            MvcResult edited = edit(LEAD, ACME, ACME_BANKING, page,
                    "{\"title\":\"Checkout specification v2\"}");
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);

            // An edit does not touch the attachments, but it answers with the whole page and the
            // SPA renders that answer. Serving [] here makes a save appear to delete every file on
            // the page until the next GET puts them back, with nothing failing anywhere.
            JsonNode attachments =
                    json.readTree(edited.getResponse().getContentAsString()).get("attachments");
            assertThat(attachments).hasSize(1);
            assertThat(attachments.get(0).get("filename").asText()).isEqualTo("wireframe.png");
        }
    }

    @Nested
    @DisplayName("Writes — the ordering, the bytes, and what the trail keeps")
    class Writes {

        @Test
        @DisplayName("REMOVING A FILE TAKES ITS BYTES WITH IT — a hard delete, not a soft one")
        void deleteRemovesTheBytes() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "private.pdf");
            String key = storageKeyOf(ACME, file);
            assertThat(store.exists(key)).isTrue();

            assertThat(remove(LEAD, ACME, ACME_BANKING, page, file)
                    .getResponse().getStatus()).isEqualTo(204);

            // Unlike the page itself, which is soft-deleted. A soft-deleted row pointing at
            // retained bytes is a deletion that did not delete, which is the wrong answer for the
            // one thing here a customer may need removed for privacy rather than tidiness.
            assertThat(attachmentRows(ACME, page)).isZero();
            assertThat(store.exists(key)).isFalse();
            assertThat(download(LEAD, ACME, ACME_BANKING, page, file)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the storage key contains nothing the client sent")
        void theKeyIsAllServerUuids() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "../../../etc/passwd");

            // The whole traversal defence: the filename is a COLUMN and never part of a path. It
            // is reduced to its last segment anyway, because a string that looks like a path
            // invites the next reader to use it as one.
            String key = storageKeyOf(ACME, file);
            assertThat(key).isEqualTo(
                    ACME + "/" + page + "/" + key.substring(key.lastIndexOf('/') + 1));
            assertThat(key).doesNotContain("..").doesNotContain("passwd");

            assertThat(detail(LEAD, ACME, ACME_BANKING, page)
                    .get("attachments").get(0).get("filename").asText())
                    .isEqualTo("passwd");
        }

        @Test
        @DisplayName("an empty part is a 400 on the file field, not a zero-byte attachment")
        void emptyUploadIsRefused() throws Exception {
            MvcResult refused = attach(LEAD, ACME, ACME_BANKING, page, "empty.txt",
                    "text/plain", new byte[0]);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("file");
            assertThat(attachmentRows(ACME, page)).isZero();
        }

        @Test
        @DisplayName("a client that sends no content type gets the generic one, never null")
        void missingContentTypeDefaults() throws Exception {
            MvcResult result = attach(LEAD, ACME, ACME_BANKING, page, "blob", null,
                    "x".getBytes(UTF_8));

            // content_type is NOT NULL. Guessing from the extension would be worse: it would state
            // a type nobody claimed, and the browser trusts this header when rendering.
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("contentType").asText())
                    .isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("THE TRAIL KEEPS THE FILENAME, which nothing else survives the delete")
        void auditKeepsWhatTheDeleteDestroys() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "signed-off.pdf");
            remove(LEAD, ACME, ACME_BANKING, page, file);

            assertThat(auditActions(ACME))
                    .containsExactly("doc_page.attachment.add", "doc_page.attachment.remove");

            // Once the row and the bytes are gone this line is the ONLY surviving statement that
            // the file existed.
            assertThat(auditChanges(ACME, "doc_page.attachment.remove"))
                    .contains("signed-off.pdf");
        }

        @Test
        @DisplayName("A FILE AN IMAGE BLOCK SHOWS CANNOT BE DELETED — 409, and the bytes survive")
        void aReferencedFileIsRefused() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "wireframe.png");
            String key = storageKeyOf(ACME, file);

            assertThat(edit(LEAD, ACME, ACME_BANKING, page,
                    "{\"title\":\"Checkout specification\",\"blocks\":[{\"blockType\":\"image\","
                            + "\"text\":\"The checkout screen\",\"attachmentId\":\"" + file
                            + "\"}]}")
                    .getResponse().getStatus())
                    .isEqualTo(200);

            MvcResult refused = remove(LEAD, ACME, ACME_BANKING, page, file);

            // An `image` block stores an id and nothing else — no bytes, no URL — so deleting the
            // attachment underneath it leaves a specification with a hole in it. There is no
            // foreign key doing this: the reference lives inside a JSONB document.
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("image block")
                    .contains("Remove or repoint");

            // AND NOTHING HAPPENED. The guard runs before the row and long before the bytes, which
            // do not come back — a 409 raised after `store.delete` would be a refusal that had
            // already destroyed the thing it refused to destroy.
            assertThat(attachmentRows(ACME, page)).isEqualTo(1);
            assertThat(store.exists(key)).isTrue();
        }

        @Test
        @DisplayName("removing the block releases the file, and another page's block never held it")
        void theRefusalIsScopedToTheBlockThatCausedIt() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, page, "wireframe.png");
            edit(LEAD, ACME, ACME_BANKING, page,
                    "{\"title\":\"Checkout specification\",\"blocks\":[{\"blockType\":\"image\","
                            + "\"text\":\"The checkout screen\",\"attachmentId\":\"" + file
                            + "\"}]}");

            // The remedy the 409's sentence names, driven: drop the block, then the file goes.
            edit(LEAD, ACME, ACME_BANKING, page,
                    "{\"title\":\"Checkout specification\",\"blocks\":[]}");

            assertThat(remove(LEAD, ACME, ACME_BANKING, page, file)
                    .getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(attachmentRows(ACME, page)).isZero();
        }

        @Test
        @DisplayName("the trail names the page by its key, so the page's history reads as one")
        void auditNamesThePage() throws Exception {
            attached(LEAD, ACME_BANKING, page, "diagram.svg");

            // doc_page as the audit target, not an attachment target of its own: the question a
            // reader asks is "what happened to DOC-1", and a row keyed on an id that no longer
            // exists after the hard delete answers nothing.
            assertThat(auditChanges(ACME, "doc_page.attachment.add"))
                    .contains("\"key\": \"" + pageKey + "\"")
                    .contains("Checkout specification");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private MvcResult attach(UUID userId, UUID companyId, UUID projectId, UUID pageId,
            String filename, String contentType, byte[] bytes) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .multipart("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/attachments")
                        .file(new MockMultipartFile("file", filename, contentType, bytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult remove(UUID userId, UUID companyId, UUID projectId, UUID pageId,
            UUID attachmentId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/attachments/" + attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult download(UUID userId, UUID companyId, UUID projectId, UUID pageId,
            UUID attachmentId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/attachments/" + attachmentId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID pageId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode detail(UUID userId, UUID companyId, UUID projectId, UUID pageId)
            throws Exception {
        return json.readTree(get(userId, companyId, projectId, pageId)
                .getResponse().getContentAsString());
    }

    private MvcResult edit(UUID userId, UUID companyId, UUID projectId, UUID pageId, String payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + projectId + "/doc-pages/" + pageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private MvcResult setStatus(UUID userId, UUID companyId, UUID projectId, UUID pageId,
            String status) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/doc-pages/" + pageId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn();
    }

    private UUID createPage(UUID userId, UUID companyId, UUID projectId, String title)
            throws Exception {

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + json.writeValueAsString(title) + "}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture's own page create must succeed, or every test below is vacuous")
                .isEqualTo(201);

        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    /** Attaches and returns the new row's id — the setup half of most tests here. */
    private UUID attached(UUID userId, UUID projectId, UUID pageId, String filename)
            throws Exception {

        MvcResult result = attach(userId, ACME, projectId, pageId, filename, "text/plain",
                "hello".getBytes(UTF_8));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private int attachmentRows(UUID companyId, UUID pageId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM attachment "
                                + "WHERE owner_type = 'DOC_PAGE' AND owner_id = CAST(? AS uuid)")) {
            statement.setString(1, pageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private String keyOf(UUID companyId, UUID pageId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT key FROM doc_page WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, pageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private String storageKeyOf(UUID companyId, UUID attachmentId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT storage_key FROM attachment WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, attachmentId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private List<String> auditActions(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT action FROM audit_event ORDER BY occurred_at, action")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<String> actions = new ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return actions;
            }
        }
    }

    private String auditChanges(UUID companyId, String action) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT changes FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
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

        UUID id = assignmentId(marker);
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

    /** Seeds the {@code doc_space} every page needs — {@code V18}'s per-project defaults. */
    private void seedProjectDefaults(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("doc-page-attachment-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
