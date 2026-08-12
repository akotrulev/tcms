package com.tcms.documentation;

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
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * External traceability targets — a case linked to a range of lines in a repository, or to a URL
 * (plan §6m; open question #26, resolved <em>build it</em> on 2026-08-02 and built in §169).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>An accepted commit that is not one.</strong> The sha is the whole reason this kind
 *       exists — an unpinned range points at different code after the next push — so a branch name
 *       sailing through is the defect that makes the feature worse than not having it. It is a
 *       string column and a string field, and nothing but this endpoint stands between them.</li>
 *   <li><strong>A {@code javascript:} URL.</strong> The value is rendered as an anchor's
 *       {@code href}, so an unchecked scheme is stored XSS reachable by anyone who may edit a
 *       case. Refused here because here is the one place it can be refused once.</li>
 *   <li><strong>The wrong gate.</strong> {@code case:edit}, not {@code doc:edit} — the reversal
 *       {@link ExternalLinkService}'s header argues. Gate this on documentation rights and only
 *       {@link Gates#anEngineerWithNoDocumentationRightsMayStillLink} goes red.</li>
 *   <li><strong>A duplicate on a double click.</strong> Two of the identifying columns are null on
 *       a whole-file link, and SQL equality over nulls is unknown rather than true, so the query
 *       spelling of the idempotency check silently writes a second row — which doubles every count
 *       drawn off this table.</li>
 *   <li><strong>A line range folded out of the identity.</strong> The cheap fix for the above is to
 *       compare only repo, path and sha; then a second range in the same file answers 200 with
 *       somebody else's link and the author's claim is dropped with a success code on top.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code e1…}, checked against every prefix
 * already in {@code src/test/java} before writing (handoff §19, and four repeats since).
 */
@AutoConfigureMockMvc
@DisplayName("External links — a case pointing at code, or at a document elsewhere")
class ExternalLinkEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("e1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("e1000000-0000-4000-8000-00000000000b");

    /** Granted to ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("e1001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody — the 404-not-403 fixture, and the sibling-case one. */
    private static final UUID ACME_LOCKED = UUID.fromString("e1001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("e1002000-0000-4000-8000-000000000001");

    /** Test Engineer — holds {@code case:edit} and, being the persona that matters here, not much else. */
    private static final UUID ENGINEER = UUID.fromString("e1003000-0000-4000-8000-000000000001");
    /** Viewer — holds {@code case:read} and no write. */
    private static final UUID VIEWER = UUID.fromString("e1003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("e1003000-0000-4000-8000-000000000003");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("e1003000-0000-4000-8000-000000000004");

    private static final UUID SUITE = UUID.fromString("e1004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("e1004000-0000-4000-8000-000000000002");

    private static final UUID CASE = UUID.fromString("e1005000-0000-4000-8000-000000000001");
    /** A second case of the same project — the one a link must not be deletable from. */
    private static final UUID OTHER_CASE = UUID.fromString("e1005000-0000-4000-8000-000000000002");
    /** This company's case, in a project nobody here holds a grant on. */
    private static final UUID SIBLING_CASE = UUID.fromString("e1005000-0000-4000-8000-000000000003");
    private static final UUID DELETED_CASE = UUID.fromString("e1005000-0000-4000-8000-000000000004");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "e1-acme", "Acme", now);
            company(UMBRA, "e1-umbra", "Umbra", now);

            activeUser(ENGINEER, "eng@e1-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@e1-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@e1-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@e1-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "E1OLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "E1LCK", "Internal Tools", now);

            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("e1-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("e1-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "E1UMB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("e1-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        seedCases();
        // Every test writes into doc_link and audit_event; without this the counting assertions
        // depend on the order the class ran in, which is handoff §19's defect.
        clearWrites();
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — case:edit, and deliberately not doc:edit")
    class Gates {

        @Test
        @DisplayName("a Test Engineer with no documentation rights may still link a case to code")
        void anEngineerWithNoDocumentationRightsMayStillLink() throws Exception {
            // THE test for the reversal. Test Engineer holds both keys, so it is not the persona
            // that separates them — what separates them is the project: ACME_BANKING has no
            // documentation space seeded here at all, and this endpoint neither reads nor needs
            // one. Gate this on doc:edit and the act still passes; gate it on a page existing and
            // this goes red, which is the failure the header's second clause names.
            MvcResult created = link(ENGINEER, ACME_BANKING, CASE, git());

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("targetKind").asText()).isEqualTo("EXTERNAL_GIT");
        }

        @Test
        @DisplayName("a Viewer is refused, and the refusal names editing cases")
        void aViewerIsRefused() throws Exception {
            MvcResult refused = link(VIEWER, ACME_BANKING, CASE, git());

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            // §32 — the act, never the key. And the act named is editing test cases: telling a
            // reader to ask for documentation rights they do not need is worse than saying nothing.
            assertThat(refused.getResponse().getContentAsString())
                    .contains("edit test cases")
                    .doesNotContain("case:edit");
        }

        @Test
        @DisplayName("a member with no grant on the project gets 404 with no body, never 403")
        void noGrantIs404() throws Exception {
            MvcResult refused = link(NO_GRANT, ACME_BANKING, CASE, git());

            assertThat(refused.getResponse().getStatus()).isEqualTo(404);
            assertThat(refused.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("another company's user gets 404")
        void anotherCompanyIs404() throws Exception {
            assertThat(link(OUTSIDER, ACME_BANKING, CASE, git()).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a case of a sibling project is 404, not 400 and not a link")
        void aSiblingProjectsCaseIs404() throws Exception {
            // RLS returns the row — a sibling project is the same company — so only CatalogueFacts'
            // project predicate refuses it. A 400 "no such case" would be a lie and a 403 would
            // confirm the id names something (§6t).
            MvcResult refused = link(ENGINEER, ACME_BANKING, SIBLING_CASE, git());

            assertThat(refused.getResponse().getStatus()).isEqualTo(404);
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("a soft-deleted case is 404")
        void aDeletedCaseIs404() throws Exception {
            assertThat(link(ENGINEER, ACME_BANKING, DELETED_CASE, git()).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // What is written
    // =====================================================================

    @Nested
    @DisplayName("The write — one row, one kind, one audit entry")
    class TheWrite {

        @Test
        @DisplayName("a git link stores the repository, path, range and commit, and no block")
        void aGitLinkIsStoredWhole() throws Exception {
            MvcResult created = link(ENGINEER, ACME_BANKING, CASE, git());

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            JsonNode view = body(created);
            assertThat(view.get("externalRepo").asText()).isEqualTo("acme/checkout-service");
            assertThat(view.get("externalPath").asText()).isEqualTo("src/Refund.java");
            assertThat(view.get("externalLineStart").asInt()).isEqualTo(40);
            assertThat(view.get("externalLineEnd").asInt()).isEqualTo(58);
            assertThat(view.get("externalCommitSha").asText()).isEqualTo("4f9c2b1");
            assertThat(view.get("relationship").asText()).isEqualTo("VERIFIES");

            Row stored = only();
            assertThat(stored.targetKind()).isEqualTo("EXTERNAL_GIT");
            assertThat(stored.sourceId()).isEqualTo(CASE);
            // ck_doc_link_target is the backstop; the factory is the gate. A row carrying both a
            // block and a repository would be a link claiming two targets.
            assertThat(stored.targetBlockId()).isNull();
            assertThat(stored.externalUrl()).isNull();
        }

        @Test
        @DisplayName("a URL link stores the URL and nothing else")
        void aUrlLinkIsStoredWhole() throws Exception {
            MvcResult created = link(ENGINEER, ACME_BANKING, CASE,
                    url("https://specs.acme.test/refunds", "REFERENCES"));

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("relationship").asText()).isEqualTo("REFERENCES");

            Row stored = only();
            assertThat(stored.targetKind()).isEqualTo("EXTERNAL_URL");
            assertThat(stored.externalUrl()).isEqualTo("https://specs.acme.test/refunds");
            assertThat(stored.externalRepo()).isNull();
            assertThat(stored.externalCommitSha()).isNull();
        }

        @Test
        @DisplayName("a link to a whole file — no line numbers — is a legitimate claim")
        void aWholeFileLinkIsAccepted() throws Exception {
            MvcResult created = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", null, null, "4f9c2b1"));

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(only().externalLineStart()).isNull();
        }

        @Test
        @DisplayName("the relationship defaults to VERIFIES")
        void theRelationshipDefaults() throws Exception {
            link(ENGINEER, ACME_BANKING, CASE, git());

            assertThat(only().relationship()).isEqualTo("VERIFIES");
        }

        @Test
        @DisplayName("the audit entry names the target, so the trail says which link was made")
        void theAuditEntryNamesTheTarget() throws Exception {
            link(ENGINEER, ACME_BANKING, CASE, git());

            // The same action name as a block link, deliberately: an audit reader filtering for
            // what happened to traceability must not have to know this product has two writers.
            assertThat(auditActions()).containsExactly("doc_link.created");
            assertThat(auditDetails()).singleElement().satisfies(detail -> {
                assertThat(detail).contains("EXTERNAL_GIT");
                assertThat(detail).contains("acme/checkout-service/src/Refund.java@4f9c2b1");
                assertThat(detail).contains("E1OLB-1");
            });
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("Re-asserting a recorded fact")
    class Idempotency {

        @Test
        @DisplayName("linking the identical target twice writes nothing and answers 200")
        void theSameTargetTwiceIsNotASecondRow() throws Exception {
            MvcResult first = link(ENGINEER, ACME_BANKING, CASE, git());
            MvcResult second = link(ENGINEER, ACME_BANKING, CASE, git());

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(second).get("id").asText()).isEqualTo(body(first).get("id").asText());
            assertThat(linkCount()).isEqualTo(1);
            // No second audit row and no second outbox notice: a trail that records a request
            // which changed nothing buries the one that did.
            assertThat(auditActions()).containsExactly("doc_link.created");
        }

        @Test
        @DisplayName("a whole-file link matches itself — the null comparison SQL gets wrong")
        void aWholeFileLinkMatchesItself() throws Exception {
            // THE reason sameTarget is Java and not a query predicate. Two of the identifying
            // columns are null here, and `external_line_start = :start` is UNKNOWN rather than
            // true when both sides are null — so the SQL spelling writes a second row and every
            // other test in this class still passes.
            String payload = gitBody("acme/checkout-service", "src/Refund.java", null, null,
                    "4f9c2b1");
            link(ENGINEER, ACME_BANKING, CASE, payload);

            assertThat(link(ENGINEER, ACME_BANKING, CASE, payload).getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(linkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a different range in the same file at the same commit is a second link")
        void adifferentRangeIsADifferentClaim() throws Exception {
            link(ENGINEER, ACME_BANKING, CASE, git());
            MvcResult second = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", 90, 120, "4f9c2b1"));

            // Folding the range out of the identity is the cheap way to write sameTarget, and it
            // answers this request with the OTHER link and a 200 — the author's claim dropped with
            // a success code on top.
            assertThat(second.getResponse().getStatus()).isEqualTo(201);
            assertThat(linkCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the same URL on a different case is a different link")
        void anotherCasesLinkIsNotThisCasesLink() throws Exception {
            link(ENGINEER, ACME_BANKING, CASE, url("https://specs.acme.test/refunds", null));
            MvcResult other = link(ENGINEER, ACME_BANKING, OTHER_CASE,
                    url("https://specs.acme.test/refunds", null));

            assertThat(other.getResponse().getStatus()).isEqualTo(201);
            assertThat(linkCount()).isEqualTo(2);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("What is refused, and with which field named")
    class Validation {

        @Test
        @DisplayName("a git link with no commit is refused, and the message says why not merely that")
        void aGitLinkWithoutAShaIsRefused() throws Exception {
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", 40, 58, null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "commitSha")).contains("next push");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("a branch name where a commit belongs is refused")
        void aBranchNameIsNotACommit() throws Exception {
            // The defect this whole kind is against, arriving as a string that looks fine. `main`
            // is also seven characters short of nothing — the pattern is hex, not length alone.
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", 40, 58, "release/2026-08"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "commitSha")).contains("moves");
        }

        @Test
        @DisplayName("a git link with no repository is refused, though the column would take it")
        void aGitLinkWithoutARepositoryIsRefused() throws Exception {
            // Narrower than ck_doc_link_target on purpose: a path with no repository names a file
            // in an unnamed project, which is a link nobody can follow.
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE,
                    gitBody(null, "src/Refund.java", 40, 58, "4f9c2b1"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "repo")).isNotNull();
        }

        @Test
        @DisplayName("an end line with no start is refused")
        void anEndWithoutAStartIsRefused() throws Exception {
            // Otherwise it renders as `Refund.java:null–58`, off a row that satisfies every
            // constraint in the schema.
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", null, 58, "4f9c2b1"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "lineStart")).isNotNull();
        }

        @Test
        @DisplayName("a range that ends before it begins is refused")
        void anInvertedRangeIsRefused() throws Exception {
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE, gitBody(
                    "acme/checkout-service", "src/Refund.java", 90, 40, "4f9c2b1"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "lineEnd")).isNotNull();
        }

        @Test
        @DisplayName("a javascript: URL is refused — the value is rendered as an href")
        void aJavascriptUrlIsRefused() throws Exception {
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE,
                    url("javascript:alert(document.cookie)", null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "url")).contains("http");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("a data: URL is refused for the same reason")
        void aDataUrlIsRefused() throws Exception {
            assertThat(link(ENGINEER, ACME_BANKING, CASE,
                    url("data:text/html,<script>1</script>", null)).getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an address that names no site is refused")
        void aHostlessUrlIsRefused() throws Exception {
            assertThat(link(ENGINEER, ACME_BANKING, CASE, url("https:///refunds", null))
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("no target kind is one error, not seven")
        void anAbsentKindIsOneError() throws Exception {
            // A form told that six fields are wrong when one is unset has been told nothing.
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE, "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorKeys(refused)).containsExactly("targetKind");
        }

        @Test
        @DisplayName("an unknown target kind is refused rather than guessed from the fields")
        void anUnknownKindIsRefused() throws Exception {
            assertThat(link(ENGINEER, ACME_BANKING, CASE,
                    "{\"targetKind\":\"EXTERNAL_JIRA\",\"url\":\"https://x.test\"}")
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("git coordinates on a URL link are refused, not ignored")
        void mixedFieldsAreRefused() throws Exception {
            // Accepting and dropping them is how a caller comes to believe a range was recorded.
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE,
                    "{\"targetKind\":\"EXTERNAL_URL\",\"url\":\"https://specs.acme.test/x\","
                            + "\"repo\":\"acme/checkout-service\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "repo")).isNotNull();
        }

        @Test
        @DisplayName("a URL on a git link is refused")
        void aUrlOnAGitLinkIsRefused() throws Exception {
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE,
                    "{\"targetKind\":\"EXTERNAL_GIT\",\"repo\":\"acme/checkout-service\","
                            + "\"path\":\"src/Refund.java\",\"commitSha\":\"4f9c2b1\","
                            + "\"url\":\"https://specs.acme.test/x\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "url")).isNotNull();
        }

        @Test
        @DisplayName("an unknown relationship is refused")
        void anUnknownRelationshipIsRefused() throws Exception {
            MvcResult refused = link(ENGINEER, ACME_BANKING, CASE,
                    url("https://specs.acme.test/x", "COVERS"));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorFor(refused, "relationship")).isNotNull();
        }
    }

    // =====================================================================
    // Removal
    // =====================================================================

    @Nested
    @DisplayName("Removal — hard, and scoped to the case that owns the link")
    class Removal {

        @Test
        @DisplayName("a link is removed outright, not tombstoned")
        void aLinkIsHardDeleted() throws Exception {
            UUID id = UUID.fromString(body(link(ENGINEER, ACME_BANKING, CASE, git())).get("id")
                    .asText());

            assertThat(unlink(ENGINEER, ACME_BANKING, CASE, id).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(linkCount()).isZero();
            assertThat(auditActions()).containsExactly("doc_link.created", "doc_link.deleted");
        }

        @Test
        @DisplayName("a link belonging to another case is a 404, not a delete")
        void anotherCasesLinkIsNotDeletable() throws Exception {
            UUID id = UUID.fromString(body(link(ENGINEER, ACME_BANKING, CASE, git())).get("id")
                    .asText());

            assertThat(unlink(ENGINEER, ACME_BANKING, OTHER_CASE, id).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(linkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("removing a link that does not exist is a 404, not an idempotent 204")
        void anAbsentLinkIs404() throws Exception {
            assertThat(unlink(ENGINEER, ACME_BANKING, CASE, UUID.randomUUID())
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a Viewer cannot remove a link")
        void aViewerCannotUnlink() throws Exception {
            UUID id = UUID.fromString(body(link(ENGINEER, ACME_BANKING, CASE, git())).get("id")
                    .asText());

            assertThat(unlink(VIEWER, ACME_BANKING, CASE, id).getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(linkCount()).isEqualTo(1);
        }
    }

    // =====================================================================
    // Correcting the claim (§174)
    // =====================================================================

    @Nested
    @DisplayName("Correcting what a link claims — PATCH, §174")
    class Relabelling {

        @Test
        @DisplayName("the relationship moves, and the row records who moved it and when")
        void theClaimIsCorrectedAndStamped() throws Exception {
            UUID id = idOf(link(ENGINEER, ACME_BANKING, CASE, git()));
            assertThat(only().updatedAt()).isNull();

            MvcResult corrected = relabel(ENGINEER, ACME_BANKING, CASE, id, relationship("REFERENCES"));

            assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(corrected).get("relationship").asText()).isEqualTo("REFERENCES");

            Row stored = only();
            assertThat(stored.relationship()).isEqualTo("REFERENCES");
            // The half V48 exists for. Before it, correcting a claim meant deleting the link and
            // making another, which rewrote created_by to whoever noticed the mistake.
            assertThat(stored.updatedAt()).isNotNull();
            assertThat(stored.updatedBy()).isEqualTo(ENGINEER);
            assertThat(auditActions()).containsExactly("doc_link.created", "doc_link.relabelled");
        }

        @Test
        @DisplayName("the target coordinates are untouched — only the claim moves")
        void onlyTheClaimMoves() throws Exception {
            UUID id = idOf(link(ENGINEER, ACME_BANKING, CASE, git()));

            relabel(ENGINEER, ACME_BANKING, CASE, id, relationship("DERIVED_FROM"));

            Row stored = only();
            // The immutability that matters, and the reason the entity keeps `updatable = false`
            // everywhere else: a link that silently changes what it points at keeps every count
            // over doc_link agreeing with itself while pointing somewhere new.
            assertThat(stored.externalRepo()).isEqualTo("acme/checkout-service");
            assertThat(stored.externalPath()).isEqualTo("src/Refund.java");
            assertThat(stored.externalLineStart()).isEqualTo(40);
            assertThat(stored.externalCommitSha()).isEqualTo("4f9c2b1");
            assertThat(stored.targetKind()).isEqualTo("EXTERNAL_GIT");
        }

        @Test
        @DisplayName("re-stating the value it already has writes nothing — no stamp, no audit row")
        void reStatingTheSameClaimWritesNothing() throws Exception {
            UUID id = idOf(link(ENGINEER, ACME_BANKING, CASE, url("https://s.test/a", "REFERENCES")));

            MvcResult again = relabel(ENGINEER, ACME_BANKING, CASE, id, relationship("REFERENCES"));

            // 200 with the row, exactly as the POST answers for a duplicate — but nothing moved, so
            // updated_by must stay null. A stamp here would report a correction nobody made, and an
            // audit row for it buries the ones that matter.
            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(only().updatedBy()).isNull();
            assertThat(only().updatedAt()).isNull();
            assertThat(auditActions()).containsExactly("doc_link.created");
        }

        @Test
        @DisplayName("an unknown or absent relationship is a field error, never a default")
        void thereIsNoDefaultOnACorrection() throws Exception {
            UUID id = idOf(link(ENGINEER, ACME_BANKING, CASE, git()));

            // The create defaults an absent relationship to VERIFIES. A correction must not: an
            // absent value here is a caller who did not say what to correct it TO, and choosing for
            // them would have the server invent a claim on the field that exists to keep "proves
            // it" and "mentions it" apart.
            assertThat(relabel(ENGINEER, ACME_BANKING, CASE, id, "{}").getResponse().getStatus())
                    .isEqualTo(400);
            assertThat(relabel(ENGINEER, ACME_BANKING, CASE, id, relationship("SORT_OF"))
                    .getResponse().getStatus()).isEqualTo(400);
            assertThat(only().relationship()).isEqualTo("VERIFIES");
            assertThat(only().updatedAt()).isNull();
        }

        @Test
        @DisplayName("a Viewer may not correct a claim, and a link of another case is a 404")
        void theGateAndTheScope() throws Exception {
            UUID id = idOf(link(ENGINEER, ACME_BANKING, CASE, git()));

            // case:edit, the same key the create and the delete charge — correcting a claim is not
            // a lesser act than making one.
            assertThat(relabel(VIEWER, ACME_BANKING, CASE, id, relationship("REFERENCES"))
                    .getResponse().getStatus()).isEqualTo(403);
            // Scoped to the case for the delete's reason: a link id belonging to another case must
            // 404 rather than be rewritten from under it.
            assertThat(relabel(ENGINEER, ACME_BANKING, OTHER_CASE, id, relationship("REFERENCES"))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(only().relationship()).isEqualTo("VERIFIES");
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private MvcResult link(UUID userId, UUID projectId, UUID caseId, String payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/external-links")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokenFor(userId, companyOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private MvcResult unlink(UUID userId, UUID projectId, UUID caseId, UUID linkId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/external-links/" + linkId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokenFor(userId, companyOf(userId))))
                .andReturn();
    }

    private MvcResult relabel(UUID userId, UUID projectId, UUID caseId, UUID linkId, String payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + projectId + "/cases/" + caseId
                                + "/external-links/" + linkId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokenFor(userId, companyOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private static UUID companyOf(UUID userId) {
        return userId.equals(OUTSIDER) ? UMBRA : ACME;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // helpers — payloads
    // =====================================================================

    private static String git() {
        return gitBody("acme/checkout-service", "src/Refund.java", 40, 58, "4f9c2b1");
    }

    private static String gitBody(
            String repo, String path, Integer start, Integer end, String sha) {

        List<String> fields = new ArrayList<>();
        fields.add("\"targetKind\":\"EXTERNAL_GIT\"");
        if (repo != null) {
            fields.add("\"repo\":\"" + repo + "\"");
        }
        if (path != null) {
            fields.add("\"path\":\"" + path + "\"");
        }
        if (start != null) {
            fields.add("\"lineStart\":" + start);
        }
        if (end != null) {
            fields.add("\"lineEnd\":" + end);
        }
        if (sha != null) {
            fields.add("\"commitSha\":\"" + sha + "\"");
        }
        return "{" + String.join(",", fields) + "}";
    }

    /** The created link's id — the pattern the delete tests spell inline four times over. */
    private static UUID idOf(MvcResult created) throws Exception {
        return UUID.fromString(body(created).get("id").asText());
    }

    private static String relationship(String value) {
        return "{\"relationship\":\"" + value + "\"}";
    }

    private static String url(String url, String relationship) {
        return "{\"targetKind\":\"EXTERNAL_URL\",\"url\":\"" + url + "\""
                + (relationship == null ? "" : ",\"relationship\":\"" + relationship + "\"") + "}";
    }

    // =====================================================================
    // helpers — reading what happened
    // =====================================================================

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String errorFor(MvcResult result, String field) throws Exception {
        JsonNode errors = body(result).get("errors");
        return errors == null || errors.get(field) == null ? null : errors.get(field).toString();
    }

    private static List<String> errorKeys(MvcResult result) throws Exception {
        List<String> keys = new ArrayList<>();
        body(result).get("errors").fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private long linkCount() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM doc_link");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** The single stored link — asserting the row rather than the response body. */
    private Row only() throws SQLException {
        List<Row> rows = stored();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<Row> stored() throws SQLException {
        List<Row> found = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT source_id, target_kind, target_block_id, external_repo,
                               external_path, external_line_start, external_line_end,
                               external_commit_sha, external_url, relationship,
                               updated_at, updated_by
                          FROM doc_link
                         ORDER BY created_at, id
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                found.add(new Row(
                        UUID.fromString(rows.getString("source_id")),
                        rows.getString("target_kind"),
                        rows.getString("target_block_id") == null
                                ? null
                                : UUID.fromString(rows.getString("target_block_id")),
                        rows.getString("external_repo"),
                        rows.getString("external_path"),
                        (Integer) rows.getObject("external_line_start"),
                        (Integer) rows.getObject("external_line_end"),
                        rows.getString("external_commit_sha"),
                        rows.getString("external_url"),
                        rows.getString("relationship"),
                        rows.getTimestamp("updated_at") == null
                                ? null
                                : rows.getTimestamp("updated_at").toInstant(),
                        rows.getString("updated_by") == null
                                ? null
                                : UUID.fromString(rows.getString("updated_by"))));
            }
        }
        return found;
    }

    private record Row(UUID sourceId, String targetKind, UUID targetBlockId, String externalRepo,
            String externalPath, Integer externalLineStart, Integer externalLineEnd,
            String externalCommitSha, String externalUrl, String relationship,
            /** V48 — null means never corrected, which is a different fact from "corrected at
             * creation" and is why the migration declines to backfill it from created_at. */
            Instant updatedAt, UUID updatedBy) {
    }

    private List<String> auditActions() throws SQLException {
        return auditColumn("action");
    }

    private List<String> auditDetails() throws SQLException {
        return auditColumn("CAST(changes AS text)");
    }

    private List<String> auditColumn(String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + column + " FROM audit_event ORDER BY occurred_at");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    // =====================================================================
    // helpers — fixtures
    // =====================================================================

    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, SUITE, ACME_BANKING, "E1OLB-S1", "Refunds");
            suite(connection, LOCKED_SUITE, ACME_LOCKED, "E1LCK-S1", "Internal");

            testCase(connection, CASE, ACME_BANKING, SUITE, "E1OLB-1", 1, false);
            testCase(connection, OTHER_CASE, ACME_BANKING, SUITE, "E1OLB-2", 2, false);
            testCase(connection, SIBLING_CASE, ACME_LOCKED, LOCKED_SUITE, "E1LCK-1", 3, false);
            testCase(connection, DELETED_CASE, ACME_BANKING, SUITE, "E1OLB-3", 4, true);
        }
    }

    private void clearWrites() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM doc_link");
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

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
        UUID id = UUID.nameUUIDFromBytes(("external-link-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId, String key, String name)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 1, ?, 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long keySeq, boolean deleted) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'APPROVED', 'MANUAL', now(), now(),
                        CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }
}
