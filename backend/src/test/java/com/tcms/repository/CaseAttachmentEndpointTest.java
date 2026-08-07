package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static java.nio.charset.StandardCharsets.UTF_8;

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
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentStore;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
/**
 * {@code /projects/{projectId}/cases/{caseId}/attachments} — decision round 3's #49 over HTTP.
 *
 * <p>{@code attachment.owner_type = 'CASE'} was declared by {@code V1} and had no writer for the
 * whole life of the product; this is the endpoint family that gives it one, and these are the
 * failures the family can have.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>An authorship arm creeping in on the delete.</strong>
 *       {@link Gates#anEngineerRemovesSomebodyElsesFile()} is the assertion that pins the decision,
 *       and it is the one a reviewer is most likely to "fix": copying {@code evidence:delete}'s
 *       {@code OWN} qualifier reads as obviously safer and is incoherent — it would make removing a
 *       case's screenshot stricter than removing the step that references it, in a product where
 *       {@code case:edit} already lets a Test Engineer rewrite a lead's whole case.</li>
 *   <li><strong>A write that reaches across the two path segments.</strong> The project gate is not
 *       the case gate, and the case gate is not the attachment gate — three segments, and
 *       {@link Scoping} drives all three.</li>
 *   <li><strong>The bytes outliving the row.</strong> A delete that removes the metadata and leaves
 *       the blob is a deletion that did not delete, which is the wrong answer for the one thing
 *       here a customer may need removed for privacy rather than tidiness.</li>
 *   <li><strong>The download inventing a permission.</strong> There is no {@code attachment:read};
 *       a Viewer who can see the case must be able to open its files, or the gate is one no
 *       administrator can grant their way out of.</li>
 *   <li><strong>{@code uploadedBy} answering null on a successful upload.</strong> §121's exact
 *       finding: the name is resolved through the tenancy filter, so resolving it after the
 *       transaction closed fails <em>closed</em> and nothing throws.</li>
 *   <li><strong>{@code V39} letting {@code STEP} back in.</strong> {@link OwnerTypes} reads the
 *       {@code CHECK} out of the live database and compares it to {@link AttachmentOwner}, because
 *       a catalogue-wide invariant needs a computed check rather than a careful reading (§120).</li>
 * </ol>
 *
 * <p>Company and user ids are {@code ac…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and §29's repeat of it.
 */
@AutoConfigureMockMvc
@DisplayName("Case attachments — the owner V1 declared and nothing wrote, and the OWN arm that is absent")
class CaseAttachmentEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ac000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("ac000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("ac001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("ac001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("ac002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code case:edit}. */
    private static final UUID LEAD = UUID.fromString("ac003000-0000-4000-8000-000000000001");
    /** Test Engineer — also holds {@code case:edit}, unqualified. */
    private static final UUID ENGINEER = UUID.fromString("ac003000-0000-4000-8000-000000000002");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("ac003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("ac003000-0000-4000-8000-000000000004");
    /**
     * <strong>Automation Service</strong> — the persona this class is really about. Holds
     * {@code result:submit} and {@code run:create} and deliberately not {@code case:edit}.
     */
    private static final UUID ROBOT = UUID.fromString("ac003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("ac004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("ac004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("ac004000-0000-4000-8000-000000000003");

    /** The case most tests here act on. */
    private static final UUID CASE_A = UUID.fromString("ac005000-0000-4000-8000-000000000001");
    /** A second, so a test that attaches need not fight one asserting an empty list. */
    private static final UUID CASE_B = UUID.fromString("ac005000-0000-4000-8000-000000000002");
    /** A third, so the per-owner scoping has something to be wrong about. */
    private static final UUID CASE_C = UUID.fromString("ac005000-0000-4000-8000-000000000003");
    /** In the sibling project nobody here is granted. */
    private static final UUID LOCKED_CASE = UUID.fromString("ac005000-0000-4000-8000-000000000004");
    /** In the other client, deliberately keyed like Acme's. */
    private static final UUID UMBRA_CASE = UUID.fromString("ac005000-0000-4000-8000-000000000005");

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
     * <p>Reading the filesystem through the port rather than through a path built here: the root is
     * a property, and a test that recomputed it would pass against an adapter that had stopped
     * using it.
     */
    @Autowired
    private AttachmentStore store;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "at-acme", "Acme", now);
            company(UMBRA, "at-umbra", "Umbra", now);

            activeUser(LEAD, "lead@at-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@at-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@at-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@at-acme.test", "N. Grant", now);
            activeUser(ROBOT, "ci@at-acme.test", "CI Runner", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "AQOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "AQLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ROBOT, "CI Runner", now);

            grantOnProject("at-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("at-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("at-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            grantOnProject("at-robot", ACME, Role.AUTOMATION_SERVICE_ID, ROBOT, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "AQUOL", "Online Banking", now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedContent();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>{@code attachment} before {@code test_case}: the two are joined only by a polymorphic
     * {@code owner_id} with no foreign key, so nothing cascades and a left-behind row would be an
     * orphan the NEXT test finds attached to a case it just created with the same id. Green alone,
     * red in the suite — §19's defect in this class's clothes.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM attachment");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — case:edit on both writes, and deliberately no OWN arm")
    class Gates {

        @Test
        @DisplayName("a Test Engineer attaches a file and gets it back rendered")
        void engineerAttaches() throws Exception {
            MvcResult result = attach(ENGINEER, ACME, ACME_BANKING, CASE_A,
                    "acceptance-criteria.pdf", "application/pdf", "%PDF-1.4".getBytes(UTF_8));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);

            JsonNode file = json.readTree(result.getResponse().getContentAsString());
            assertThat(file.get("filename").asText()).isEqualTo("acceptance-criteria.pdf");
            assertThat(file.get("contentType").asText()).isEqualTo("application/pdf");
            assertThat(file.get("sizeBytes").asLong()).isEqualTo(8);

            // §121's finding, one slice on: the uploader is resolved through the tenancy filter,
            // so resolving it OUTSIDE the service's transaction runs with no app.current_company
            // and names nobody. That failure is silent — the field is simply null on every
            // successful upload and nothing throws — so it has to be asserted here or not at all.
            assertThat(file.get("uploadedBy").get("id").asText())
                    .isEqualTo(ENGINEER.toString());
            assertThat(file.get("uploadedBy").get("displayName").asText())
                    .isEqualTo("E. Engineer");

            // The Location header names something that actually serves bytes. POST /ingestions
            // answered 201 with a Location that 404'd from the day it shipped until §119; this is
            // the assertion that would have caught it.
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo(file.get("url").asText());
            assertThat(download(ENGINEER, ACME, ACME_BANKING, CASE_A,
                            UUID.fromString(file.get("id").asText()))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a Viewer cannot attach, and nothing is written when refused")
        void viewerCannotAttach() throws Exception {
            MvcResult refused = attach(VIEWER, ACME, ACME_BANKING, CASE_A, "notes.txt",
                    "text/plain", "x".getBytes(UTF_8));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(attachmentRows(ACME, CASE_A))
                    .as("a 403 that wrote the row anyway is worse than either answer alone")
                    .isZero();
        }

        @Test
        @DisplayName("AUTOMATION SERVICE cannot attach — it does not hold case:edit")
        void automationServiceCannotAttach() throws Exception {
            // Not a security claim as sharp as §121's, but the same reasoning: the CI credential is
            // the realistic breach, and the smallest role in the product stays small by holding
            // result:submit and nothing that edits a case definition.
            assertThat(attach(ROBOT, ACME, ACME_BANKING, CASE_A, "junit.xml", "text/xml",
                            "<testsuite/>".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("and the robot's refusal is the permission, not a broken route or token")
        void theRobotReachesTheCase() throws Exception {
            // Without this, the test above passes equally well against a route that does not exist
            // or a token that is wrong — and would keep passing after the gate was removed.
            assertThat(get(ROBOT, ACME, ACME_BANKING, CASE_A).getResponse().getStatus())
                    .as("Automation Service holds case:read, so it sees the case it cannot edit")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("A TEST ENGINEER REMOVES A FILE A LEAD ATTACHED — there is no OWN arm here")
        void anEngineerRemovesSomebodyElsesFile() throws Exception {
            // THE assertion of this slice, and the one most likely to be "fixed" into failing.
            // evidence:delete is OWN-qualified for a Test Engineer and copying that here reads as
            // obviously safer. It is incoherent: case:edit already lets this same person rewrite
            // every step, the title and the preconditions of the lead's case, so refusing them the
            // screenshot would make the file stricter than the step that references it.
            UUID leadsFile = attached(LEAD, ACME_BANKING, CASE_A, "lead-spec.pdf");

            assertThat(remove(ENGINEER, ACME, ACME_BANKING, CASE_A, leadsFile)
                    .getResponse().getStatus()).isEqualTo(204);
            assertThat(attachmentRows(ACME, CASE_A)).isZero();
        }

        @Test
        @DisplayName("a Viewer cannot remove, and the file survives the refusal")
        void viewerCannotRemove() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, CASE_A, "spec.pdf");

            assertThat(remove(VIEWER, ACME, ACME_BANKING, CASE_A, file)
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(attachmentRows(ACME, CASE_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("a Viewer CAN download — there is no attachment:read and there must not be")
        void viewerCanDownload() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, CASE_A, "spec.pdf");

            // Anyone who may read the case may read what is attached to it. Inventing a read key
            // here would be a permission no role is granted, which is a 403 nobody can fix — the
            // standing argument that has kept evidence:read out of the catalogue since M0.
            MvcResult served = download(VIEWER, ACME, ACME_BANKING, CASE_A, file);

            assertThat(served.getResponse().getStatus()).isEqualTo(200);
            assertThat(served.getResponse().getContentAsString()).isEqualTo("hello");
            assertThat(served.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("inline; filename=\"spec.pdf\"");
        }

        @Test
        @DisplayName("a member with no project grant gets 404, never 403")
        void noGrantIsNotFound() throws Exception {
            // §6t: a 403 would confirm the project exists to somebody who was never given it.
            assertThat(attach(NO_GRANT, ACME, ACME_BANKING, CASE_A, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("Scoping — three path segments, three separate gates")
    class Scoping {

        @Test
        @DisplayName("a case in a project the caller was not granted is 404, not a write")
        void siblingProjectIsRefused() throws Exception {
            assertThat(attach(LEAD, ACME, ACME_LOCKED, LOCKED_CASE, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(attachmentRows(ACME, LOCKED_CASE)).isZero();
        }

        @Test
        @DisplayName("a granted project plus a case that is not in it is 404")
        void caseMustBeInThatProject() throws Exception {
            // Without the second lookup, /projects/{granted}/cases/{ungranted} attaches to a case
            // the caller was never given — the gate CaseQuarantineService states in these words.
            assertThat(attach(LEAD, ACME, ACME_BANKING, LOCKED_CASE, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("AN ATTACHMENT OF ANOTHER CASE CANNOT BE REACHED THROUGH THIS ONE")
        void attachmentIsScopedToItsOwner() throws Exception {
            UUID onB = attached(LEAD, ACME_BANKING, CASE_B, "b.txt");

            // The id is real, the case is real, the caller may edit both — and the pair is still
            // wrong. An id-only lookup would serve and delete it happily.
            assertThat(download(LEAD, ACME, ACME_BANKING, CASE_A, onB)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(remove(LEAD, ACME, ACME_BANKING, CASE_A, onB)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(attachmentRows(ACME, CASE_B))
                    .as("the refused delete must not have removed it from its real owner")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("another company's case is 404 even with a valid session here")
        void crossCompanyIsRefused() throws Exception {
            assertThat(attach(LEAD, ACME, UMBRA_BANKING, UMBRA_CASE, "x.txt", "text/plain",
                            "x".getBytes(UTF_8)).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(attachmentRows(UMBRA, UMBRA_CASE)).isZero();
        }
    }

    @Nested
    @DisplayName("The detail — where the list is served, and the shape of nothing")
    class Detail {

        @Test
        @DisplayName("an unattached case serves [] and never null")
        void emptyIsAList() throws Exception {
            JsonNode attachments = detail(LEAD, ACME, ACME_BANKING, CASE_A).get("attachments");

            // Unlike `flake` one field over, there is no third state to distinguish here. A
            // nullable list would hand the SPA two spellings of nothing — the defect §121 phase 4
            // found on that neighbouring field.
            assertThat(attachments).isNotNull();
            assertThat(attachments.isArray()).isTrue();
            assertThat(attachments).isEmpty();
        }

        @Test
        @DisplayName("files render on the case, oldest first")
        void filesRenderInUploadOrder() throws Exception {
            attached(LEAD, ACME_BANKING, CASE_A, "first.txt");
            attached(ENGINEER, ACME_BANKING, CASE_A, "second.txt");

            JsonNode attachments = detail(VIEWER, ACME, ACME_BANKING, CASE_A).get("attachments");

            // created_at order, not filename order: the panel reads as a history of what somebody
            // added, and re-sorting would land the newest upload in the middle of the list it was
            // just appended to.
            assertThat(attachments).hasSize(2);
            assertThat(attachments.get(0).get("filename").asText()).isEqualTo("first.txt");
            assertThat(attachments.get(1).get("filename").asText()).isEqualTo("second.txt");
            assertThat(attachments.get(1).get("uploadedBy").get("displayName").asText())
                    .isEqualTo("E. Engineer");
        }

        @Test
        @DisplayName("A FILE ON ONE CASE DOES NOT RENDER ON ANOTHER")
        void listIsScopedToItsCase() throws Exception {
            attached(LEAD, ACME_BANKING, CASE_A, "a.txt");

            // The batch resolve keys on owner_id. A grouping bug there is invisible on a
            // single-case fixture and shows up as every case sharing one list.
            assertThat(detail(LEAD, ACME, ACME_BANKING, CASE_B).get("attachments")).isEmpty();
            assertThat(detail(LEAD, ACME, ACME_BANKING, CASE_C).get("attachments")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Writes — the ordering, the bytes, and what the trail keeps")
    class Writes {

        @Test
        @DisplayName("REMOVING A FILE TAKES ITS BYTES WITH IT — a hard delete, not a soft one")
        void deleteRemovesTheBytes() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, CASE_A, "private.pdf");
            String key = storageKeyOf(ACME, file);
            assertThat(store.exists(key)).isTrue();

            assertThat(remove(LEAD, ACME, ACME_BANKING, CASE_A, file)
                    .getResponse().getStatus()).isEqualTo(204);

            // A soft-deleted row pointing at retained bytes is a deletion that did not delete,
            // which is the wrong answer for the one thing here a customer may need removed for
            // privacy rather than tidiness.
            assertThat(attachmentRows(ACME, CASE_A)).isZero();
            assertThat(store.exists(key)).isFalse();
            assertThat(download(LEAD, ACME, ACME_BANKING, CASE_A, file)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the storage key contains nothing the client sent")
        void theKeyIsAllServerUuids() throws Exception {
            UUID file = attached(LEAD, ACME_BANKING, CASE_A, "../../../etc/passwd");

            // The whole traversal defence: the filename is a COLUMN and never part of a path. It
            // is reduced to its last segment anyway, because a string that looks like a path
            // invites the next reader to use it as one.
            String key = storageKeyOf(ACME, file);
            assertThat(key).isEqualTo(ACME + "/" + CASE_A + "/" + key.substring(
                    key.lastIndexOf('/') + 1));
            assertThat(key).doesNotContain("..").doesNotContain("passwd");

            assertThat(detail(LEAD, ACME, ACME_BANKING, CASE_A)
                    .get("attachments").get(0).get("filename").asText())
                    .isEqualTo("passwd");
        }

        @Test
        @DisplayName("an empty part is a 400 on the file field, not a zero-byte attachment")
        void emptyUploadIsRefused() throws Exception {
            MvcResult refused = attach(LEAD, ACME, ACME_BANKING, CASE_A, "empty.txt",
                    "text/plain", new byte[0]);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("file");
            assertThat(attachmentRows(ACME, CASE_A)).isZero();
        }

        @Test
        @DisplayName("a client that sends no content type gets the generic one, never null")
        void missingContentTypeDefaults() throws Exception {
            MvcResult result = attach(LEAD, ACME, ACME_BANKING, CASE_A, "blob", null,
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
            UUID file = attached(LEAD, ACME_BANKING, CASE_A, "signed-off.pdf");
            remove(LEAD, ACME, ACME_BANKING, CASE_A, file);

            assertThat(auditActions(ACME))
                    .containsExactly("case.attachment.add", "case.attachment.remove");

            // Once the row and the bytes are gone this line is the ONLY surviving statement that
            // the file existed — §121's argument for keeping a quarantine reason in the trail, one
            // entity over.
            assertThat(auditChanges(ACME, "case.attachment.remove"))
                    .contains("signed-off.pdf");
        }
    }

    @Nested
    @DisplayName("V39 — the owner vocabulary, checked against the database rather than read")
    class OwnerTypes {

        @Test
        @DisplayName("THE CHECK CONSTRAINT AND AttachmentOwner NAME THE SAME FIVE VALUES")
        void theEnumAndTheConstraintAgree() throws Exception {
            // §120's rule: a catalogue-wide invariant needs a computed check, not a careful
            // reading. Adding a value to the enum without a migration — or narrowing the CHECK
            // without retiring the factory — fails here rather than at somebody's INSERT.
            java.util.Set<String> declared = java.util.Arrays.stream(AttachmentOwner.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(constraintValues()).isEqualTo(declared);
        }

        @Test
        @DisplayName("STEP is gone, and it is gone because it was reconsidered and not deferred")
        void stepIsNotInTheConstraint() throws Exception {
            // #50. A step's file belongs to the recorded RESULT (STEP_RESULT, written since §67),
            // not to the case definition — a file on the step definition would be versioned with
            // the case and frozen into every snapshot that quoted the step. Leaving the value in
            // would keep the enum-with-no-consumer check reporting one permanent dead entry, which
            // is how a check stops being read.
            assertThat(constraintValues()).doesNotContain("STEP");
            assertThat(constraintValues())
                    .containsExactlyInAnyOrder("CASE", "EXECUTION", "STEP_RESULT", "DOC_PAGE",
                            "ARTIFACT");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private MvcResult attach(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            String filename, String contentType, byte[] bytes) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .multipart("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/attachments")
                        .file(new MockMultipartFile("file", filename, contentType, bytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult remove(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            UUID attachmentId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/attachments/" + attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult download(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            UUID attachmentId) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId + "/attachments/"
                                + attachmentId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode detail(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return json.readTree(get(userId, companyId, projectId, caseId)
                .getResponse().getContentAsString());
    }

    /** Attaches and returns the new row's id — the setup half of most tests here. */
    private UUID attached(UUID userId, UUID projectId, UUID caseId, String filename)
            throws Exception {
        MvcResult result = attach(userId, ACME, projectId, caseId, filename, "text/plain",
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private int attachmentRows(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM attachment "
                                + "WHERE owner_type = 'CASE' AND owner_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
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
                List<String> actions = new java.util.ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return actions;
            }
        }
    }

    /** The five values {@code V39} left in the {@code CHECK}, read out of the live database. */
    private java.util.Set<String> constraintValues() throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conname = 'attachment_owner_type_check'")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the constraint V39 rewrites must exist under this name")
                        .isTrue();
                java.util.regex.Matcher values =
                        java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(rows.getString(1));
                java.util.Set<String> found = new java.util.LinkedHashSet<>();
                while (values.find()) {
                    found.add(values.group(1));
                }
                return found;
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

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void seedContent() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-at-1", 1, "Payments");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-at-2", 2, "Internal");

            testCase(connection, CASE_A, ACME, ACME_BANKING, PAYMENTS, "TC-at-1", 901,
                    "Checkout with a saved card");
            testCase(connection, CASE_B, ACME, ACME_BANKING, PAYMENTS, "TC-at-2", 902,
                    "Checkout with a new card");
            testCase(connection, CASE_C, ACME, ACME_BANKING, PAYMENTS, "TC-at-3", 903,
                    "Refund a settled payment");
            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-at-4", 904,
                    "In the locked project");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are numbered per company, so the collision is
            // the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-at-1", 1, "Payments");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-at-1", 901,
                    "Checkout with a saved card");
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
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

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, String title) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, automation_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        ?, ?, ?, 'AUTOMATED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-attachment-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
