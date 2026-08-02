package com.tcms.project;

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
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.ResultSet;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{id}/reviews} and {@code POST …/reviews/{id}/participations} over HTTP, and
 * the review rows the three status ladders write behind them — against real Postgres with RLS in
 * force.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A withdrawal recorded as a rejection.</strong> The submitter taking their own
 *       submission back and a colleague sending it back walk the <em>same</em> ladder edge at the
 *       <em>same</em> permission (§6g: submitting needs only edit rights), so the only thing telling
 *       them apart is who is asking. Collapsed, the record a team keeps to show an auditor who said
 *       what names a reviewer who never spoke, and nothing anywhere else in the suite notices.</li>
 *   <li><strong>A second door to the sign-off.</strong> If the participation endpoint accepted
 *       {@code APPROVED} as a verdict, everyone holding the advisory {@code *:review} key could
 *       approve — §6g's single gate, bypassed by the tier below it.</li>
 *   <li><strong>A gate everybody passes.</strong> Viewer holds {@code case:comment} and
 *       <strong>not</strong> {@code case:review} (roles spec §A7, and V3's own comment about the
 *       three tiers), which is what makes {@link PeerVerdicts#aViewerCannotRecordAVerdict}
 *       discriminating rather than vacuous — §71 had to record a gate whose mutation reddened
 *       nothing because both keys were granted to identical roles.</li>
 *   <li><strong>A review record for two of the three artifacts.</strong> The case path runs through
 *       {@code CaseBulkService} and the other two through their own status services, so a slice that
 *       wired the suite and the plan would look complete and leave §6g's <em>common</em> case — a
 *       case — with no record at all.</li>
 *   <li><strong>A version pinned late.</strong> The whole point of {@code subject_version_no} is
 *       that it names the content somebody was asked to read; re-stamping it on approval would make
 *       every review agree with the artifact by construction.</li>
 *   <li><strong>Two open reviews for one subject.</strong> Withdraw-then-resubmit is ordinary use,
 *       and if the close did not happen the second submission would leave two rows {@code IN_REVIEW}
 *       — one of them for ever, on an artifact that is not in review.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code re…}, checked against every prefix
 * already in {@code src/test/java} before writing (handoff §19, and it has collided five times
 * since).
 */
@AutoConfigureMockMvc
@DisplayName("Reviews — the record the ladders write, and the peer verdict that moves nothing")
class ReviewEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("9a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("9a000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("9a001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD only — the sibling a review id must not cross into. */
    private static final UUID ACME_MOBILE = UUID.fromString("9a001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("9a002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds every {@code *:review} AND every {@code *:approve}. */
    private static final UUID LEAD = UUID.fromString("9a003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. Holds {@code case:review}, {@code suite:review}, {@code plan:review} and
     * <strong>none</strong> of the approve keys — roles spec §A4's second separation, and the
     * persona the whole advisory tier exists for.
     */
    private static final UUID ENGINEER = UUID.fromString("9a003000-0000-4000-8000-000000000002");
    /**
     * Viewer. Holds {@code case:comment} and {@code project:read}, and <strong>not</strong>
     * {@code case:review} — the one persona that separates the comment tier from the review tier.
     */
    private static final UUID VIEWER = UUID.fromString("9a003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("9a003000-0000-4000-8000-000000000004");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("9a003000-0000-4000-8000-000000000005");

    /** The suite the cases live in, and a review subject in its own right. */
    private static final UUID PAYMENTS = UUID.fromString("9a004000-0000-4000-8000-000000000001");
    private static final UUID CASE_ONE = UUID.fromString("9a005000-0000-4000-8000-000000000001");
    private static final UUID CASE_TWO = UUID.fromString("9a005000-0000-4000-8000-000000000002");

    private static final UUID ABSENT = UUID.fromString("9a00ffff-0000-4000-8000-00000000ffff");

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
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rv-acme", "Acme", now);
            company(UMBRA, "rv-umbra", "Umbra", now);

            activeUser(LEAD, "lead@rv-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@rv-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@rv-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@rv-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@rv-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "RVAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "RVAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("rv-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("rv-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("rv-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("rv-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "RVUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("rv-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedProjectDefaults();
        seedRepository();
    }

    // =====================================================================
    // The ladders open and close the record — all three subject types
    // =====================================================================

    @Nested
    @DisplayName("The record the ladders write")
    class TheLadders {

        @Test
        @DisplayName("submitting a plan opens a review pinned to the version submitted")
        void submittingOpensAReview() throws Exception {
            UUID plan = plan(LEAD, "Release 4.3");

            setPlanStatus(LEAD, plan, "IN_REVIEW");

            JsonNode review = onlyReview(LEAD, "PLAN", plan);
            assertThat(review.get("state").asText()).isEqualTo("IN_REVIEW");
            assertThat(review.get("subjectVersionNo").asInt())
                    .as("the plan's current version, pinned at submission")
                    .isEqualTo(1);
            assertThat(review.get("submittedBy").get("displayName").asText())
                    .as("the MEMBERSHIP's name, never the account's canonical one (§6u)")
                    .isEqualTo("L. Lead of Acme");
            assertThat(review.get("participations")).isEmpty();
            assertThat(review.get("approvedBy").isNull()).isTrue();
        }

        @Test
        @DisplayName("approving closes it, names the signer, and writes the APPROVAL row")
        void approvingClosesIt() throws Exception {
            UUID plan = plan(LEAD, "Release 4.4");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            setPlanStatus(LEAD, plan, "APPROVED");

            JsonNode review = onlyReview(LEAD, "PLAN", plan);
            assertThat(review.get("state").asText()).isEqualTo("APPROVED");
            assertThat(review.get("approvedBy").get("id").asText()).isEqualTo(LEAD.toString());
            assertThat(review.get("approvedAt").isNull()).isFalse();

            JsonNode participation = review.get("participations").get(0);
            assertThat(participation.get("kind").asText()).isEqualTo("APPROVAL");
            assertThat(participation.get("verdict").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("the submitter taking it back is a WITHDRAWAL, and writes no verdict")
        void theSubmitterWithdrawsRatherThanBeingRejected() throws Exception {
            UUID plan = plan(ENGINEER, "Release 4.5");
            setPlanStatus(ENGINEER, plan, "IN_REVIEW");

            setPlanStatus(ENGINEER, plan, "DRAFT");

            JsonNode review = onlyReview(LEAD, "PLAN", plan);
            assertThat(review.get("state").asText())
                    .as("nobody judged this plan — its author stopped asking")
                    .isEqualTo("WITHDRAWN");
            assertThat(review.get("participations"))
                    .as("a withdrawal is not a verdict and must not read as one")
                    .isEmpty();
        }

        @Test
        @DisplayName("somebody else sending it back is a REJECTION, with the verdict to prove it")
        void anotherPersonSendingItBackIsARejection() throws Exception {
            UUID plan = plan(ENGINEER, "Release 4.6");
            setPlanStatus(ENGINEER, plan, "IN_REVIEW");

            setPlanStatus(LEAD, plan, "DRAFT");

            JsonNode review = onlyReview(LEAD, "PLAN", plan);
            assertThat(review.get("state").asText()).isEqualTo("REJECTED");

            JsonNode participation = review.get("participations").get(0);
            assertThat(participation.get("kind").asText()).isEqualTo("APPROVAL");
            assertThat(participation.get("verdict").asText()).isEqualTo("REJECTED");
            assertThat(participation.get("user").get("id").asText()).isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("a move that never touches review writes nothing at all")
        void aMoveOutsideReviewWritesNothing() throws Exception {
            UUID plan = plan(LEAD, "Release 4.7");

            // DRAFT → ACTIVE, the path a project that never turned review on takes (§6g: review is
            // optional and off by default). A record here would mean every team that does not
            // review at all accumulates reviews nobody held.
            setPlanStatus(LEAD, plan, "ACTIVE");

            assertThat(reviewsOf(LEAD, "PLAN", plan)).isEmpty();
        }

        @Test
        @DisplayName("re-submitting opens a second review, and only one of them is open")
        void resubmittingOpensASecondReview() throws Exception {
            UUID plan = plan(LEAD, "Release 4.8");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            setPlanStatus(LEAD, plan, "DRAFT");

            setPlanStatus(LEAD, plan, "IN_REVIEW");

            JsonNode reviews = reviewsOf(LEAD, "PLAN", plan);
            assertThat(reviews).hasSize(2);
            assertThat(reviews.get(0).get("state").asText())
                    .as("newest first — the open one leads")
                    .isEqualTo("IN_REVIEW");
            assertThat(openReviewCount("PLAN", plan))
                    .as("V22's partial unique index has one row to adjudicate, not two")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a suite goes through the same record, from its own ladder")
        void aSuiteGetsTheSameRecord() throws Exception {
            setSuiteStatus(ENGINEER, PAYMENTS, "IN_REVIEW");

            JsonNode review = onlyReview(LEAD, "SUITE", PAYMENTS);
            assertThat(review.get("subjectType").asText()).isEqualTo("SUITE");
            assertThat(review.get("state").asText()).isEqualTo("IN_REVIEW");
            assertThat(review.get("submittedBy").get("id").asText()).isEqualTo(ENGINEER.toString());
        }

        @Test
        @DisplayName("a bulk submit opens one review per case — §6g's common case")
        void bulkSubmittingOpensOneReviewPerCase() throws Exception {
            bulkSetStatus(ENGINEER, "IN_REVIEW", CASE_ONE, CASE_TWO);

            assertThat(onlyReview(LEAD, "CASE", CASE_ONE).get("state").asText())
                    .isEqualTo("IN_REVIEW");
            assertThat(onlyReview(LEAD, "CASE", CASE_TWO).get("state").asText())
                    .isEqualTo("IN_REVIEW");

            // And the lead approving both closes exactly those two.
            bulkSetStatus(LEAD, "APPROVED", CASE_ONE, CASE_TWO);

            assertThat(onlyReview(LEAD, "CASE", CASE_ONE).get("state").asText())
                    .isEqualTo("APPROVED");
            assertThat(onlyReview(LEAD, "CASE", CASE_TWO).get("approvedBy").get("id").asText())
                    .isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("an artifact edited while in review keeps the version it was submitted at")
        void theVersionIsPinnedAtSubmission() throws Exception {
            UUID plan = plan(LEAD, "Release 4.9");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            int submittedAt = onlyReview(LEAD, "PLAN", plan).get("subjectVersionNo").asInt();

            // A scope change writes a plan version (§74), so the artifact moves past the review.
            patchPlanScope(LEAD, plan, PAYMENTS);

            assertThat(onlyReview(LEAD, "PLAN", plan).get("subjectVersionNo").asInt())
                    .as("the reviewer was asked to read v%s, and that is what the record says",
                            submittedAt)
                    .isEqualTo(submittedAt);
            assertThat(planVersionOf(plan))
                    .as("the fixture is only meaningful if the plan really did move on")
                    .isGreaterThan(submittedAt);
        }
    }

    // =====================================================================
    // The advisory tier
    // =====================================================================

    @Nested
    @DisplayName("Peer verdicts — advisory, and only advisory")
    class PeerVerdicts {

        @Test
        @DisplayName("an engineer records LGTM and gets the whole trail back")
        void engineerRecordsLgtm() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = participate(ENGINEER, review, "LGTM", "Scope looks right to me");

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode participation = body(result).get("participations").get(0);
            assertThat(participation.get("kind").asText())
                    .as("advisory by construction — this endpoint has no path to APPROVAL")
                    .isEqualTo("PEER_REVIEW");
            assertThat(participation.get("verdict").asText()).isEqualTo("LGTM");
            assertThat(participation.get("comment").asText()).isEqualTo("Scope looks right to me");
            assertThat(body(result).get("state").asText())
                    .as("a peer verdict accumulates on the item WITHOUT moving it (§6g)")
                    .isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("requesting changes does not move the plan off IN_REVIEW either")
        void requestingChangesMovesNothing() throws Exception {
            UUID plan = plan(LEAD, "Release 5.1");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());

            participate(ENGINEER, review, "CHANGES_REQUESTED", "Add the migration suite");

            assertThat(planStatusOf(plan)).isEqualTo("IN_REVIEW");
            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText()).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("APPROVED is refused as a verdict — the sign-off is the ladder's, not this one's")
        void approvingThroughAVerdictIsRefused() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = participate(ENGINEER, review, "APPROVED", null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("verdict");
            assertThat(participationCount(review))
                    .as("a refused verdict must write nothing")
                    .isZero();
        }

        @Test
        @DisplayName("REJECTED is refused for the same reason")
        void rejectingThroughAVerdictIsRefused() throws Exception {
            UUID review = openPlanReview();

            assertThat(participate(ENGINEER, review, "REJECTED", null).getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a Viewer holds case:comment and not plan:review, and is refused with 403")
        void aViewerCannotRecordAVerdict() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = participate(VIEWER, review, "LGTM", null);

            assertThat(result.getResponse().getStatus())
                    .as("403 rather than 404: the Viewer can see this project and this review")
                    .isEqualTo(403);
            assertThat(detailOf(result))
                    .as("never the permission key — it publishes the catalogue to the one caller "
                            + "not entitled to it")
                    .doesNotContain("plan:review");
            assertThat(participationCount(review)).isZero();
        }

        @Test
        @DisplayName("both verdicts are kept when one person changes their mind")
        void repeatVerdictsAreBothKept() throws Exception {
            UUID review = openPlanReview();

            participate(ENGINEER, review, "CHANGES_REQUESTED", "Missing the payments suite");
            MvcResult second = participate(ENGINEER, review, "LGTM", "Fixed, thanks");

            JsonNode trail = body(second).get("participations");
            assertThat(trail).hasSize(2);
            assertThat(trail.get(0).get("verdict").asText())
                    .as("oldest first — the request for changes comes before the LGTM answering it")
                    .isEqualTo("CHANGES_REQUESTED");
            assertThat(trail.get(1).get("verdict").asText()).isEqualTo("LGTM");
        }

        @Test
        @DisplayName("a closed review refuses the verdict with 409")
        void aClosedReviewRefuses() throws Exception {
            UUID plan = plan(LEAD, "Release 5.2");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            setPlanStatus(LEAD, plan, "APPROVED");

            MvcResult result = participate(ENGINEER, review, "LGTM", null);

            assertThat(result.getResponse().getStatus())
                    .as("nothing about the request is malformed — the review has ended")
                    .isEqualTo(409);
            assertThat(participationCount(review))
                    .as("only the APPROVAL row the approval itself wrote")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a blank comment is stored as no comment, not as an empty one")
        void aBlankCommentIsNoComment() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = participate(ENGINEER, review, "LGTM", "   ");

            assertThat(body(result).get("participations").get(0).get("comment").isNull()).isTrue();
        }
    }

    // =====================================================================
    // Isolation and the shapes of "no"
    // =====================================================================

    @Nested
    @DisplayName("Isolation — the review id is resolved through the project, never the company")
    class Isolation {

        @Test
        @DisplayName("a sibling project's review id is 404, not somebody else's review")
        void aSiblingProjectsReviewIsNotReachable() throws Exception {
            UUID plan = plan(LEAD, "Release 5.3");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_MOBILE + "/reviews/" + review
                                    + "/participations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("verdict", "LGTM"))))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("RLS scopes a findById to the COMPANY; the project is this endpoint's job")
                    .isEqualTo(404);
            assertThat(participationCount(review)).isZero();
        }

        @Test
        @DisplayName("another company's caller gets 404 from the read, not an empty list")
        void anotherCompanysCallerIsNotFound() throws Exception {
            UUID plan = plan(LEAD, "Release 5.4");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/reviews")
                            .param("subjectType", "PLAN")
                            .param("subjectId", plan.toString())
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + tokenFor(OUTSIDER, UMBRA))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("a member with no grant on the project gets 404, never 403")
        void aMemberWithNoGrantIsNotFound() throws Exception {
            UUID plan = plan(LEAD, "Release 5.5");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            MvcResult result = get(NO_GRANT, "PLAN", plan);

            assertThat(result.getResponse().getStatus())
                    .as("§6t: an unreachable project and a non-existent one are indistinguishable")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an unknown subject type is a field error, not an empty list")
        void anUnknownSubjectTypeIsAFieldError() throws Exception {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/reviews")
                            .param("subjectType", "RUN")
                            .param("subjectId", ABSENT.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("subjectType");
        }

        @Test
        @DisplayName("a subject nobody ever submitted answers 200 with an empty array")
        void anUnreviewedSubjectIsAnEmptyList() throws Exception {
            MvcResult result = get(LEAD, "CASE", CASE_ONE);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).isEmpty();
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    /** A plan created through the real endpoint, so its shape is never a fixture's opinion. */
    private UUID plan(UUID userId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("suiteIds", List.of());
        payload.put("caseIds", List.of());

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    /** A plan submitted by the lead — the starting point of every advisory-tier test. */
    private UUID openPlanReview() throws Exception {
        UUID plan = plan(LEAD, "Release " + UUID.randomUUID());
        setPlanStatus(LEAD, plan, "IN_REVIEW");
        return UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
    }

    private MvcResult setPlanStatus(UUID userId, UUID planId, String status) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", status))))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the ladder move this test depends on; %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    private void setSuiteStatus(UUID userId, UUID suiteId, String status) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ACME_BANKING + "/suites/" + suiteId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", status))))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void bulkSetStatus(UUID userId, String status, UUID... caseIds) throws Exception {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("action", "SET_STATUS");
        operation.put("caseIds", java.util.Arrays.stream(caseIds).map(UUID::toString).toList());
        operation.put("dryRun", false);
        operation.put("payload", Map.of("lifecycleStatus", status));

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/cases/bulk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(operation)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(body(result).get("changed"))
                .as("the fixture depends on the bulk operation actually changing the cases")
                .hasSize(caseIds.length);
    }

    /** A scope change, which is what makes a plan write a new version (§74). */
    private void patchPlanScope(UUID userId, UUID planId, UUID suiteId) throws Exception {
        MvcResult read = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .header(HttpHeaders.IF_MATCH,
                                "\"" + body(read).get("lockVersion").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        // The name goes back unchanged: a PATCH omitting a scalar CLEARS it
                        // (§74's absent/null/empty rule), and this fixture is about the scope.
                        .content(json.writeValueAsString(Map.of(
                                "name", body(read).get("name").asText(),
                                "suiteIds", List.of(suiteId.toString())))))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private MvcResult participate(UUID userId, UUID reviewId, String verdict, String comment)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict", verdict);
        payload.put("comment", comment);

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult get(UUID userId, String subjectType, UUID subjectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ACME_BANKING + "/reviews")
                        .param("subjectType", subjectType)
                        .param("subjectId", subjectId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private JsonNode reviewsOf(UUID userId, String subjectType, UUID subjectId) throws Exception {
        MvcResult result = get(userId, subjectType, subjectId);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result);
    }

    private JsonNode onlyReview(UUID userId, String subjectType, UUID subjectId) throws Exception {
        JsonNode reviews = reviewsOf(userId, subjectType, subjectId);
        assertThat(reviews).as("expected exactly one review for this subject").hasSize(1);
        return reviews.get(0);
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String detailOf(MvcResult result) throws Exception {
        return body(result).get("detail").asText();
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        assertThat(node).as("a 400 must name the fields it refused").isNotNull();
        node.properties().forEach(entry -> {
            List<String> messages = new ArrayList<>();
            entry.getValue().forEach(message -> messages.add(message.asText()));
            errors.put(entry.getKey(), messages);
        });
        return errors;
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
        if (projects.findById(id).isEmpty()) {
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
        UUID id = UUID.nameUUIDFromBytes(("review:" + marker).getBytes());
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

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
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
     * One suite and two cases, written directly.
     *
     * <p>Through SQL rather than the create endpoints because what this class tests is what the
     * <em>status</em> paths write; going through the authoring endpoints would make the fixture
     * depend on their payload rules for facts this class does not assert. Both cases start
     * {@code DRAFT}, which is where every case on a real instance starts.
     */
    private void seedRepository() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO suite
                        (id, company_id, project_id, parent_id, key, key_seq, path, name,
                         description, position, lifecycle_status, current_version_no, lock_version,
                         created_at, updated_at, deleted_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, 'TS-1', 1,
                            CAST('payments' AS ltree), 'Payments', NULL, 0, 'DRAFT', 1, 0,
                            now(), now(), NULL)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, PAYMENTS.toString());
                statement.setString(2, ACME.toString());
                statement.setString(3, ACME_BANKING.toString());
                statement.executeUpdate();
            }
            testCase(connection, CASE_ONE, "TC-1", 1, "Card payment succeeds");
            testCase(connection, CASE_TWO, "TC-2", 2, "Refund is issued");
        }
    }

    private void testCase(Connection connection, UUID id, String key, long keySeq, String title)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     objective, expected_outcome, step_format, gherkin_source, priority, severity,
                     estimated_duration_sec, lifecycle_status, automation_status, automation_key,
                     not_automatable_reason, owner_id, current_version_no, lock_version,
                     created_at, created_by, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, NULL, NULL, NULL, 'STRUCTURED', NULL, 'CRITICAL', 'HIGH',
                        NULL, 'DRAFT', 'MANUAL', NULL, NULL, NULL, 1, 1,
                        now(), NULL, now(), NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.setString(4, PAYMENTS.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
    }

    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM review_participation");
                execute(connection, "DELETE FROM review");
                execute(connection, "DELETE FROM plan_scope_item");
                execute(connection, "DELETE FROM test_plan_version");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role, past the API
    // =====================================================================

    private int openReviewCount(String subjectType, UUID subjectId) throws SQLException {
        return count("""
                SELECT count(*) FROM review
                 WHERE subject_type = ? AND subject_id = CAST(? AS uuid) AND state = 'IN_REVIEW'
                """, subjectType, subjectId.toString());
    }

    private int participationCount(UUID reviewId) throws SQLException {
        return count(
                "SELECT count(*) FROM review_participation WHERE review_id = CAST(? AS uuid)",
                reviewId.toString());
    }

    private int count(String sql, String... arguments) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setString(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private String planStatusOf(UUID planId) throws SQLException {
        return planColumn(planId, "status");
    }

    private int planVersionOf(UUID planId) throws SQLException {
        return Integer.parseInt(planColumn(planId, "CAST(current_version_no AS text)"));
    }

    private String planColumn(UUID planId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
