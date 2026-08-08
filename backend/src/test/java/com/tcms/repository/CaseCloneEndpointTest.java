package com.tcms.repository;

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
import java.nio.charset.StandardCharsets;
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
 * {@code POST /projects/{projectId}/cases/{caseId}/clone} — plan §6v, decision round 3 #59.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A clone that carries the automation key.</strong>
 *       {@link Stripping#theAutomationKeyIsNotCopiedAndTheStatusIs()}. It is unique per company
 *       (§6c), so a copy either violates the constraint or — with uniqueness relaxed — makes every
 *       ingested CI result for that key match two cases (§117). The paired assertion is that the
 *       <em>status</em> <strong>is</strong> copied: a clone that reset it too would look tidy and
 *       would hide the case from §120's {@code undeclared_automation}, the signal built to find
 *       exactly this shape.</li>
 *   <li><strong>A clone that keeps the source's step ids.</strong>
 *       {@link Copying#theStepsAreNewRows()}. Adopting them moves those steps' identity — and so
 *       every {@code step_result} recorded against them — onto a case nobody has ever run.</li>
 *   <li><strong>A clone of an approved case that is itself approved.</strong>
 *       {@link Stripping#theCloneIsADraftEvenWhenTheSourceIsApproved()} — §6v rule 2, the laundered
 *       sign-off an audit exists to catch.</li>
 *   <li><strong>ATTACHMENTS SHARED BY REFERENCE.</strong>
 *       {@link Files#deletingTheClonesFileLeavesTheSourcesReadable()} is the assertion the storage
 *       decision turns on, and it is the one §6v's own table gets wrong: two rows over one
 *       {@code storage_key} means either delete destroys the other's bytes, silently, and the
 *       survivor renders as a file that 404s. Nothing else here would notice — a reference copy
 *       passes every other test in this class.</li>
 *   <li><strong>A retry that clones twice.</strong> {@link Replay#aRetriedIntentClonesOnce()}. The
 *       header is the create path's, so this is really a test that the clone did not accidentally
 *       take its own route around it — and that a replay copies no files a second time.</li>
 *   <li><strong>A Viewer cloning.</strong> {@link Gates#aViewerCannotClone()} — the gate is
 *       {@code case:create}, which is the one permission Viewer does not hold among the readers of
 *       a case.</li>
 * </ol>
 *
 * <p>Ids are {@code 1a…}, and the company slug and email domain are {@code clone-…} — checked
 * against every prefix <em>and</em> every slug already in {@code src/test/java}, because §128 lost
 * a whole class to a duplicate slug while every UUID was unique.
 */
@AutoConfigureMockMvc
@DisplayName("Cloning a case — what comes across, what must not, and the files")
class CaseCloneEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("1a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("1a000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("1a001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("1a001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("1a001000-0000-4000-8000-000000000003");

    /** Test Lead — holds {@code case:create}. */
    private static final UUID LEAD = UUID.fromString("1a003000-0000-4000-8000-000000000001");
    /** Viewer — reads the case and may not clone it. */
    private static final UUID VIEWER = UUID.fromString("1a003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("1a003000-0000-4000-8000-000000000003");

    private static final UUID PAYMENTS = UUID.fromString("1a004000-0000-4000-8000-000000000001");
    /** A second suite in the same project — the explicit destination. */
    private static final UUID REFUNDS = UUID.fromString("1a004000-0000-4000-8000-000000000002");
    private static final UUID LOCKED_SUITE = UUID.fromString("1a004000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_SUITE = UUID.fromString("1a004000-0000-4000-8000-000000000004");

    /** In the sibling project nobody here is granted. */
    private static final UUID LOCKED_CASE = UUID.fromString("1a005000-0000-4000-8000-000000000001");
    /** In the other client, deliberately keyed like Acme's. */
    private static final UUID UMBRA_CASE = UUID.fromString("1a005000-0000-4000-8000-000000000002");

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
    private AttachmentStore store;

    @Autowired
    private TransactionTemplate tx;

    /** The case every test clones — built through the API, so it is a case the product can make. */
    private UUID source;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "clone-acme", "Acme", now);
            company(UMBRA, "clone-umbra", "Umbra", now);

            activeUser(LEAD, "lead@clone-acme.test", "L. Lead", now);
            activeUser(VIEWER, "viewer@clone-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@clone-acme.test", "N. Grant", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CLNOB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "CLNLK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("cl-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("cl-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CLNUO", "Online Banking", now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedStructure();

        source = createSourceCase();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>Children before parents. Every test here creates cases and most create attachments, so
     * without this the second test finds the first's clone in the project and a count assertion
     * reads whatever ran before it — green alone, red in the suite (§19, and §128 one slice ago).
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM attachment");
                execute(connection, "DELETE FROM case_tag");
                execute(connection, "DELETE FROM case_condition");
                execute(connection, "DELETE FROM case_classification");
                execute(connection, "DELETE FROM test_case_draft");
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM condition");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — case:create, and 404 before 403")
    class Gates {

        @Test
        @DisplayName("a Viewer cannot clone, though they can read the case")
        void aViewerCannotClone() throws Exception {
            assertThat(cloneCase(VIEWER, ACME, ACME_BANKING, source, body("Viewer's copy"))
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(caseCount(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("and the Viewer's refusal is the permission, not a broken route")
        void theViewerReachesTheCase() throws Exception {
            // Without this the test above passes equally well against a route that does not exist,
            // and would keep passing after the gate was deleted.
            assertThat(readCase(VIEWER, ACME, ACME_BANKING, source).getResponse().getStatus())
                    .as("a Viewer holds case:read, so they see the case they may not copy")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = cloneCase(VIEWER, ACME, ACME_BANKING, source, body("nope"))
                    .getResponse().getContentAsString();

            assertThat(refused).doesNotContain("case:create");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            assertThat(cloneCase(NO_GRANT, ACME, ACME_BANKING, source, body("nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the project gate is not the case gate")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            // /projects/{granted}/cases/{in another project}. Without the second check this clones
            // a case the caller was never given, through a project id they were.
            assertThat(cloneCase(LEAD, ACME, ACME_BANKING, LOCKED_CASE, body("nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's case is a 404, not a 403")
        void anotherCompanysCaseIsInvisible() throws Exception {
            assertThat(cloneCase(LEAD, ACME, UMBRA_BANKING, UMBRA_CASE, body("nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("What comes across")
    class Copying {

        @Test
        @DisplayName("the authored content is copied field for field")
        void theContentIsCopied() throws Exception {
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            assertThat(copy.get("title").asText()).isEqualTo("The copy");
            assertThat(copy.get("objective").asText()).isEqualTo("Prove the saved-card path.");
            assertThat(copy.get("description").asText()).isEqualTo("Checks out with a stored card.");
            assertThat(copy.get("expectedOutcome").asText()).isEqualTo("The payment is captured.");
            assertThat(copy.get("priority").asText()).isEqualTo("HIGH");
            assertThat(copy.get("severity").asText()).isEqualTo("CRITICAL");
            assertThat(copy.get("estimatedDurationSec").asInt()).isEqualTo(120);
            assertThat(copy.get("stepFormat").asText()).isEqualTo("STRUCTURED");
        }

        @Test
        @DisplayName("THE STEPS ARE NEW ROWS — same text, different ids")
        void theStepsAreNewRows() throws Exception {
            List<String> sourceIds = stepIdsOf(readCase(LEAD, ACME, ACME_BANKING, source));

            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));
            List<String> cloneIds = stepIdsOf(copy);

            assertThat(copy.get("steps")).hasSize(2);
            assertThat(copy.get("steps").get(0).get("action").asText())
                    .isEqualTo("Open the basket");
            assertThat(copy.get("steps").get(1).get("action").asText())
                    .isEqualTo("Pay with the saved card");

            // step_result rows reference these ids. A clone that adopted them would move the
            // source's recorded results onto a case that has never been run — and both cases would
            // read correctly on screen while it happened.
            assertThat(cloneIds).doesNotContainAnyElementsOf(sourceIds);
        }

        @Test
        @DisplayName("the tags, the classification and the conditions come across")
        void theAssociationsAreCopied() throws Exception {
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            assertThat(textsOf(copy.get("tags"))).containsExactlyInAnyOrder("regression", "cart");
            assertThat(copy.get("classificationValueIds")).hasSize(1);
            assertThat(copy.get("preconditions")).hasSize(1);
            assertThat(copy.get("preconditions").get(0).get("title").asText())
                    .isEqualTo("The user is signed in");
        }

        @Test
        @DisplayName("no suiteId means the source's suite; an explicit one moves the copy")
        void theSuiteDefaultsToTheSources() throws Exception {
            JsonNode alongside = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("Alongside")));
            assertThat(alongside.get("suiteId").asText()).isEqualTo(PAYMENTS.toString());

            JsonNode elsewhere = created(cloneCase(LEAD, ACME, ACME_BANKING, source,
                    "{\"title\": \"Elsewhere\", \"suiteId\": \"" + REFUNDS + "\"}"));
            assertThat(elsewhere.get("suiteId").asText()).isEqualTo(REFUNDS.toString());
        }

        @Test
        @DisplayName("a suite in another project is a field error, not a cross-project clone")
        void theSuiteMustBeInThisProject() throws Exception {
            MvcResult refused = cloneCase(LEAD, ACME, ACME_BANKING, source,
                    "{\"title\": \"Elsewhere\", \"suiteId\": \"" + LOCKED_SUITE + "\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("suiteId");
        }

        @Test
        @DisplayName("the source is left completely alone")
        void theSourceIsUntouched() throws Exception {
            JsonNode before = readCaseBody(LEAD, ACME, ACME_BANKING, source);
            cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy"));
            JsonNode after = readCaseBody(LEAD, ACME, ACME_BANKING, source);

            // Including lockVersion and currentVersionNo: a clone that wrote a version row against
            // the SOURCE would be attributing an edit to somebody who copied it.
            assertThat(after).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("What must not come across")
    class Stripping {

        @Test
        @DisplayName("THE AUTOMATION KEY IS NOT COPIED, AND THE STATUS COMES DOWN WITH IT")
        void theAutomationKeyIsNotCopiedAndTheStatusIs() throws Exception {
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            // The key is unique per company. Copying it is either a constraint violation or, with
            // uniqueness relaxed, an ingested result matching two cases.
            assertThat(copy.get("automationKey").isNull()).isTrue();

            // AND THEREFORE the status cannot stay AUTOMATED: CaseEdit refuses that status without
            // a key, so "copy the status" and "never copy the key" are jointly unsatisfiable for
            // the one status where either matters — which is what made this test red first.
            // TO_BE_AUTOMATED is the accurate state, not a compromise: intended to be automated,
            // no automation yet.
            assertThat(copy.get("automationStatus").asText()).isEqualTo("TO_BE_AUTOMATED");
        }

        @Test
        @DisplayName("the other three automation statuses are copied unchanged")
        void theOtherStatusesSurvive() throws Exception {
            // Only AUTOMATED is special, and only because of the key. A blanket reset would tell a
            // reader that a NOT_AUTOMATABLE case — one with a written reason it cannot be automated
            // — is now merely pending automation, which is the opposite claim.
            setAutomation(ACME, source, "NOT_AUTOMATABLE", "Needs a physical card reader.");

            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            assertThat(copy.get("automationStatus").asText()).isEqualTo("NOT_AUTOMATABLE");
            assertThat(copy.get("notAutomatableReason").asText())
                    .isEqualTo("Needs a physical card reader.");
        }

        @Test
        @DisplayName("the clone is a DRAFT even when the source is APPROVED")
        void theCloneIsADraftEvenWhenTheSourceIsApproved() throws Exception {
            approve(ACME, source);
            assertThat(readCaseBody(LEAD, ACME, ACME_BANKING, source).get("lifecycleStatus")
                    .asText()).isEqualTo("APPROVED");

            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            // §6v rule 2. Approval attaches to content a named person read; carrying it to a copy
            // nobody has read is the laundered sign-off an audit exists to catch.
            assertThat(copy.get("lifecycleStatus").asText()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("the clone gets its own key and its own version 1")
        void theCloneIsANewAggregate() throws Exception {
            JsonNode sourceCase = readCaseBody(LEAD, ACME, ACME_BANKING, source);
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            assertThat(copy.get("key").asText()).isNotEqualTo(sourceCase.get("key").asText());
            assertThat(copy.get("currentVersionNo").asInt()).isEqualTo(1);
            assertThat(versionRowCount(ACME, UUID.fromString(copy.get("id").asText())))
                    .as("its own history, starting now — not a copy of the source's")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a blank title is refused by the create path's own rule")
        void theTitleIsRequired() throws Exception {
            MvcResult refused = cloneCase(LEAD, ACME, ACME_BANKING, source, "{\"title\": \"  \"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("title");
            assertThat(caseCount(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("an omitted title is refused too — the server invents no \"Copy of\"")
        void theServerDoesNotDefaultTheTitle() throws Exception {
            // The decision, pinned. A default here ships two rows a reader cannot tell apart in a
            // list, and the SPA's dialog is where the suggestion belongs — somewhere a person sees
            // it before it becomes a row.
            assertThat(cloneCase(LEAD, ACME, ACME_BANKING, source, "{}").getResponse().getStatus())
                    .isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("The files — copied, not shared")
    class Files {

        @Test
        @DisplayName("the clone's files are its own, under different keys")
        void theFilesAreCopied() throws Exception {
            UUID file = attach(LEAD, ACME, ACME_BANKING, source, "spec.pdf", "the spec");
            String sourceKey = storageKeyOf(ACME, file);

            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));
            UUID cloneId = UUID.fromString(copy.get("id").asText());

            List<String> cloneKeys = storageKeysOf(ACME, cloneId);
            assertThat(cloneKeys).hasSize(1);
            assertThat(cloneKeys.get(0)).isNotEqualTo(sourceKey);
            assertThat(store.exists(cloneKeys.get(0))).isTrue();

            // Same filename, same bytes, same checksum — the row is a copy of a file, not a new
            // upload. The checksum in particular is CARRIED rather than recomputed: recomputing it
            // would bless whatever the bytes are today, so a silently corrupted source would hand
            // its clone a fresh attestation that the corruption is correct.
            assertThat(checksumOf(ACME, attachmentIdOf(ACME, cloneId)))
                    .isEqualTo(checksumOf(ACME, file));
            assertThat(download(LEAD, ACME, ACME_BANKING, cloneId, attachmentIdOf(ACME, cloneId))
                    .getResponse().getContentAsString())
                    .isEqualTo("the spec");
        }

        @Test
        @DisplayName("DELETING THE CLONE'S FILE LEAVES THE SOURCE'S READABLE")
        void deletingTheClonesFileLeavesTheSourcesReadable() throws Exception {
            // §6v says attachments come across "by reference, not byte copy". This is the assertion
            // that says it cannot: AttachmentStore.delete is keyed and holds no reference count, so
            // two rows over one key means either delete destroys the other's bytes — and the
            // survivor renders as a file that 404s with nothing having failed. A reference copy
            // passes every other test in this class.
            UUID file = attach(LEAD, ACME, ACME_BANKING, source, "spec.pdf", "the spec");
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));
            UUID cloneId = UUID.fromString(copy.get("id").asText());

            assertThat(remove(LEAD, ACME, ACME_BANKING, cloneId, attachmentIdOf(ACME, cloneId))
                    .getResponse().getStatus()).isEqualTo(204);

            assertThat(store.exists(storageKeyOf(ACME, file))).isTrue();
            assertThat(download(LEAD, ACME, ACME_BANKING, source, file).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a source file whose bytes are gone is skipped, not cloned as a second orphan")
        void anOrphanedFileIsNotCloned() throws Exception {
            UUID file = attach(LEAD, ACME, ACME_BANKING, source, "lost.pdf", "gone");
            // The orphan the write ordering produces on purpose — a row whose download 404s. It is
            // one operator incident; cloning it would make it two, the second untraceable to it.
            store.delete(storageKeyOf(ACME, file));

            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));

            assertThat(attachmentRows(ACME, UUID.fromString(copy.get("id").asText()))).isZero();
            assertThat(copy.get("attachments")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Idempotency — the create path's, reached through this door")
    class Replay {

        @Test
        @DisplayName("a retried intent clones once, and copies nothing twice")
        void aRetriedIntentClonesOnce() throws Exception {
            attach(LEAD, ACME, ACME_BANKING, source, "spec.pdf", "the spec");

            MvcResult first = cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy"), "intent-1");
            MvcResult second = cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy"), "intent-1");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus())
                    .as("200, so a client can tell \"I made this\" from \"I already had this\"")
                    .isEqualTo(200);

            UUID cloneId = UUID.fromString(created(first).get("id").asText());
            assertThat(json.readTree(second.getResponse().getContentAsString()).get("id").asText())
                    .isEqualTo(cloneId.toString());
            assertThat(caseCount(ACME)).isEqualTo(2);

            // The half a replay could still get wrong: the create is idempotent and the file copy
            // is not part of it, so a replay that re-ran the copy would attach a second identical
            // file to a case that already has one, with a 200 on top.
            assertThat(attachmentRows(ACME, cloneId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the provenance is one audit row naming both keys")
        void theTrailSaysWhereItCameFrom() throws Exception {
            JsonNode copy = created(cloneCase(LEAD, ACME, ACME_BANKING, source, body("The copy")));
            String sourceKey = readCaseBody(LEAD, ACME, ACME_BANKING, source).get("key").asText();

            String changes = auditChangesFor(ACME, "case.cloned");

            // §6v asks for clonedFrom provenance "recorded in the audit event". The KEY, because
            // that is what an audit reader recognises a case by and the one thing the id does not
            // tell them.
            assertThat(changes).contains(sourceKey);
            assertThat(changes).contains(copy.get("key").asText());
            assertThat(auditActionCount(ACME, "case.created"))
                    .as("the created row is still written — this is a second row, not a rename. "
                            + "Two: the fixture's own source case, and the clone.")
                    .isEqualTo(2);
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private static String body(String title) {
        return "{\"title\": " + quoted(title) + "}";
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private MvcResult cloneCase(UUID userId, UUID companyId, UUID projectId, UUID caseId, String body)
            throws Exception {
        return cloneCase(userId, companyId, projectId, caseId, body, null);
    }

    private MvcResult cloneCase(UUID userId, UUID companyId, UUID projectId, UUID caseId, String body,
            String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/cases/" + caseId + "/clone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode created(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a created case, got: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult readCase(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode readCaseBody(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return json.readTree(
                readCase(userId, companyId, projectId, caseId).getResponse().getContentAsString());
    }

    private UUID attach(UUID userId, UUID companyId, UUID projectId, UUID caseId, String filename,
            String content) throws Exception {

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .multipart("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/attachments")
                        .file(new MockMultipartFile("file", filename, "application/pdf",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private MvcResult remove(UUID userId, UUID companyId, UUID projectId, UUID caseId, UUID fileId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/attachments/" + fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult download(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            UUID fileId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/attachments/" + fileId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private List<String> stepIdsOf(MvcResult result) throws Exception {
        return stepIdsOf(json.readTree(result.getResponse().getContentAsString()));
    }

    private static List<String> stepIdsOf(JsonNode caseNode) {
        List<String> ids = new ArrayList<>();
        caseNode.get("steps").forEach(step -> ids.add(step.get("id").asText()));
        return ids;
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /**
     * The case every test clones, created through the API so it is one the product can actually
     * make — two steps, two tags, a classification value, a precondition and an automation key.
     */
    private UUID createSourceCase() throws Exception {
        UUID condition = createCondition();
        UUID classificationValue = firstClassificationValue();

        String payload = "{\"title\": \"Checkout with a saved card\","
                + "\"suiteId\": \"" + PAYMENTS + "\","
                + "\"objective\": \"Prove the saved-card path.\","
                + "\"description\": \"Checks out with a stored card.\","
                + "\"expectedOutcome\": \"The payment is captured.\","
                + "\"priority\": \"HIGH\",\"severity\": \"CRITICAL\","
                + "\"automationStatus\": \"AUTOMATED\","
                + "\"automationKey\": \"checkout.saved_card\",\"notAutomatableReason\": null,"
                + "\"estimatedDurationSec\": 120,"
                + "\"stepFormat\": \"STRUCTURED\",\"gherkinSource\": null,"
                + "\"steps\": ["
                + "{\"id\": null,\"keyword\": null,\"action\": \"Open the basket\","
                + "\"testData\": null,\"expectedResult\": null,\"sharedStepId\": null},"
                + "{\"id\": null,\"keyword\": null,\"action\": \"Pay with the saved card\","
                + "\"testData\": null,\"expectedResult\": \"Captured\",\"sharedStepId\": null}],"
                + "\"tags\": [\"regression\", \"cart\"],"
                + "\"classificationValueIds\": [\"" + classificationValue + "\"],"
                + "\"preconditions\": [{\"conditionId\": \"" + condition + "\"}],"
                + "\"changeComment\": null}";

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture case: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createCondition() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/conditions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"The user is signed in\","
                                + "\"type\": \"PRE\","
                                + "\"body\": [\"Sign in as a returning customer.\"]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture condition: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /** Any seeded value — the assertion is that the association travels, not which value it is. */
    private UUID firstClassificationValue() throws Exception {
        JsonNode dimensions = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ACME_BANKING + "/classification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());

        for (JsonNode dimension : dimensions) {
            if (!dimension.get("values").isEmpty()) {
                return UUID.fromString(dimension.get("values").get(0).get("id").asText());
            }
        }
        throw new IllegalStateException("tcms_seed_project_defaults seeded no vocabulary values");
    }

    private void setAutomation(UUID companyId, UUID caseId, String status, String reason)
            throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "UPDATE test_case SET automation_status = ?, automation_key = NULL, "
                                + "not_automatable_reason = ? WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, status);
            statement.setString(2, reason);
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void approve(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "UPDATE test_case SET lifecycle_status = 'APPROVED' "
                                + "WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }

    /** Scoped to the project under test — {@code LOCKED_CASE} lives in this company too. */
    private int caseCount(UUID companyId) throws SQLException {
        return countOf(companyId, "SELECT count(*) FROM test_case WHERE deleted_at IS NULL "
                + "AND project_id = '" + ACME_BANKING + "'");
    }

    private int versionRowCount(UUID companyId, UUID caseId) throws SQLException {
        return countOf(companyId,
                "SELECT count(*) FROM test_case_version WHERE case_id = '" + caseId + "'");
    }

    private int attachmentRows(UUID companyId, UUID caseId) throws SQLException {
        return countOf(companyId, "SELECT count(*) FROM attachment WHERE owner_type = 'CASE' "
                + "AND owner_id = '" + caseId + "'");
    }

    private int auditActionCount(UUID companyId, String action) throws SQLException {
        return countOf(companyId,
                "SELECT count(*) FROM audit_event WHERE action = '" + action + "'");
    }

    private String auditChangesFor(UUID companyId, String action) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT changes::text FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
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

    private List<String> storageKeysOf(UUID companyId, UUID caseId) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT storage_key FROM attachment WHERE owner_type = 'CASE' "
                                + "AND owner_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
            }
        }
        return keys;
    }

    private UUID attachmentIdOf(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT id FROM attachment WHERE owner_type = 'CASE' "
                                + "AND owner_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? UUID.fromString(rows.getString(1)) : null;
            }
        }
    }

    private String checksumOf(UUID companyId, UUID attachmentId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT checksum FROM attachment WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, attachmentId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int countOf(UUID companyId, String sql) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
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

    private void seedStructure() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-cl-1", 1, "Payments");
            suite(connection, REFUNDS, ACME, ACME_BANKING, "TS-cl-2", 2, "Refunds");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-cl-3", 3, "Internal");

            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-cl-90", 990,
                    "In the locked project");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are numbered per company, so the collision is
            // the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-cl-1", 1, "Payments");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-cl-90", 990,
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
                        ?, ?, ?, 'MANUAL')
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
        return UUID.nameUUIDFromBytes(("case-clone-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
