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
 * {@code GET/PUT/DELETE /projects/{projectId}/cases/{caseId}/draft} — the autosave buffer over
 * HTTP, and {@code test_case_draft}'s first writer (decision round 3, #57).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A save that does not clear the buffer.</strong>
 *       {@link Promotion#savingTheCaseClearsTheBuffer()} is the assertion the whole slice turns on,
 *       and its absence is invisible to every other test here: writing and reading a draft works
 *       perfectly with the clear deleted. What breaks is the <em>next</em> mount, which offers to
 *       restore content the user already saved — "your save did not land", about a save that did.
 *       V1's comment on the table has promised this since M0 and nothing enforced it.</li>
 *   <li><strong>A draft that outlives its case's version without saying so.</strong>
 *       {@link Staleness#aDraftWrittenAgainstAnOlderVersionSaysSo()}. The existing {@code If-Match}
 *       machinery <em>cannot</em> catch the restore-over-a-colleague's-edit path — see
 *       {@link CaseDraftInput} — because the client's precondition comes from a fresh read and is
 *       therefore correct. Only the recorded {@code lockVersion} knows.</li>
 *   <li><strong>Absence of a draft reading as absence of the case.</strong>
 *       {@link Reading#noDraftIsA204AndNotA404()}. Almost every case has no draft almost all of the
 *       time, so collapsing the two tells an editor their case is gone on the ordinary path.</li>
 *   <li><strong>A Viewer reading somebody's unfinished sentences.</strong>
 *       {@link Gates#aViewerCannotReadTheDraftEither()} — the read is gated on {@code case:edit},
 *       not on the {@code project:read} that every role holds, and it is the one gate here that
 *       would look harmless to relax.</li>
 *   <li><strong>The buffer being validated.</strong> {@link Writing#aHalfWrittenDraftIsBuffered()}.
 *       Autosave that refuses a blank title fails while somebody is mid-sentence, which is the one
 *       thing a keystroke buffer may never do.</li>
 *   <li><strong>A shared buffer that does not name its author.</strong>
 *       {@link Writing#theDraftNamesWhoTypedIt()}. There is one row per case, so a restore that
 *       cannot say whose words these are publishes a colleague's half-finished work under your
 *       name.</li>
 * </ol>
 *
 * <p>Company and user ids are {@code d8…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and the five repeats since.
 */
@AutoConfigureMockMvc
@DisplayName("The case draft buffer — autosave, one row per person, and the save that clears yours")
class CaseDraftEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d8000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("d8000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("d8001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("d8001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("d8002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code case:edit}. */
    private static final UUID LEAD = UUID.fromString("d8003000-0000-4000-8000-000000000001");
    /** Test Engineer — also holds {@code case:edit}, unqualified. The colleague. */
    private static final UUID ENGINEER = UUID.fromString("d8003000-0000-4000-8000-000000000002");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("d8003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("d8003000-0000-4000-8000-000000000004");

    private static final UUID PAYMENTS = UUID.fromString("d8004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("d8004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("d8004000-0000-4000-8000-000000000003");

    /** Most tests draft against this one. */
    private static final UUID CHECKOUT = UUID.fromString("d8005000-0000-4000-8000-000000000001");
    /** A second, so a test that buffers need not fight one asserting an empty buffer. */
    private static final UUID REFUND = UUID.fromString("d8005000-0000-4000-8000-000000000002");
    /** A third, for the promotion path. */
    private static final UUID SETTLEMENT = UUID.fromString("d8005000-0000-4000-8000-000000000003");
    /** In the sibling project nobody here is granted. */
    private static final UUID LOCKED_CASE = UUID.fromString("d8005000-0000-4000-8000-000000000004");
    /** In the other client, deliberately keyed like Acme's. */
    private static final UUID UMBRA_CASE = UUID.fromString("d8005000-0000-4000-8000-000000000005");

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
            company(ACME, "cdr-acme", "Acme", now);
            company(UMBRA, "cdr-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cdr-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@cdr-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@cdr-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@cdr-acme.test", "N. Grant", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CDROL", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "CDRLK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("cd-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("cd-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("cd-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CDRUO", "Online Banking", now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedContent();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>{@code test_case_draft} first, because {@code test_case} is its parent. Every test here
     * writes one, so without this the second test to name a case finds a draft already buffered and
     * asserts the wrong transition — green alone, red in the suite, which is §19's defect in this
     * class's clothes and the sixth instance of it.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM test_case_draft");
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — case:edit on all three verbs, including the read")
    class Gates {

        @Test
        @DisplayName("a Viewer cannot READ the draft, though they can read the case")
        void aViewerCannotReadTheDraftEither() throws Exception {
            // THE gate of this slice that looks harmless to relax. The draft's content is the
            // case's content, so serving it under project:read would disclose nothing a Viewer
            // cannot already see in the case itself — which is exactly the argument that would be
            // made for the change, and it is answering the wrong question. A draft is somebody's
            // unfinished sentence, shown nowhere else in the product, and a Viewer is specifically
            // the client's own stakeholder invited to read coverage rather than to watch it being
            // written.
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(1, "Half a ti"));

            assertThat(get(VIEWER, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("and the Viewer's refusal is the permission, not a broken route")
        void theViewerReachesTheCase() throws Exception {
            // Without this, the test above passes equally well against a route that does not exist
            // — and would keep passing after the gate was removed.
            assertThat(readCase(VIEWER, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .as("a Viewer holds case:read, so they see the case whose draft they may not")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Viewer cannot write one either")
        void aViewerCannotBuffer() throws Exception {
            assertThat(put(VIEWER, ACME, ACME_BANKING, CHECKOUT, draftBody(1, "nope"))
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(draftExists(ACME, CHECKOUT)).isFalse();
        }

        @Test
        @DisplayName("a Viewer cannot discard one")
        void aViewerCannotDiscard() throws Exception {
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(1, "mine"));

            assertThat(delete(VIEWER, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(draftExists(ACME, CHECKOUT))
                    .as("a 403 that discarded anyway is worse than either answer alone")
                    .isTrue();
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = put(VIEWER, ACME, ACME_BANKING, CHECKOUT, draftBody(1, "nope"))
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves")
                    .doesNotContain("case:edit");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            assertThat(put(NO_GRANT, ACME, ACME_BANKING, CHECKOUT, draftBody(1, "nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the project gate is not the case gate")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            // /projects/{granted}/cases/{ungranted}. Without the second check this buffers a draft
            // against a case in a project the caller was never given, through a project id they
            // were — and the GET then reads it back.
            assertThat(put(LEAD, ACME, ACME_BANKING, LOCKED_CASE, draftBody(1, "nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(draftExists(ACME, LOCKED_CASE)).isFalse();
        }

        @Test
        @DisplayName("another company's case is a 404, not a 403")
        void anotherCompanysCaseIsInvisible() throws Exception {
            assertThat(put(LEAD, ACME, UMBRA_BANKING, UMBRA_CASE, draftBody(1, "nope"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("Writing — a buffer, not a save")
    class Writing {

        @Test
        @DisplayName("a half-written draft is buffered, blank title and all")
        void aHalfWrittenDraftIsBuffered() throws Exception {
            // The point of the whole table. CaseEdit refuses a blank title on save and must; doing
            // it here would make autosave fail while somebody is mid-sentence, at which point the
            // buffer protects nothing during exactly the interval it exists for.
            MvcResult result = put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    "{\"lockVersion\": 0, \"content\": {\"title\": \"\", "
                            + "\"objective\": \"Half a thou\"}}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("content").get("objective").asText()).isEqualTo("Half a thou");
            assertThat(body.get("content").get("title").asText()).isEmpty();
        }

        @Test
        @DisplayName("ONE BUFFER PER PERSON — a colleague's draft is not yours to be offered")
        void oneBufferPerPerson() throws Exception {
            // ~~The 1:1 primary key's cost, made visible: two people editing one case share the
            // row, so a returning editor may be offered a colleague's words, and restoring them
            // silently would publish that person's half-finished sentence under your name. The old
            // assertion read `updatedBy` for exactly that sentence.~~
            //
            // → V64 (audit D1). The key is (case_id, user_id), so the engineer's words are the
            // engineer's row and the lead is offered nothing at all — the 204 below IS the change.
            // `updatedBy` left the contract with the hazard, because under this key it could only
            // ever answer "you".
            put(ENGINEER, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "Engineer's words"));

            assertThat(get(LEAD, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .as("the lead has buffered nothing on this case, so there is nothing to restore")
                    .isEqualTo(204);

            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "Lead's words"));

            assertThat(json.readTree(get(ENGINEER, ACME, ACME_BANKING, CHECKOUT)
                            .getResponse().getContentAsString())
                    .get("content").get("title").asText())
                    .as("and the two rows coexist — the lead's write did not overwrite the engineer's")
                    .isEqualTo("Engineer's words");
            assertThat(json.readTree(get(LEAD, ACME, ACME_BANKING, CHECKOUT)
                            .getResponse().getContentAsString())
                    .get("content").get("title").asText())
                    .isEqualTo("Lead's words");
            assertThat(draftRowCount(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("the served draft carries no updatedBy — it could only say \"you\"")
        void theDraftDoesNotNameItsAuthor() throws Exception {
            // The removal is asserted rather than left to the schema, because a field that
            // discloses a hazard the schema no longer has is worse than no field: it asks a
            // returning editor to weigh whose words these are when the answer is always theirs.
            put(ENGINEER, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "Engineer's words"));

            JsonNode body = json.readTree(
                    get(ENGINEER, ACME, ACME_BANKING, CHECKOUT).getResponse().getContentAsString());

            assertThat(body.has("updatedBy")).isFalse();
            assertThat(body.has("updatedAt")).as("when it was buffered is still served").isTrue();
        }

        @Test
        @DisplayName("a second autosave REPLACES the first, it does not merge")
        void theBufferIsReplacedWholesale() throws Exception {
            put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    "{\"lockVersion\": 0, \"content\": {\"title\": \"First\", "
                            + "\"objective\": \"Present at first\"}}");

            MvcResult second = put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    "{\"lockVersion\": 0, \"content\": {\"title\": \"Second\"}}");

            JsonNode content = json.readTree(second.getResponse().getContentAsString()).get("content");
            assertThat(content.get("title").asText()).isEqualTo("Second");
            assertThat(content.get("objective").isNull())
                    .as("a merge would have to decide what an absent field means, and absent and "
                            + "null are the same Java value here — see CaseUpdateRequest")
                    .isTrue();
        }

        @Test
        @DisplayName("two autosaves leave ONE row, not a history")
        void theBufferIsNotVersioned() throws Exception {
            // "NEVER versioned" is V1's word for it. A buffer that accumulated rows would be a
            // second history beside test_case_version, made of keystrokes — which is precisely the
            // noise §6j introduced the content hash to keep out of the first one.
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "one"));
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "two"));
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "three"));

            assertThat(draftRowCount(ACME)).isEqualTo(1);
            assertThat(versionRowCount(ACME))
                    .as("and buffering wrote no case version at all")
                    .isZero();
        }

        @Test
        @DisplayName("an absent lockVersion is a 400 naming the field, never a default")
        void theLockVersionIsRequired() throws Exception {
            // Every value it could default to is a claim about which version the draft was written
            // against — and guessing wrong is how a restore silently overwrites a committed edit.
            // See CaseDraftInput for the four-step sequence the existing If-Match cannot catch.
            MvcResult result = put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    "{\"content\": {\"title\": \"No version\"}}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("lockVersion");
            assertThat(draftExists(ACME, CHECKOUT)).isFalse();
        }

        @Test
        @DisplayName("the body error is raised before the case is looked up")
        void theBodyIsCheckedBeforeThe404() throws Exception {
            // A malformed request is malformed whichever case it names, and answering 404 for a
            // body error sends the caller to check an id that is fine.
            assertThat(put(LEAD, ACME, ACME_BANKING, LOCKED_CASE,
                    "{\"content\": {\"title\": \"x\"}}")
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a draft over the ceiling is a 400 on the field, not a 500")
        void anOversizedDraftIsRefused() throws Exception {
            // Nothing else bounds this row: the content is unvalidated, the caller is a timer, and
            // no retention prunes the table.
            String huge = "x".repeat(CaseDraftService.MAX_CONTENT_BYTES + 1);
            MvcResult result = put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    json.writeValueAsString(java.util.Map.of(
                            "lockVersion", 0,
                            "content", java.util.Map.of("title", huge))));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("content");
            assertThat(draftExists(ACME, CHECKOUT)).isFalse();
        }
    }

    @Nested
    @DisplayName("Reading — and the 204 that is not a 404")
    class Reading {

        @Test
        @DisplayName("no draft is a 204, and the case is fine")
        void noDraftIsA204AndNotA404() throws Exception {
            // Almost every case has no draft almost all of the time — an ordinary state of a
            // perfectly readable case, not a missing resource. Collapsing the two would tell an
            // editor their case was gone every time they opened one they had not left work in,
            // which is most of the installation. §35's argument for `200 []` on a case with no
            // versions, arriving at a different status because there is no collection to be empty.
            MvcResult result = get(LEAD, ACME, ACME_BANKING, REFUND);

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("an unreachable case is still a 404 — the two are distinguishable")
        void theCaseGateStillAnswers404() throws Exception {
            assertThat(get(LEAD, ACME, ACME_BANKING, LOCKED_CASE).getResponse().getStatus())
                    .as("204 means 'readable, nothing buffered'; 404 means 'not yours'")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the draft round-trips through the column unchanged")
        void whatWasBufferedIsWhatComesBack() throws Exception {
            put(LEAD, ACME, ACME_BANKING, CHECKOUT,
                    "{\"lockVersion\": 0, \"content\": {\"title\": \"Checkout, revised\","
                            + "\"objective\": \"Prove the saved card path\","
                            + "\"steps\": [{\"action\": \"Open the basket\"},"
                            + "{\"action\": \"Pay with the saved card\"}]}}");

            JsonNode content = json.readTree(
                            get(LEAD, ACME, ACME_BANKING, CHECKOUT).getResponse().getContentAsString())
                    .get("content");

            assertThat(content.get("title").asText()).isEqualTo("Checkout, revised");
            assertThat(content.get("steps")).hasSize(2);
            assertThat(content.get("steps").get(1).get("action").asText())
                    .as("the step ORDER is what makes a restored draft the same draft")
                    .isEqualTo("Pay with the saved card");
        }

        @Test
        @DisplayName("updatedAt comes from the server, not from the client")
        void theTimestampIsTheServersOwn() throws Exception {
            // The screen says "saved a moment ago". Two clocks disagreeing about when work was
            // last safe is a small lie told on exactly the screen where trust is the feature.
            MvcResult result = put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "typed"));

            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("updatedAt").isNull())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Staleness — the check If-Match structurally cannot make")
    class Staleness {

        @Test
        @DisplayName("a draft written against the CURRENT version is not stale")
        void aCurrentDraftIsNotStale() throws Exception {
            long current = lockVersionOf(ACME, CHECKOUT);
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(current, "up to date"));

            assertThat(json.readTree(
                            get(LEAD, ACME, ACME_BANKING, CHECKOUT).getResponse().getContentAsString())
                    .get("stale").asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("a draft written against an OLDER version says so — and is still served")
        void aDraftWrittenAgainstAnOlderVersionSaysSo() throws Exception {
            // The sequence If-Match cannot see, and the reason lockVersion is on the input at all:
            //
            //   1. The engineer opens the case and types. The draft is buffered at version N.
            //   2. The lead saves the case. It is now at N+1.
            //   3. The engineer returns. The editor loads the case FRESH — N+1 — and offers the
            //      draft. Accepting and saving sends If-Match: N+1, which is correct, so the
            //      stale-editor check passes and the lead's change is overwritten by text written
            //      before it existed.
            //
            // The check compares what the client read against what is stored, and the client did
            // read the current version. What nothing else asks is what the DRAFT was written
            // against.
            long before = lockVersionOf(ACME, CHECKOUT);
            put(ENGINEER, ACME, ACME_BANKING, CHECKOUT, draftBody(before, "the engineer's words"));

            patchCase(LEAD, ACME, ACME_BANKING, CHECKOUT, before,
                    caseEdit("The lead's edit"));

            assertThat(lockVersionOf(ACME, CHECKOUT))
                    .as("the save must actually have moved the case, or this asserts nothing")
                    .isGreaterThan(before);

            // The lead's save cleared the buffer, which is the correct behaviour and is asserted
            // in Promotion — so the draft is re-buffered here at the OLD version, which is exactly
            // what a tab left open on the engineer's machine does on its next autosave tick.
            put(ENGINEER, ACME, ACME_BANKING, CHECKOUT, draftBody(before, "the engineer's words"));

            JsonNode body = json.readTree(
                    get(ENGINEER, ACME, ACME_BANKING, CHECKOUT).getResponse().getContentAsString());

            assertThat(body.get("stale").asBoolean()).isTrue();
            assertThat(body.get("content").get("title").asText())
                    .as("advisory, never enforcing — the words are the author's, and dropping them "
                            + "would be the product deciding somebody else's edit invalidates your "
                            + "sentence")
                    .isEqualTo("the engineer's words");
        }
    }

    @Nested
    @DisplayName("Discarding, and the promotion that clears the buffer")
    class Promotion {

        @Test
        @DisplayName("SAVING THE CASE CLEARS THE BUFFER — V1's own comment, finally enforced")
        void savingTheCaseClearsTheBuffer() throws Exception {
            // THE assertion of this slice, and the one whose absence every other test here
            // survives: writing and reading a draft works perfectly with the clear deleted. What
            // breaks is the NEXT mount, which offers to restore content the user already saved —
            // "your save did not land", about a save that did, on the one screen where that doubt
            // is most expensive.
            long version = lockVersionOf(ACME, SETTLEMENT);
            put(LEAD, ACME, ACME_BANKING, SETTLEMENT, draftBody(version, "unsaved words"));
            assertThat(draftExists(ACME, SETTLEMENT)).isTrue();

            assertThat(patchCase(LEAD, ACME, ACME_BANKING, SETTLEMENT, version,
                    caseEdit("Settlement, saved properly"))
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(draftExists(ACME, SETTLEMENT)).isFalse();
            assertThat(get(LEAD, ACME, ACME_BANKING, SETTLEMENT).getResponse().getStatus())
                    .as("and the editor's next mount is offered nothing to restore")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a colleague's save LEAVES YOUR BUFFER and marks it stale (V64, was: cleared it)")
        void anotherPersonsSaveLeavesYoursAlone() throws Exception {
            // ~~Not a defect, and worth an assertion precisely because it looks like one: the
            // buffer is per case, so the person who presses Save ends the buffer's job whoever they
            // are. Pinning it here means a later slice that makes the buffer per-person has to
            // change a test that says what the old shape was.~~
            //
            // → This IS that slice (V64, audit D1). It was a defect after all — a save by somebody
            // who was not in the room threw away work that was not theirs to throw away — and the
            // old assertion is kept above, struck through, so the reversal is legible.
            long version = lockVersionOf(ACME, SETTLEMENT);
            put(ENGINEER, ACME, ACME_BANKING, SETTLEMENT, draftBody(version, "engineer's words"));

            assertThat(patchCase(LEAD, ACME, ACME_BANKING, SETTLEMENT, version,
                    caseEdit("Lead saved")).getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(draftExists(ACME, SETTLEMENT, ENGINEER))
                    .as("the engineer's unsaved work survives a save they did not make")
                    .isTrue();

            JsonNode offered = json.readTree(get(ENGINEER, ACME, ACME_BANKING, SETTLEMENT)
                    .getResponse().getContentAsString());
            assertThat(offered.get("content").get("title").asText()).isEqualTo("engineer's words");
            assertThat(offered.get("stale").asBoolean())
                    .as("and what the colleague's save DID do is exactly this — the case moved on")
                    .isTrue();
        }

        @Test
        @DisplayName("a REFUSED save leaves the buffer alone")
        void aFailedSaveKeepsTheDraft() throws Exception {
            // The other half, and the more important one: a save refused for a blank title must
            // not also destroy the buffered work the author was about to lose. The clear runs after
            // validation for exactly this reason, and a clear moved to the top of the method would
            // pass every test above.
            long version = lockVersionOf(ACME, SETTLEMENT);
            put(LEAD, ACME, ACME_BANKING, SETTLEMENT, draftBody(version, "still needed"));

            assertThat(patchCase(LEAD, ACME, ACME_BANKING, SETTLEMENT, version,
                    caseEdit(""))
                    .getResponse().getStatus())
                    .as("a blank title is refused by CaseEdit")
                    .isEqualTo(400);

            assertThat(draftExists(ACME, SETTLEMENT))
                    .as("the work the author was about to lose is still buffered")
                    .isTrue();
        }

        @Test
        @DisplayName("discarding removes the buffer and leaves the case untouched")
        void discardingDropsTheRow() throws Exception {
            put(LEAD, ACME, ACME_BANKING, CHECKOUT, draftBody(0, "throw this away"));

            assertThat(delete(LEAD, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(draftExists(ACME, CHECKOUT)).isFalse();
            assertThat(readCase(LEAD, ACME, ACME_BANKING, CHECKOUT).getResponse().getStatus())
                    .as("discarding a draft is not deleting a case")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("discarding a draft that is not there succeeds — it is idempotent")
        void discardingNothingIsNotA404() throws Exception {
            // The caller asked for a state and that is the state it is in. A second click, a retry
            // after a timeout, and a concurrent save that already cleared the row must none of them
            // read as an error.
            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUND).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUND).getResponse().getStatus())
                    .isEqualTo(204);
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    /** One autosave carrying a title, at a stated base version. */
    private static String draftBody(long lockVersion, String title) {
        return "{\"lockVersion\": " + lockVersion + ", \"content\": {\"title\": \""
                + title + "\"}}";
    }

    /**
     * A complete {@code TestCaseInput}, which is what a real save has to be.
     *
     * <p>The editor submits every field it renders on every save, because {@link CaseUpdateRequest}
     * cannot tell an absent field from one explicitly nulled — so a body carrying only a title is
     * not a smaller version of this request, it is a different one. That is precisely the asymmetry
     * this slice exists over: the <em>draft</em> may be a fragment and the <em>save</em> may not.
     */
    private static String caseEdit(String title) {
        return "{\"title\": \"" + title + "\","
                + "\"suiteId\": \"" + PAYMENTS + "\","
                + "\"objective\": \"Prove the saved-card path.\","
                + "\"description\": \"Checks out using a stored card.\","
                + "\"expectedOutcome\": \"The payment is captured.\","
                + "\"priority\": \"HIGH\",\"severity\": \"HIGH\","
                + "\"automationStatus\": \"MANUAL\","
                + "\"automationKey\": null,\"notAutomatableReason\": null,"
                + "\"estimatedDurationSec\": 120,"
                + "\"stepFormat\": \"STRUCTURED\","
                + "\"steps\": [{\"id\": null,\"keyword\": null,\"action\": \"Open the basket\","
                + "\"testData\": null,\"expectedResult\": null}],"
                + "\"changeComment\": null}";
    }

    private MvcResult put(UUID userId, UUID companyId, UUID projectId, UUID caseId, String body)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/cases/" + caseId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/cases/" + caseId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult readCase(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult patchCase(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            long ifMatch, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .header(HttpHeaders.IF_MATCH, Long.toString(ifMatch))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Whether ANYBODY has a buffer on this case — the question the clear-on-save tests ask. */
    private boolean draftExists(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM test_case_draft WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Whether ONE PERSON has one — the question V64 made different from the one above. */
    private boolean draftExists(UUID companyId, UUID caseId, UUID userId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM test_case_draft "
                                + "WHERE case_id = CAST(? AS uuid) AND user_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.setString(2, userId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private int draftRowCount(UUID companyId) throws SQLException {
        return countOf(companyId, "SELECT count(*) FROM test_case_draft");
    }

    private int versionRowCount(UUID companyId) throws SQLException {
        return countOf(companyId, "SELECT count(*) FROM test_case_version");
    }

    private int countOf(UUID companyId, String sql) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private long lockVersionOf(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT lock_version FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
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

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-cdr-1", 1, "Payments");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-cdr-2", 2, "Internal");

            testCase(connection, CHECKOUT, ACME, ACME_BANKING, PAYMENTS, "TC-cdr-1", 901,
                    "Checkout with a saved card");
            testCase(connection, REFUND, ACME, ACME_BANKING, PAYMENTS, "TC-cdr-2", 902,
                    "Refund a settled payment");
            testCase(connection, SETTLEMENT, ACME, ACME_BANKING, PAYMENTS, "TC-cdr-3", 903,
                    "Settle at end of day");
            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-cdr-4", 904,
                    "In the locked project");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are numbered per company, so the collision is
            // the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-cdr-1", 1, "Payments");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-cdr-1", 901,
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
        return UUID.nameUUIDFromBytes(("case-draft-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
