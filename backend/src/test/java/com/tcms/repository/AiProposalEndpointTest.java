package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.ApiToken;
import com.tcms.identity.ApiTokenLookup;
import com.tcms.identity.ApiTokenLookupRepository;
import com.tcms.identity.ApiTokenRepository;
import com.tcms.identity.ApiTokenSecrets;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /projects/{projectId}/ai-proposals} — filing a generated case, and reading the queue.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A caller setting {@code verdictSource}.</strong>
 *       {@link Verdicts#aClaimedSourceIsIgnoredAndDerivedInstead()} is the assertion here whose
 *       absence would be a product defect rather than a usability one: §6w.1a's whole argument is
 *       that a connecting agent's verdict is never counted as verified, and a request field — or a
 *       stray Jackson binding — would let any client mint verified coverage by sending a string.
 *       There are two barriers and this test is what keeps them both honest.</li>
 *   <li><strong>Filing gated as a read.</strong> {@code case:create} is the key; Viewer holds
 *       {@code project:read} like everyone and must be refused.</li>
 *   <li><strong>A retry filing a second proposal.</strong> The caller is a program that cannot see
 *       the twin, and the reviewer pays for the duplicate.</li>
 *   <li><strong>The payload being validated at file time.</strong> A proposal naming a suite that
 *       was later deleted must still be filed and still be readable — the decision about whether it
 *       is a legal case belongs to accept, a week later, against the project as it is then.</li>
 *   <li><strong>A queue that mixes decided rows into the default page.</strong></li>
 * </ol>
 *
 * <p>Company and user ids are {@code a1…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and the five repeats after it.
 */
@AutoConfigureMockMvc
@DisplayName("AI proposals — filing a generated case, and who is allowed to say it was checked")
class AiProposalEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("a1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("a1000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("a1001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("a1001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("a1002000-0000-4000-8000-000000000001");

    /** Test Engineer — holds {@code case:create}. */
    private static final UUID ENGINEER = UUID.fromString("a1003000-0000-4000-8000-000000000001");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("a1003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("a1003000-0000-4000-8000-000000000003");

    private static final UUID PAYMENTS = UUID.fromString("a1004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("a1004000-0000-4000-8000-000000000002");

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
    private ApiTokenRepository apiTokens;

    @Autowired
    private ApiTokenLookupRepository apiTokenLookups;

    @Autowired
    private ApiTokenSecrets secrets;

    @Autowired
    private TransactionTemplate tx;

    /**
     * Driven by hand, because the links a proposal's {@code verifies} becomes are written by an
     * outbox handler in {@code documentation} and the scheduler's floor is about a minute.
     * {@code NotificationDeliveryTest}'s shape.
     */
    @Autowired
    private com.tcms.tenancy.OutboxDispatcher dispatcher;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ap-acme", "Acme", now);
            company(UMBRA, "ap-umbra", "Umbra", now);

            activeUser(ENGINEER, "eng@ap-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@ap-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@ap-acme.test", "N. Grant", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "APOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "APLCK", "Internal Tools", now);

            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("ap-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("ap-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "APUOL", "Online Banking", now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedContent();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>{@code ai_proposal} before {@code test_case}, because an accepted proposal references one —
     * and before {@code suite} for the same reason. Without this the queue tests count rows filed by
     * whichever test ran first: green alone, red in the suite, which is §19's defect in this class's
     * clothes.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM api_token_lookup");
                execute(connection, "DELETE FROM api_token");
                // The `verifies` slice's four. `outbox` first, so a proposal_accepted notice one
                // test recorded is never delivered inside another — the dispatcher is driven by
                // hand here and would otherwise link a case that test knows nothing about.
                // Then the link before the block before the page, which is the FK order.
                execute(connection, "DELETE FROM outbox");
                execute(connection, "DELETE FROM doc_link");
                execute(connection, "DELETE FROM doc_block");
                execute(connection, "DELETE FROM doc_page");
                execute(connection, "DELETE FROM ai_proposal");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — case:create, and the read that costs visibility alone")
    class Gates {

        @Test
        @DisplayName("a Test Engineer files a proposal and gets a Location")
        void engineerCanFile() throws Exception {
            MvcResult filed = file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund a payment"));

            assertThat(filed.getResponse().getStatus()).isEqualTo(201);
            JsonNode body = json.readTree(filed.getResponse().getContentAsString());
            assertThat(body.get("status").asText()).isEqualTo("PENDING");
            assertThat(body.get("title").asText()).isEqualTo("Refund a payment");
            assertThat(filed.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/ai-proposals/"
                            + body.get("id").asText());
        }

        @Test
        @DisplayName("a Viewer is refused — proposing is creating, not reading")
        void viewerIsRefused() throws Exception {
            // The gate that matters. Every read in this module costs project visibility, which a
            // Viewer has, so an endpoint gated like its neighbours would let the client's read-only
            // stakeholder fill the reviewer's queue.
            MvcResult refused =
                    file(VIEWER, ACME, ACME_BANKING, null, proposal("Refund a payment"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(countProposals(ACME)).isZero();
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = file(VIEWER, ACME, ACME_BANKING, null, proposal("Refund"))
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves")
                    .doesNotContain("case:create");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a Viewer can still READ the queue — reading a proposal costs visibility")
        void viewerCanRead() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund a payment"));

            assertThat(list(VIEWER, ACME, ACME_BANKING, null).getResponse().getStatus())
                    .as("a proposal is a proposed case, and a Viewer may read cases")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            assertThat(file(NO_GRANT, ACME, ACME_BANKING, null, proposal("Refund"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(list(NO_GRANT, ACME, ACME_BANKING, null).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's project is a 404, not a 403")
        void anotherCompanysProjectIsInvisible() throws Exception {
            assertThat(file(ENGINEER, ACME, UMBRA_BANKING, null, proposal("Refund"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a proposal is not reachable through a sibling project's path")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));

            // /projects/{ungranted}/ai-proposals/{real}. The project gate answers 404 first; even
            // past it, findScopedById names the project so the row is not in the other one.
            assertThat(findOne(ENGINEER, ACME, ACME_LOCKED, id).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("The verdict, and who is allowed to say it was checked")
    class Verdicts {

        @Test
        @DisplayName("no verdict claimed lands UNVERIFIED with source NONE")
        void anUnclaimedProposalIsUnverifiedByNobody() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    proposal("Refund a payment")));

            assertThat(body.get("verdict").asText())
                    .as("UNVERIFIED is the honest default, not an error state (§6w.6)")
                    .isEqualTo("UNVERIFIED");
            assertThat(body.get("verdictSource").asText()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("a claimed verdict is recorded as EXTERNAL, whatever it claims")
        void aClaimedVerdictIsExternal() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund a payment"}, "verdict": "GROUNDED"}
                    """));

            assertThat(body.get("verdict").asText()).isEqualTo("GROUNDED");
            assertThat(body.get("verdictSource").asText())
                    .as("TCMS cannot see that agent's critic, so its claim is EXTERNAL forever")
                    .isEqualTo("EXTERNAL");
        }

        @Test
        @DisplayName("claiming UNVERIFIED is not the same as claiming nothing")
        void assertingUnverifiedIsStillAnAssertion() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund a payment"}, "verdict": "UNVERIFIED"}
                    """));

            assertThat(body.get("verdict").asText()).isEqualTo("UNVERIFIED");
            assertThat(body.get("verdictSource").asText())
                    .as("both store UNVERIFIED; the source is what tells the reviewer whether "
                            + "anybody looked, and merging them loses that sentence")
                    .isEqualTo("EXTERNAL");
        }

        @Test
        @DisplayName("A CALLER CANNOT SAY TCMS CHECKED IT — the source is derived, never bound")
        void aClaimedSourceIsIgnoredAndDerivedInstead() throws Exception {
            // THE test of this slice. §6w.1a: an agent's verdict is recorded, shown, attributed —
            // and never counted as verified, "because TCMS cannot see that agent's critic, its
            // isolation, or its prompt". A request field here, or a Jackson binding somebody adds
            // later for symmetry, is all it takes for any MCP client to mint verified coverage.
            //
            // Two barriers: no field on AiProposalInput, and V43's CHECK does not permit the value
            // at all. This asserts the first; the second is why the first failing is a 500 rather
            // than a lie.
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund a payment"},
                     "verdict": "GROUNDED",
                     "verdictSource": "TCMS_VERIFIED"}
                    """));

            assertThat(body.get("verdictSource").asText())
                    .as("the value a caller sent must never reach the column")
                    .isEqualTo("EXTERNAL");
            assertThat(storedVerdictSource(ACME, UUID.fromString(body.get("id").asText())))
                    .isEqualTo("EXTERNAL");
        }

        @Test
        @DisplayName("grounding round-trips as sent — evidence is displayed, not interpreted")
        void groundingIsStoredVerbatim() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund a payment"},
                     "verdict": "PARTIAL",
                     "grounding": {"steps": [{"n": 1, "rejected": "invented an API"}],
                                   "unverified_claims": ["the refund window is 30 days"]}}
                    """));

            assertThat(body.get("grounding").get("unverified_claims").get(0).asText())
                    .isEqualTo("the refund window is 30 days");
            assertThat(body.get("grounding").get("steps").get(0).get("rejected").asText())
                    .as("a schema here would refuse the parts this product has not thought of")
                    .isEqualTo("invented an API");
        }

        @Test
        @DisplayName("a proposal filed in a browser session is not filed by an agent")
        void aSessionIsNotAnAgent() throws Exception {
            assertThat(bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")))
                    .get("filedByAgent").asBoolean())
                    .as("the flag is what changes the reviewer's reading, and a session must not "
                            + "set it — the agent-token path is covered by the token tests")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The body — what is judged now, and what waits for accept")
    class Body {

        @Test
        @DisplayName("a proposal with no case is a 400 naming the field")
        void theCaseIsRequired() throws Exception {
            MvcResult refused = file(ENGINEER, ACME, ACME_BANKING, null, "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("testCase");
        }

        @Test
        @DisplayName("a blank title is a 400 — the queue has nothing to render a row with")
        void theTitleIsRequired() throws Exception {
            MvcResult refused = file(ENGINEER, ACME, ACME_BANKING, null,
                    "{\"testCase\": {\"title\": \"   \"}}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("testCase.title");
        }

        @Test
        @DisplayName("A SUITE THAT NO LONGER EXISTS IS STILL FILED — validation waits for accept")
        void thePayloadIsNotValidatedAtFileTime() throws Exception {
            // The trade this endpoint makes, stated as a test so it cannot be "fixed". Whether the
            // payload is a legal case is a question about the project's suites and vocabularies AT
            // ACCEPT TIME, which may be a week later. Validating here as well would answer it
            // twice, and the two answers can differ — the accept-time one being the only one under
            // which a case is actually created.
            //
            // The cost is real and is the right way round: the reviewer meets the failure with the
            // field errors in hand, rather than an agent's whole batch being thrown away because
            // somebody renamed a suite.
            String body = "{\"testCase\": {\"title\": \"Refund a payment\", \"suiteId\": \""
                    + UUID.randomUUID() + "\"}}";

            assertThat(file(ENGINEER, ACME, ACME_BANKING, null, body).getResponse().getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("the payload comes back as the case input it was sent as")
        void thePayloadRoundTrips() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund a payment",
                                  "objective": "Prove the refund lands",
                                  "steps": [{"action": "Open the order", "expectedResult": "It opens"},
                                            {"action": "Refund it", "expectedResult": "Refunded"}]}}
                    """));

            assertThat(body.get("testCase").get("objective").asText())
                    .isEqualTo("Prove the refund lands");
            assertThat(body.get("testCase").get("steps")).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Idempotency — the first of the eight whose caller has no screen")
    class Idempotency {

        @Test
        @DisplayName("a retry replays the original and files nothing")
        void aRetryReplaysTheOriginal() throws Exception {
            MvcResult first = file(ENGINEER, ACME, ACME_BANKING, "batch-7", proposal("Refund"));
            MvcResult retry = file(ENGINEER, ACME, ACME_BANKING, "batch-7", proposal("Refund"));

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(idOf(retry)).isEqualTo(idOf(first));
            assertThat(countProposals(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a replay carries no Location — nothing was filed by that request")
        void aReplayCarriesNoLocation() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, "batch-7", proposal("Refund"));

            assertThat(file(ENGINEER, ACME, ACME_BANKING, "batch-7", proposal("Refund"))
                    .getResponse().getHeader("Location"))
                    .isNull();
        }

        @Test
        @DisplayName("a retry whose payload drifted still replays rather than 400ing")
        void aRetryIgnoresADriftedPayload() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, "batch-7", proposal("Refund"));

            // The lookup runs BEFORE validation, for CaseCreateService's reason: the client is
            // asking "did my intent land", and a 400 would send it to fix fields on a proposal
            // that already exists.
            MvcResult retry = file(ENGINEER, ACME, ACME_BANKING, "batch-7", "{}");

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(countProposals(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a blank key is the same as no key")
        void aBlankKeyIsAbsent() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, "  ", proposal("Refund one"));
            file(ENGINEER, ACME, ACME_BANKING, "  ", proposal("Refund two"));

            assertThat(countProposals(ACME))
                    .as("'' stored as a key is one key every misconfigured client replays against")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a key over the cap is a field error, not an indexed header")
        void theKeyIsCapped() throws Exception {
            MvcResult refused = file(ENGINEER, ACME, ACME_BANKING, "k".repeat(201),
                    proposal("Refund"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("Idempotency-Key");
        }
    }

    @Nested
    @DisplayName("The queue")
    class Queue {

        @Test
        @DisplayName("the default page is the pending ones, newest first")
        void theQueueDefaultsToPending() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, proposal("First"));
            file(ENGINEER, ACME, ACME_BANKING, null, proposal("Second"));

            JsonNode page = bodyOf(list(ENGINEER, ACME, ACME_BANKING, null));

            assertThat(page.get("total").asLong()).isEqualTo(2);
            assertThat(page.get("items")).hasSize(2);
            assertThat(page.get("items").get(0).get("status").asText()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("a summary row carries the verdict AND its source")
        void aSummaryCarriesBothHalves() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund"}, "verdict": "GROUNDED"}
                    """);

            JsonNode row = bodyOf(list(ENGINEER, ACME, ACME_BANKING, null)).get("items").get(0);

            assertThat(row.get("verdict").asText()).isEqualTo("GROUNDED");
            assertThat(row.get("verdictSource").asText())
                    .as("a row showing the verdict without its source shows a claim as a finding")
                    .isEqualTo("EXTERNAL");
            assertThat(row.get("createdBy").get("displayName").asText())
                    .as("resolved inside the read transaction — an unset company names nobody")
                    .isEqualTo("E. Engineer");
        }

        @Test
        @DisplayName("stepCount is read off the payload rather than stored beside it")
        void theStepCountIsDerived() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund",
                                  "steps": [{"action": "a"}, {"action": "b"}, {"action": "c"}]}}
                    """);

            assertThat(bodyOf(list(ENGINEER, ACME, ACME_BANKING, null))
                    .get("items").get(0).get("stepCount").asInt())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("asking for a decided status returns none while nothing is decided")
        void theStatusFilterIsHonoured() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund"));

            JsonNode page = bodyOf(list(ENGINEER, ACME, ACME_BANKING, "ACCEPTED"));

            assertThat(page.get("total").asLong()).isZero();
            assertThat(page.get("items")).isEmpty();
        }

        @Test
        @DisplayName("the queue is per project, not per company")
        void theQueueIsScopedToItsProject() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund"));

            // ACME_LOCKED is in the same company and this caller holds no grant on it, so the
            // answer is 404 rather than an empty page — which is also the answer that does not
            // tell them the project exists.
            assertThat(list(ENGINEER, ACME, ACME_LOCKED, null).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("one proposal reads back with its payload and its grounding")
        void oneProposalReadsBack() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund"}, "grounding": {"note": "checked"}}
                    """));

            JsonNode body = bodyOf(findOne(ENGINEER, ACME, ACME_BANKING, id));

            assertThat(body.get("id").asText()).isEqualTo(id.toString());
            assertThat(body.get("grounding").get("note").asText()).isEqualTo("checked");
            assertThat(body.get("acceptedCase").isNull())
                    .as("nothing is accepted yet, and a pending proposal names no case")
                    .isTrue();
        }

        @Test
        @DisplayName("filing is written to the audit log with the derived source")
        void filingIsAudited() throws Exception {
            file(ENGINEER, ACME, ACME_BANKING, null, """
                    {"testCase": {"title": "Refund"}, "verdict": "GROUNDED"}
                    """);

            assertThat(auditActions(ACME)).containsExactly("ai_proposal.filed");
            assertThat(auditDetails(ACME).get(0))
                    .as("whether a claim of grounding came from outside is the fact an auditor "
                            + "is looking for, and it is derived rather than sent")
                    .contains("EXTERNAL");
        }
    }

    @Nested
    @DisplayName("The decisions — and the caller who holds the permission and may not decide")
    class Decisions {

        @Test
        @DisplayName("AN AGENT TOKEN CANNOT ACCEPT ITS OWN PROPOSAL — the boundary, in one test")
        void aCredentialCannotAccept() throws Exception {
            // THE test of this slice. The Agent role HOLDS case:create — the WIP plan §2.2 keeps it
            // there because the accept path needs it — so a gate written as a permission alone lets
            // an agent file a proposal and accept it in two calls, which is the review surface
            // defeated by the thing it exists to review.
            //
            // No permission can express this: a token's set is its owner's set intersected with its
            // role's, so every permission a token holds is one its owner holds. The only separating
            // fact is that a token is in play, and it became readable outside `identity` one slice
            // ago (§132).
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    fullProposal("Refund a payment")));
            AgentToken agent = seedAgentToken();

            MvcResult refused = acceptAs(agent.secret(), ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(countCases(ACME))
                    .as("a 403 that created the case anyway is worse than either answer alone")
                    .isZero();
            assertThat(storedStatus(ACME, id)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("and the refusal says it is a person's decision, not that a key is missing")
        void theCredentialRefusalSaysWhy() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("Refund")));
            AgentToken agent = seedAgentToken();

            String body = acceptAs(agent.secret(), ACME_BANKING, id)
                    .getResponse().getContentAsString();

            // Telling an agent it lacks a permission would be FALSE — its owner holds case:create —
            // and would send whoever is debugging it to widen a grant that changes nothing.
            assertThat(body).contains("person");
            assertThat(body).doesNotContain("case:create");
        }

        @Test
        @DisplayName("the same token CAN file — so the refusal above is the decision, not the route")
        void theAgentReachesTheEndpoint() throws Exception {
            AgentToken agent = seedAgentToken();

            MvcResult filed = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/ai-proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(proposal("Generated by the agent")))
                    .andReturn();

            // Without this, the two tests above pass equally well against a broken token, a wrong
            // route or a resource an agent cannot reach at all — and would keep passing after the
            // credential check was deleted.
            assertThat(filed.getResponse().getStatus()).isEqualTo(201);
            assertThat(bodyOf(filed).get("filedByAgent").asBoolean())
                    .as("and the proposal knows a credential filed it")
                    .isTrue();
        }

        @Test
        @DisplayName("a person accepts, and the case arrives through the ordinary create path")
        void acceptCreatesTheCase() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    fullProposal("Refund a settled payment")));

            MvcResult accepted = accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
            JsonNode created = bodyOf(accepted);
            assertThat(created.get("key").asText())
                    .as("a display key allocated by the create path, not by this one")
                    .startsWith("TC-");
            assertThat(created.get("lifecycleStatus").asText())
                    .as("there is no path from a model to Approved")
                    .isEqualTo("DRAFT");
            assertThat(created.get("owner").get("id").asText())
                    .as("authorship is a person, always — and it is the ACCEPTING person")
                    .isEqualTo(ENGINEER.toString());
            assertThat(accepted.getResponse().getHeader("Location"))
                    .as("what this request created is a case, so that is where Location points")
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/cases/"
                            + created.get("id").asText());
        }

        @Test
        @DisplayName("the accepted proposal names the case, and leaves the queue")
        void acceptRecordsTheDecision() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("Refund")));
            String caseId = bodyOf(accept(ENGINEER, ACME, ACME_BANKING, id)).get("id").asText();

            JsonNode proposal = bodyOf(findOne(ENGINEER, ACME, ACME_BANKING, id));
            assertThat(proposal.get("status").asText()).isEqualTo("ACCEPTED");
            assertThat(proposal.get("acceptedCase").get("id").asText()).isEqualTo(caseId);
            assertThat(proposal.get("acceptedCase").get("key").asText()).startsWith("TC-");
            assertThat(proposal.get("decidedBy").get("id").asText()).isEqualTo(ENGINEER.toString());

            assertThat(bodyOf(list(ENGINEER, ACME, ACME_BANKING, null)).get("total").asLong())
                    .as("the queue is what is still waiting")
                    .isZero();
        }

        @Test
        @DisplayName("accepting twice answers with the case and creates only one")
        void acceptIsIdempotent() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("Refund")));

            MvcResult first = accept(ENGINEER, ACME, ACME_BANKING, id);
            MvcResult second = accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(second.getResponse().getStatus())
                    .as("the state they asked for is the state it is in")
                    .isEqualTo(200);
            assertThat(second.getResponse().getHeader("Location")).isNull();
            assertThat(bodyOf(second).get("id").asText()).isEqualTo(bodyOf(first).get("id").asText());
            assertThat(countCases(ACME)).isEqualTo(1);
        }

        @Test
        @DisplayName("a payload the project can no longer take is a 400 with the field errors")
        void anUnacceptablePayloadLandsOnTheFields() throws Exception {
            // The other half of "stored unvalidated". The proposal was filed happily; the question
            // of whether it is a legal case is answered HERE, by the create path, against the
            // project as it is now — and the reviewer gets the same message an author would.
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    "{\"testCase\": {\"title\": \"Refund\", \"suiteId\": \""
                            + UUID.randomUUID() + "\"}}"));

            MvcResult refused = accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("suiteId");
            assertThat(storedStatus(ACME, id))
                    .as("a failed accept decides nothing")
                    .isEqualTo("PENDING");
            assertThat(countCases(ACME)).isZero();
        }

        @Test
        @DisplayName("a rejection is recorded, not deleted")
        void rejectKeepsTheRow() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));

            MvcResult rejected = reject(ENGINEER, ACME, ACME_BANKING, id,
                    "{\"note\": \"Invented an endpoint that does not exist\"}");

            assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = bodyOf(rejected);
            assertThat(body.get("status").asText()).isEqualTo("REJECTED");
            assertThat(body.get("decisionNote").asText())
                    .isEqualTo("Invented an endpoint that does not exist");
            assertThat(countProposals(ACME))
                    .as("a profile rejected four times in five is a measurable fact only if the "
                            + "rejections survive")
                    .isEqualTo(1);
            assertThat(countCases(ACME)).isZero();
        }

        @Test
        @DisplayName("a rejection with no note is fine")
        void theNoteIsOptional() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));

            assertThat(reject(ENGINEER, ACME, ACME_BANKING, id, "{\"note\": null}")
                    .getResponse().getStatus())
                    .as("a mandatory reason on a queue of fifty is a form filled with the word 'no'")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("rejecting twice keeps the FIRST note")
        void aSecondRejectionDoesNotEraseTheReason() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));
            reject(ENGINEER, ACME, ACME_BANKING, id, "{\"note\": \"Hallucinated the API\"}");

            MvcResult again = reject(ENGINEER, ACME, ACME_BANKING, id, "{\"note\": \"\"}");

            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(bodyOf(again).get("decisionNote").asText())
                    .as("a second click must not erase the reason the decision was made for")
                    .isEqualTo("Hallucinated the API");
        }

        @Test
        @DisplayName("accepting a rejected proposal is a 409, and rejecting an accepted one too")
        void aDecidedProposalCannotBeDecidedAgain() throws Exception {
            UUID rejected = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("One")));
            reject(ENGINEER, ACME, ACME_BANKING, rejected, "{\"note\": null}");
            assertThat(accept(ENGINEER, ACME, ACME_BANKING, rejected).getResponse().getStatus())
                    .isEqualTo(409);

            UUID accepted = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("Two")));
            accept(ENGINEER, ACME, ACME_BANKING, accepted);
            assertThat(reject(ENGINEER, ACME, ACME_BANKING, accepted, "{\"note\": null}").getResponse().getStatus())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("a Viewer can decide nothing")
        void viewerCannotDecide() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));

            assertThat(accept(VIEWER, ACME, ACME_BANKING, id).getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(reject(VIEWER, ACME, ACME_BANKING, id, "{\"note\": null}").getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(countCases(ACME)).isZero();
        }

        @Test
        @DisplayName("both decisions are audited, and the accept records the provenance")
        void theDecisionsAreAudited() throws Exception {
            UUID accepted = idOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    fullProposal("One")));
            accept(ENGINEER, ACME, ACME_BANKING, accepted);
            UUID rejected = idOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Two")));
            reject(ENGINEER, ACME, ACME_BANKING, rejected, "{\"note\": null}");

            assertThat(auditActions(ACME))
                    .as("case.created is the create path's own row and must still be there — the "
                            + "case is a case like any other and its history has to say so")
                    .contains("ai_proposal.filed", "case.created", "ai_proposal.accepted",
                            "ai_proposal.rejected");
        }
    }

    @Nested
    @DisplayName("Verifies — the requirements a proposal claims, and the links they become")
    class Verifies {

        @Test
        @DisplayName("a proposal carries its claims back exactly as filed")
        void theClaimsRoundTrip() throws Exception {
            Page spec = page("Refunds", "A refund lands within 30 days", "Nothing is charged");

            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null,
                    fullProposalVerifying("Refund a payment", spec.id(), spec.block(0), null)));

            JsonNode filed = bodyOf(findOne(ENGINEER, ACME, ACME_BANKING, id));
            assertThat(filed.get("verifies")).hasSize(1);
            assertThat(filed.get("verifies").get(0).get("pageId").asText())
                    .isEqualTo(spec.id().toString());
            assertThat(filed.get("verifies").get(0).get("blockId").asText())
                    .isEqualTo(spec.block(0).toString());
            assertThat(filed.get("verifies").get(0).get("relationship").isNull())
                    .as("stored as sent — the default is the link path's, applied when the row is "
                            + "written, not a value this endpoint invents on the way in")
                    .isTrue();
        }

        @Test
        @DisplayName("a proposal that claimed nothing serves [], never an absent field")
        void nothingClaimedIsTheEmptyArray() throws Exception {
            JsonNode body = bodyOf(file(ENGINEER, ACME, ACME_BANKING, null, proposal("Refund")));

            assertThat(body.get("verifies").isArray())
                    .as("AiProposal.verifies is in the contract's required array, so an absent key "
                            + "is a body no generated client type admits")
                    .isTrue();
            assertThat(body.get("verifies")).isEmpty();
        }

        @Test
        @DisplayName("A CLAIM NAMING A BLOCK THAT IS GONE IS FILED, and refused at accept")
        void anUnknownBlockIsFiledAndRefusedAtAccept() throws Exception {
            // The same bargain the payload is stored under, one field over: whether a block exists
            // is a question about the project's documentation AT ACCEPT TIME. Filing must succeed
            // — otherwise an agent's whole batch is lost to a page somebody tidied — and the
            // reviewer meets the failure with the index of the claim in hand.
            Page spec = page("Refunds", "A refund lands within 30 days");
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", spec.id(), UUID.randomUUID(), null)));

            MvcResult refused = accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .as("indexed, because 'one of twelve is gone' is not something a reviewer can "
                            + "act on")
                    .contains("verifies[0].blockId");
            assertThat(storedStatus(ACME, id))
                    .as("a failed accept decides nothing")
                    .isEqualTo("PENDING");
            assertThat(countCases(ACME))
                    .as("the check runs BEFORE the create, so a proposal either becomes a case "
                            + "with all its links or stays a proposal")
                    .isZero();
        }

        @Test
        @DisplayName("a block of ANOTHER page of this project is refused too")
        void theBlockMustBeOnThePageTheProposalNamed() throws Exception {
            Page refunds = page("Refunds", "A refund lands within 30 days");
            Page charges = page("Charges", "A charge is authorised before capture");

            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", refunds.id(), charges.block(0), null)));

            MvcResult refused = accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a block resolves against the page it belongs to and nothing else, so "
                            + "without the pair check the link would render on a document nobody "
                            + "meant")
                    .contains("verifies[0].blockId");
        }

        @Test
        @DisplayName("a relationship outside the three is refused AT FILE TIME")
        void anUnknownRelationshipIsRefusedOnTheWayIn() throws Exception {
            Page spec = page("Refunds", "A refund lands within 30 days");

            MvcResult refused = file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", spec.id(), spec.block(0), "COVERS"));

            // Not "waits for accept" like everything else: a vocabulary of three fixed words is
            // exactly what can be judged without the project's state, and leaving it would mean
            // either a silently invented VERIFIES or a dead outbox row a week later.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("verifies[0].relationship");
            assertThat(countProposals(ACME)).isZero();
        }

        @Test
        @DisplayName("a claim with no block id is refused at file time — the only shape check")
        void bothIdsAreRequired() throws Exception {
            String body = """
                    {"testCase": {"title": "Refund"},
                     "verifies": [{"pageId": "%s"}]}
                    """.formatted(UUID.randomUUID());

            MvcResult refused = file(ENGINEER, ACME, ACME_BANKING, null, body);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("verifies[0].blockId");
        }

        @Test
        @DisplayName("ACCEPTING WRITES THE LINK, through the ordinary link path")
        void acceptLinksTheCaseToEveryClaimedBlock() throws Exception {
            // THE test of this slice. An agent that generates a case FOR a requirement can say so,
            // and a person accepting is what turns the claim into coverage — with the resulting
            // doc_link indistinguishable from one somebody made by hand, exactly as the accepted
            // case is indistinguishable from one somebody authored.
            Page spec = page("Refunds", "A refund lands within 30 days", "Nothing is charged");
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", spec.id(), spec.block(0), null)));

            String caseKey = bodyOf(accept(ENGINEER, ACME, ACME_BANKING, id)).get("key").asText();
            deliver(ACME);

            JsonNode links = linksOn(spec, 0);
            assertThat(links).hasSize(1);
            assertThat(links.get(0).get("sourceType").asText()).isEqualTo("TEST_CASE");
            assertThat(links.get(0).get("sourceKey").asText()).isEqualTo(caseKey);
            assertThat(links.get(0).get("relationship").asText())
                    .as("absent means VERIFIES, which is the contract's default and the column's")
                    .isEqualTo("VERIFIES");
            assertThat(linksOn(spec, 1))
                    .as("only the block that was claimed — a page-wide link would make every "
                            + "statement on the page read as covered")
                    .isEmpty();
        }

        @Test
        @DisplayName("two claims become two links, and the relationship sent is the one written")
        void everyClaimIsWritten() throws Exception {
            Page spec = page("Refunds", "A refund lands within 30 days", "Nothing is charged");
            String body = """
                    {"testCase": {"title": "Refund a payment", "suiteId": "%s",
                                  "stepFormat": "STRUCTURED", "priority": "MEDIUM",
                                  "automationStatus": "MANUAL",
                                  "steps": [{"action": "Open", "expectedResult": "It opens"}]},
                     "verifies": [{"pageId": "%s", "blockId": "%s"},
                                  {"pageId": "%s", "blockId": "%s", "relationship": "REFERENCES"}]}
                    """.formatted(PAYMENTS, spec.id(), spec.block(0), spec.id(), spec.block(1));

            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, body));
            accept(ENGINEER, ACME, ACME_BANKING, id);
            deliver(ACME);

            assertThat(linksOn(spec, 0).get(0).get("relationship").asText()).isEqualTo("VERIFIES");
            assertThat(linksOn(spec, 1).get(0).get("relationship").asText())
                    .as("only VERIFIES is coverage; the other two are claims somebody chose not to "
                            + "state as proof, and the handler must not flatten them")
                    .isEqualTo("REFERENCES");
        }

        @Test
        @DisplayName("delivering twice writes one link — the handler leans on the link path")
        void deliveryIsIdempotent() throws Exception {
            Page spec = page("Refunds", "A refund lands within 30 days");
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", spec.id(), spec.block(0), null)));
            accept(ENGINEER, ACME, ACME_BANKING, id);

            deliver(ACME);
            redeliver(ACME);

            // Outbox delivery is at least once. Nothing here keys on the delivery having happened;
            // what answers a repeat is BlockLinkService's own rule that re-asserting a recorded
            // fact is not an error — and a second row would double every count over this table.
            assertThat(linksOn(spec, 0)).hasSize(1);
        }

        @Test
        @DisplayName("a proposal claiming nothing records no event at all")
        void nothingClaimedIsNoNotice() throws Exception {
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposal("Refund")));

            accept(ENGINEER, ACME, ACME_BANKING, id);

            assertThat(outboxEventTypes(ACME))
                    .as("an event whose handler would iterate an empty list is a delivery cycle to "
                            + "do nothing, and a row per accepted proposal saying something "
                            + "happened")
                    .doesNotContain("repository.proposal_accepted");
        }

        @Test
        @DisplayName("a page deleted between the accept and the delivery loses the link, not the case")
        void aVanishedPageIsADeliveryRatherThanAFailure() throws Exception {
            Page spec = page("Refunds", "A refund lands within 30 days");
            UUID id = idOf(file(ENGINEER, ACME, ACME_BANKING, null, fullProposalVerifying(
                    "Refund a payment", spec.id(), spec.block(0), null)));
            accept(ENGINEER, ACME, ACME_BANKING, id);
            softDeletePage(ACME, spec.id());

            deliver(ACME);

            // Retrying cannot make a deleted page exist, so throwing would exhaust the row into a
            // DEAD state meaning "this event was fine". The case stays; the claim it made is
            // visible on the proposal, which is where a reviewer would look for it.
            assertThat(outboxStatuses(ACME, "repository.proposal_accepted"))
                    .containsExactly("SENT");
            assertThat(countCases(ACME)).isEqualTo(1);
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    /** The smallest body this endpoint accepts. Deliberately NOT a legal case — see below. */
    private static String proposal(String title) {
        return "{\"testCase\": {\"title\": \"" + title + "\"}}";
    }

    /**
     * A body that is also a legal CASE — title, format, a step, a priority and an automation
     * status, filed into a suite that exists.
     *
     * <p>The two helpers existing side by side <strong>is</strong> this slice's central trade,
     * written as fixture: {@link #proposal} is accepted by the file endpoint and refused by accept,
     * because whether a proposal is a legal case is a question answered at accept time against the
     * project as it is then. Anything that made the first helper unusable would mean file-time
     * validation had crept in.
     */
    private static String fullProposal(String title) {
        return """
                {"testCase": {"title": "%s",
                              "suiteId": "%s",
                              "stepFormat": "STRUCTURED",
                              "priority": "MEDIUM",
                              "automationStatus": "MANUAL",
                              "steps": [{"action": "Open the order",
                                         "expectedResult": "It opens"}]}}
                """.formatted(title, PAYMENTS);
    }

    /**
     * {@link #fullProposal} plus one claim, so the two helpers differ in exactly the field under
     * test.
     *
     * @param relationship null to send no key at all — the ordinary case, and the one that proves
     *                     the default is applied where the row is written rather than here
     */
    private static String fullProposalVerifying(
            String title, UUID pageId, UUID blockId, String relationship) {

        String claim = relationship == null
                ? "{\"pageId\": \"%s\", \"blockId\": \"%s\"}".formatted(pageId, blockId)
                : "{\"pageId\": \"%s\", \"blockId\": \"%s\", \"relationship\": \"%s\"}"
                        .formatted(pageId, blockId, relationship);

        return """
                {"testCase": {"title": "%s",
                              "suiteId": "%s",
                              "stepFormat": "STRUCTURED",
                              "priority": "MEDIUM",
                              "automationStatus": "MANUAL",
                              "steps": [{"action": "Open the order",
                                         "expectedResult": "It opens"}]},
                 "verifies": [%s]}
                """.formatted(title, PAYMENTS, claim);
    }

    /** A documentation page in {@link #ACME_BANKING}, and the ids of the blocks on it. */
    private record Page(UUID id, List<UUID> blocks) {

        UUID block(int index) {
            return blocks.get(index);
        }
    }

    /**
     * Creates a page through the ordinary endpoint rather than by INSERT.
     *
     * <p>Block ids are server-generated and are the thing under test here — a fixture that chose
     * them would be asserting against ids the product never issued, and would not notice the day
     * the create path stopped returning them.
     */
    private Page page(String title, String... paragraphs) throws Exception {
        StringBuilder blocks = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (!blocks.isEmpty()) {
                blocks.append(",");
            }
            blocks.append("{\"blockType\": \"paragraph\", \"text\": \"")
                    .append(paragraph)
                    .append("\"}");
        }

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/doc-pages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + title + "\", \"blocks\": [" + blocks + "]}"))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture itself must land, or every assertion below is about nothing")
                .isEqualTo(201);

        JsonNode body = bodyOf(created);
        List<UUID> blockIds = new ArrayList<>();
        for (JsonNode block : body.get("blocks")) {
            blockIds.add(UUID.fromString(block.get("id").asText()));
        }
        return new Page(UUID.fromString(body.get("id").asText()), blockIds);
    }

    /** The links a page's nth block currently carries, read back through the page endpoint. */
    private JsonNode linksOn(Page page, int blockIndex) throws Exception {
        MvcResult read = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ACME_BANKING + "/doc-pages/" + page.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return bodyOf(read).get("blocks").get(blockIndex).get("links");
    }

    /**
     * Runs the outbox by hand, in the company scope and outside any transaction — the dispatcher
     * refuses to run inside one, and RLS answers zero rows without the scope.
     *
     * <p>The scheduler's own floor is about a minute, which no test can wait for.
     */
    private void deliver(UUID companyId) {
        inCompany(companyId, () -> assertThat(dispatcher.dispatchDue())
                .as("nothing was delivered, so every assertion after this is about the state "
                        + "before the handler ran")
                .isPositive());
    }

    /**
     * A second delivery of the same event — the at-least-once case.
     *
     * <p>The row is {@code SENT} after {@link #deliver}, so the dispatcher will not pick it up
     * again — its due-ness test is {@code PENDING} and a schedule in the past. Putting both back is
     * the redelivery a crashed settle produces, and {@code NotificationDeliveryTest} moves the same
     * columns for the same reason.
     */
    private void redeliver(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("""
                        UPDATE outbox
                           SET status = 'PENDING', next_attempt_at = now() - interval '1 hour'
                         WHERE event_type = 'repository.proposal_accepted'
                        """)) {
            statement.executeUpdate();
        }
        inCompany(companyId, () -> assertThat(dispatcher.dispatchDue())
                .as("the redelivery did not happen, so the idempotency below is untested")
                .isPositive());
    }

    private List<String> outboxEventTypes(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("SELECT event_type FROM outbox")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<String> types = new ArrayList<>();
                while (rows.next()) {
                    types.add(rows.getString(1));
                }
                return types;
            }
        }
    }

    private List<String> outboxStatuses(UUID companyId, String eventType) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT status FROM outbox WHERE event_type = ?")) {
            statement.setString(1, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
                return statuses;
            }
        }
    }

    /** As the delete endpoint does it — the page stops being live without its blocks going away. */
    private void softDeletePage(UUID companyId, UUID pageId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "UPDATE doc_page SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, pageId.toString());
            statement.executeUpdate();
        }
    }

    private MvcResult file(
            UUID userId, UUID companyId, UUID projectId, String idempotencyKey, String body)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/ai-proposals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult list(UUID userId, UUID companyId, UUID projectId, String status)
            throws Exception {
        String query = status == null ? "" : "?status=" + status;
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/ai-proposals" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult findOne(UUID userId, UUID companyId, UUID projectId, UUID proposalId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/ai-proposals/" + proposalId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult accept(UUID userId, UUID companyId, UUID projectId, UUID proposalId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/ai-proposals/" + proposalId
                                + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    /** The same call under an agent credential rather than a session. */
    private MvcResult acceptAs(String bearer, UUID projectId, UUID proposalId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/ai-proposals/" + proposalId
                                + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andReturn();
    }

    private MvcResult reject(
            UUID userId, UUID companyId, UUID projectId, UUID proposalId, String body)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/ai-proposals/" + proposalId + "/reject")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    /**
     * A live agent credential owned by {@link #ENGINEER}, carrying the {@code Agent} role — which
     * holds {@code case:create}. That is the point: the refusal under test is not a permission.
     */
    private AgentToken seedAgentToken() {
        ApiTokenSecrets.NewSecret secret = secrets.generate();
        UUID id = UUID.randomUUID();
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            apiTokens.save(ApiToken.issue(id, ACME, "agent " + id, secret.prefix(),
                    ENGINEER, Role.AGENT_ID, ACME_BANKING, null, Instant.now()));
            apiTokenLookups.save(ApiTokenLookup.of(secret.hash(), id, ACME));
        }));
        return new AgentToken(id, secret.secret());
    }

    private record AgentToken(UUID tokenId, String secret) {
    }

    private long countCases(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("SELECT count(*) FROM test_case")) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private String storedStatus(UUID companyId, UUID proposalId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT status FROM ai_proposal WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, proposalId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(bodyOf(result).get("id").asText());
    }

    private long countProposals(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement("SELECT count(*) FROM ai_proposal")) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private String storedVerdictSource(UUID companyId, UUID proposalId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT verdict_source FROM ai_proposal WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, proposalId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private List<String> auditActions(UUID companyId) throws SQLException {
        return auditColumn(companyId, "action");
    }

    private List<String> auditDetails(UUID companyId) throws SQLException {
        return auditColumn(companyId, "changes");
    }

    private List<String> auditColumn(UUID companyId, String column) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT " + column + " FROM audit_event ORDER BY occurred_at")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
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

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-ap-1", 1, "Payments");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-ap-2", 2, "Internal");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
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

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("ai-proposal-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
