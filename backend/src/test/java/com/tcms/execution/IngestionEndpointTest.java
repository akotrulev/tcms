package com.tcms.execution;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /projects/{projectId}/ingestions} over HTTP — <strong>and the mapping endpoint that
 * makes its unmatched list actionable.</strong>
 *
 * <h2>What can only be asserted from outside</h2>
 *
 * <ol>
 *   <li><strong>The ladder's three rungs, each ALONE.</strong> A test matched by
 *       {@code automation_key}, one matched only by an embedded display key, and one matched only
 *       by a learned mapping. Written as one report exercising all three, a ladder that collapsed
 *       to its first rung would still pass, because the first rung would carry the count.</li>
 *   <li><strong>The transaction the parse must not be inside, and the one the write must.</strong>
 *       §116's red run was a company context opened inside an already-begun transaction, which
 *       reaches no GUC — so every RLS read returned zero rows and every valid credential 401'd. The
 *       same shape here would make the idempotency lookup find nothing, so <em>every</em> retry
 *       would ingest twice. Only reachable over HTTP: a harness that opened a context before
 *       calling in would be green.</li>
 *   <li><strong>A report that matches nothing writes NO run.</strong> A run of zero cases reports
 *       0 of 0, 100%, complete — §99's defect one screen over — and it is the FIRST report a team
 *       posts that most often matches nothing.</li>
 *   <li><strong>{@code case:edit} on the mapping write, which Automation Service does not
 *       hold.</strong> The security property of the whole feature: a leaked CI token can post
 *       results and cannot re-point where anybody's results land, which would launder a red suite
 *       into a green one.</li>
 *   <li><strong>{@code source = INGESTED} on the created run.</strong> A column {@code V1} has
 *       carried since M0 with only one value ever written to it.</li>
 * </ol>
 *
 * <p>Ids use the {@code d7…} prefix, which no other class in the suite uses.
 */
@AutoConfigureMockMvc
@DisplayName("POST /ingestions — a CI job posts results, and §6c's ladder places them")
class IngestionEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d7000000-0000-4000-8000-00000000000a");

    private static final UUID BANKING = UUID.fromString("d7001000-0000-4000-8000-000000000001");
    /**
     * A second project in the SAME company — the only way to assert the {@code projectId} predicate
     * on the history reads. RLS and the Hibernate filter both pass a sibling project's row, so a
     * lookup by id alone would serve it to somebody holding no grant here.
     */
    private static final UUID SAVINGS = UUID.fromString("d7001000-0000-4000-8000-000000000002");

    private static final UUID LEAD = UUID.fromString("d7003000-0000-4000-8000-000000000001");
    /** Automation Service — holds {@code run:create} and {@code result:submit}, not {@code case:edit}. */
    private static final UUID ROBOT = UUID.fromString("d7003000-0000-4000-8000-000000000002");
    private static final UUID VIEWER = UUID.fromString("d7003000-0000-4000-8000-000000000003");

    private static final UUID PAYMENTS = UUID.fromString("d7004000-0000-4000-8000-000000000001");

    private static final UUID ABSENT = UUID.fromString("d700ffff-0000-4000-8000-00000000ffff");

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

    /** Reachable by ladder step 1 — it carries an {@code automation_key}. */
    private UUID keyedCase;
    /** Reachable by ladder step 2 ALONE — no automation key, its display key is in the test name. */
    private UUID annotatedCase;
    /** Reachable by ladder step 3 ALONE — no key, no display key in the name; a mapping only. */
    private UUID mappedCase;
    /** Reachable by no rung. Its presence is what proves a matched report does not sweep it in. */
    private UUID strangerCase;

    private String annotatedKey;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "in-acme", "Acme", now);
            activeUser(LEAD, "lead@in-acme.test", "L. Lead", now);
            activeUser(ROBOT, "ci@in-acme.test", "CI", now);
            activeUser(VIEWER, "viewer@in-acme.test", "V. Viewer", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "INBANK", "Online Banking", now);
            seedProject(SAVINGS, ACME, "INSAV", "Savings", now);
            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ROBOT, "CI", now);
            member(ACME, VIEWER, "V. Viewer", now);
            grant("in-lead", ACME, Role.TEST_LEAD_ID, LEAD, BANKING, now);
            grant("in-robot", ACME, Role.AUTOMATION_SERVICE_ID, ROBOT, BANKING, now);
            grant("in-viewer", ACME, Role.VIEWER_ID, VIEWER, BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        clearMutableRows();
        seedTree();

        // Only the FIRST carries an automationKey, and that asymmetry is the fixture's whole
        // point: rungs 2 and 3 are unreachable if rung 1 can answer for everything. The other
        // three are MANUAL — which is also §6c's "cases receiving results while marked MANUAL",
        // the unlinked automation a team does not know it has.
        keyedCase = createCase("Refund the whole captured amount",
                "com.acme.RefundTest#refundsInFull");
        annotatedCase = createCase("Partial refund rounds correctly", null);
        mappedCase = createCase("Refund is idempotent", null);
        strangerCase = createCase("Nothing in any report names this", null);

        annotatedKey = displayKeyOf(annotatedCase);

        resetRunCounter();
        clearAudit();
    }

    // =====================================================================
    // §6c's ladder — each rung alone
    // =====================================================================

    @Nested
    @DisplayName("The four-step ladder — and each rung is exercised on its own")
    class Ladder {

        @Test
        @DisplayName("step 1 — an exact automation_key match")
        void exactAutomationKey() throws Exception {
            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-1");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            JsonNode job = body(posted);
            assertThat(job.get("matchedCount").asInt()).isOne();
            assertThat(job.get("unmatchedCount").asInt()).isZero();
            assertThat(statusKindOf(keyedCase)).isEqualTo("PASSED");
        }

        @Test
        @DisplayName("step 2 ALONE — a display key embedded in the test name, no automation key")
        void embeddedDisplayKey() throws Exception {
            // The case has NO automation_key, so a ladder that stopped at rung 1 matches nothing
            // here and this test is the one that catches it.
            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.PartialTest", "rounds [" + annotatedKey + "]",
                            "expected 3 but was 4")), "ik-2");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(posted).get("matchedCount").asInt()).isOne();
            assertThat(statusKindOf(annotatedCase)).isEqualTo("FAILED");
            assertThat(commentOf(annotatedCase)).contains("expected 3 but was 4");
        }

        @Test
        @DisplayName("step 3 ALONE — a mapping a person confirmed, through the real endpoint")
        void learnedMapping() throws Exception {
            // Confirmed through POST /automation-mappings, not seeded SQL: the two halves of the
            // slice are checked against each other, and the NORMALIZATION agreeing is the whole
            // point — a writer and a parser that normalize differently produce a row that exists,
            // a test that runs, and nothing binding them.
            assertThat(confirm(LEAD, "com.acme.IdemTest#IS  Idempotent", mappedCase)
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.IdemTest", "is idempotent", null)), "ik-3");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(posted).get("matchedCount").asInt()).isOne();
            assertThat(statusKindOf(mappedCase)).isEqualTo("PASSED");
        }

        @Test
        @DisplayName("step 4 — an unmatched test is counted and NAMED, and nothing is created")
        void unmatchedIsNamedAndCreatesNothing() throws Exception {
            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.GhostTest", "nobody has ever seen this", null)), "ik-4");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            JsonNode job = body(posted);
            assertThat(job.get("matchedCount").asInt()).isZero();
            assertThat(job.get("unmatchedCount").asInt()).isOne();
            assertThat(job.get("unmatched").get(0).get("name").asText())
                    .isEqualTo("com.acme.GhostTest#nobody has ever seen this");
            // The exact key POST /automation-mappings takes — served rather than left for the
            // client to recompute, which would be a second implementation of the one rule that
            // must agree exactly.
            assertThat(job.get("unmatched").get(0).get("normalizedName").asText())
                    .isEqualTo("com.acme.ghosttest#nobody has ever seen this");

            // §6c's optional case stub, declined. Nothing was created.
            assertThat(caseCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("a report matching NOTHING opens no run — not a run of zero cases")
        void nothingMatchedMeansNoRun() throws Exception {
            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.GhostTest", "unknown", null)), "ik-5");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            // A run of zero cases reports 0 of 0, 100%, complete — and this is the report a team
            // posts FIRST, while deciding whether the integration works at all.
            assertThat(body(posted).get("runId").isNull()).isTrue();
            assertThat(runCount()).isZero();
        }

        @Test
        @DisplayName("an unmatched test does not sweep an unrelated case into the run")
        void onlyMatchedCasesAreMaterialised() throws Exception {
            ingest(ROBOT, junit(
                    testCase("com.acme.RefundTest", "refundsInFull", null),
                    testCase("com.acme.GhostTest", "unknown", null)), "ik-6");

            assertThat(executionCount()).isOne();
            assertThat(statusKindOf(strangerCase)).isNull();
        }
    }

    // =====================================================================
    // The run
    // =====================================================================

    @Nested
    @DisplayName("The run the results land in")
    class Run {

        @Test
        @DisplayName("the created run is INGESTED and has STARTED — two columns nothing else wrote")
        void theRunIsIngestedAndStarted() throws Exception {
            ingest(ROBOT, junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-7");

            // test_run.source has had one legal value written to it since M0. TestRun#create
            // hard-coded MANUAL until this slice made it a parameter.
            assertThat(runColumn("source")).isEqualTo("INGESTED");
            // §72's rule: a run begins when the first result is recorded, not when it is named.
            assertThat(runColumn("status")).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("posting into a CLOSED run is a 409, and nothing is recorded")
        void aClosedRunIsRefused() throws Exception {
            ingest(ROBOT, junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-8");
            UUID runId = onlyRunId();
            closeRun(runId);

            MvcResult refused = ingestInto(ROBOT, runId,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-9");

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(jobCount()).isOne();
        }

        @Test
        @DisplayName("a runId that is not this project's live run is a 404")
        void anUnknownRunIs404() throws Exception {
            assertThat(ingestInto(ROBOT, ABSENT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-10")
                    .getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — the CI retry")
    class Idempotency {

        @Test
        @DisplayName("a retry under the same key is 200 with the ORIGINAL job and no second run")
        void aRetryReplays() throws Exception {
            MvcResult first = ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-same");
            MvcResult retry = ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-same");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("id").asText()).isEqualTo(body(first).get("id").asText());

            // The failure this prevents is not a duplicate row: it is a SECOND run, over which the
            // same build's results are split, each reporting a pass rate over a denominator nobody
            // chose. And it is the assertion that would go red if the write ran outside a
            // transaction — the lookup would return zero rows under RLS with no error anywhere.
            assertThat(runCount()).isOne();
            assertThat(jobCount()).isOne();
        }

        @Test
        @DisplayName("no Idempotency-Key at all is a 400 on that field — not a silent accept")
        void theKeyIsRequired() throws Exception {
            MvcResult refused = ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("Idempotency-Key")).isTrue();
            assertThat(jobCount()).isZero();
        }
    }

    // =====================================================================
    // The gates
    // =====================================================================

    @Nested
    @DisplayName("The gates — result:submit here, case:edit on the mapping")
    class Gates {

        @Test
        @DisplayName("a Viewer is refused the ingestion — 403, nothing written")
        void viewerIsRefused() throws Exception {
            MvcResult refused = ingest(VIEWER,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-11");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(jobCount()).isZero();
            assertThat(runCount()).isZero();
        }

        /**
         * <strong>The security property of the whole ingestion feature.</strong>
         *
         * <p>Automation Service holds {@code result:submit} and {@code run:create} and not
         * {@code case:edit} (roles spec §A6). Without that split a leaked CI token could bind every
         * failing test's name to a throwaway case and launder a red suite into a green one, leaving
         * a coverage dashboard that is confidently wrong and an audit log recording nothing
         * unusual. If this ever answers 200, read {@code AutomationMappingService}'s header before
         * "fixing" the gate.
         */
        @Test
        @DisplayName("the CI token may POST results and may NOT confirm a mapping")
        void theTokenCannotRepointResults() throws Exception {
            assertThat(ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-12")
                    .getResponse().getStatus()).isEqualTo(201);

            assertThat(confirm(ROBOT, "com.acme.RefundTest#refundsInFull", strangerCase)
                    .getResponse().getStatus())
                    .as("Automation Service must not hold case:edit — see this test's javadoc.")
                    .isEqualTo(403);
            assertThat(mappingCount()).isZero();
        }
    }

    // =====================================================================
    // Refusals about the payload
    // =====================================================================

    @Nested
    @DisplayName("Refusing a payload — and refusing it BEFORE anything is written")
    class Refusals {

        @Test
        @DisplayName("a malformed report is a field error on `report`, and writes no job row")
        void aMalformedReportWritesNothing() throws Exception {
            MvcResult refused = ingest(ROBOT, "not xml at all <<<", "ik-13");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("report")).isTrue();
            // No FAILED row: it would consume the idempotency key, so the CORRECTED retry under
            // the key CI generated once would replay the failure for ever.
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("the same key succeeds after a malformed report is corrected")
        void aCorrectedRetryUnderTheSameKeyWorks() throws Exception {
            assertThat(ingest(ROBOT, "not xml at all <<<", "ik-14")
                    .getResponse().getStatus()).isEqualTo(400);

            assertThat(ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-14")
                    .getResponse().getStatus())
                    .as("A FAILED job row would have burned this key — decision F.")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("an unnamed format is a field error on `format`")
        void anUnknownFormatIsRefused() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("format", null);
            payload.put("report", junit(testCase("c", "n", null)));

            MvcResult refused = post(ROBOT, "/projects/" + BANKING + "/ingestions", payload,
                    "ik-15");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("format")).isTrue();
        }
    }

    // =====================================================================
    // The mapping endpoint
    // =====================================================================

    @Nested
    @DisplayName("The mapping endpoint — what makes declining auto-created stubs workable")
    class Mappings {

        @Test
        @DisplayName("re-confirming MOVES the binding rather than refusing it")
        void reconfirmingMoves() throws Exception {
            assertThat(confirm(LEAD, "com.acme.IdemTest#is idempotent", mappedCase)
                    .getResponse().getStatus()).isEqualTo(200);
            // A 409 here would make correcting a mis-binding a delete-then-create, with a window
            // in the middle during which the CI job silently stops matching.
            MvcResult moved = confirm(LEAD, "com.acme.IdemTest#is idempotent", strangerCase);

            assertThat(moved.getResponse().getStatus()).isEqualTo(200);
            assertThat(mappingCount()).isOne();

            ingest(ROBOT, junit(testCase("com.acme.IdemTest", "is idempotent", null)), "ik-16");
            assertThat(statusKindOf(strangerCase)).isEqualTo("PASSED");
            assertThat(statusKindOf(mappedCase)).isNull();
        }

        @Test
        @DisplayName("a case from another project cannot be bound — a field error, not a 404")
        void aForeignCaseIsAFieldError() throws Exception {
            MvcResult refused = confirm(LEAD, "com.acme.SomeTest#x", ABSENT);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("caseId")).isTrue();
        }

        @Test
        @DisplayName("the list serves the case KEY beside the id, and the delete is idempotent")
        void listAndDelete() throws Exception {
            MvcResult confirmed = confirm(LEAD, "com.acme.IdemTest#is idempotent", mappedCase);
            String mappingId = body(confirmed).get("id").asText();

            JsonNode listed = body(get(LEAD, "/projects/" + BANKING + "/automation-mappings"));
            assertThat(listed).hasSize(1);
            // An id is not an answer a person can check against the case grid they are looking at.
            assertThat(listed.get(0).get("caseKey").asText()).isEqualTo(displayKeyOf(mappedCase));
            assertThat(listed.get(0).get("testName").asText())
                    .isEqualTo("com.acme.idemtest#is idempotent");

            assertThat(delete(LEAD, mappingId).getResponse().getStatus()).isEqualTo(204);
            assertThat(delete(LEAD, mappingId).getResponse().getStatus()).isEqualTo(204);
            assertThat(mappingCount()).isZero();
        }
    }

    // =====================================================================
    // Collapsing several tests onto one case
    // =====================================================================

    @Test
    @DisplayName("three tests matching one case give it the WORST outcome, and the job keeps all three")
    void severalTestsCollapseToTheWorst() throws Exception {
        // A parameterised test posts several rows against one case and an execution holds one
        // verdict. Taking the last would report this case as passed.
        MvcResult posted = ingest(ROBOT, junit(
                testCase("com.acme.RefundTest", "refundsInFull", null),
                testCase("com.acme.RefundTest", "refundsInFull", "expected 3"),
                testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-17");

        assertThat(posted.getResponse().getStatus()).isEqualTo(201);
        assertThat(statusKindOf(keyedCase)).isEqualTo("FAILED");
        assertThat(executionCount()).isOne();
        // The collapse is visible on the job rather than inferred from a count that does not add up.
        assertThat(body(posted).get("matchedCount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("one audit row for the job, not one per result")
    void oneAuditRowPerJob() throws Exception {
        ingest(ROBOT, junit(
                testCase("com.acme.RefundTest", "refundsInFull", null),
                testCase("com.acme.PartialTest", "rounds [" + annotatedKey + "]", null)), "ik-18");

        // Two results, and the run.created row beside this one. A row per verdict would bury the
        // project's audit log in the one event whose interesting fact is the job.
        assertThat(auditCount("ingestion.completed")).isOne();
    }

    // =====================================================================
    // The history — the two reads, and the Location that used to 404
    // =====================================================================

    /**
     * {@code GET /ingestions} and {@code GET /ingestions/{jobId}}.
     *
     * <p>Four things here can only be asserted from outside:
     *
     * <ol>
     *   <li><strong>The {@code Location} resolves.</strong> {@code POST /ingestions} has answered
     *       201 with a {@code Location} since it shipped and there was no {@code GET} at that path
     *       — a claim the API made and could not honour. The test follows the header rather than
     *       building the URL, so it fails if either side moves.</li>
     *   <li><strong>The detail's {@code unmatched} equals the post's own.</strong>
     *       {@link UnmatchedTests} is a second implementation of
     *       {@code IngestionService.unmatchedIn} — read back out of the stored payload instead of
     *       computed from the report — and this is the assertion that makes the second copy legal
     *       rather than a liability.</li>
     *   <li><strong>A replay carries the names.</strong> It served {@code unmatchedCount: 12} beside
     *       {@code unmatched: []} until the payload was read back, and the retry is the caller that
     *       most needs them.</li>
     *   <li><strong>A sibling project's job id is a 404.</strong> The row is company-scoped by RLS,
     *       so an id from another project in the same company resolves through {@code findById} —
     *       the two-segment defect, asserted with a real second project rather than trusted.</li>
     * </ol>
     */
    @Nested
    @DisplayName("The history — GET /ingestions, and the Location that used to point at a 404")
    class History {

        @Test
        @DisplayName("the list is newest first and carries no unmatched array at all")
        void theListIsSummaries() throws Exception {
            ingest(ROBOT, junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-h1");
            ingest(ROBOT, junit(testCase("com.acme.GhostTest", "unknown", null)), "ik-h2");

            JsonNode listed = body(get(LEAD, "/projects/" + BANKING + "/ingestions"));

            assertThat(listed).hasSize(2);
            // Newest first — the second post, which matched nothing and so opened no run.
            assertThat(listed.get(0).get("matchedCount").asInt()).isZero();
            assertThat(listed.get(0).get("unmatchedCount").asInt()).isOne();
            assertThat(listed.get(0).get("runId").isNull()).isTrue();
            assertThat(listed.get(1).get("matchedCount").asInt()).isOne();
            assertThat(listed.get(1).get("runKey").asText()).startsWith("TR-");

            // The names are ABSENT, not empty. An empty array beside a count of one reads as "the
            // list was cut" — the meaning unmatchedCount is documented to carry — when the truth
            // is that this endpoint does not serve them. Reading every job's JSONB blob to render
            // one screen is what the shape exists to avoid.
            assertThat(listed.get(0).has("unmatched")).isFalse();
        }

        @Test
        @DisplayName("the Location header names a resource that resolves to the same job")
        void theLocationResolves() throws Exception {
            MvcResult posted = ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-h3");

            String location = posted.getResponse().getHeader(HttpHeaders.LOCATION);
            assertThat(location).isNotNull();

            // Followed rather than reconstructed: a test that built the path itself would still
            // pass if the header named something else entirely, which is exactly the defect this
            // closes.
            MvcResult fetched = get(LEAD, location.substring("/api/v1".length()));

            assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(fetched).get("id").asText()).isEqualTo(body(posted).get("id").asText());
        }

        @Test
        @DisplayName("the detail's unmatched list is EXACTLY the one the post answered with")
        void theDetailAgreesWithThePost() throws Exception {
            MvcResult posted = ingest(ROBOT, junit(
                    testCase("com.acme.RefundTest", "refundsInFull", null),
                    testCase("com.acme.GhostTest", "one that failed", "boom"),
                    testCase("com.acme.GhostTest", "one that passed", null)), "ik-h4");

            JsonNode fromPost = body(posted).get("unmatched");
            JsonNode fromRead = body(get(LEAD,
                    "/projects/" + BANKING + "/ingestions/" + body(posted).get("id").asText()))
                    .get("unmatched");

            // Two implementations of one rule, asserted against each other rather than read
            // carefully — the standing this module gives R6's second policy in SQL (§111).
            assertThat(fromRead).isEqualTo(fromPost);
            assertThat(fromRead).hasSize(2);
            // The outcome is on the wire because it changes the urgency: an unmatched FAILURE is a
            // red test nobody is being told about.
            assertThat(fromRead.get(0).get("outcome").asText()).isEqualTo("FAILED");
            assertThat(fromRead.get(0).get("normalizedName").asText())
                    .isEqualTo("com.acme.ghosttest#one that failed");
        }

        @Test
        @DisplayName("a retry under the same key carries the NAMES again, not an empty array")
        void aReplayCarriesTheNames() throws Exception {
            ingest(ROBOT, junit(
                    testCase("com.acme.GhostTest", "first", null),
                    testCase("com.acme.GhostTest", "second", null)), "ik-h5");

            MvcResult retry = ingest(ROBOT, junit(
                    testCase("com.acme.GhostTest", "first", null),
                    testCase("com.acme.GhostTest", "second", null)), "ik-h5");

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            // `unmatchedCount: 2` beside `unmatched: []` was the shape before the payload was read
            // back — and this retry is the second time one build has failed to bind these tests.
            assertThat(body(retry).get("unmatchedCount").asInt()).isEqualTo(2);
            assertThat(body(retry).get("unmatched")).hasSize(2);
        }

        @Test
        @DisplayName("a Viewer may read the history — the gate is membership, not result:submit")
        void aViewerMayRead() throws Exception {
            ingest(ROBOT, junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-h6");

            // The Viewer is refused the POST (see Gates) and allowed the read, because a member may
            // read this project's runs and their verdicts, and a job is the record of how some of
            // those verdicts got there. A new ingestion:read key would be one seeded to nobody.
            MvcResult listed = get(VIEWER, "/projects/" + BANKING + "/ingestions");

            assertThat(listed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(listed)).hasSize(1);
        }

        @Test
        @DisplayName("a SIBLING PROJECT's job id is a 404, and is not in this project's list")
        void aSiblingProjectsJobIsUnreachable() throws Exception {
            ingest(ROBOT, junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-h7");
            UUID elsewhere = seedJobInSavings();

            // The row passes RLS and the Hibernate filter — it is the same company — so findById
            // would have returned it to somebody holding no grant on the project that owns it.
            assertThat(get(LEAD, "/projects/" + BANKING + "/ingestions/" + elsewhere)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(body(get(LEAD, "/projects/" + BANKING + "/ingestions"))).hasSize(1);
        }

        @Test
        @DisplayName("a job id that is nothing at all is the same bodiless 404")
        void anAbsentJobIs404() throws Exception {
            MvcResult missing = get(LEAD, "/projects/" + BANKING + "/ingestions/" + ABSENT);

            assertThat(missing.getResponse().getStatus()).isEqualTo(404);
            assertThat(missing.getResponse().getContentAsString()).isEmpty();
        }
    }

    // =====================================================================
    // The columns that record that a CREDENTIAL acted
    // =====================================================================

    /**
     * <strong>The only place in the suite where a write is authenticated by a real
     * {@code api_token}.</strong> Every other endpoint test issues a session for a user id, which
     * is the shape that let three token columns sit unwritten through two slices that shipped
     * tokens: the code path a CI job actually takes was never driven.
     */
    @Nested
    @DisplayName("Attribution — a CI job's row names the credential, not only its owner")
    class Attribution {

        @Test
        @DisplayName("an ingestion by a token fills actor_token_id AND actor_user_id")
        void bothActorColumnsAreWritten() throws Exception {
            CiToken ci = seedCiToken();

            MvcResult posted = ingestAs(ci.secret(),
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-at-1");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            // The owner is still the actor: they are answerable for the credential, and dropping
            // them would leave a row nobody can be asked about. The token is recorded BESIDE them,
            // which is what separates "L. Lead edited this" from "L. Lead's pipeline did".
            assertThat(auditActorUser()).isEqualTo(ROBOT);
            assertThat(auditActorToken()).isEqualTo(ci.tokenId());
        }

        @Test
        @DisplayName("the verdict names the credential and NOT a person")
        void theExecutionRecordsTheCredential() throws Exception {
            CiToken ci = seedCiToken();

            ingestAs(ci.secret(),
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-at-3");

            // executed_by stays null: the owner holds the credential, they did not run the test.
            // Before this slice BOTH were null, so an ingested verdict was attributable to nothing
            // at all — indistinguishable from one whose actor had been lost.
            assertThat(executedBy(keyedCase)).isNull();
            assertThat(executedByToken(keyedCase)).isEqualTo(ci.tokenId());
        }

        @Test
        @DisplayName("the case history renders the credential's NAME, which is what the SPA prints")
        void theHistoryNamesTheCredential() throws Exception {
            CiToken ci = seedCiToken();

            ingestAs(ci.secret(),
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-at-4");

            JsonNode entry = body(get(LEAD,
                    "/projects/" + BANKING + "/cases/" + keyedCase + "/history"))
                    .get("entries").get(0);

            // The wire field is a String and the SPA renders it directly — a uuid here would read
            // as "ingested by 8f3c1a…", which is the shape a name exists to avoid.
            assertThat(entry.get("executedByToken").asText()).isEqualTo(ci.name());
            assertThat(entry.get("executedBy").isNull()).isTrue();
        }

        @Test
        @DisplayName("the same ingestion over a SESSION leaves actor_token_id null")
        void aSessionWriteNamesNoCredential() throws Exception {
            // The negative half, and the one that fails if the column is ever filled from
            // something other than the token context — a hardcoded value, or a context leaked
            // from a previous request on this thread.
            MvcResult posted = ingest(ROBOT,
                    junit(testCase("com.acme.RefundTest", "refundsInFull", null)), "ik-at-2");

            assertThat(posted.getResponse().getStatus()).isEqualTo(201);
            assertThat(auditActorUser()).isEqualTo(ROBOT);
            assertThat(auditActorToken()).isNull();
            // And neither column on the verdict: a session ingestion identifies nobody who ran the
            // suite, which is honest rather than a gap.
            assertThat(executedBy(keyedCase)).isNull();
            assertThat(executedByToken(keyedCase)).isNull();
        }
    }

    /** A live credential for {@link #ROBOT}, scoped to BANKING — what a CI job holds. */
    private CiToken seedCiToken() {
        ApiTokenSecrets.NewSecret secret = secrets.generate();
        UUID id = UUID.randomUUID();
        String name = "ci " + id;
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            apiTokens.save(ApiToken.issue(id, ACME, name, secret.prefix(),
                    ROBOT, Role.AUTOMATION_SERVICE_ID, BANKING, null, Instant.now()));
            apiTokenLookups.save(ApiTokenLookup.of(secret.hash(), id, ACME));
        }));
        return new CiToken(id, name, secret.secret());
    }

    private record CiToken(UUID tokenId, String name, String secret) {
    }

    private MvcResult ingestAs(String bearer, String report, String idempotencyKey)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + BANKING + "/ingestions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ingestionPayload(report, null))))
                .andReturn();
    }

    private UUID auditActorUser() throws SQLException {
        return uuid("SELECT actor_user_id FROM audit_event WHERE action = 'ingestion.completed'");
    }

    private UUID auditActorToken() throws SQLException {
        return uuid("SELECT actor_token_id FROM audit_event WHERE action = 'ingestion.completed'");
    }

    private UUID executedBy(UUID caseId) throws SQLException {
        return uuid("SELECT executed_by FROM test_execution WHERE case_id = '" + caseId + "'");
    }

    private UUID executedByToken(UUID caseId) throws SQLException {
        return uuid("SELECT executed_by_token FROM test_execution WHERE case_id = '" + caseId + "'");
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private MvcResult ingest(UUID userId, String report, String idempotencyKey) throws Exception {
        return post(userId, "/projects/" + BANKING + "/ingestions",
                ingestionPayload(report, null), idempotencyKey);
    }

    private MvcResult ingestInto(UUID userId, UUID runId, String report, String idempotencyKey)
            throws Exception {

        return post(userId, "/projects/" + BANKING + "/ingestions",
                ingestionPayload(report, runId), idempotencyKey);
    }

    private static Map<String, Object> ingestionPayload(String report, UUID runId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", "JUNIT");
        payload.put("report", report);
        payload.put("runId", runId == null ? null : runId.toString());
        payload.put("buildNumber", "4412");
        return payload;
    }

    private MvcResult confirm(UUID userId, String testName, UUID caseId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("testName", testName);
        payload.put("caseId", caseId.toString());
        return post(userId, "/projects/" + BANKING + "/automation-mappings", payload, null);
    }

    private MvcResult delete(UUID userId, String mappingId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + BANKING + "/automation-mappings/" + mappingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult post(
            UUID userId, String path, Map<String, Object> payload, String idempotencyKey)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1" + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult get(UUID userId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private String tokenFor(UUID userId) {
        return tx.execute(status -> tokens.issue(userId, ACME, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // =====================================================================
    // helpers — reports
    // =====================================================================

    /** One {@code <testcase>}; a non-null {@code failure} makes it fail. */
    private static String testCase(String className, String name, String failure) {
        return "<testcase classname=\"" + className + "\" name=\"" + name + "\" time=\"0.1\">"
                + (failure == null ? "" : "<failure message=\"" + failure + "\"/>")
                + "</testcase>";
    }

    private static String junit(String... cases) {
        return "<testsuite name=\"s\">" + String.join("", cases) + "</testsuite>";
    }

    // =====================================================================
    // helpers — seeding
    // =====================================================================

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

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {

        UUID id = UUID.nameUUIDFromBytes(("ingestion:" + marker).getBytes());
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

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedTree() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, BANKING.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO suite
                        (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                            'TS-1', 1, 'Payments', 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, PAYMENTS.toString());
                statement.setString(2, ACME.toString());
                statement.setString(3, BANKING.toString());
                statement.executeUpdate();
            }
        }
    }

    /**
     * A completed job belonging to {@link #SAVINGS}, written directly.
     *
     * <p>Directly rather than through the endpoint, because what is under test is the
     * {@code projectId} predicate on the read — not a second project's ingestion, which would need
     * its own vocabulary, grants and cases to prove the same one thing.
     */
    private UUID seedJobInSavings() throws SQLException {
        UUID id = UUID.fromString("d7005000-0000-4000-8000-000000000001");
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO ingestion_job
                         (id, company_id, project_id, format, idempotency_key, status,
                          matched_count, unmatched_count, created_at)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'JUNIT',
                             'elsewhere', 'COMPLETED', 0, 0, now())
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, SAVINGS.toString());
            statement.executeUpdate();
        }
        return id;
    }

    private void clearMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            for (String table : List.of(
                    "audit_event", "automation_key_mapping", "ingestion_job", "defect_link",
                    "step_result", "test_execution", "test_run", "plan_scope_item",
                    "test_plan_version", "test_plan", "case_tag", "suite_tag_rule", "suite_case",
                    "test_case_version", "test_step", "test_case", "suite_version", "suite")) {
                execute(connection, "DELETE FROM " + table);
            }
            execute(connection, "UPDATE key_sequence SET next_value = 1");
        }
    }

    private void resetRunCounter() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection,
                    "UPDATE key_sequence SET next_value = 1 WHERE entity_type = 'RUN'");
        }
    }

    private void clearAudit() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event");
        }
    }

    /**
     * @param automationKey non-null makes the case {@code AUTOMATED}. The create endpoint refuses
     *                      {@code AUTOMATED} without one — a real rule this fixture found by being
     *                      written against the real endpoint rather than against SQL.
     */
    private UUID createCase(String title, String automationKey) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", PAYMENTS.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by IngestionEndpointTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", automationKey == null ? "MANUAL" : "AUTOMATED");
        payload.put("automationKey", automationKey);
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        MvcResult created = post(LEAD, "/projects/" + BANKING + "/cases", payload, null);
        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private void closeRun(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_run SET status = 'COMPLETED' WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back, past the API
    // =====================================================================

    private String displayKeyOf(UUID caseId) throws SQLException {
        return string("SELECT key FROM test_case WHERE id = CAST('" + caseId + "' AS uuid)");
    }

    /** The KIND of the status recorded on this case's only execution, or null when there is none. */
    private String statusKindOf(UUID caseId) throws SQLException {
        return string("""
                SELECT s.kind FROM test_execution e
                JOIN case_status s ON s.id = e.status_id
                WHERE e.case_id = CAST('""" + caseId + "' AS uuid)");
    }

    private String commentOf(UUID caseId) throws SQLException {
        return string("SELECT comment FROM test_execution WHERE case_id = CAST('"
                + caseId + "' AS uuid)");
    }

    private String runColumn(String column) throws SQLException {
        return string("SELECT " + column + " FROM test_run");
    }

    private UUID onlyRunId() throws SQLException {
        return UUID.fromString(string("SELECT id FROM test_run"));
    }

    private long runCount() throws SQLException {
        return count("SELECT COUNT(*) FROM test_run");
    }

    private long jobCount() throws SQLException {
        return count("SELECT COUNT(*) FROM ingestion_job");
    }

    private long executionCount() throws SQLException {
        return count("SELECT COUNT(*) FROM test_execution");
    }

    private long mappingCount() throws SQLException {
        return count("SELECT COUNT(*) FROM automation_key_mapping");
    }

    private long caseCount() throws SQLException {
        return count("SELECT COUNT(*) FROM test_case WHERE deleted_at IS NULL");
    }

    private long auditCount(String action) throws SQLException {
        return count("SELECT COUNT(*) FROM audit_event WHERE action = '" + action + "'");
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private UUID uuid(String sql) throws SQLException {
        String value = string(sql);
        return value == null ? null : UUID.fromString(value);
    }

    private String string(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
