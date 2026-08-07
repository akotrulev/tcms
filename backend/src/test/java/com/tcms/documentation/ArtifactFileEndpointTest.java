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
 * {@code POST /projects/{projectId}/artifacts/files} and the download beside it — #49's phase 3.
 *
 * <p>{@code ArtifactKind.FILE} said "not registrable yet" in three places and
 * {@code attachment.owner_type = 'ARTIFACT'} has been declared since {@code V1} with no writer. This
 * is the endpoint pair that closes both, and the last of the five surviving owner types to get one.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The scope qualifier lost on the upload path.</strong>
 *       {@link Gates#anEngineerCannotUploadAtProjectScope()} against
 *       {@link Gates#anEngineerUploadsAtPlanScope()}. The reference create is gated by
 *       {@code writeGrantedByRowScope} and the file create is a <em>second</em> call site of it — a
 *       plain {@code writeGranted} here compiles, passes every other test in this class, and hands a
 *       Test Engineer the project's test strategy, which every plan inherits read-only. Both
 *       directions are pinned, because refusing them <em>both</em> scopes is the mirror failure and
 *       is the one that looks like caution.</li>
 *   <li><strong>The two rows written in the wrong order, or one of them not at all.</strong>
 *       {@code artifact.attachment_id} is a foreign key onto {@code attachment}, so the artifact
 *       cannot be inserted first; {@code attachment.owner_id} is {@code NOT NULL} and must name the
 *       artifact, so neither can be built without the other's id.
 *       {@link Registration#bothRowsAndTheBytes()} asserts all three artefacts of one upload.</li>
 *   <li><strong>The bytes surviving the register row.</strong>
 *       {@link Removal#unregisteringAFileDestroysTheBytes()}. This is the first artifact kind whose
 *       deletion destroys something rather than removing a pointer, and a delete that takes the row
 *       and leaves the blob is a deletion that did not delete — invisible from every screen.</li>
 *   <li><strong>The register rendering an upload as though it were a link.</strong>
 *       {@link Register#theRegisterRendersNameSizeAndDownload()}: {@code filename} and
 *       {@code sizeBytes} were on the wire for three slices with no writer, and {@code url} now
 *       carries a third meaning. A row that is a {@code FILE} and answers {@code url: null} is one
 *       the reader can see and cannot open.</li>
 *   <li><strong>A {@code FILE} row created with no file.</strong>
 *       {@link Registration#anEmptyPartIsRefusedAndWritesNothing()} — the state the single-request
 *       design exists to make unreachable, which is only true if the empty part is refused before
 *       either row is written.</li>
 *   <li><strong>The download becoming an oracle.</strong> {@link Scoping} drives a sibling project,
 *       another company, and an artifact that is a reference rather than an upload — all one
 *       bodiless 404 (§6t).</li>
 * </ol>
 *
 * <p>Company and user ids are {@code 4f…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and its five repeats, the most recent
 * being §121 phase 3's collision eighty lines away in another class.
 */
@AutoConfigureMockMvc
@DisplayName("File artifacts — the bytes arrive with the registration, and leave with it")
class ArtifactFileEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("4f000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("4f000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("4f001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("4f001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("4f002000-0000-4000-8000-000000000001");

    private static final UUID BANKING_PLAN = UUID.fromString("4f004000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code artifact:edit} at both scopes and {@code artifact:delete}. */
    private static final UUID LEAD = UUID.fromString("4f003000-0000-4000-8000-000000000001");
    /**
     * <strong>Test Engineer</strong> — the persona this class is really about. Holds
     * {@code artifact:edit} <em>plan-scope-only</em> ({@code V19}'s qualifier) and holds
     * {@code artifact:delete} at no scope at all.
     */
    private static final UUID ENGINEER = UUID.fromString("4f003000-0000-4000-8000-000000000002");
    /** Viewer — reads the register, writes nothing, and may download. */
    private static final UUID VIEWER = UUID.fromString("4f003000-0000-4000-8000-000000000003");
    /** A Test Lead in the other client, genuinely granted there. */
    private static final UUID OUTSIDER = UUID.fromString("4f003000-0000-4000-8000-000000000004");

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

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "4f-acme", "Acme", now);
            company(UMBRA, "4f-umbra", "Umbra", now);

            activeUser(LEAD, "lead@4f-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@4f-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@4f-acme.test", "V. Viewer", now);
            activeUser(OUTSIDER, "outsider@4f-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "4FOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "4FLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);

            grantOnProject("4f-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("4f-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("4f-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        // Umbra's grant is written under UMBRA's context: role_assignment is under FORCE ROW LEVEL
        // SECURITY, so an UMBRA-owned INSERT inside the ACME block is refused by the policy rather
        // than silently misfiled. Phase 2 lost a whole run to writing it the other way.
        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "4FUMB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("4f-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedProjectDefaults();
        seedPlans();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>Order is a foreign key each time: {@code artifact} before {@code attachment} (its
     * {@code attachment_id} references it) and before {@code test_plan} ({@code plan_id}).
     * {@code attachment} is joined to the artifact by nothing in the other direction — a polymorphic
     * {@code owner_id} with no constraint — so a left-behind row is an orphan the NEXT test finds,
     * which is green alone and red in the suite. §19's shape.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM artifact");
                execute(connection, "DELETE FROM attachment");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — artifact:edit, scope-qualified, exactly as the reference create is")
    class Gates {

        @Test
        @DisplayName("A TEST ENGINEER CANNOT UPLOAD AT PROJECT SCOPE — the qualifier reaches here too")
        void anEngineerCannotUploadAtProjectScope() throws Exception {
            // THE assertion of this phase. The file create is a SECOND call site of
            // writeGrantedByRowScope, and a plain writeGranted here compiles, passes every other
            // test in this class, and lets an engineer replace the project's test strategy — a
            // document every plan inherits read-only (plan §6n).
            MvcResult refused = upload(ENGINEER, ACME, ACME_BANKING,
                    """
                    {"scope":"PROJECT","category":"Strategy","title":"Test strategy"}""",
                    "strategy.pdf", "application/pdf", "PDF".getBytes(UTF_8));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            // A body naming the field, not a bare 403: the caller is one radio button away from a
            // 201 and the form can put the sentence on the scope selector.
            assertThat(errorFields(refused)).containsExactly("scope");

            assertThat(artifactRows(ACME)).as("a 403 that wrote the row anyway").isZero();
            assertThat(attachmentRows(ACME))
                    .as("and the blob row is the half a permission check is least likely to reach")
                    .isZero();
        }

        @Test
        @DisplayName("and the same engineer CAN upload at plan scope — the mirror failure")
        void anEngineerUploadsAtPlanScope() throws Exception {
            // Without this, the test above is consistent with refusing an engineer both scopes,
            // which is Lead-only and is what the roles spec's ✓ explicitly is not. It is also the
            // failure more likely to ship, because it reads as caution.
            MvcResult result = upload(ENGINEER, ACME, ACME_BANKING,
                    """
                    {"scope":"PLAN","planId":"%s","category":"SignOff","title":"Release sign-off"}"""
                            .formatted(BANKING_PLAN),
                    "signoff.pdf", "application/pdf", "SIGNED".getBytes(UTF_8));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("kind").asText()).isEqualTo("FILE");
        }

        @Test
        @DisplayName("a Viewer cannot upload at either scope, and nothing is written")
        void viewerCannotUpload() throws Exception {
            MvcResult refused = upload(VIEWER, ACME, ACME_BANKING,
                    """
                    {"scope":"PROJECT","category":"TestData","title":"Seed data"}""",
                    "seed.csv", "text/csv", "a,b".getBytes(UTF_8));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(artifactRows(ACME)).isZero();
            assertThat(attachmentRows(ACME)).isZero();
        }
    }

    @Nested
    @DisplayName("Registration — one request, two rows and the bytes")
    class Registration {

        @Test
        @DisplayName("BOTH ROWS AND THE BYTES, with each pointing at the other")
        void bothRowsAndTheBytes() throws Exception {
            MvcResult result = upload(LEAD, ACME, ACME_BANKING,
                    """
                    {"scope":"PROJECT","category":"Environment","title":"Environment guide",\
                    "description":"How to stand up staging"}""",
                    "environment.md", "text/markdown", "# Staging".getBytes(UTF_8));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode artifact = body(result);

            assertThat(artifact.get("kind").asText()).isEqualTo("FILE");
            assertThat(artifact.get("filename").asText()).isEqualTo("environment.md");
            assertThat(artifact.get("sizeBytes").asLong()).isEqualTo(9);
            assertThat(artifact.get("status").asText()).isEqualTo("ACTIVE");
            // §121's finding: the author is resolved through the tenancy filter, so resolving it
            // outside the service's transaction runs with no app.current_company and names nobody —
            // silently, on every successful upload.
            assertThat(artifact.get("updatedBy").get("displayName").asText()).isEqualTo("L. Lead");

            UUID artifactId = UUID.fromString(artifact.get("id").asText());

            // The two rows reference each other, which is the whole reason the artifact's id is
            // generated before either object exists. Assert BOTH directions: writing only
            // attachment.owner_id would leave a register row that cannot find its bytes, and
            // writing only artifact.attachment_id would leave a blob nothing owns.
            assertThat(attachmentIdOf(ACME, artifactId))
                    .as("artifact.attachment_id — the FILE kind's payload column")
                    .isNotNull();
            assertThat(ownerOf(ACME, attachmentIdOf(ACME, artifactId)))
                    .as("attachment.owner_type/owner_id — NOT NULL, so the blob names its artifact")
                    .isEqualTo("ARTIFACT:" + artifactId);

            // And the bytes, through the port rather than through a path rebuilt here.
            assertThat(store.exists(storageKeyOf(ACME, attachmentIdOf(ACME, artifactId)))).isTrue();

            // The url the register serves actually serves the bytes. POST /ingestions answered 201
            // with a Location that 404'd from the day it shipped until §119.
            MvcResult downloaded = download(LEAD, ACME, ACME_BANKING, artifactId);
            assertThat(downloaded.getResponse().getStatus()).isEqualTo(200);
            assertThat(downloaded.getResponse().getContentAsString()).isEqualTo("# Staging");
            assertThat(artifact.get("url").asText())
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/artifacts/" + artifactId
                            + "/content");
        }

        @Test
        @DisplayName("the download is an ATTACHMENT, not inline — an artifact is a held document")
        void theDownloadIsNotInline() throws Exception {
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "quarter-plan.xlsx");

            MvcResult downloaded = download(LEAD, ACME, ACME_BANKING, artifactId);

            // The one deliberate difference from every other attachment response in the product. A
            // page's image renders beside its paragraphs; a register lists documents by name, and
            // what somebody clicking one wants is the file.
            assertThat(downloaded.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("attachment; filename=\"quarter-plan.xlsx\"");
        }

        @Test
        @DisplayName("AN EMPTY PART IS REFUSED and writes neither row")
        void anEmptyPartIsRefusedAndWritesNothing() throws Exception {
            // The single-request design exists to make "a FILE artifact with no file" unreachable,
            // and that is only true if this is refused before anything is written.
            MvcResult refused = upload(LEAD, ACME, ACME_BANKING,
                    """
                    {"scope":"PROJECT","category":"TestData","title":"Nothing"}""",
                    "empty.bin", "application/octet-stream", new byte[0]);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("file");
            assertThat(artifactRows(ACME)).isZero();
            assertThat(attachmentRows(ACME)).isZero();
        }

        @Test
        @DisplayName("the register's own rules still apply — a plan of another project is refused")
        void theSharedValidationStillRuns() throws Exception {
            // The file path validates through the SAME method as the reference create, so this is
            // the assertion that the widening did not quietly fork the rules. A plan id the caller
            // guessed must not attach a document to a sibling project's cycle.
            MvcResult refused = upload(LEAD, ACME, ACME_BANKING,
                    """
                    {"scope":"PLAN","planId":"%s","category":"SignOff","title":"Sign-off"}"""
                            .formatted(UUID.randomUUID()),
                    "signoff.pdf", "application/pdf", "PDF".getBytes(UTF_8));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFields(refused)).containsExactly("planId");
            assertThat(attachmentRows(ACME)).isZero();
        }
    }

    @Nested
    @DisplayName("The register — an upload renders its name, its size and a way to open it")
    class Register {

        @Test
        @DisplayName("THE REGISTER RENDERS NAME, SIZE AND DOWNLOAD, and a link still renders neither")
        void theRegisterRendersNameSizeAndDownload() throws Exception {
            UUID fileId = registered(LEAD, ACME_BANKING, "PROJECT", null, "topology.png");
            createReference(LEAD, ACME_BANKING);

            JsonNode register = body(list(LEAD, ACME, ACME_BANKING));
            assertThat(register).hasSize(2);

            JsonNode upload = rowFor(register, fileId);
            // filename and sizeBytes were on the wire for three slices with no writer behind them.
            // A FILE that renders them null is a row the reader can see and cannot open.
            assertThat(upload.get("filename").asText()).isEqualTo("topology.png");
            assertThat(upload.get("sizeBytes").asLong()).isPositive();
            assertThat(upload.get("url").asText()).endsWith("/artifacts/" + fileId + "/content");

            JsonNode link = rowFor(register, id -> !id.equals(fileId));
            assertThat(link.get("kind").asText()).isEqualTo("EXTERNAL_URL");
            assertThat(link.get("filename").isNull())
                    .as("a link has no bytes, so the two fields keep meaning what they meant")
                    .isTrue();
            assertThat(link.get("sizeBytes").isNull()).isTrue();
            assertThat(link.get("url").asText()).isEqualTo("https://example.test/runbook");
        }

        @Test
        @DisplayName("one query for the whole register, not one per upload")
        void theFileLookupIsBatched() throws Exception {
            // ArtifactFiles is plural for this reason, and the assertion is behavioural rather than
            // a query count: three uploads must all render, which a per-row lookup would also do —
            // so what this really pins is that the batch keys correctly by artifact when there is
            // more than one row to confuse.
            UUID first = registered(LEAD, ACME_BANKING, "PROJECT", null, "one.txt");
            UUID second = registered(LEAD, ACME_BANKING, "PROJECT", null, "two.txt");
            UUID third = registered(LEAD, ACME_BANKING, "PROJECT", null, "three.txt");

            JsonNode register = body(list(LEAD, ACME, ACME_BANKING));

            assertThat(rowFor(register, first).get("filename").asText()).isEqualTo("one.txt");
            assertThat(rowFor(register, second).get("filename").asText()).isEqualTo("two.txt");
            assertThat(rowFor(register, third).get("filename").asText()).isEqualTo("three.txt");
        }
    }

    @Nested
    @DisplayName("Removal — the first artifact kind whose deletion destroys something")
    class Removal {

        @Test
        @DisplayName("UNREGISTERING A FILE DESTROYS THE BYTES, the blob row and the artifact row")
        void unregisteringAFileDestroysTheBytes() throws Exception {
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "obsolete.pdf");
            String key = storageKeyOf(ACME, attachmentIdOf(ACME, artifactId));

            assertThat(remove(LEAD, ACME, ACME_BANKING, artifactId).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(artifactRows(ACME)).isZero();
            assertThat(attachmentRows(ACME))
                    .as("a delete that takes the register row and leaves the blob row")
                    .isZero();
            assertThat(store.exists(key))
                    .as("...and one that leaves the BYTES is a deletion that did not delete — the "
                            + "wrong answer for the one thing a customer may need removed for "
                            + "privacy rather than tidiness")
                    .isFalse();
        }

        @Test
        @DisplayName("an engineer cannot unregister one, and the bytes survive the refusal")
        void anEngineerCannotUnregister() throws Exception {
            // artifact:delete, not artifact:edit — §124's key, and it now guards the last copy of a
            // file rather than a record of one.
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "signoff.pdf");
            String key = storageKeyOf(ACME, attachmentIdOf(ACME, artifactId));

            assertThat(remove(ENGINEER, ACME, ACME_BANKING, artifactId).getResponse().getStatus())
                    .isEqualTo(403);

            assertThat(artifactRows(ACME)).isEqualTo(1);
            assertThat(store.exists(key))
                    .as("a refused delete that destroyed the bytes anyway is unrecoverable")
                    .isTrue();
        }

        @Test
        @DisplayName("removing a reference touches no blob — the no-op arm keys on the column")
        void removingAReferenceLeavesTheOtherUpload() throws Exception {
            // removeFile keys on attachment_id rather than on kind, and this is what would catch it
            // reaching for another row's blob when the artifact has none of its own.
            UUID fileId = registered(LEAD, ACME_BANKING, "PROJECT", null, "keep-me.pdf");
            String key = storageKeyOf(ACME, attachmentIdOf(ACME, fileId));
            UUID linkId = createReference(LEAD, ACME_BANKING);

            assertThat(remove(LEAD, ACME, ACME_BANKING, linkId).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(attachmentRows(ACME)).isEqualTo(1);
            assertThat(store.exists(key)).isTrue();
        }
    }

    @Nested
    @DisplayName("Scoping — four things the download must answer 404 to, all of them bodiless")
    class Scoping {

        @Test
        @DisplayName("anybody who reads the register may download — no attachment:read")
        void aViewerDownloads() throws Exception {
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "guide.pdf");

            // Inventing a download key would be a permission no role is granted, which is a 403
            // nobody can fix. A Viewer sees the row, so a Viewer opens it.
            assertThat(download(VIEWER, ACME, ACME_BANKING, artifactId).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a REFERENCE artifact has no content — 404, not an empty 200")
        void aReferenceHasNoBytes() throws Exception {
            UUID linkId = createReference(LEAD, ACME_BANKING);

            assertThat(download(LEAD, ACME, ACME_BANKING, linkId).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an artifact of a SIBLING project is not reachable through this one's path")
        void aSiblingProjectsArtifactIsNotReachable() throws Exception {
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "guide.pdf");

            // The path segment must SELECT the row, not merely authorise the call: the caller holds
            // no grant on ACME_LOCKED, and findInProject is what keeps the id from resolving
            // through a project they were never gated on.
            assertThat(download(LEAD, ACME, ACME_LOCKED, artifactId).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("and another company's is 404 with no body, never 403")
        void anotherCompanysIsNotReachable() throws Exception {
            UUID artifactId = registered(LEAD, ACME_BANKING, "PROJECT", null, "guide.pdf");

            MvcResult refused = download(OUTSIDER, UMBRA, UMBRA_BANKING, artifactId);

            assertThat(refused.getResponse().getStatus()).isEqualTo(404);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a 403 would confirm the id names something real (§6t)")
                    .isEmpty();
        }
    }

    // =====================================================================

    private MvcResult upload(
            UUID userId, UUID companyId, UUID projectId, String artifactJson,
            String filename, String contentType, byte[] bytes) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .multipart("/api/v1/projects/" + projectId + "/artifacts/files")
                        .file(new MockMultipartFile("artifact", "", MediaType.APPLICATION_JSON_VALUE,
                                artifactJson.getBytes(UTF_8)))
                        .file(new MockMultipartFile("file", filename, contentType, bytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    /** Registers a file and returns the artifact's id — the setup half of most tests here. */
    private UUID registered(UUID userId, UUID projectId, String scope, UUID planId, String filename)
            throws Exception {

        String payload = planId == null
                ? "{\"scope\":\"" + scope + "\",\"category\":\"Environment\",\"title\":\""
                        + filename + "\"}"
                : "{\"scope\":\"" + scope + "\",\"planId\":\"" + planId
                        + "\",\"category\":\"SignOff\",\"title\":\"" + filename + "\"}";

        MvcResult result = upload(userId, ACME, projectId, payload, filename,
                "application/octet-stream", ("bytes of " + filename).getBytes(UTF_8));
        assertThat(result.getResponse().getStatus())
                .as("the fixture's own upload must succeed, or the test below is vacuous")
                .isEqualTo(201);
        return UUID.fromString(body(result).get("id").asText());
    }

    /** An {@code EXTERNAL_URL} through the JSON create — the register's other half. */
    private UUID createReference(UUID userId, UUID projectId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"PROJECT","category":"Tooling","kind":"EXTERNAL_URL",\
                                "title":"Runbook","url":"https://example.test/runbook"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(body(result).get("id").asText());
    }

    private MvcResult download(UUID userId, UUID companyId, UUID projectId, UUID artifactId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/artifacts/" + artifactId
                                + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult remove(UUID userId, UUID companyId, UUID projectId, UUID artifactId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/artifacts/" + artifactId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult list(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode rowFor(JsonNode register, UUID id) {
        return rowFor(register, candidate -> candidate.equals(id));
    }

    private static JsonNode rowFor(JsonNode register, java.util.function.Predicate<UUID> matches) {
        for (JsonNode row : register) {
            if (matches.test(UUID.fromString(row.get("id").asText()))) {
                return row;
            }
        }
        throw new AssertionError("No such row in the register");
    }

    private List<String> errorFields(MvcResult result) throws Exception {
        JsonNode errors = body(result).get("errors");
        List<String> fields = new java.util.ArrayList<>();
        errors.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private int artifactRows(UUID companyId) throws SQLException {
        return count(companyId, "SELECT count(*) FROM artifact");
    }

    private int attachmentRows(UUID companyId) throws SQLException {
        return count(companyId, "SELECT count(*) FROM attachment WHERE owner_type = 'ARTIFACT'");
    }

    private int count(UUID companyId, String sql) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(sql)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private UUID attachmentIdOf(UUID companyId, UUID artifactId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT attachment_id FROM artifact WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, artifactId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String value = rows.getString(1);
                return value == null ? null : UUID.fromString(value);
            }
        }
    }

    /** {@code owner_type:owner_id}, so one assertion covers both halves of the polymorphic key. */
    private String ownerOf(UUID companyId, UUID attachmentId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT owner_type, owner_id FROM attachment WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, attachmentId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) + ":" + rows.getString(2) : null;
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

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
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

    /**
     * One plan, for the plan-scope half of the qualifier.
     *
     * <p>Raw SQL rather than {@code execution}'s repository, because this module's test has no more
     * business importing {@code execution} than the module does — {@link PlanFacts} reads the table
     * the same way.
     */
    private void seedPlans() throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement("""
                        INSERT INTO test_plan (id, company_id, project_id, key, key_seq, name)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setString(1, BANKING_PLAN.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.setString(4, "4FOLB-P1");
            statement.setLong(5, 1);
            statement.setString(6, "Release 1");
            statement.executeUpdate();
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

        UUID id = UUID.nameUUIDFromBytes(("artifact-file-endpoint:" + marker).getBytes(UTF_8));
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
