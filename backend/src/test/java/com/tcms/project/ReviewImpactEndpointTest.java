package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.auth.SessionTokens;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 005 S7 + S9b — {@code GET /projects/{id}/settings/review-impact}, the review-owned counts, the
 * {@link ReviewImpactSource} defaults, and {@code reviewEnabledDoc} on the settings wire.
 *
 * <p>The review rows are written through the entity factories rather than the endpoints: the
 * question here is what the counts say about rows in a given state, not how the rows got there
 * (that is {@code ReviewEndpointTest}'s). A {@code DOC} review needs no page behind it — the
 * subject id has no foreign key, which is the same property that lets a review outlive its subject.
 */
@AutoConfigureMockMvc
@DisplayName("Review impact — what a change to the review settings would land on")
class ReviewImpactEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("9b000000-0000-4000-8000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("9b001000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT = UUID.fromString("9b001000-0000-4000-8000-000000000002");

    private static final UUID LEAD = UUID.fromString("9b003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("9b003000-0000-4000-8000-000000000002");
    private static final UUID VIEWER = UUID.fromString("9b003000-0000-4000-8000-000000000003");
    private static final UUID NO_GRANT = UUID.fromString("9b003000-0000-4000-8000-000000000004");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionTokens tokens;

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
    private ReviewRepository reviews;

    @Autowired
    private ReviewParticipationRepository participations;

    @Autowired
    private ObjectProvider<ReviewImpactSource> impactSources;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "ri-acme", "Acme", now));
            }
            activeUser(LEAD, "lead@ri-acme.test", now);
            activeUser(ENGINEER, "eng@ri-acme.test", now);
            activeUser(VIEWER, "viewer@ri-acme.test", now);
            activeUser(NO_GRANT, "nogrant@ri-acme.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            if (projects.findById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "RIA", "Impact", null, now));
            }
            if (projects.findById(OTHER_PROJECT).isEmpty()) {
                projects.save(Project.create(OTHER_PROJECT, ACME, "RIB", "Sibling", null, now));
            }
            member(LEAD, "L. Lead", now);
            member(ENGINEER, "E. Engineer", now);
            member(VIEWER, "V. Viewer", now);
            member(NO_GRANT, "N. Grant", now);
            grant("lead", Role.TEST_LEAD_ID, LEAD, PROJECT, now);
            grant("eng", Role.TEST_ENGINEER_ID, ENGINEER, PROJECT, now);
            grant("view", Role.VIEWER_ID, VIEWER, PROJECT, now);

            participations.deleteAll();
            reviews.deleteAll();
        }));
    }

    @Nested
    @DisplayName("The counts")
    class TheCounts {

        @Test
        @DisplayName("an untouched project is all zeros and an empty list — never a null")
        void allZeros() throws Exception {
            JsonNode impact = impactAs(LEAD);

            assertThat(impact.get("casesInReview").asInt()).isZero();
            assertThat(impact.get("suitesInReview").asInt()).isZero();
            assertThat(impact.get("plansInReview").asInt()).isZero();
            assertThat(impact.get("docsInReview").asInt()).isZero();
            assertThat(impact.get("awaitingFinalApproval").asInt()).isZero();
            assertThat(impact.get("draftDocPages").asInt()).isZero();
            assertThat(impact.get("plansBlockedFromRunning").isArray()).isTrue();
            assertThat(impact.get("plansBlockedFromRunning")).isEmpty();
            impact.properties().forEach(entry ->
                    assertThat(entry.getValue().isNull())
                            .as("%s must not be null — the contract says nothing here is", entry.getKey())
                            .isFalse());
        }

        @Test
        @DisplayName("open reviews are counted per subject type, DOC included; closed ones are not")
        void openReviewsPerSubjectType() throws Exception {
            openReview(ReviewSubjectType.CASE, ENGINEER);
            openReview(ReviewSubjectType.CASE, ENGINEER);
            openReview(ReviewSubjectType.SUITE, ENGINEER);
            openReview(ReviewSubjectType.PLAN, ENGINEER);
            openReview(ReviewSubjectType.DOC, ENGINEER);
            UUID closed = openReview(ReviewSubjectType.CASE, ENGINEER);
            inCompany(ACME, () -> tx.executeWithoutResult(status ->
                    reviews.findById(closed).ifPresent(review -> {
                        review.close(true);
                        reviews.save(review);
                    })));

            JsonNode impact = impactAs(LEAD);

            assertThat(impact.get("casesInReview").asInt()).isEqualTo(2);
            assertThat(impact.get("suitesInReview").asInt()).isEqualTo(1);
            assertThat(impact.get("plansInReview").asInt()).isEqualTo(1);
            assertThat(impact.get("docsInReview").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("awaitingFinalApproval counts open reviews with an LGTM from somebody other than the submitter")
        void awaitingFinalApproval() throws Exception {
            UUID peerApproved = openReview(ReviewSubjectType.CASE, ENGINEER);
            UUID selfApproved = openReview(ReviewSubjectType.CASE, ENGINEER);
            UUID changesRequested = openReview(ReviewSubjectType.CASE, ENGINEER);
            openReview(ReviewSubjectType.CASE, ENGINEER);

            verdict(peerApproved, LEAD, ReviewParticipation.Verdict.LGTM);
            verdict(peerApproved, VIEWER, ReviewParticipation.Verdict.LGTM);
            verdict(selfApproved, ENGINEER, ReviewParticipation.Verdict.LGTM);
            verdict(changesRequested, LEAD, ReviewParticipation.Verdict.CHANGES_REQUESTED);

            JsonNode impact = impactAs(LEAD);

            assertThat(impact.get("casesInReview").asInt()).isEqualTo(4);
            assertThat(impact.get("awaitingFinalApproval").asInt())
                    .as("one review, however many peers said LGTM; the submitter's own LGTM and "
                            + "a CHANGES_REQUESTED do not count")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("another project's reviews are not counted")
        void scopedToTheProject() throws Exception {
            openReviewOn(OTHER_PROJECT, ReviewSubjectType.CASE, ENGINEER);

            assertThat(impactAs(LEAD).get("casesInReview").asInt()).isZero();
        }
    }

    @Nested
    @DisplayName("The SPI — declared here, implemented by the leaves")
    class TheSpi {

        @Test
        @DisplayName("a source that answers nothing contributes 0 and [] — the default, never a null")
        void defaultsAnswerNone() {
            ReviewImpactSource silent = new ReviewImpactSource() { };

            assertThat(silent.draftDocPages(PROJECT)).isZero();
            assertThat(silent.plansBlockedFromRunning(PROJECT, ProjectSettings.defaults())).isEmpty();
        }

        @Test
        @DisplayName("the counts are the SUM over every registered source — a module joins by registering a bean")
        void registeredSourcesAreFolded() throws Exception {
            // Whatever the leaves have registered by now (B3 adds documentation and execution),
            // the number on the wire is their sum — computed here over the live bean list, so a
            // source that stopped being a bean, or one the service stopped folding, is a red run.
            int expected = 0;
            for (ReviewImpactSource source : impactSources.orderedStream().toList()) {
                try (var ignored = CompanyContext.open(ACME)) {
                    expected += tx.execute(status -> source.draftDocPages(PROJECT));
                }
            }

            assertThat(impactAs(LEAD).get("draftDocPages").asInt()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("The gate — project:settings, the write key")
    class TheGate {

        @Test
        @DisplayName("a Viewer is 403 with the key named — the counts describe the team's backlog")
        void viewerIs403() throws Exception {
            MvcResult refused = request(VIEWER, PROJECT);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            JsonNode body = json.readTree(refused.getResponse().getContentAsString());
            assertThat(body.get("permission").asText()).isEqualTo("project:settings");
            assertThat(body.get("scope").asText()).isEqualTo("PROJECT");
        }

        @Test
        @DisplayName("a Test Engineer is 403 — reads are gated on the settings WRITE key, not project:read")
        void engineerIs403() throws Exception {
            assertThat(request(ENGINEER, PROJECT).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("no grant is 404, never 403 — §6t")
        void noGrantIs404() throws Exception {
            assertThat(request(NO_GRANT, PROJECT).getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("reviewEnabledDoc on the settings wire (S9b)")
    class ReviewEnabledDoc {

        @Test
        @DisplayName("defaults to false, round-trips through the PUT, and gates the DOC subject type")
        void roundTrips() throws Exception {
            JsonNode before = settingsAs(LEAD);
            assertThat(before.get("reviewEnabledDoc").asBoolean()).isFalse();

            MvcResult updated = mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/projects/" + PROJECT + "/settings")
                            .header("Authorization", "Bearer " + tokenFor(LEAD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reviewEnabledCase":false,"reviewEnabledSuite":false,
                                     "reviewEnabledPlan":false,"reviewEnabledDoc":true,
                                     "requiredPeerReviews":1,"authorMayApproveOwnWork":false,
                                     "editAfterApproval":"RETAIN","requireApprovedCaseForRun":false}"""))
                    .andReturn();
            assertThat(updated.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(updated.getResponse().getContentAsString())
                    .get("reviewEnabledDoc").asBoolean()).isTrue();
            assertThat(settingsAs(LEAD).get("reviewEnabledDoc").asBoolean()).isTrue();

            ReviewSettings enabledOnlyForDocs = new ReviewSettings(
                    false, false, false, true, 1, false, EditAfterApproval.RETAIN, false);
            assertThat(enabledOnlyForDocs.reviewEnabledFor(ReviewSubjectType.DOC)).isTrue();
            assertThat(enabledOnlyForDocs.reviewEnabledFor(ReviewSubjectType.CASE)).isFalse();
        }
    }

    // --------------------------------------------------------------- plumbing ----

    private JsonNode impactAs(UUID userId) throws Exception {
        MvcResult result = request(userId, PROJECT);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult request(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/settings/review-impact")
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
    }

    private JsonNode settingsAs(UUID userId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/settings")
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID openReview(ReviewSubjectType type, UUID submitter) {
        return openReviewOn(PROJECT, type, submitter);
    }

    private UUID openReviewOn(UUID projectId, ReviewSubjectType type, UUID submitter) {
        // Context OUTSIDE the transaction, like every other helper here. CompanyAwareTransactionManager
        // hooks doBegin, so `app.current_company` is set when the transaction STARTS — opening the
        // scope inside it is too late, the GUC stays unset and RLS refuses the insert outright.
        try (var ignored = CompanyContext.open(ACME)) {
            return tx.execute(status -> {
                Review review = Review.open(
                        ACME, projectId, type, UUID.randomUUID(), 1, 1, submitter, Instant.now());
                return reviews.saveAndFlush(review).getId();
            });
        }
    }

    private void verdict(UUID reviewId, UUID reviewer, ReviewParticipation.Verdict verdict) {
        inCompany(ACME, () -> tx.executeWithoutResult(status -> participations.saveAndFlush(
                ReviewParticipation.of(
                        ACME, reviewId, reviewer, ReviewParticipation.Kind.PEER_REVIEW,
                        verdict, null, Instant.now()))));
    }

    private String tokenFor(UUID userId) {
        return tx.execute(status -> tokens.issue(userId, ACME, 1, false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void member(UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(ACME, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(ACME, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void grant(String marker, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("review-impact:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, ACME, roleId, userId, projectId, null, now));
        }
    }
}
