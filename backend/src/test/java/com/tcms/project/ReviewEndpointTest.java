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
import com.tcms.identity.Team;
import com.tcms.identity.TeamRepository;
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

    /** A team in Acme — §6g's "assign a user <em>or</em> a team". */
    private static final UUID ACME_QA_TEAM = UUID.fromString("9a006000-0000-4000-8000-000000000001");
    /** A team in Umbra. Satisfies nothing here, and is the fourth column in §64's family. */
    private static final UUID UMBRA_TEAM = UUID.fromString("9a006000-0000-4000-8000-000000000002");

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
    private ProjectSettingsRepository projectSettings;

    @Autowired
    private TeamRepository teams;

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

            seedTeam(ACME_QA_TEAM, ACME, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "RVUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("rv-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);

            seedTeam(UMBRA_TEAM, UMBRA, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedProjectDefaults();
        seedRepository();

        // §6g's opt-in is OFF by default (V23), which is what a real project gets — so a fixture
        // that did not do this would leave every assertion in this class reading an empty list, and
        // that is exactly what happened when the setting landed: 17 of these tests went red at once
        // on a code change that was working correctly. Recorded rather than papered over, because
        // the inverse — a fixture sitting in a state no real install produces — is §72's finding and
        // this is the moment it would have been introduced.
        //
        // The two POLICY settings are left at their permissive values here on purpose. These tests
        // are about the RECORD (who was asked, who said what, who signed off) and most of them have
        // one persona submit and then approve, which the default author-cannot-approve rule refuses.
        // The rule and the peer minimum have their own nested class, which turns them on explicitly.
        enableReview(ACME, ACME_BANKING, 0, true);
        enableReview(ACME, ACME_MOBILE, 0, true);
        enableReview(UMBRA, UMBRA_BANKING, 0, true);
    }

    /**
     * Turns §6g's workflow on for a project, for all three artifact types.
     *
     * @param requiredPeers     §6g's minimum peer reviews — pinned onto each review as it opens, so
     *                          this must be set BEFORE the submission it is meant to govern
     * @param authorMayApprove  {@code true} disables the author-cannot-approve-own-work rule
     */
    private void enableReview(
            UUID companyId, UUID projectId, int requiredPeers, boolean authorMayApprove) {

        inCompany(companyId, () -> tx.executeWithoutResult(status -> {
            Instant at = Instant.now();
            ProjectSettings row = projectSettings.findForProject(projectId)
                    .orElseGet(() -> ProjectSettings.initial(companyId, projectId, at));
            row.apply(
                    // §6g's GATING rule stays off here, and that is the fixture asserting
                    // something: it is deliberately not governed by the review opt-in this
                    // helper turns on (see ReviewSettings), so a helper that switched it on
                    // alongside would hide exactly the coupling §82 refused to create. There were
                    // two until §97 (M7) removed requireApprovedPlanToStart with its edge.
                    new ReviewSettings(
                            true, true, true, true, requiredPeers, authorMayApprove,
                            EditAfterApproval.RETAIN, false, false),
                    // 016 D-g's default. Nothing in this class reads it — it travels beside
                    // ReviewSettings rather than inside it, because time tracking is not a review
                    // setting — and `true` is what a project actually carries.
                    true,
                    null,
                    at);
            projectSettings.saveAndFlush(row);
        }));
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

            // IN_REVIEW → DRAFT with no review ever opened. §97 (M7) removed DRAFT → ACTIVE, which
            // was the move this test used to make — the path a project that never turned review on
            // takes. The property is the same one: `ReviewService.moved` with a standing of
            // OUTSIDE_REVIEW and no open record must write NOTHING, or every team that does not
            // review at all accumulates reviews nobody held.
            setPlanStatus(LEAD, plan, "DRAFT");

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
    @DisplayName("Comments — the tier below review, and the thread that is one level deep")
    class Comments {

        @Test
        @DisplayName("a Viewer holding case:comment may comment on a PLAN review")
        void aViewerMayCommentOnAPlan() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = comment(VIEWER, review, "Is the migration suite in scope?", null);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            THE ONE-KEY DECISION, and the persona that makes it discriminating. \
                            The Viewer holds case:comment and NOT plan:review — the same persona \
                            that gets 403 from the verdict endpoint one @Nested class up. A gate \
                            keyed on the subject type would answer 403 here, and §6b's whole \
                            reason for case:comment is the client who reviews coverage without \
                            write access.""")
                    .isEqualTo(201);

            JsonNode thread = body(result).get("comments").get(0);
            assertThat(thread.get("body").asText()).isEqualTo("Is the migration suite in scope?");
            assertThat(thread.get("author").get("id").asText()).isEqualTo(VIEWER.toString());
            assertThat(thread.get("resolvedAt").isNull()).isTrue();
            assertThat(thread.get("replies")).isEmpty();
        }

        @Test
        @DisplayName("a reply hangs under its root, and the root is the only top-level entry")
        void aReplyHangsUnderItsRoot() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Is the migration suite in scope?", null));

            MvcResult result = comment(ENGINEER, review, "It is — added last week.", root);

            JsonNode threads = body(result).get("comments");
            assertThat(threads).as("one thread, not two entries").hasSize(1);
            assertThat(threads.get(0).get("replies")).hasSize(1);
            assertThat(threads.get(0).get("replies").get(0).get("body").asText())
                    .isEqualTo("It is — added last week.");
            assertThat(threads.get(0).get("replies").get(0).get("replies"))
                    .as("exactly one level: a reply's own replies is always empty")
                    .isEmpty();
        }

        @Test
        @DisplayName("a reply to a reply is a field error — a thread has a bottom")
        void aReplyToAReplyIsRefused() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Root", null));
            UUID reply = commentId(comment(ENGINEER, review, "Reply", root));

            MvcResult result = comment(VIEWER, review, "Reply to the reply", reply);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("parentId");
            assertThat(commentCount(review))
                    .as("a refused reply must write nothing")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a parent from another project's review is refused, and stores nothing")
        void aParentFromAnotherReviewIsRefused() throws Exception {
            UUID mine = openPlanReview();
            UUID theirs = openPlanReview();
            UUID foreign = commentId(comment(VIEWER, theirs, "Somewhere else", null));

            MvcResult result = comment(VIEWER, mine, "Threaded onto the wrong conversation", foreign);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            §64's sharedStepId and §68's classification value, third time: the id \
                            satisfies the foreign key, the company column and RLS, and unchecked \
                            it is STORED — after which the remark renders inside a conversation \
                            its author never saw.""")
                    .isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("parentId");
            assertThat(commentCount(mine)).isZero();
            assertThat(commentCount(theirs)).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty remark is a field error, not a 201 with nothing in it")
        void aBlankBodyIsRefused() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = comment(VIEWER, review, "   ", null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("body");
            assertThat(commentCount(review)).isZero();
        }

        @Test
        @DisplayName("a remark past the length bound is refused")
        void anOversizedBodyIsRefused() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = comment(VIEWER, review, "x".repeat(5_001), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("body");
        }

        @Test
        @DisplayName("a closed review refuses the comment with 409, as it refuses the verdict")
        void aClosedReviewRefuses() throws Exception {
            UUID plan = plan(LEAD, "Release 6.1");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            setPlanStatus(LEAD, plan, "APPROVED");

            MvcResult result = comment(VIEWER, review, "One more thought", null);

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(result))
                    .as("the same sentence the verdict endpoint gives — one spelling, one way out")
                    .contains("Submit the plan for review again");
            assertThat(commentCount(review)).isZero();
        }

        @Test
        @DisplayName("an engineer resolves a thread, and the record names who did it")
        void resolvingNamesItsActor() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Is the migration suite in scope?", null));

            MvcResult result = resolve(ENGINEER, review, root, true);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode thread = body(result).get("comments").get(0);
            assertThat(thread.get("resolvedAt").isNull()).isFalse();
            assertThat(thread.get("resolvedBy").get("id").asText())
                    .as("""
                            V24's column. V1 gave the table a resolved_at and no actor, which is \
                            enough to grey a thread out and not enough for the one thing the \
                            record exists for.""")
                    .isEqualTo(ENGINEER.toString());
        }

        @Test
        @DisplayName("a Viewer cannot resolve — writing a remark and closing one are two tiers")
        void aViewerCannotResolve() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Mine", null));

            MvcResult result = resolve(VIEWER, review, root, true);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            THE ASYMMETRY. The same caller was allowed to write this very comment \
                            two lines up; resolving it is the reviewing tier's act, because a \
                            client marking their own feedback dealt with takes it off the \
                            reviewer's list without anybody having addressed it.""")
                    .isEqualTo(403);
            assertThat(resolvedCount(review)).isZero();
        }

        @Test
        @DisplayName("resolving a reply is refused — half a conversation is not dealt with")
        void aReplyCannotBeResolved() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Root", null));
            UUID reply = commentId(comment(ENGINEER, review, "Reply", root));

            MvcResult result = resolve(ENGINEER, review, reply, true);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("resolved");
            assertThat(resolvedCount(review)).isZero();
        }

        @Test
        @DisplayName("re-opening clears both columns together")
        void reopeningClearsBoth() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Root", null));
            resolve(ENGINEER, review, root, true);

            MvcResult result = resolve(ENGINEER, review, root, false);

            JsonNode thread = body(result).get("comments").get(0);
            assertThat(thread.get("resolvedAt").isNull()).isTrue();
            assertThat(thread.get("resolvedBy").isNull())
                    .as("""
                            both columns move together in both directions — V24's CHECK is the \
                            backstop, and a resolver left behind on a re-opened thread would read \
                            as a resolution somebody undid without saying so""")
                    .isTrue();
        }

        @Test
        @DisplayName("resolving an already-resolved thread is a 200 that re-stamps nothing")
        void resolvingTwiceIsIdempotent() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Root", null));
            String first = body(resolve(ENGINEER, review, root, true))
                    .get("comments").get(0).get("resolvedAt").asText();

            MvcResult again = resolve(ENGINEER, review, root, true);

            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(again).get("comments").get(0).get("resolvedAt").asText())
                    .as("""
                            THE DEFECT THIS CAUGHT, in the first draft of this very slice: an \
                            unconditional write re-stamped resolved_at and handed resolved_by to \
                            whoever clicked last, so the two columns V24 added to record who dealt \
                            with a thread would have named the second person to agree rather than \
                            the person who decided. A 200 that changes nothing is the \
                            behaviour.""")
                    .isEqualTo(first);
            assertThat(body(again).get("comments").get(0).get("resolvedBy").get("id").asText())
                    .isEqualTo(ENGINEER.toString());
        }

        @Test
        @DisplayName("a missing `resolved` is a field error, not a silent no-op")
        void anAbsentResolvedIsAFieldError() throws Exception {
            UUID review = openPlanReview();
            UUID root = commentId(comment(VIEWER, review, "Root", null));

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + ACME_BANKING + "/reviews/" + review
                                    + "/comments/" + root + "/resolution")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("resolved");
        }

        @Test
        @DisplayName("a comment id from another review is 404, and resolves nothing there")
        void aCommentFromAnotherReviewIsNotFound() throws Exception {
            UUID mine = openPlanReview();
            UUID theirs = openPlanReview();
            UUID foreign = commentId(comment(VIEWER, theirs, "Somewhere else", null));

            MvcResult result = resolve(ENGINEER, mine, foreign, true);

            assertThat(result.getResponse().getStatus())
                    .as("resolved through the review, never findById — RLS scopes that to the "
                            + "company")
                    .isEqualTo(404);
            assertThat(resolvedCount(theirs))
                    .as("and the row it named must be untouched")
                    .isZero();
        }

        @Test
        @DisplayName("a comment id that names nothing is a bodiless 404")
        void anUnknownCommentIsNotFound() throws Exception {
            UUID review = openPlanReview();

            assertThat(resolve(ENGINEER, review, ABSENT, true).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a member with no grant cannot comment, and gets 404 rather than 403")
        void aMemberWithNoGrantIsNotFound() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = comment(NO_GRANT, review, "Can I see this?", null);

            assertThat(result.getResponse().getStatus())
                    .as("persona 3: the rows ARE in this caller's company, so only the project "
                            + "grant separates them — 404, never 403 (§6t)")
                    .isEqualTo(404);
            assertThat(commentCount(review)).isZero();
        }
    }

    // =====================================================================
    // Assignment — who was ASKED, which is the fact the record was missing
    // =====================================================================

    @Nested
    @DisplayName("Assignment — the request, and the four things it refuses")
    class Assignment {

        @Test
        @DisplayName("a lead asks an engineer, and the request names both of them")
        void aLeadAsksAnEngineer() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, ENGINEER, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode asked = body(result).get("assignees").get(0);
            assertThat(asked.get("user").get("id").asText()).isEqualTo(ENGINEER.toString());
            assertThat(asked.get("team").isNull())
                    .as("exactly one of the two is populated — V25's num_nonnulls check")
                    .isTrue();
            assertThat(asked.get("assignedBy").get("id").asText()).isEqualTo(LEAD.toString());
            assertThat(body(result).get("participations"))
                    .as("asked is not the same fact as spoke — the whole reason for the table")
                    .isEmpty();
        }

        @Test
        @DisplayName("a team can be asked, and its members are deliberately not expanded")
        void aTeamCanBeAsked() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, null, ACME_QA_TEAM);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode asked = body(result).get("assignees").get(0);
            assertThat(asked.get("team").get("name").asText()).isEqualTo("QA");
            assertThat(asked.get("user").isNull()).isTrue();
            assertThat(asked.get("team").has("members"))
                    .as("§6g: any member may action it — and a frozen member list read as a "
                            + "roster of who owes a review is exactly what TeamRef refuses to be")
                    .isFalse();
        }

        @Test
        @DisplayName("an engineer may ask — the request is the reviewing tier's, not the approver's")
        void anEngineerMayAsk() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(ENGINEER, review, VIEWER, null);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            THE GATE DECISION, and the persona that makes it discriminating. \
                            ENGINEER holds plan:review and NOT plan:approve, so a gate keyed on \
                            the approve key answers 403 here — and would confine the request to \
                            the very people whose sign-off it exists to PREPARE.""")
                    .isEqualTo(201);
            assertThat(body(result).get("assignees").get(0).get("user").get("id").asText())
                    .as("a Viewer can be asked: they hold project:read, which is the bar")
                    .isEqualTo(VIEWER.toString());
        }

        @Test
        @DisplayName("a Viewer cannot ask — the bottom tier does not fill other people's queues")
        void aViewerCannotAsk() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(VIEWER, review, ENGINEER, null);

            assertThat(result.getResponse().getStatus())
                    .as("403 rather than 404: the Viewer can see this project and this review")
                    .isEqualTo(403);
            assertThat(detailOf(result)).doesNotContain("plan:review");
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("asking somebody already asked writes nothing and keeps the original asker")
        void reAskingIsIdempotent() throws Exception {
            UUID review = openPlanReview();
            JsonNode first = body(assign(LEAD, review, ENGINEER, null)).get("assignees").get(0);

            MvcResult again = assign(ENGINEER, review, ENGINEER, null);

            assertThat(again.getResponse().getStatus()).isEqualTo(201);
            assertThat(assignmentCount(review)).isEqualTo(1);
            JsonNode after = body(again).get("assignees").get(0);
            assertThat(after.get("assignedBy").get("id").asText())
                    .as("""
                            §79's defect in the one place it would recur: re-stamping would hand \
                            assignedBy to whoever clicked last, so the column kept to record who \
                            asked would name the second person to agree.""")
                    .isEqualTo(LEAD.toString());
            assertThat(after.get("assignedAt").asText()).isEqualTo(first.get("assignedAt").asText());
        }

        @Test
        @DisplayName("re-asking the same reviewer records no second occurrence")
        void reAskingAnnouncesNothing() throws Exception {
            UUID review = openPlanReview();

            assign(LEAD, review, ENGINEER, null);
            assign(ENGINEER, review, ENGINEER, null);

            assertThat(occurrenceCount(review, "review.requested"))
                    .as("""
                            The second call writes no assignment row, so it records nothing: an \
                            event there would announce a second time that a colleague had been \
                            asked once, and at-least-once delivery means the channel would see \
                            both.""")
                    .isOne();
        }

        @Test
        @DisplayName("asking a team is an occurrence too, naming the team and not its members")
        void askingATeamIsAnOccurrence() throws Exception {
            UUID review = openPlanReview();

            assign(LEAD, review, null, ACME_QA_TEAM);

            assertThat(occurrenceCount(review, "review.requested")).isOne();
            assertThat(occurrencePayload(review, "review.requested"))
                    .as("the payload's two assignee arms are exactly one id — the handler picks, "
                            + "and a team's members are resolved by nobody because §6g says any "
                            + "member may action it")
                    .contains("\"assigneeTeamId\": \"" + ACME_QA_TEAM + "\"")
                    .contains("\"assigneeUserId\": null");
        }

        @Test
        @DisplayName("naming neither a person nor a team is a field error, and writes nothing")
        void neitherIsAFieldError() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, null, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("userId");
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("naming both is the same field error — the rule is exactly one")
        void bothIsTheSameFieldError() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, ENGINEER, ACME_QA_TEAM);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("userId");
            assertThat(assignmentCount(review))
                    .as("a refused request must write neither half")
                    .isZero();
        }

        @Test
        @DisplayName("a colleague with no grant on the project cannot be asked")
        void somebodyWhoCannotSeeTheProjectCannotBeAsked() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, NO_GRANT, null);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            An ACTIVE member of this company holding no project grant. Company \
                            membership is the bar §71 used one table over and it is the wrong one \
                            here: they cannot open the artifact, so the request would sit on \
                            their list for ever.""")
                    .isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("userId");
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("a SIBLING COMPANY's account is refused with the same sentence, and stored never")
        void aSiblingCompanysAccountCannotBeAsked() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, OUTSIDER, null);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            §71's defect one table over: assignee_user_id references \
                            instance-level app_user, so this id satisfies the FK, the company \
                            column, RLS and every constraint — and holds no grant here.""")
                    .isEqualTo(400);
            assertThat(errorsOf(result).get("userId"))
                    .as("""
                            the SAME sentence the no-grant case gets. Telling them apart makes \
                            the endpoint an oracle for which accounts exist on the instance \
                            (§6t).""")
                    .isEqualTo(errorsOf(assign(LEAD, review, NO_GRANT, null)).get("userId"));
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("the submitter cannot be asked — their own review would not count")
        void theSubmitterCannotBeAsked() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, LEAD, null);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            Derived, not invented: standingLgtms already skips the submitter's \
                            own LGTM, so this request could never be discharged.""")
                    .isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("userId");
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("a sibling company's team is refused, and its name never reaches the response")
        void aSiblingCompanysTeamCannotBeAsked() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = assign(LEAD, review, null, UMBRA_TEAM);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("teamId");
            assertThat(assignmentCount(review))
                    .as("§64's sharedStepId from the other end — an unchecked team_id is a stored "
                            + "reference nothing validated")
                    .isZero();
        }

        @Test
        @DisplayName("a closed review takes no request — 409, the fourth write path saying it")
        void aClosedReviewRefusesTheRequest() throws Exception {
            UUID plan = plan(LEAD, "Release 6.1");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            setPlanStatus(LEAD, plan, "APPROVED");

            MvcResult result = assign(LEAD, review, ENGINEER, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(assignmentCount(review)).isZero();
        }

        @Test
        @DisplayName("withdrawing removes the row entirely — a request leaves no tombstone")
        void withdrawingIsAHardDelete() throws Exception {
            UUID review = openPlanReview();
            UUID assignment = assignmentId(assign(LEAD, review, ENGINEER, null));

            MvcResult result = unassign(LEAD, review, assignment);

            assertThat(result.getResponse().getStatus())
                    .as("200 with the whole review, not 204 — every other write here re-serves it")
                    .isEqualTo(200);
            assertThat(body(result).get("assignees")).isEmpty();
            assertThat(assignmentCount(review))
                    .as("an assignment is a worklist entry, not part of the audit record (V25)")
                    .isZero();
        }

        @Test
        @DisplayName("an assignment id belonging to another review is 404, and deletes nothing")
        void anotherReviewsAssignmentIsNotReachable() throws Exception {
            UUID mine = openPlanReview();
            UUID other = openPlanReview();
            UUID assignment = assignmentId(assign(LEAD, other, ENGINEER, null));

            MvcResult result = unassign(LEAD, mine, assignment);

            assertThat(result.getResponse().getStatus())
                    .as("""
                            findInReview, never findById — the latter is scoped by RLS to the \
                            COMPANY, so it resolves this row and the caller withdraws a request \
                            attached to a review the gate never examined. Two reviews in one \
                            project is the narrower case; the cross-PROJECT one is refused a \
                            frame earlier, by findInProject, and is pinned in Isolation.""")
                    .isEqualTo(404);
            assertThat(assignmentCount(other)).isEqualTo(1);
        }

        @Test
        @DisplayName("an assignment id naming nothing is a bodiless 404")
        void anAbsentAssignmentIs404() throws Exception {
            UUID review = openPlanReview();

            MvcResult result = unassign(LEAD, review, ABSENT);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString())
                    .as("identical to the review's own 404, so the pair cannot probe which ids "
                            + "exist (§6t)")
                    .isEmpty();
        }

        @Test
        @DisplayName("a closed review refuses the withdrawal too, and the row survives")
        void aClosedReviewRefusesTheWithdrawal() throws Exception {
            UUID plan = plan(LEAD, "Release 6.2");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            UUID assignment = assignmentId(assign(LEAD, review, ENGINEER, null));
            setPlanStatus(LEAD, plan, "APPROVED");

            MvcResult result = unassign(LEAD, review, assignment);

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(assignmentCount(review)).isEqualTo(1);
        }

        @Test
        @DisplayName("deleting a team takes its outstanding requests with it, and nothing else")
        void deletingATeamCascadesOntoItsRequests() throws Exception {
            UUID plan = plan(LEAD, "Release 6.3");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID review = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            assign(LEAD, review, null, ACME_QA_TEAM);
            assign(LEAD, review, ENGINEER, null);
            assertThat(assignmentCount(review)).isEqualTo(2);

            deleteTeamRow(ACME_QA_TEAM);

            assertThat(assignmentCount(review))
                    .as("""
                            V25's ON DELETE CASCADE, and the decision §71's 409 is deliberately \
                            NOT extended to: what is lost is a request, not authority, and the \
                            review stays reviewable by exactly the same people. The person asked \
                            individually is untouched.""")
                    .isEqualTo(1);
            assertThat(onlyReview(LEAD, "PLAN", plan).get("assignees")).hasSize(1);
        }

        @Test
        @DisplayName("the request and the verdict are two lists, and the panel reads both")
        void askedAndSpokeAreServedSeparately() throws Exception {
            UUID review = openPlanReview();
            assign(LEAD, review, ENGINEER, null);

            MvcResult result = participate(ENGINEER, review, "LGTM", "Looks right");

            assertThat(body(result).get("assignees")).hasSize(1);
            assertThat(body(result).get("participations")).hasSize(1);
            assertThat(body(result).get("assignees").get(0).get("user").get("id").asText())
                    .as("the same person in both, which is what the workflow working looks like")
                    .isEqualTo(body(result).get("participations").get(0)
                            .get("user").get("id").asText());
        }
    }

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
    // The project's own §6g policy — V23, and the four things it unblocks
    // =====================================================================

    @Nested
    @DisplayName("The settings endpoint")
    class Settings {

        @Test
        @DisplayName("a project nobody configured answers §6g's defaults, not an empty body")
        void defaultsForAnUnconfiguredProject() throws Exception {
            // ACME_MOBILE is enabled by the fixture, so this uses a project the fixture left alone
            // by resetting it first — the point is the SHAPE of the answer for a project with no
            // deliberate configuration, which is what every real project looks like on day one.
            assertThat(putSettings(LEAD, ACME_MOBILE, defaultSettingsPayload())
                    .getResponse().getStatus()).isEqualTo(200);

            JsonNode settings = body(getSettings(LEAD, ACME_MOBILE));

            assertThat(settings.get("reviewEnabledCase").asBoolean()).isFalse();
            assertThat(settings.get("reviewEnabledSuite").asBoolean()).isFalse();
            assertThat(settings.get("reviewEnabledPlan").asBoolean()).isFalse();
            assertThat(settings.get("requiredPeerReviews").asInt()).isZero();
            // The rule is ON by default, which is this field being FALSE — §6g calls it the single
            // most valuable setting here, and a default that made it opt-in would be the wrong way
            // round in the one direction that matters.
            assertThat(settings.get("authorMayApproveOwnWork").asBoolean()).isFalse();
            assertThat(settings.get("editAfterApproval").asText()).isEqualTo("RETAIN");
        }

        @Test
        @DisplayName("a Viewer can read them — §6g's 'no extra UI' is unimplementable otherwise")
        void aViewerMayRead() throws Exception {
            // The Viewer holds project:read and NOT project:settings. If this were 403 the SPA
            // could not decide whether to render a review panel for the persona §6g's requirement
            // is most about, and the only ways out are worse: render it always, or infer the
            // setting from a 409 after somebody clicks.
            assertThat(getSettings(VIEWER, ACME_BANKING).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an engineer cannot change them — the write is project:settings")
        void writeNeedsTheKey() throws Exception {
            MvcResult refused = putSettings(ENGINEER, ACME_BANKING, defaultSettingsPayload());

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            // Never the permission key — naming it publishes the catalogue to the caller who is not
            // entitled to it.
            assertThat(detailOf(refused)).doesNotContain("project:settings");
        }

        @Test
        @DisplayName("a caller with no grant gets 404, never 403 — the project is not confirmed")
        void noGrantIsNotFound() throws Exception {
            assertThat(getSettings(NO_GRANT, ACME_BANKING).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(putSettings(NO_GRANT, ACME_BANKING, defaultSettingsPayload())
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the peer minimum is bounded at both ends, on its own field")
        void peerMinimumIsBounded() throws Exception {
            Map<String, Object> tooMany = defaultSettingsPayload();
            tooMany.put("requiredPeerReviews", 21);

            MvcResult refused = putSettings(LEAD, ACME_BANKING, tooMany);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("requiredPeerReviews");

            Map<String, Object> negative = defaultSettingsPayload();
            negative.put("requiredPeerReviews", -1);
            assertThat(putSettings(LEAD, ACME_BANKING, negative).getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an unknown edit-after-approval mode is a field error, not a 500")
        void unknownModeIsAFieldError() throws Exception {
            Map<String, Object> payload = defaultSettingsPayload();
            payload.put("editAfterApproval", "REREVIEW");

            MvcResult refused = putSettings(LEAD, ACME_BANKING, payload);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("editAfterApproval");
        }

        @Test
        @DisplayName("a saved value round-trips and names who changed it")
        void roundTrips() throws Exception {
            Map<String, Object> payload = defaultSettingsPayload();
            payload.put("reviewEnabledPlan", true);
            payload.put("requiredPeerReviews", 2);
            payload.put("editAfterApproval", "RE_REVIEW");

            assertThat(putSettings(LEAD, ACME_BANKING, payload).getResponse().getStatus())
                    .isEqualTo(200);

            JsonNode settings = body(getSettings(LEAD, ACME_BANKING));
            assertThat(settings.get("reviewEnabledPlan").asBoolean()).isTrue();
            assertThat(settings.get("reviewEnabledCase").asBoolean()).isFalse();
            assertThat(settings.get("requiredPeerReviews").asInt()).isEqualTo(2);
            assertThat(settings.get("editAfterApproval").asText()).isEqualTo("RE_REVIEW");
            assertThat(settings.get("updatedBy").get("id").asText())
                    .isEqualTo(LEAD.toString());
            assertThat(settings.get("updatedAt").isNull()).isFalse();
        }
    }

    @Nested
    @DisplayName("The opt-in — review off means no record, and the ladder untouched")
    class OptIn {

        @Test
        @DisplayName("with review off, submitting a plan opens no review at all")
        void offMeansNoRecord() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);
            Map<String, Object> allOff = defaultSettingsPayload();
            assertThat(putSettings(LEAD, ACME_BANKING, allOff).getResponse().getStatus())
                    .isEqualTo(200);

            UUID plan = plan(LEAD, "Release with review off");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            assertThat(reviewsOf(LEAD, "PLAN", plan))
                    .as("review is off for plans, so no record is kept")
                    .isEmpty();
        }

        @Test
        @DisplayName("with review off, the STATUS still moves — the ladder is not the apparatus")
        void offDoesNotWithdrawTheLadder() throws Exception {
            assertThat(putSettings(LEAD, ACME_BANKING, defaultSettingsPayload())
                    .getResponse().getStatus()).isEqualTo(200);

            UUID plan = plan(LEAD, "Release, ladder only");

            // The whole point of the narrow reading. A project turning review off must not discover
            // that IN_REVIEW and APPROVED have been withdrawn from a status its team has used since
            // M1 — and since off is the DEFAULT, the opposite reading would have taken approvals
            // away from every existing project on the day this shipped.
            MvcResult submitted = attemptPlanStatus(LEAD, plan, "IN_REVIEW");
            assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(submitted).get("status").asText()).isEqualTo("IN_REVIEW");

            MvcResult approved = attemptPlanStatus(LEAD, plan, "APPROVED");
            assertThat(approved.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(approved).get("status").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("switching review off does not close a review that is already open")
        void offDoesNotCloseOpenReviews() throws Exception {
            UUID plan = plan(LEAD, "Release, mid-flight");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText()).isEqualTo("IN_REVIEW");

            assertThat(putSettings(LEAD, ACME_BANKING, defaultSettingsPayload())
                    .getResponse().getStatus()).isEqualTo(200);

            // Those rows record something that happened. A project changing its policy has not
            // caused everybody who was asked to withdraw, so the guard covers only the OPENING arm.
            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText()).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("the opt-in is per artifact type, not per project")
        void perArtifactType() throws Exception {
            Map<String, Object> plansOnly = defaultSettingsPayload();
            plansOnly.put("reviewEnabledPlan", true);
            assertThat(putSettings(LEAD, ACME_BANKING, plansOnly).getResponse().getStatus())
                    .isEqualTo(200);

            UUID plan = plan(LEAD, "Release, plans reviewed");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            assertThat(reviewsOf(LEAD, "PLAN", plan)).hasSize(1);

            setSuiteStatus(LEAD, PAYMENTS, "IN_REVIEW");
            assertThat(reviewsOf(LEAD, "SUITE", PAYMENTS))
                    .as("suites are not reviewed in this project, so the suite ladder writes nothing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Author cannot approve own work — §6g's most valuable setting")
    class AuthorCannotApprove {

        @Test
        @DisplayName("the submitter is refused their own sign-off, with 409 and a reason")
        void theSubmitterIsRefused() throws Exception {
            // The rule ON is the DEFAULT — authorMayApproveOwnWork false.
            enableReview(ACME, ACME_BANKING, 0, false);

            UUID plan = plan(LEAD, "Release, self-approved");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            MvcResult refused = attemptPlanStatus(LEAD, plan, "APPROVED");

            // 409, not 403: the permission IS held — this lead may approve plans — and what refuses
            // is the project's policy about this particular review. A 403 would send them to ask
            // for a permission they already have.
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("somebody else");

            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText())
                    .as("the review is untouched — the refusal happens before anything is written")
                    .isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("a different lead may approve the same review")
        void somebodyElseMayApprove() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, false);

            // Submitted by the ENGINEER, who holds plan:edit — §6g: "submitting for review needs
            // only edit rights" — and approved by the LEAD, which is the arrangement the rule
            // exists to produce.
            UUID plan = plan(LEAD, "Release, peer approved");
            setPlanStatus(ENGINEER, plan, "IN_REVIEW");

            MvcResult approved = attemptPlanStatus(LEAD, plan, "APPROVED");

            assertThat(approved.getResponse().getStatus()).isEqualTo(200);
            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("turning the rule off lets the submitter approve — it is a setting, not a law")
        void theRuleCanBeTurnedOff() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);

            UUID plan = plan(LEAD, "Release, small team");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            assertThat(attemptPlanStatus(LEAD, plan, "APPROVED").getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("The peer minimum — counted from the trail, pinned at submission")
    class PeerMinimum {

        @Test
        @DisplayName("approval is refused until enough peers have said LGTM")
        void refusedUntilMet() throws Exception {
            enableReview(ACME, ACME_BANKING, 1, true);

            UUID plan = plan(LEAD, "Release, needs a peer");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            MvcResult refused = attemptPlanStatus(LEAD, plan, "APPROVED");
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("1 peer review").contains("has 0");

            UUID reviewId = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());
            assertThat(verdict(ENGINEER, reviewId, "LGTM").getResponse().getStatus()).isEqualTo(201);

            assertThat(attemptPlanStatus(LEAD, plan, "APPROVED").getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("the submitter's own LGTM does not count as a peer review")
        void theSubmittersOwnLgtmDoesNotCount() throws Exception {
            enableReview(ACME, ACME_BANKING, 1, true);

            UUID plan = plan(LEAD, "Release, self LGTM");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID reviewId = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());

            assertThat(verdict(LEAD, reviewId, "LGTM").getResponse().getStatus()).isEqualTo(201);

            // The two settings answer different questions, and a peer review by the person who
            // asked for it is not a second opinion — so this holds even with the
            // author-cannot-approve rule switched off.
            MvcResult refused = attemptPlanStatus(LEAD, plan, "APPROVED");
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("has 0");
        }

        @Test
        @DisplayName("a peer who changes their mind stops counting — the LATEST verdict is what counts")
        void relentingIsVisible() throws Exception {
            enableReview(ACME, ACME_BANKING, 1, true);

            UUID plan = plan(LEAD, "Release, changed mind");
            setPlanStatus(LEAD, plan, "IN_REVIEW");
            UUID reviewId = UUID.fromString(onlyReview(LEAD, "PLAN", plan).get("id").asText());

            assertThat(verdict(ENGINEER, reviewId, "LGTM").getResponse().getStatus()).isEqualTo(201);
            assertThat(verdict(ENGINEER, reviewId, "CHANGES_REQUESTED").getResponse().getStatus())
                    .isEqualTo(201);

            // The trail is append-only precisely so relenting is visible. A COUNT(DISTINCT user)
            // WHERE verdict='LGTM' would still see the first row and approve — which is the reading
            // that makes a review trail decorative.
            assertThat(attemptPlanStatus(LEAD, plan, "APPROVED").getResponse().getStatus())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("raising the minimum does not freeze a review already open")
        void thePinnedCountGoverns() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);

            UUID plan = plan(LEAD, "Release, bar raised later");
            setPlanStatus(LEAD, plan, "IN_REVIEW");

            // The reviewers on this review were asked under the old rule and some may already have
            // spoken. The count is copied onto the review at submission for exactly this reason —
            // Review#requiredPeerReviews — so the new setting governs the NEXT one.
            enableReview(ACME, ACME_BANKING, 5, true);

            assertThat(attemptPlanStatus(LEAD, plan, "APPROVED").getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Edit after approval — the RE_REVIEW arm")
    class EditAfterApprovalMode {

        @Test
        @DisplayName("RETAIN leaves an approved plan approved when it is edited")
        void retainKeepsTheApproval() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);

            UUID plan = approvedPlan("Release, retained");

            renamePlan(LEAD, plan, "Release, retained (typo fixed)");

            assertThat(planStatusOf(plan)).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("RE_REVIEW returns an edited approved plan to draft, and closes nothing else")
        void reReviewReturnsItToDraft() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);
            UUID plan = approvedPlan("Release, re-reviewed");

            Map<String, Object> payload = defaultSettingsPayload();
            payload.put("reviewEnabledPlan", true);
            payload.put("authorMayApproveOwnWork", true);
            payload.put("editAfterApproval", "RE_REVIEW");
            assertThat(putSettings(LEAD, ACME_BANKING, payload).getResponse().getStatus())
                    .isEqualTo(200);

            renamePlan(LEAD, plan, "Release, re-reviewed (edited)");

            assertThat(planStatusOf(plan)).isEqualTo("DRAFT");
            // The approving review is history and stays APPROVED — it records what happened, and
            // the plan's own status is the live fact. Rescinding an approval does not rewrite the
            // review that granted it (Review's class header).
            assertThat(onlyReview(LEAD, "PLAN", plan).get("state").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("the demotion does NOT cost plan:approve — an engineer's edit performs it")
        void theEditorNeedsOnlyEditRights() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);
            UUID plan = approvedPlan("Release, engineer edits");

            Map<String, Object> payload = defaultSettingsPayload();
            payload.put("reviewEnabledPlan", true);
            payload.put("authorMayApproveOwnWork", true);
            payload.put("editAfterApproval", "RE_REVIEW");
            assertThat(putSettings(LEAD, ACME_BANKING, payload).getResponse().getStatus())
                    .isEqualTo(200);

            // This looks like §75's defect reopened and is not — see EditAfterApproval.RE_REVIEW.
            // Nobody requested the transition, the project configured it in advance, and charging
            // the approve key here would make an approved plan UNEDITABLE by the people §6g expects
            // to edit it, which turns a policy into a lock.
            renamePlan(ENGINEER, plan, "Release, engineer edited");

            assertThat(planStatusOf(plan)).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("with review off for plans, RE_REVIEW does not fire — it is a review setting")
        void tiedToTheOptIn() throws Exception {
            enableReview(ACME, ACME_BANKING, 0, true);
            UUID plan = approvedPlan("Release, review off later");

            Map<String, Object> payload = defaultSettingsPayload();
            payload.put("reviewEnabledPlan", false);
            payload.put("editAfterApproval", "RE_REVIEW");
            assertThat(putSettings(LEAD, ACME_BANKING, payload).getResponse().getStatus())
                    .isEqualTo(200);

            renamePlan(LEAD, plan, "Release, review off later (edited)");

            // A project that opted out of §6g for plans should not find one §6g rule still firing —
            // an edit silently un-approving something on a project whose team was told review is
            // off is the surprise, not the protection.
            assertThat(planStatusOf(plan)).isEqualTo("APPROVED");
        }
    }

    /** A plan taken all the way to APPROVED through the real endpoints. */
    private UUID approvedPlan(String name) throws Exception {
        UUID plan = plan(LEAD, name);
        setPlanStatus(LEAD, plan, "IN_REVIEW");
        setPlanStatus(LEAD, plan, "APPROVED");
        return plan;
    }

    private void renamePlan(UUID userId, UUID planId, String name) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the edit this test depends on; %s", result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

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

    /**
     * The plan-status move WITHOUT the 200 assertion — for the tests whose whole subject is a
     * refusal. {@link #setPlanStatus} asserts success because every test that uses it depends on the
     * move having happened; these need the status code itself.
     */
    private MvcResult attemptPlanStatus(UUID userId, UUID planId, String status) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", status))))
                .andReturn();
    }

    /** A peer verdict, without the 201 assertion. */
    private MvcResult verdict(UUID userId, UUID reviewId, String verdict) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict", verdict);
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult getSettings(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult putSettings(UUID userId, UUID projectId, Map<String, Object> payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    /** Every setting at its §6g default, as a request body — the base for a one-field override. */
    private static Map<String, Object> defaultSettingsPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewEnabledCase", false);
        payload.put("reviewEnabledSuite", false);
        payload.put("reviewEnabledPlan", false);
        payload.put("requiredPeerReviews", 0);
        payload.put("authorMayApproveOwnWork", false);
        payload.put("editAfterApproval", "RETAIN");
        return payload;
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

    private MvcResult comment(UUID userId, UUID reviewId, String body, UUID parentId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("parentId", parentId);

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult assign(UUID userId, UUID reviewId, UUID assigneeId, UUID teamId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", assigneeId);
        payload.put("teamId", teamId);

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/assignees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult unassign(UUID userId, UUID reviewId, UUID assignmentId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/assignees/" + assignmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    /** The id of the request a 201 just created — the newest, the render being oldest-first. */
    private UUID assignmentId(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on this write succeeding; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);

        JsonNode asked = body(created).get("assignees");
        return UUID.fromString(asked.get(asked.size() - 1).get("id").asText());
    }

    private MvcResult resolve(UUID userId, UUID reviewId, UUID commentId, boolean resolved)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ACME_BANKING + "/reviews/" + reviewId
                                + "/comments/" + commentId + "/resolution")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resolved", resolved))))
                .andReturn();
    }

    /**
     * The id of the comment a 201 just created — the newest root, or the newest reply under one.
     *
     * <p>The response is the whole review rather than the row, so the id has to be read out of the
     * threads. Taking the <em>last</em> entry at each level relies on the render being oldest-first,
     * which {@code ReviewCommentRepository.findForReviews} guarantees and a test above asserts.
     */
    private UUID commentId(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on this write succeeding; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);

        JsonNode threads = body(created).get("comments");
        JsonNode last = threads.get(threads.size() - 1);
        JsonNode replies = last.get("replies");
        return UUID.fromString(
                (replies.isEmpty() ? last : replies.get(replies.size() - 1)).get("id").asText());
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

    /**
     * Creates the team once and leaves it alone thereafter.
     *
     * <p>Not {@code save} unconditionally: {@code team} is not in {@link #clearMutableRows}, so the
     * row survives between tests, and re-saving a detached {@link Team} whose {@code @Version} is
     * still 0 against a row Hibernate has already bumped is a {@code StaleObjectStateException} —
     * which reds not this test but every test in the class, from {@code @BeforeEach}. One of the
     * assignment tests also deletes the team on purpose, so the absent case is real rather than
     * defensive.
     */
    private void seedTeam(UUID teamId, UUID companyId, Instant now) {
        if (teams.findScopedById(teamId).isEmpty()) {
            teams.saveAndFlush(Team.create(teamId, companyId, "QA", null, null, now));
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
                        (id, company_id, project_id, key, key_seq, name, description, position, lifecycle_status, current_version_no, lock_version, created_at, updated_at, deleted_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1, 'Payments', NULL, 0, 'DRAFT', 1, 0, now(), now(), NULL)
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
                execute(connection, "DELETE FROM review_comment");
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

    /** Read past the API, so a refusal that answered 400 and wrote anyway is visible. */
    private int commentCount(UUID reviewId) throws SQLException {
        return count(
                "SELECT count(*) FROM review_comment WHERE review_id = CAST(? AS uuid)",
                reviewId.toString());
    }

    private int resolvedCount(UUID reviewId) throws SQLException {
        return count("""
                SELECT count(*) FROM review_comment
                 WHERE review_id = CAST(? AS uuid) AND resolved_at IS NOT NULL
                """, reviewId.toString());
    }

    /** Read past the API, so a refusal that answered 400 and wrote anyway is visible. */
    private int assignmentCount(UUID reviewId) throws SQLException {
        return count(
                "SELECT count(*) FROM review_assignment WHERE review_id = CAST(? AS uuid)",
                reviewId.toString());
    }

    /**
     * Deletes a team row directly, past {@code TeamWriteService}.
     *
     * <p>The endpoint refuses a team holding role grants (§71) and this test is about what happens
     * to a team that is legitimately deleted, so driving it through the API would be testing the
     * refusal rather than the cascade. The cascade is the database's, which is where it is asserted.
     */
    private void deleteTeamRow(UUID teamId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "DELETE FROM team WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, teamId.toString());
            statement.executeUpdate();
        }
    }

    private int participationCount(UUID reviewId) throws SQLException {
        return count(
                "SELECT count(*) FROM review_participation WHERE review_id = CAST(? AS uuid)",
                reviewId.toString());
    }

    /**
     * How many occurrences of one event type this review recorded.
     *
     * <p>Scoped by {@code aggregate_id} rather than counting the whole table, so it says something
     * about this review whatever else the suite has left behind.
     */
    private int occurrenceCount(UUID reviewId, String eventType) throws SQLException {
        return count(
                "SELECT count(*) FROM outbox WHERE aggregate_id = CAST(? AS uuid) "
                        + "AND event_type = ?",
                reviewId.toString(), eventType);
    }

    /** The document itself — JSONB, so Postgres renders it back with its own spacing. */
    private String occurrencePayload(UUID reviewId, String eventType) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT payload::text FROM outbox WHERE aggregate_id = CAST(? AS uuid) "
                             + "AND event_type = ?")) {
            statement.setString(1, reviewId.toString());
            statement.setString(2, eventType);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no %s occurrence was recorded", eventType).isTrue();
                return rows.getString(1);
            }
        }
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
