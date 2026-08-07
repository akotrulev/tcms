package com.tcms.work;

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
import java.sql.PreparedStatement;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /work-items} — the kanban board (plan §6l).
 *
 * <h2>The failures this class exists to catch, and why none of them throws</h2>
 *
 * <ol>
 *   <li><strong>A derived card that can be moved.</strong> The single invariant §6l is built around:
 *       a card mirroring an execution must not own a stored status, or a tester marks the case
 *       Passed in the run and the card sits in "In Progress" forever. The endpoint has to
 *       <em>refuse</em> the move, and refuse it as a 409 rather than a 404 — a 404 reads as "the
 *       card is gone" and invites a reload that brings it straight back.</li>
 *   <li><strong>A finished execution still on the board.</strong> §6l: "execution completed → card
 *       leaves". A generator that returns every execution turns the board into an append-only log of
 *       everything ever run, which looks like data and grows forever.</li>
 *   <li><strong>Skipped work resurrected.</strong> The unrecorded executions in a <em>completed</em>
 *       run are cases that were deliberately not run. Putting them on the board makes closed runs a
 *       permanent backlog nobody can ever clear.</li>
 *   <li><strong>A task assigned to somebody who cannot open its project.</strong> The id is trivially
 *       available from any other tab, the card renders their name perfectly, and the only person who
 *       ever sees the problem is the assignee — who gets a 404 when they click it.</li>
 *   <li><strong>{@code kind} accepted from the client.</strong> A POST with {@code kind: EXECUTION}
 *       would materialise a derived item, which is defect 1 arriving through the front door.</li>
 *   <li><strong>A personal board carrying work from a project the caller cannot open (§83).</strong>
 *       The cross-project board names no project, so it has no 404 to answer and nothing about the
 *       request bounds it — the bound has to be the set of projects it assembles from. Built from
 *       "cards assigned to me" instead, it renders the case key, the title and the project name of a
 *       client nobody granted, and the assignee is the only person who ever sees it.</li>
 *   <li><strong>A team board bounded by the TEAM's visibility rather than the caller's (§84).</strong>
 *       Defect 6 one step further out, and the more inviting mistake, because "everything assigned to
 *       this team" is one query and reads correctly — it renders the case key, the title and the
 *       project name of every project any member was granted, to a reader granted none of them. It
 *       needs a team-mate who can see a project the caller cannot ({@code TEAMMATE} on
 *       {@code OTHER_PROJECT}) or the two bounds agree on every row in this fixture and the test
 *       passes against the leak.</li>
 *   <li><strong>The read gate used as the write gate.</strong> Every role holds {@code project:read};
 *       {@code workitem:manage} is held by four of the six (roles spec §A6). A Viewer must be able to
 *       look at the board and unable to change it.</li>
 * </ol>
 *
 * <h2>The fixture</h2>
 *
 * <p>Two companies with identically keyed cases, so only ids prove the scoping. In Acme: a run
 * {@code IN_PROGRESS} holding one unrecorded and one recorded execution, a {@code COMPLETED} run
 * holding an unrecorded one, a case flagged in {@code case_health}, and a stored task. Personas are
 * a Test Lead (holds {@code workitem:manage}), a Viewer (does not), and a member with no grant at
 * all.
 *
 * <p><strong>Three Acme projects, and the third is what makes §83 provable.</strong> LEAD is granted
 * on {@code PROJECT} and {@code SECOND_PROJECT} and not on {@code OTHER_PROJECT} — each of the three
 * holds an outstanding execution assigned to LEAD, so the personal board must show two of them and
 * hide the third. With one reachable project a board that quietly still reads a single project
 * passes every assertion in this class.
 *
 * <p>Ids are unique to this class — {@code b0…} — and so are the company slugs and project keys.
 * {@code CaseHealthRecomputeTest} in this same slice lost eleven tests to a slug it shared with
 * another class: green alone, red in the suite, §19's shape exactly.
 */
@AutoConfigureMockMvc
@DisplayName("Board endpoints — tasks are rows, everything else is a projection")
class BoardEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_PROJECT = UUID.fromString("b0001000-0000-4000-8000-000000000003");
    /**
     * A second Acme project LEAD <em>is</em> granted on — the one that makes §83's aggregate provable.
     * With only one reachable project, a personal board that quietly still reads a single project
     * passes every assertion.
     */
    private static final UUID SECOND_PROJECT =
            UUID.fromString("b0001000-0000-4000-8000-000000000004");
    private static final UUID SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000002");
    private static final UUID SECOND_SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000003");
    private static final UUID SIBLING_SUITE = UUID.fromString("b0002000-0000-4000-8000-000000000004");

    /** Holds workitem:manage on PROJECT. */
    private static final UUID LEAD = UUID.fromString("b0003000-0000-4000-8000-000000000001");
    /** Can see the board, holds no workitem:manage. */
    private static final UUID VIEWER = UUID.fromString("b0003000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("b0003000-0000-4000-8000-000000000003");

    /**
     * On {@code DEV_TEAM} with LEAD, and granted on {@code OTHER_PROJECT} — which LEAD is not.
     *
     * <p>The persona §84's bound needs and no earlier slice did: a <em>team-mate</em> who can see a
     * project the caller cannot. Without them, "bounded by the caller's visibility" and "bounded by
     * the team's" answer identically on every row in this fixture, and the security test passes
     * against the leak.
     */
    private static final UUID TEAMMATE = UUID.fromString("b0003000-0000-4000-8000-000000000004");
    /** An Acme member granted on PROJECT and on NO team — the negative half of the expansion. */
    private static final UUID OUTSIDER = UUID.fromString("b0003000-0000-4000-8000-000000000005");

    private static final UUID LIVE_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000001");
    private static final UUID CLOSED_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000002");
    private static final UUID SECOND_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000003");
    private static final UUID SIBLING_RUN = UUID.fromString("b0004000-0000-4000-8000-000000000004");

    /** Unrecorded, in the live run — the one EXECUTION card. */
    private static final UUID OUTSTANDING = UUID.fromString("b0005000-0000-4000-8000-000000000001");
    /** Recorded, in the live run — finished work, must have left the board. */
    private static final UUID FINISHED = UUID.fromString("b0005000-0000-4000-8000-000000000002");
    /** Unrecorded, in the CLOSED run — skipped, must not be resurrected. */
    private static final UUID SKIPPED = UUID.fromString("b0005000-0000-4000-8000-000000000003");
    /** Flagged in case_health — the one CASE_MAINTENANCE card. */
    private static final UUID FLAGGED = UUID.fromString("b0005000-0000-4000-8000-000000000004");
    /** Umbra's case, keyed identically to prove only ids scope this. */
    private static final UUID FOREIGN = UUID.fromString("b0005000-0000-4000-8000-000000000005");

    // §121 phase 3's fixture — the fourth generator. Every row here exists to make one card either
    // appear or not appear, and the two that must NOT appear are the ones a plausible
    // implementation gets wrong: a row that carries evidence but no decision, and a decision on a
    // case nobody is running any more.
    //
    // These two ids started at ...000c/...000d and COLLIDED WITH TEAM_ELSEWHERE, which is declared
    // 80 lines further down in a different block. Sixth instance of the family the handoff has
    // tracked since §19: every case helper here is ON CONFLICT (id) DO NOTHING, so the collision
    // silently DROPPED the later insert and TEAM_ELSEWHERE's execution pointed at this case, in
    // the wrong project, with the wrong owner. The only test that failed was §84's team-board
    // bound, which is nothing to do with either row. Grep the whole prefix before taking one.

    /**
     * Quarantined, and <strong>owned by VIEWER while LEAD is who quarantined it</strong>.
     *
     * <p>The only configuration in which "the card belongs to the owner" and "the card belongs to
     * whoever decided" give different answers. With one person in both roles, a board that assigns
     * the debt to the decider passes every assertion — and ships a product where pulling somebody
     * else's flaky test out of the gate moves their work onto your own list.
     */
    private static final UUID QUARANTINED = UUID.fromString("b0005000-0000-4000-8000-00000000000e");
    /**
     * A {@code case_flake} row with a <strong>high score and no quarantine</strong> — the trap.
     *
     * <p>The generator reads the authored flag, never the derived score, and this is the row that
     * can tell them apart: a query written over {@code flake_score} produces a card here, and every
     * other test in this class still passes, because every other quarantined case in the fixture
     * would also be flaky. Deriving the card from the score is the version §121 argued against, so
     * it needs a row that fails.
     */
    private static final UUID RELEASED = UUID.fromString("b0005000-0000-4000-8000-00000000000f");

    /** Outstanding in SECOND_PROJECT and assigned to LEAD — the card only §83's board can show. */
    private static final UUID ELSEWHERE = UUID.fromString("b0005000-0000-4000-8000-00000000000a");
    /**
     * Outstanding in OTHER_PROJECT and assigned to LEAD, who holds no grant there.
     *
     * <p>The whole risk of a board that stops naming a project: work can be assigned to somebody who
     * cannot open the project it is in (this class's defect 4, from the other end), and an aggregate
     * built from "cards assigned to me" rather than from "projects I can see" would put it on their
     * board — where the title, the case key and the project name of a client they were never granted
     * all render perfectly.
     */
    private static final UUID UNREACHABLE = UUID.fromString("b0005000-0000-4000-8000-00000000000b");

    // The review fixture (§81). Each case exists to make one card either appear or not appear —
    // "asked and answered", "the review is over" and "the subject is gone" all look identical on a
    // board that only joins review_assignment.

    /** Under review, asked of LEAD directly. The one CASE review card on LEAD's board. */
    private static final UUID UNDER_REVIEW = UUID.fromString("b0005000-0000-4000-8000-000000000006");
    /** Under review, asked of LEAD, who has already given a verdict — the card must have left. */
    private static final UUID ANSWERED = UUID.fromString("b0005000-0000-4000-8000-000000000007");
    /** Asked of LEAD, but the review is APPROVED — over, so no card. */
    private static final UUID SIGNED_OFF = UUID.fromString("b0005000-0000-4000-8000-000000000008");
    /** Asked of LEAD, and the case has since been soft-deleted. */
    private static final UUID GONE = UUID.fromString("b0005000-0000-4000-8000-000000000009");

    private static final UUID REVIEW_OPEN = UUID.fromString("b0006000-0000-4000-8000-000000000001");
    private static final UUID REVIEW_ANSWERED =
            UUID.fromString("b0006000-0000-4000-8000-000000000002");
    private static final UUID REVIEW_CLOSED =
            UUID.fromString("b0006000-0000-4000-8000-000000000003");
    private static final UUID REVIEW_DELETED_SUBJECT =
            UUID.fromString("b0006000-0000-4000-8000-000000000004");
    /** On the SUITE, asked of a team — the read-time expansion, and the non-case subject. */
    private static final UUID REVIEW_SUITE = UUID.fromString("b0006000-0000-4000-8000-000000000005");
    /**
     * Umbra's own open review — and it is asked of <strong>LEAD</strong>, who is not an Umbra member
     * at all. {@code assignee_user_id} references instance-level {@code app_user}, so that row
     * inserts happily (§64's family, the one §80 checks at write time), and what must keep it off
     * Acme's board is the company predicate rather than anybody's membership.
     */
    private static final UUID REVIEW_FOREIGN =
            UUID.fromString("b0006000-0000-4000-8000-000000000006");

    /** VIEWER and LEAD both belong to it; the suite review is asked of the team. */
    private static final UUID TEAM = UUID.fromString("b0007000-0000-4000-8000-000000000001");

    // §84's team-board fixture. DEV_TEAM is deliberately a SECOND team rather than more members on
    // TEAM: §81's `oneCardPerReviewer` asserts the exact set of reviewers on the suite review, so
    // adding TEAMMATE to the team that review was asked of would have reddened a passing test for a
    // reason that has nothing to do with reviewers.

    /** LEAD (the caller), VIEWER (a member who is not the caller) and TEAMMATE. */
    private static final UUID DEV_TEAM = UUID.fromString("b0007000-0000-4000-8000-000000000002");
    /** One member, NO_GRANT, who can see nothing — a reachable team with an empty board. */
    private static final UUID QUIET_TEAM = UUID.fromString("b0007000-0000-4000-8000-000000000003");
    /** No members at all — the other empty board, and the one answered before any query. */
    private static final UUID LONELY_TEAM = UUID.fromString("b0007000-0000-4000-8000-000000000004");
    /** Umbra's own team. Must be a 404 to an Acme caller, exactly like an id that names nothing. */
    private static final UUID UMBRA_TEAM = UUID.fromString("b0007000-0000-4000-8000-000000000005");
    /** Names no team anywhere. */
    private static final UUID UNKNOWN_TEAM = UUID.fromString("b0007000-0000-4000-8000-0000000000ff");

    /**
     * Outstanding in OTHER_PROJECT and assigned to TEAMMATE, who <em>is</em> granted there.
     *
     * <p>{@link #UNREACHABLE} is the same shape from the personal board's angle — work the assignee
     * cannot open. This is the team board's own: work a team-mate legitimately owns, in a project the
     * <em>reader</em> was never granted. A team board bounded by the team rather than by the caller
     * renders its key, its title and the project's name, and the only assertion that can catch it is
     * one where the two bounds disagree.
     */
    private static final UUID TEAM_ELSEWHERE =
            UUID.fromString("b0005000-0000-4000-8000-00000000000c");

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
            company(ACME, "board-acme", "Acme", now);
            company(UMBRA, "board-umbra", "Umbra", now);
            activeUser(LEAD, "lead@board-acme.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@board-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@board-acme.test", "N. Grant", now);
            activeUser(TEAMMATE, "teammate@board-acme.test", "T. Mate", now);
            activeUser(OUTSIDER, "outsider@board-acme.test", "O. Sider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BRDA", "Board Project", now);
            seedProject(OTHER_PROJECT, ACME, "BRDB", "Sibling Project", now);
            seedProject(SECOND_PROJECT, ACME, "BRDC", "Second Project", now);
            member(ACME, LEAD, "L. Lead (Acme)", now);
            member(ACME, VIEWER, "V. Viewer (Acme)", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, TEAMMATE, "T. Mate (Acme)", now);
            member(ACME, OUTSIDER, "O. Sider (Acme)", now);
            grantOnProject("lead", ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, now);
            grantOnProject("viewer", ACME, Role.VIEWER_ID, VIEWER, PROJECT, now);
            // LEAD on the second project and deliberately NOT on OTHER_PROJECT, which stays the
            // sibling nobody granted — the two halves of §83's bound need both to exist at once.
            grantOnProject("lead-second", ACME, Role.TEST_LEAD_ID, LEAD, SECOND_PROJECT, now);
            // §84: TEAMMATE is granted where LEAD is not, which is the only configuration in which
            // "the caller's visibility" and "the team's visibility" give different answers.
            grantOnProject("teammate-other", ACME, Role.TEST_LEAD_ID, TEAMMATE, OTHER_PROJECT, now);
            grantOnProject("outsider", ACME, Role.VIEWER_ID, OUTSIDER, PROJECT, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_PROJECT, UMBRA, "BRDU", "Umbra Project", now)));

        clearMutableRows();

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, PROJECT);
            suite(acme, SUITE, ACME, PROJECT, "TS-1", "Root");
            testCase(acme, OUTSTANDING, ACME, PROJECT, SUITE, "TC-1", 1, LEAD);
            testCase(acme, FINISHED, ACME, PROJECT, SUITE, "TC-2", 2, LEAD);
            testCase(acme, SKIPPED, ACME, PROJECT, SUITE, "TC-3", 3, LEAD);
            testCase(acme, FLAGGED, ACME, PROJECT, SUITE, "TC-4", 4, LEAD);

            run(acme, LIVE_RUN, ACME, PROJECT, "TR-1", 1, "IN_PROGRESS");
            run(acme, CLOSED_RUN, ACME, PROJECT, "TR-2", 2, "COMPLETED");

            execution(acme, ACME, PROJECT, LIVE_RUN, OUTSTANDING, false, LEAD);
            execution(acme, ACME, PROJECT, LIVE_RUN, FINISHED, true, LEAD);
            execution(acme, ACME, PROJECT, CLOSED_RUN, SKIPPED, false, LEAD);

            health(acme, ACME, PROJECT, FLAGGED, "never_executed");

            // §121's quarantine fixture. TC-4 is deliberately BOTH flagged and quarantined: two
            // sources, one case, and the two cards must not share an id.
            testCase(acme, QUARANTINED, ACME, PROJECT, SUITE, "TC-12", 12, VIEWER);
            testCase(acme, RELEASED, ACME, PROJECT, SUITE, "TC-13", 13, LEAD);
            flake(acme, ACME, PROJECT, QUARANTINED, "0.7500", LEAD, "Flaky on Firefox");
            flake(acme, ACME, PROJECT, FLAGGED, "0.5000", LEAD, "Races the login redirect");
            flake(acme, ACME, PROJECT, RELEASED, "0.9000", null, null);

            // The review fixture. VIEWER is in the team on purpose: the suite review is asked of
            // nobody by name, so if the expansion is missing, VIEWER's board is silently empty and
            // the only test that fails is the one written for it.
            testCase(acme, UNDER_REVIEW, ACME, PROJECT, SUITE, "TC-5", 5, LEAD);
            testCase(acme, ANSWERED, ACME, PROJECT, SUITE, "TC-6", 6, LEAD);
            testCase(acme, SIGNED_OFF, ACME, PROJECT, SUITE, "TC-7", 7, LEAD);
            testCase(acme, GONE, ACME, PROJECT, SUITE, "TC-8", 8, LEAD);
            softDelete(acme, GONE);
            // Quarantined AND since soft-deleted. "This test is excluded from the gate" is not
            // something to say about a case nobody is running — and only a hard delete cascades
            // the flake row away (V38), so the row is still there to be selected.
            flake(acme, ACME, PROJECT, GONE, "0.6000", LEAD, "Deleted, and still quarantined");

            team(acme, TEAM, ACME, "Board Reviewers");
            teamMember(acme, ACME, TEAM, VIEWER);
            teamMember(acme, ACME, TEAM, LEAD);

            // §84's three teams. QUIET_TEAM and LONELY_TEAM are the two ways a board can be
            // reachable and empty, and they must be told apart from a team that does not exist.
            team(acme, DEV_TEAM, ACME, "Delivery");
            teamMember(acme, ACME, DEV_TEAM, LEAD);
            teamMember(acme, ACME, DEV_TEAM, VIEWER);
            teamMember(acme, ACME, DEV_TEAM, TEAMMATE);

            team(acme, QUIET_TEAM, ACME, "Quiet");
            teamMember(acme, ACME, QUIET_TEAM, NO_GRANT);

            team(acme, LONELY_TEAM, ACME, "Nobody");

            review(acme, REVIEW_OPEN, ACME, PROJECT, "CASE", UNDER_REVIEW, "IN_REVIEW");
            askUser(acme, ACME, REVIEW_OPEN, LEAD, VIEWER);

            review(acme, REVIEW_ANSWERED, ACME, PROJECT, "CASE", ANSWERED, "IN_REVIEW");
            askUser(acme, ACME, REVIEW_ANSWERED, LEAD, VIEWER);
            spoke(acme, ACME, REVIEW_ANSWERED, LEAD, "CHANGES_REQUESTED");

            review(acme, REVIEW_CLOSED, ACME, PROJECT, "CASE", SIGNED_OFF, "APPROVED");
            askUser(acme, ACME, REVIEW_CLOSED, LEAD, VIEWER);

            review(acme, REVIEW_DELETED_SUBJECT, ACME, PROJECT, "CASE", GONE, "IN_REVIEW");
            askUser(acme, ACME, REVIEW_DELETED_SUBJECT, LEAD, VIEWER);

            // Asked of the team AND of LEAD by name — LEAD is owed one verdict, so one card.
            review(acme, REVIEW_SUITE, ACME, PROJECT, "SUITE", SUITE, "IN_REVIEW");
            askTeam(acme, ACME, REVIEW_SUITE, TEAM, VIEWER);
            askUser(acme, ACME, REVIEW_SUITE, LEAD, VIEWER);

            // §83's two Acme projects beside the board's own. Both hold one outstanding execution
            // assigned to LEAD; only the grant tells them apart, which is the point.
            // Display keys are unique per COMPANY, not per project (uq_test_case_key), so these
            // continue Acme's numbering rather than restarting at 1 the way Umbra's do.
            seedProjectDefaults(acme, ACME, SECOND_PROJECT);
            suite(acme, SECOND_SUITE, ACME, SECOND_PROJECT, "TS-2", "Root");
            testCase(acme, ELSEWHERE, ACME, SECOND_PROJECT, SECOND_SUITE, "TC-10", 10, LEAD);
            run(acme, SECOND_RUN, ACME, SECOND_PROJECT, "TR-3", 3, "IN_PROGRESS");
            execution(acme, ACME, SECOND_PROJECT, SECOND_RUN, ELSEWHERE, false, LEAD);

            seedProjectDefaults(acme, ACME, OTHER_PROJECT);
            suite(acme, SIBLING_SUITE, ACME, OTHER_PROJECT, "TS-3", "Root");
            testCase(acme, UNREACHABLE, ACME, OTHER_PROJECT, SIBLING_SUITE, "TC-9", 9, LEAD);
            run(acme, SIBLING_RUN, ACME, OTHER_PROJECT, "TR-4", 4, "IN_PROGRESS");
            execution(acme, ACME, OTHER_PROJECT, SIBLING_RUN, UNREACHABLE, false, LEAD);

            // §84: the same project, work owned by somebody who CAN see it and shares a team with
            // somebody who cannot. This row is the entire difference between the two bounds.
            testCase(acme, TEAM_ELSEWHERE, ACME, OTHER_PROJECT, SIBLING_SUITE, "TC-11", 11, TEAMMATE);
            execution(acme, ACME, OTHER_PROJECT, SIBLING_RUN, TEAM_ELSEWHERE, false, TEAMMATE);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "TS-1", "Root");
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, null);

            // Umbra's own quarantine, on a case keyed TC-1 exactly like Acme's — so only the
            // company predicate can keep it off Acme's board, never the key.
            flake(umbra, UMBRA, UMBRA_PROJECT, FOREIGN, "0.8000", LEAD, "Umbra's own decision");

            review(umbra, REVIEW_FOREIGN, UMBRA, UMBRA_PROJECT, "CASE", FOREIGN, "IN_REVIEW");
            askUser(umbra, UMBRA, REVIEW_FOREIGN, LEAD, null);

            // Umbra's team, with LEAD in it. team_membership.user_id references instance-level
            // app_user, so this inserts happily — §71's invisible-grant family — and what must keep
            // Acme's caller out is the company scoping on the lookup, not anybody's membership.
            team(umbra, UMBRA_TEAM, UMBRA, "Umbra Delivery");
            teamMember(umbra, UMBRA, UMBRA_TEAM, LEAD);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The response — the contract's shape, for both kinds of card")
    class Shape {

        @Test
        @DisplayName("every field the contract marks required is present on every card")
        void requiredFieldsArePresent() throws Exception {
            JsonNode board = boardAs(LEAD, PROJECT);

            assertThat(board).isNotEmpty();
            for (JsonNode card : board) {
                assertThat(List.of("id", "derived", "kind", "title", "status", "priority",
                        "overdue", "projectId", "projectKey", "allowedTransitions"))
                        .allSatisfy(field -> assertThat(card.has(field))
                                .as("WorkItem.%s is required by openapi.yaml, missing on %s",
                                        field, card.get("kind"))
                                .isTrue());
            }
        }

        @Test
        @DisplayName("status is a code from the contract's enum, never a display label")
        void statusIsAStableCode() throws Exception {
            // §53's whole finding on the frontend side: the SPA compared status to 'To do' and the
            // mock served 'To do', so mock mode agreed with itself while a real server answering
            // TODO would have filed every card into no column. This is that check on the server.
            //
            // The valid set is DERIVED from the enum rather than listed. It was listed until §54,
            // and the literal list is what would have quietly gone stale when BLOCKED was added —
            // a check whose expected values are maintained by hand stops checking the thing it was
            // written for the moment somebody forgets to widen it.
            List<String> columns = java.util.Arrays.stream(WorkItemStatus.values())
                    .map(Enum::name)
                    .toList();

            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                assertThat(card.get("status").asText()).isIn(columns);
            }
        }
    }

    @Nested
    @DisplayName("Derived cards — projections, and the rules about which rows become one")
    class Derived {

        @Test
        @DisplayName("an unrecorded execution in a live run is a card")
        void outstandingExecutionsAppear() throws Exception {
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION")).containsExactly("TC-1");
        }

        @Test
        @DisplayName("a recorded execution has left the board")
        void recordedExecutionsAreGone() throws Exception {
            // §6l: "execution completed → card leaves". Otherwise the board is an append-only log
            // of everything ever run, and it grows without bound while looking like a to-do list.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION"))
                    .as("TC-2 was recorded and is finished work")
                    .doesNotContain("TC-2");
        }

        @Test
        @DisplayName("an unrecorded execution in a CLOSED run is not resurrected")
        void skippedWorkStaysSkipped() throws Exception {
            // The cases nobody ran in a run that is over. They are a reporting fact (§52's suite
            // timeline counts them as notRun) and not outstanding work — on the board they would be
            // a backlog that can never be cleared, because the run they belong to is closed.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION"))
                    .as("TC-3's run is COMPLETED")
                    .doesNotContain("TC-3");
        }

        @Test
        @DisplayName("a case flagged in case_health is a maintenance card naming its signal")
        void flaggedCasesAppear() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "CASE_MAINTENANCE");

            assertThat(card.get("linkedKey").asText()).isEqualTo("TC-4");
            assertThat(card.get("description").asText())
                    .as("a card that hides its reason behind a click gets ignored in bulk")
                    .contains("NEVER_EXECUTED");
        }

        @Test
        @DisplayName("a derived card offers no transitions at all")
        void derivedCardsCannotBeMoved() throws Exception {
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                if (card.get("derived").asBoolean()) {
                    assertThat(card.get("allowedTransitions"))
                            .as("%s follows its source; offering a move that silently does nothing "
                                    + "is worse than offering none", card.get("id").asText())
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("a derived id is not a UUID, so it can never collide with a task's")
        void derivedIdsAreSynthetic() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "EXECUTION");
            assertThat(card.get("id").asText()).startsWith("execution:");
            assertThat(card.get("sourceType").asText()).isEqualTo("EXECUTION");
        }
    }

    @Nested
    @DisplayName("Review cards — the inbox §6g's apparatus did not have (§81)")
    class Reviews {

        @Test
        @DisplayName("a review I have been asked for is a card naming the artifact, not the review")
        void openRequestsAppear() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "REVIEW");

            assertThat(card.get("linkedKey").asText()).isEqualTo("TC-5");
            assertThat(card.get("title").asText()).isEqualTo("Case TC-5");
            // sourceType/sourceId are what the card OPENS, and a review has no URL of its own —
            // §6g's panel renders on the artifact's page. A sourceId naming the review would be an
            // id the SPA can do nothing with, on the one card whose purpose is to be clicked.
            assertThat(card.get("sourceType").asText()).isEqualTo("CASE");
            assertThat(card.get("sourceId").asText()).isEqualTo(UNDER_REVIEW.toString());
            assertThat(card.get("id").asText())
                    .isEqualTo("review:" + REVIEW_OPEN + ":" + LEAD);
            assertThat(card.get("status").asText()).isEqualTo("TODO");
        }

        @Test
        @DisplayName("the card says who asked, because a request with no asker is not a request")
        void theCardNamesTheAsker() throws Exception {
            assertThat(cardOfKind(boardAs(LEAD, PROJECT), "REVIEW").get("description").asText())
                    .isEqualTo("Review requested by V. Viewer (Acme)");
        }

        @Test
        @DisplayName("a review asked of a team is on every member's board, expanded at read time")
        void teamRequestsExpand() throws Exception {
            // §80 refused to expand a team when the request is WRITTEN, because membership moves.
            // The same fact makes expanding it on READ correct: somebody who joins tomorrow finds
            // the request waiting, and this is the only test that can tell the two apart, because
            // VIEWER was never named on any assignment row.
            assertThat(keysOfKind(boardAs(VIEWER, PROJECT, true), "REVIEW"))
                    .as("VIEWER is in the team the suite review was asked of")
                    .containsExactly("TS-1");
        }

        @Test
        @DisplayName("a suite review is a card, so the subject triple survives the expansion")
        void nonCaseSubjectsExpand() throws Exception {
            // The reason this generator lives in `work` at all: expanding (type, id) means reading
            // repository AND execution, which the module owning the review record may not do.
            JsonNode suiteCard = null;
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                if ("REVIEW".equals(card.get("kind").asText())
                        && "SUITE".equals(card.get("sourceType").asText())) {
                    suiteCard = card;
                }
            }

            assertThat(suiteCard).as("the suite review must produce a card").isNotNull();
            assertThat(suiteCard.get("title").asText()).isEqualTo("Root");
            assertThat(suiteCard.get("sourceId").asText()).isEqualTo(SUITE.toString());
        }

        @Test
        @DisplayName("being asked twice — by name and through a team — is still one card")
        void oneRequestPerPerson() throws Exception {
            // LEAD is named on the suite review AND is in the team it was asked of. Two cards would
            // be the board saying they owe two verdicts on one artifact.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT, true), "REVIEW"))
                    .filteredOn("TS-1"::equals)
                    .hasSize(1);
        }

        @Test
        @DisplayName("a review I have already answered has left the board")
        void answeredRequestsAreGone() throws Exception {
            // And "answered" means SPOKEN, not agreed: LEAD requested changes on TC-6, which is the
            // whole of what was asked of them — the ball is with the author. A card that stays up
            // until they approve makes "changes requested" read as work the reviewer still owes.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "REVIEW")).doesNotContain("TC-6");
        }

        @Test
        @DisplayName("a review that is over leaves no card, though the request row survives it")
        void closedReviewsAreGone() throws Exception {
            // Unassigning is the only thing that deletes a review_assignment row (§80), so every
            // request ever made is still in the table. Without the state predicate the board would
            // carry all of them, for ever.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "REVIEW")).doesNotContain("TC-7");
        }

        @Test
        @DisplayName("the whole set: only live, unanswered requests about artifacts that still exist")
        void onlyTheOutstandingOnesAreCards() throws Exception {
            // The exact set rather than three separate absences, and it is the only assertion that
            // can fail on the deleted-case arm: without the COALESCE guard that review joins to
            // three NULLs and produces a card with NO key and NO title, which every
            // doesNotContain("TC-8") in this class passes cleanly.
            //
            // Asserted on the PERSONAL board, and writing it against the project board is what
            // showed why: there, TS-1 appears TWICE and correctly — once for LEAD and once for
            // VIEWER, who is in the team it was asked of. "One card per reviewer" (§6l) means the
            // project board is a list of requests, not of reviews.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT, true), "REVIEW"))
                    .as("TC-5 is open and unanswered; TS-1 is the suite asked of the team LEAD is "
                            + "in. TC-6 was answered, TC-7 is approved, TC-8 is deleted.")
                    .containsExactlyInAnyOrder("TC-5", "TS-1");
        }

        @Test
        @DisplayName("the project board carries one card PER REVIEWER, not one per review")
        void oneCardPerReviewer() throws Exception {
            List<String> assignees = new ArrayList<>();
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                if ("REVIEW".equals(card.get("kind").asText())
                        && "TS-1".equals(card.get("linkedKey").asText())) {
                    assignees.add(card.get("assignee").get("id").asText());
                }
            }

            // §6l's words exactly: "Review requested → card for each reviewer". One card carrying
            // three names is not a queue entry for any of them — a personal board is filtered by
            // assignee, so a shared card would appear on nobody's.
            assertThat(assignees).containsExactlyInAnyOrder(LEAD.toString(), VIEWER.toString());
        }

        @Test
        @DisplayName("another company's request never reaches this board, member or not")
        void otherCompaniesStayOut() throws Exception {
            // Umbra asked LEAD, who is not an Umbra member — assignee_user_id references
            // instance-level app_user, so that row exists. What keeps it out is the company
            // predicate, which is the only thing that can.
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                assertThat(card.get("projectId").asText()).isEqualTo(PROJECT.toString());
            }
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "REVIEW"))
                    .as("Umbra's TC-1 is keyed identically to Acme's")
                    .doesNotContain("TC-1");
        }

        @Test
        @DisplayName("a review card cannot be moved — the sign-off has one door and this is not it")
        void reviewCardsAreNotMovable() throws Exception {
            String cardId = cardOfKind(boardAs(LEAD, PROJECT), "REVIEW").get("id").asText();

            // 409, not 404: the card is real and only its storage is not. §6l's eventual behaviour
            // is that dragging it to Done performs the approval — which is a gated act with an
            // If-Match behind it, so a drag would be a second, unguarded door to the sign-off.
            patchAs(LEAD, cardId, "DONE", 409);
        }
    }

    @Nested
    @DisplayName("Quarantine cards — §6l's fourth generator, silently empty until §121")
    class Quarantine {

        @Test
        @DisplayName("a quarantined case is a card naming who decided and why")
        void quarantinedCasesAreCards() throws Exception {
            JsonNode card = cardOfKind(boardAs(LEAD, PROJECT), "QUARANTINE", "TC-12");

            assertThat(card.get("linkedKey").asText()).isEqualTo("TC-12");
            assertThat(card.get("id").asText())
                    .as("a derived card has no row to have an id, and this prefix is what PATCH "
                            + "recognises it by")
                    .isEqualTo("quarantine:" + QUARANTINED);
            assertThat(card.get("sourceType").asText())
                    .as("sourceType is what the card OPENS, and a quarantine has no page of its "
                            + "own — the case is what a reader needs to reach")
                    .isEqualTo("CASE");
            assertThat(card.get("description").asText())
                    .as("a card that says a test is excluded and cannot say why or by whom is "
                            + "debt nobody can evaluate without reading the test (V38's comment)")
                    .contains("Flaky on Firefox")
                    .contains("Lead");
            assertThat(card.get("status").asText()).isEqualTo("TODO");
        }

        @Test
        @DisplayName("the whole set: live, quarantined cases in this company and nothing else")
        void onlyLiveQuarantinesAreCards() throws Exception {
            // The exact set rather than three absences, for §81's reason and one of its own: the
            // failure most available here is a card generated from the SCORE instead of the flag,
            // and TC-13 — high score, no decision — is the only row in the fixture that can fail
            // on it. TC-8 is quarantined and soft-deleted; Umbra's TC-1 is another company's.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "QUARANTINE"))
                    .containsExactlyInAnyOrder("TC-4", "TC-12");
        }

        @Test
        @DisplayName("a case that is both flagged and quarantined is two cards with two ids")
        void twoSourcesAreTwoCards() throws Exception {
            // TC-4 is in case_health AND in case_flake. Sharing the `case:` prefix would give both
            // cards the id `case:{TC-4}` — the API returns both, the board renders both, and the
            // SPA's list key collides silently on the one screen whose whole job is to be a list.
            List<String> ids = new ArrayList<>();
            for (JsonNode card : boardAs(LEAD, PROJECT)) {
                if ("TC-4".equals(card.path("linkedKey").asText())) {
                    ids.add(card.get("id").asText());
                }
            }

            assertThat(ids).containsExactlyInAnyOrder(
                    "case:" + FLAGGED, "quarantine:" + FLAGGED);
        }

        @Test
        @DisplayName("the card is the case OWNER's, not the decider's")
        void theDebtBelongsToTheOwner() throws Exception {
            // TC-12 is owned by VIEWER and was quarantined by LEAD. Assigning the card to whoever
            // quarantined the test would make pulling somebody else's flaky test out of the gate an
            // act that moves their work onto your own board — and the decider is named in the
            // sentence, which is where "who decided this" belongs.
            assertThat(keysOfKind(boardAs(VIEWER, PROJECT, true), "QUARANTINE"))
                    .containsExactly("TC-12");
            assertThat(keysOfKind(boardAs(LEAD, PROJECT, true), "QUARANTINE"))
                    .as("LEAD quarantined TC-12 and does not own it")
                    .doesNotContain("TC-12");
        }

        @Test
        @DisplayName("a quarantine card cannot be dragged — releasing a test is a gated write")
        void quarantineCardsAreNotMovable() throws Exception {
            String cardId =
                    cardOfKind(boardAs(LEAD, PROJECT), "QUARANTINE", "TC-12").get("id").asText();

            // 409, for the execution card's reason. And its own: releasing a test needs case:edit
            // and a reason field (§121), neither of which a drag has anywhere to collect.
            patchAs(LEAD, cardId, "DONE", 409);
        }
    }

    @Nested
    @DisplayName("Creating a task")
    class Creating {

        @Test
        @DisplayName("a created task starts in TODO and is not derived")
        void createsATask() throws Exception {
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Update the refund cases"}""".formatted(PROJECT), 201);

            assertThat(created.get("status").asText())
                    .as("a create that could choose its column is a move nobody recorded")
                    .isEqualTo("TODO");
            assertThat(created.get("derived").asBoolean()).isFalse();
            assertThat(created.get("kind").asText()).isEqualTo("TASK");
            // Every column but the one it is in. Sized from the enum rather than written as `4`,
            // for the reason the status check above now gives: a hand-maintained expectation is
            // the first thing to go stale when the enum grows, and it grew in this slice.
            assertThat(created.get("allowedTransitions"))
                    .hasSize(WorkItemStatus.values().length - 1);
        }

        @Test
        @DisplayName("kind is ignored — a client cannot materialise a derived item")
        void kindIsNotAnInput() throws Exception {
            // The front-door version of the invariant. A stored row claiming to be an EXECUTION owns
            // a status the execution cannot see, and the two diverge on the first result recorded.
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Sneaky","kind":"EXECUTION"}""".formatted(PROJECT), 201);

            assertThat(created.get("kind").asText()).isEqualTo("TASK");
            assertThat(created.get("derived").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a blank title is refused on its own field")
        void titleIsRequired() throws Exception {
            JsonNode problem = createAs(LEAD, """
                    {"projectId":"%s","title":"   "}""".formatted(PROJECT), 400);

            assertThat(problem.get("errors").has("title")).isTrue();
        }

        @Test
        @DisplayName("an assignee with no grant on the project is refused")
        void assigneeMustBeOnTheProject() throws Exception {
            // NO_GRANT is an ACTIVE member of Acme, so a membership check alone accepts them. The
            // card would render their name, show on their board, and 404 when they click it — and
            // the person who assigned it would never see anything wrong.
            JsonNode problem = createAs(LEAD, """
                    {"projectId":"%s","title":"For a stranger","assigneeId":"%s"}"""
                    .formatted(PROJECT, NO_GRANT), 400);

            assertThat(problem.get("errors").has("assigneeId")).isTrue();
        }

        @Test
        @DisplayName("an unassigned task is unassigned, not silently assigned to its author")
        void unassignedStaysUnassigned() throws Exception {
            // §6l's unclaimed pool. Defaulting to the author makes it unreachable, and the
            // correction is invisible once made because the author's own name looks plausible.
            JsonNode created = createAs(LEAD, """
                    {"projectId":"%s","title":"Nobody's yet"}""".formatted(PROJECT), 201);

            assertThat(created.hasNonNull("assignee")).isFalse();
        }
    }

    @Nested
    @DisplayName("Moving a card — the invariant, enforced on the server")
    class Moving {

        @Test
        @DisplayName("a task moves, and reports its new column")
        void aTaskMoves() throws Exception {
            String id = createAs(LEAD, """
                    {"projectId":"%s","title":"Movable"}""".formatted(PROJECT), 201)
                    .get("id").asText();

            JsonNode moved = patchAs(LEAD, id, "IN_PROGRESS", 200);

            assertThat(moved.get("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(moved.get("allowedTransitions"))
                    .as("the column it is now in is not a move it can make")
                    .noneSatisfy(t -> assertThat(t.asText()).isEqualTo("IN_PROGRESS"));
        }

        @Test
        @DisplayName("a derived card is refused with 409, not 404")
        void aDerivedCardCannotBeMoved() throws Exception {
            // THE test for this module. 404 would read as "that card is gone" and invite a reload
            // that brings it straight back; 409 says the request is impossible, not the resource
            // absent. The detail names the action that does work.
            String derivedId = cardOfKind(boardAs(LEAD, PROJECT), "EXECUTION").get("id").asText();

            JsonNode problem = patchAs(LEAD, derivedId, "DONE", 409);

            assertThat(problem.get("detail").asText())
                    .as("the refusal has to say where to go instead, or it is just a wall")
                    .contains("follows");
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIdsAreNotFound() throws Exception {
            patchAs(LEAD, UUID.randomUUID().toString(), "DONE", 404);
        }

        @Test
        @DisplayName("an unknown column is 400 and names the valid ones")
        void unknownColumnsAreRefused() throws Exception {
            // This test used to send "BLOCKED" as its unknown value, which stopped being unknown
            // in §54 — so it would have gone green for the wrong reason, asserting a 400 that was
            // now a successful move. Worth recording: a negative test whose input becomes valid
            // does not fail, it silently stops testing anything.
            String id = createAs(LEAD, """
                    {"projectId":"%s","title":"Movable"}""".formatted(PROJECT), 201)
                    .get("id").asText();

            JsonNode problem = patchAs(LEAD, id, "PARKED", 400);

            assertThat(problem.get("detail").asText())
                    .contains("TODO", "IN_PROGRESS", "BLOCKED", "IN_REVIEW", "DONE");
        }

        @Test
        @DisplayName("BLOCKED is a real column a task can be moved into and back out of")
        void blockedIsAColumn() throws Exception {
            // §53 left BLOCKED out of the enum on purpose because the board renders exactly the
            // columns the enum names, and a status with no column is a card that exists, is
            // returned by this API, and appears nowhere on screen. The value, the label and the
            // fifth grid column arrive together in §54; this is the server half.
            String id = createAs(LEAD, """
                    {"projectId":"%s","title":"Waiting on the API team"}""".formatted(PROJECT), 201)
                    .get("id").asText();

            assertThat(patchAs(LEAD, id, "BLOCKED", 200).get("status").asText()).isEqualTo("BLOCKED");
            // Out again, with no return-to-sender rule: the board is where people correct their own
            // record, and a board that argues is a board people stop updating.
            assertThat(patchAs(LEAD, id, "DONE", 200).get("status").asText()).isEqualTo("DONE");
        }
    }

    @Nested
    @DisplayName("Idempotency-Key — a retry must not leave two cards on the board")
    class Idempotency {

        @Test
        @DisplayName("the same key twice answers 200 with the task the first call made")
        void aRetryReplays() throws Exception {
            // 201 then 200, and the SAME id. Two identical cards in To do are two people picking up
            // the same work and the second one finding it already done — which is worse on a board
            // than anywhere else, because a coordination surface that misstates how much work there
            // is stops being used.
            String body = """
                    {"projectId":"%s","title":"Only once"}""".formatted(PROJECT);

            JsonNode first = createAs(LEAD, body, 201, "intent-54-a");
            JsonNode replay = createAs(LEAD, body, 200, "intent-54-a");

            assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
            assertThat(titlesOf(boardAs(LEAD, PROJECT)))
                    .filteredOn("Only once"::equals)
                    .as("the retry must not have left a second card")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a replay answers with the task it made even when the payload has drifted")
        void aReplayIgnoresTheNewPayload() throws Exception {
            // The client is asking "did my intent land", not "please apply this body". Validating
            // first would send it to fix fields on a card that already exists — CaseCreateService's
            // rule, and the reason the replay lookup runs before validation.
            JsonNode first = createAs(LEAD, """
                    {"projectId":"%s","title":"As typed"}""".formatted(PROJECT), 201, "intent-54-b");

            JsonNode replay = createAs(LEAD, """
                    {"projectId":"%s","title":"   "}""".formatted(PROJECT), 200, "intent-54-b");

            assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
            assertThat(replay.get("title").asText()).isEqualTo("As typed");
        }

        @Test
        @DisplayName("two different keys create two tasks")
        void differentKeysAreDifferentIntents() throws Exception {
            String body = """
                    {"projectId":"%s","title":"Twice on purpose"}""".formatted(PROJECT);

            JsonNode first = createAs(LEAD, body, 201, "intent-54-c");
            JsonNode second = createAs(LEAD, body, 201, "intent-54-d");

            assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        }

        @Test
        @DisplayName("a key the caller cannot reach the project with is 404, not a replay")
        void aReplayIsStillGated() throws Exception {
            // Recognising somebody else's key would confirm that something exists in a project the
            // caller may not know about — which is why the lookup runs inside the gate rather than
            // in front of it.
            createAs(LEAD, """
                    {"projectId":"%s","title":"Lead's"}""".formatted(PROJECT), 201, "intent-54-e");

            createAs(NO_GRANT, """
                    {"projectId":"%s","title":"Lead's"}""".formatted(PROJECT), 404, "intent-54-e");
        }

        @Test
        @DisplayName("an over-long key is refused as a field error, not indexed")
        void anOverLongKeyIsRefused() throws Exception {
            JsonNode problem = createAs(LEAD, """
                    {"projectId":"%s","title":"Hostile header"}""".formatted(PROJECT), 400,
                    "x".repeat(201));

            assertThat(problem.get("errors").has("Idempotency-Key")).isTrue();
        }

        @Test
        @DisplayName("no key at all still creates, every time")
        void theHeaderIsOptional() throws Exception {
            String body = """
                    {"projectId":"%s","title":"Unkeyed"}""".formatted(PROJECT);

            JsonNode first = createAs(LEAD, body, 201);
            JsonNode second = createAs(LEAD, body, 201);

            // The partial index is ON (created_idempotency_key IS NOT NULL) precisely so that two
            // unkeyed creates are not a uniqueness violation on NULL.
            assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        }
    }

    @Nested
    @DisplayName("Gates — the read gate is not the write gate")
    class Gates {

        @Test
        @DisplayName("a Viewer sees the board")
        void viewersCanRead() throws Exception {
            assertThat(boardAs(VIEWER, PROJECT)).isNotEmpty();
        }

        @Test
        @DisplayName("a Viewer cannot create a task — 403, not 404")
        void viewersCannotWrite() throws Exception {
            // The distinction that matters: they CAN see the project, so hiding it would be a lie.
            // 403 here and 404 for NO_GRANT below is the whole of §6t's rule on one endpoint.
            createAs(VIEWER, """
                    {"projectId":"%s","title":"Not mine to add"}""".formatted(PROJECT), 403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 on read and on write")
        void noGrantIsNotFound() throws Exception {
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));

            createAs(NO_GRANT, """
                    {"projectId":"%s","title":"Invisible"}""".formatted(PROJECT), 404);
        }

        @Test
        @DisplayName("a sibling project the caller was not granted is 404, not an empty board")
        void siblingProjectsAreNotReachable() throws Exception {
            // OTHER_PROJECT is in the same company, so RLS and the Hibernate filter both pass it.
            // Only the project grant keeps it out — persona 3's failure mode one level up.
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", OTHER_PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
        }

        @Test
        @DisplayName("another company's project is 404, indistinguishable from an absent one")
        void otherCompaniesAreNotFound() throws Exception {
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("projectId", UMBRA_PROJECT.toString())
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
        }
    }

    @Nested
    @DisplayName("mine=true — the personal board")
    class Mine {

        @Test
        @DisplayName("filters to the caller across stored and derived cards alike")
        void filtersBothKinds() throws Exception {
            createAs(LEAD, """
                    {"projectId":"%s","title":"Mine","assigneeId":"%s"}"""
                    .formatted(PROJECT, LEAD), 201);
            createAs(LEAD, """
                    {"projectId":"%s","title":"Nobody's"}""".formatted(PROJECT), 201);

            JsonNode mine = boardAs(LEAD, PROJECT, true);

            assertThat(mine).isNotEmpty();
            for (JsonNode card : mine) {
                assertThat(card.get("assignee").get("id").asText())
                        .as("a personal board showing somebody else's work is a leak of attention, "
                                + "and nothing would ever fail because of it")
                        .isEqualTo(LEAD.toString());
            }
            assertThat(titlesOf(mine)).doesNotContain("Nobody's");
        }
    }

    @Nested
    @DisplayName("My Work — the same board with no project named (§6l, §83)")
    class MyWork {

        @Test
        @DisplayName("aggregates the caller's work across every project they can see")
        void aggregatesAcrossProjects() throws Exception {
            // The one assertion a still-single-project implementation cannot pass. LEAD is granted
            // on PROJECT and SECOND_PROJECT and has an outstanding execution in each.
            assertThat(keysOfKind(myWork(LEAD), "EXECUTION"))
                    .containsExactlyInAnyOrder("TC-1", "TC-10");
        }

        @Test
        @DisplayName("work in a project the caller was never granted stays off it")
        void ungrantedProjectsContributeNothing() throws Exception {
            // TC-9's execution is assigned to LEAD, in Acme, in a live run — everything except the
            // grant. An aggregate built from "cards assigned to me" instead of "projects I can see"
            // renders it perfectly: its title, its key, and the name of a client nobody let them
            // near. The per-project board answers 404 for this project; the personal board has no
            // 404 to answer, so the bound has to be the project set it assembles from.
            assertThat(titlesOf(myWork(LEAD))).doesNotContain("Case TC-9");
            assertThat(keysOfKind(myWork(LEAD), "EXECUTION")).doesNotContain("TC-9");
        }

        @Test
        @DisplayName("another company's request for the same person is not on it")
        void otherCompaniesNeverAggregate() throws Exception {
            // REVIEW_FOREIGN asks LEAD — an instance-level app_user id — to review an Umbra case,
            // which inserts happily (§64's family). The session's company is what keeps it out, and
            // a cross-project read is exactly where a company predicate is easiest to lose.
            JsonNode board = myWork(LEAD);

            assertThat(keysOfKind(board, "REVIEW")).doesNotContain("TC-1");
            for (JsonNode card : board) {
                assertThat(card.get("projectKey").asText())
                        .as("BRDU is Umbra's project key")
                        .isNotEqualTo("BRDU");
            }
        }

        @Test
        @DisplayName("every card on it is assigned to the caller, in both kinds")
        void isPersonal() throws Exception {
            createAs(LEAD, """
                    {"projectId":"%s","title":"Somebody else's"}""".formatted(PROJECT), 201);

            JsonNode board = myWork(LEAD);

            assertThat(board).isNotEmpty();
            for (JsonNode card : board) {
                assertThat(card.get("assignee").get("id").asText()).isEqualTo(LEAD.toString());
            }
            assertThat(titlesOf(board)).doesNotContain("Somebody else's");
        }

        @Test
        @DisplayName("each card names its own project, which is how an aggregate can be grouped")
        void cardsCarryTheirProject() throws Exception {
            // projectId/projectKey were on the card before there was a board that needed them: with
            // one project in the answer they were a nicety, and here they are the only thing that
            // says where a card comes from. A generator reading the REQUEST's project instead of the
            // ROW's would label every card with whichever project happened to be first.
            for (JsonNode card : myWork(LEAD)) {
                String expected = card.get("projectId").asText().equals(SECOND_PROJECT.toString())
                        ? "BRDC"
                        : "BRDA";
                assertThat(card.get("projectKey").asText()).isEqualTo(expected);
            }
            assertThat(projectKeysOf(myWork(LEAD))).contains("BRDA", "BRDC");
        }

        @Test
        @DisplayName("a member with no grants at all gets an empty board, not a 404")
        void noGrantsIsAnEmptyBoard() throws Exception {
            // The project board's 404 says "you cannot see THAT" — this request names nothing to be
            // unable to see. It is also the empty-IN() path: with no visible project there is no set
            // to query with, and `IN ()` is a syntax error rather than a predicate matching nothing.
            var response = mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .param("mine", "true")
                            .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(json.readTree(response.getContentAsString())).isEmpty();
        }

        @Test
        @DisplayName("no project and no mine is refused — 400, because it is not a board")
        void unscopedIsRefused() throws Exception {
            // Refused rather than narrowed: forcing mine=true on would answer a request nobody made
            // with a list the client cannot tell apart from a company holding only its own work.
            var response = mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString())
                    .as("the message must name the two requests that do work")
                    .contains("projectId")
                    .contains("mine=true");
        }

        @Test
        @DisplayName("the project board still answers only its own project")
        void theProjectBoardDidNotWiden() throws Exception {
            // The regression the widening invites: one query per fact over a SET of projects is one
            // edit away from a project board that returns the caller's whole company.
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION")).containsExactly("TC-1");
        }
    }

    @Nested
    @DisplayName("The team board — the third of §6l's three, and the last one without an endpoint")
    class TeamBoard {

        @Test
        @DisplayName("shows a team-mate's work, which is what read-time expansion means")
        void expandsTheTeam() throws Exception {
            // VIEWER is on DEV_TEAM and is not the caller. Their suite-review card is the fixture's
            // own proof: nothing in this test writes a membership row, so an implementation that
            // materialised the team at any earlier moment cannot produce it.
            createAs(LEAD, """
                    {"projectId":"%s","title":"Viewer's task","assigneeId":"%s"}"""
                    .formatted(PROJECT, VIEWER), 201);

            assertThat(titlesOf(teamBoard(LEAD, DEV_TEAM))).contains("Viewer's task");
        }

        @Test
        @DisplayName("a person outside the team contributes nothing, however visible their work")
        void nonMembersAreNotOnIt() throws Exception {
            // OUTSIDER holds a grant on PROJECT, so this card is on the project board and on
            // OUTSIDER's own. A team board that filtered on "anything in the projects the team can
            // see" rather than on the members themselves would carry it.
            createAs(LEAD, """
                    {"projectId":"%s","title":"Outsider's task","assigneeId":"%s"}"""
                    .formatted(PROJECT, OUTSIDER), 201);

            assertThat(titlesOf(teamBoard(LEAD, DEV_TEAM))).doesNotContain("Outsider's task");
        }

        @Test
        @DisplayName("a team-mate's work in a project the CALLER cannot open stays off it")
        void boundedByTheCallersVisibility() throws Exception {
            // THE test of this slice. TC-11 is TEAMMATE's outstanding execution in OTHER_PROJECT:
            // TEAMMATE is granted there, is on DEV_TEAM, and the run is live — everything a team
            // board built from "cards assigned to this team's members" needs to render it, and LEAD
            // holds no grant on that project at all. §83's leak one step out, and the one an
            // implementation reaching for a single query lands on.
            assertThat(keysOfKind(teamBoard(LEAD, DEV_TEAM), "EXECUTION"))
                    .as("TC-11 is a team-mate's, in a project the reader was never granted")
                    .containsExactlyInAnyOrder("TC-1", "TC-10");
            assertThat(projectKeysOf(teamBoard(LEAD, DEV_TEAM)))
                    .as("BRDB is the sibling project nobody granted LEAD")
                    .doesNotContain("BRDB");
        }

        @Test
        @DisplayName("the same team, two readers, two different boards — and that is the design")
        void theBoardIsTheReadersNotTheTeams() throws Exception {
            // The other half of the test above, and what stops it being vacuous: TC-11 is a real,
            // renderable card that the bound is hiding rather than a row that does not exist.
            // TEAMMATE sees OTHER_PROJECT and nothing else, so their view of DEV_TEAM is exactly
            // the two cards LEAD's view cannot contain — TC-9 among them, because LEAD is a member
            // of the team and has work in a project only TEAMMATE can open.
            assertThat(keysOfKind(teamBoard(TEAMMATE, DEV_TEAM), "EXECUTION"))
                    .containsExactlyInAnyOrder("TC-9", "TC-11");
        }

        @Test
        @DisplayName("the whole set, per kind — an aggregate of the team's work across two projects")
        void theWholeSet() throws Exception {
            JsonNode board = teamBoard(LEAD, DEV_TEAM);

            // Exact sets rather than absences, for §81's reason: the failure this class is most
            // exposed to is a card with no key and no title, which every doesNotContain passes.
            assertThat(keysOfKind(board, "EXECUTION")).containsExactlyInAnyOrder("TC-1", "TC-10");
            assertThat(keysOfKind(board, "CASE_MAINTENANCE")).containsExactly("TC-4");
            assertThat(keysOfKind(board, "REVIEW"))
                    .as("TC-5 is LEAD's; TS-1 was asked of a team LEAD and VIEWER are both on, and "
                            + "one card per reviewer means it appears twice")
                    .containsExactlyInAnyOrder("TC-5", "TS-1", "TS-1");
        }

        @Test
        @DisplayName("every card belongs to a member — nobody else's work rides along")
        void everyCardBelongsToAMember() throws Exception {
            JsonNode board = teamBoard(LEAD, DEV_TEAM);

            assertThat(board).isNotEmpty();
            for (JsonNode card : board) {
                assertThat(card.get("assignee").get("id").asText())
                        .isIn(LEAD.toString(), VIEWER.toString(), TEAMMATE.toString());
            }
        }

        @Test
        @DisplayName("with a projectId it narrows to that project — §6l's swimlanes-by-member board")
        void narrowsToOneProject() throws Exception {
            // The two parameters bound different axes, who and where, so their conjunction is a
            // request with an obvious answer rather than two boards ANDed together.
            assertThat(keysOfKind(teamBoard(LEAD, DEV_TEAM, PROJECT), "EXECUTION"))
                    .containsExactly("TC-1");
        }

        @Test
        @DisplayName("a project the caller cannot reach is still 404, team or no team")
        void theProjectGateStillApplies() throws Exception {
            // TEAMMATE can see OTHER_PROJECT and is on the team; that must not turn LEAD's request
            // for it into a 200. The team narrows the answer, it never widens the gate.
            assertThat(statusOf(request(LEAD, DEV_TEAM, OTHER_PROJECT, null))).isEqualTo(404);
        }

        @Test
        @DisplayName("a team that does not exist is 404")
        void unknownTeamsAreNotFound() throws Exception {
            assertThat(statusOf(request(LEAD, UNKNOWN_TEAM, null, null))).isEqualTo(404);
        }

        @Test
        @DisplayName("another company's team is the same 404 — not an existence oracle")
        void otherCompaniesTeamsAreNotFound() throws Exception {
            // LEAD is even a member of UMBRA_TEAM at the instance level (team_membership.user_id
            // references app_user). If this answered anything but 404, the endpoint would let any
            // session confirm whether a guessed UUID names a team in some other client's company —
            // §6t's rule, and the reason the lookup is company-scoped rather than by id alone.
            assertThat(statusOf(request(LEAD, UMBRA_TEAM, null, null))).isEqualTo(404);
        }

        @Test
        @DisplayName("a team with no members is a reachable, empty board — 200 [], never 404")
        void emptyTeamsAreReachable() throws Exception {
            var response = request(LEAD, LONELY_TEAM, null, null);

            assertThat(statusOf(response)).isEqualTo(200);
            assertThat(json.readTree(response.getContentAsString())).isEmpty();
        }

        @Test
        @DisplayName("an empty team plus an unreachable project is still 404, not an empty board")
        void theEmptyShortcutCannotSwallowThe404() throws Exception {
            // The ordering trap. Answering an empty team before the project gate runs is the
            // obvious optimisation — there is nothing to assemble either way — and it turns this
            // request into a 200 that confirms OTHER_PROJECT exists, to a caller with no grant on
            // it. Hence the shortcut lives inside the gated read rather than in front of it.
            assertThat(statusOf(request(LEAD, LONELY_TEAM, OTHER_PROJECT, null))).isEqualTo(404);
        }

        @Test
        @DisplayName("a team whose members have no visible work is the same 200 []")
        void membersWithNoWorkAreTheSameAnswer() throws Exception {
            // Indistinguishable from the row above on purpose: both are "this team has nothing for
            // you", and a client able to tell them apart would be reading team membership out of a
            // board it was never granted.
            var response = request(LEAD, QUIET_TEAM, null, null);

            assertThat(statusOf(response)).isEqualTo(200);
            assertThat(json.readTree(response.getContentAsString())).isEmpty();
        }

        @Test
        @DisplayName("teamId with mine=true is refused — they select two different boards")
        void twoBoardsAtOnceAreRefused() throws Exception {
            var response = request(LEAD, DEV_TEAM, null, true);

            assertThat(statusOf(response)).isEqualTo(400);
            assertThat(response.getContentAsString())
                    .as("the message must name both, and say the two do not combine")
                    .contains("teamId")
                    .contains("mine=true");
        }

        @Test
        @DisplayName("a Viewer may open a team board — reading it needs no key nobody holds")
        void readingNeedsNoNewPermission() throws Exception {
            // No permission key was invented for this board and none is asked for: every card on it
            // is one the caller could already see on a project board. A key seeded to nobody would
            // be a 403 no administrator could grant their way out of (§67/§79/§80).
            assertThat(statusOf(request(VIEWER, DEV_TEAM, null, null))).isEqualTo(200);
        }

        @Test
        @DisplayName("a caller outside the team may still open its board")
        void membershipIsNotRequiredToRead() throws Exception {
            // §6k: the org chart is readable by any member of the company. A team board its lead's
            // manager cannot open is a board for nobody — and the work on it is bounded by the
            // reader's own grants either way, which is what makes this safe rather than lax.
            var response = request(OUTSIDER, DEV_TEAM, null, null);

            assertThat(statusOf(response)).isEqualTo(200);
        }

        @Test
        @DisplayName("the personal and project boards did not change shape")
        void theOtherTwoBoardsAreUntouched() throws Exception {
            // The regression a shared filter invites: `present` now takes a set, and the personal
            // board is the one-element case of it.
            assertThat(keysOfKind(myWork(LEAD), "EXECUTION"))
                    .containsExactlyInAnyOrder("TC-1", "TC-10");
            assertThat(keysOfKind(boardAs(LEAD, PROJECT), "EXECUTION")).containsExactly("TC-1");
        }
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================

    private JsonNode teamBoard(UUID userId, UUID teamId) throws Exception {
        return teamBoard(userId, teamId, null);
    }

    private JsonNode teamBoard(UUID userId, UUID teamId, UUID projectId) throws Exception {
        var response = request(userId, teamId, projectId, null);
        assertThat(statusOf(response)).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private org.springframework.mock.web.MockHttpServletResponse request(
            UUID userId, UUID teamId, UUID projectId, Boolean mine) throws Exception {

        var request = MockMvcRequestBuilders.get("/api/v1/work-items")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME));
        if (teamId != null) {
            request = request.param("teamId", teamId.toString());
        }
        if (projectId != null) {
            request = request.param("projectId", projectId.toString());
        }
        if (mine != null) {
            request = request.param("mine", String.valueOf(mine));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private static int statusOf(org.springframework.mock.web.MockHttpServletResponse response) {
        return response.getStatus();
    }

    private JsonNode myWork(UUID userId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                        .param("mine", "true")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode boardAs(UUID userId, UUID projectId) throws Exception {
        return boardAs(userId, projectId, false);
    }

    private JsonNode boardAs(UUID userId, UUID projectId, boolean mine) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/v1/work-items")
                        .param("projectId", projectId.toString())
                        .param("mine", String.valueOf(mine))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode createAs(UUID userId, String body, int expectedStatus) throws Exception {
        return createAs(userId, body, expectedStatus, null);
    }

    private JsonNode createAs(UUID userId, String body, int expectedStatus, String idempotencyKey)
            throws Exception {

        var request = MockMvcRequestBuilders.post("/api/v1/work-items")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }

        var response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        String content = response.getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private JsonNode patchAs(UUID userId, String cardId, String status, int expectedStatus)
            throws Exception {

        var response = mvc.perform(MockMvcRequestBuilders.patch("/api/v1/work-items/" + cardId)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        String content = response.getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private static List<String> keysOfKind(JsonNode board, String kind) {
        List<String> keys = new ArrayList<>();
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())) {
                keys.add(card.get("linkedKey").asText());
            }
        }
        return keys;
    }

    /**
     * The one card of this kind about this artifact.
     *
     * <p>{@link #cardOfKind} takes the <em>first</em> card of a kind, which was unambiguous while
     * every generator produced at most one card per board. §121's fixture has two quarantine cards
     * on purpose, and the board's sort is by id — so "the first QUARANTINE card" is whichever UUID
     * sorts lower, which is not a thing any test means.
     */
    private static JsonNode cardOfKind(JsonNode board, String kind, String linkedKey) {
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())
                    && linkedKey.equals(card.path("linkedKey").asText())) {
                return card;
            }
        }
        throw new AssertionError("no " + kind + " card for " + linkedKey + " on the board");
    }

    private static JsonNode cardOfKind(JsonNode board, String kind) {
        for (JsonNode card : board) {
            if (kind.equals(card.get("kind").asText())) {
                return card;
            }
        }
        throw new AssertionError("no card of kind " + kind + " on the board");
    }

    private static List<String> projectKeysOf(JsonNode board) {
        List<String> keys = new ArrayList<>();
        board.forEach(card -> keys.add(card.get("projectKey").asText()));
        return keys;
    }

    private static List<String> titlesOf(JsonNode board) {
        List<String> titles = new ArrayList<>();
        board.forEach(card -> titles.add(card.get("title").asText()));
        return titles;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // Fixture plumbing
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
        UUID id = UUID.nameUUIDFromBytes(("board:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // review_assignment and review_participation before review, and both before the
                // subject tables: the FKs cascade from review, but nothing cascades from a case to
                // the review of it — the subject triple has no FK at all, which is what lets one
                // record serve three artifacts (§76).
                for (String table : List.of("work_item_link", "work_item", "case_health",
                        "case_flake",
                        "step_result", "test_execution", "test_run", "review_assignment",
                        "review_participation", "review_comment", "review", "team_membership",
                        "team", "test_case_version", "test_case", "suite")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 1, ?, 0, 'APPROVED', NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, UUID ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, owner_id, created_at,
                     updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', CAST(? AS uuid), now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, ownerId == null ? null : ownerId.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, "Run " + key);
            statement.setString(7, status);
            statement.setString(8, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, boolean recorded, UUID assigneeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     assignee_id, executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        CASE WHEN ? THEN (SELECT s.id FROM case_status s
                                           WHERE s.project_id = CAST(? AS uuid) LIMIT 1)
                             ELSE NULL END,
                        CAST(? AS uuid),
                        CASE WHEN ? THEN now() ELSE NULL END,
                        now(), now())
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setBoolean(6, recorded);
            statement.setString(7, projectId.toString());
            statement.setString(8, assigneeId == null ? null : assigneeId.toString());
            statement.setBoolean(9, recorded);
            statement.executeUpdate();
        }
    }

    private void softDelete(Connection connection, UUID caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void team(Connection connection, UUID id, UUID companyId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team (id, company_id, name) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private void teamMember(Connection connection, UUID companyId, UUID teamId, UUID userId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_membership (company_id, team_id, user_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                ON CONFLICT (team_id, user_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, userId.toString());
            statement.executeUpdate();
        }
    }

    private void review(Connection connection, UUID id, UUID companyId, UUID projectId,
            String subjectType, UUID subjectId, String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review
                    (id, company_id, project_id, subject_type, subject_id, subject_version_no,
                     state, created_by, created_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), 1,
                        ?, CAST(? AS uuid), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, subjectType);
            statement.setString(5, subjectId.toString());
            statement.setString(6, state);
            statement.setString(7, VIEWER.toString());
            statement.executeUpdate();
        }
    }

    /** @param askedBy nullable — an offboarded asker is a real state, and the card must survive it. */
    private void askUser(Connection connection, UUID companyId, UUID reviewId, UUID userId,
            UUID askedBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review_assignment
                    (id, company_id, review_id, assignee_user_id, assigned_by)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid))
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, reviewId.toString());
            statement.setString(3, userId.toString());
            statement.setString(4, askedBy == null ? null : askedBy.toString());
            statement.executeUpdate();
        }
    }

    private void askTeam(Connection connection, UUID companyId, UUID reviewId, UUID teamId,
            UUID askedBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review_assignment
                    (id, company_id, review_id, assignee_team_id, assigned_by)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid))
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, reviewId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, askedBy == null ? null : askedBy.toString());
            statement.executeUpdate();
        }
    }

    private void spoke(Connection connection, UUID companyId, UUID reviewId, UUID userId,
            String verdict) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review_participation
                    (id, company_id, review_id, user_id, kind, verdict, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        'PEER_REVIEW', ?, now())
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, reviewId.toString());
            statement.setString(3, userId.toString());
            statement.setString(4, verdict);
            statement.executeUpdate();
        }
    }

    /**
     * A {@code case_flake} row — evidence always, a quarantine only when {@code by} is given.
     *
     * <p>The four authored columns are set together or not at all, which is not this helper being
     * tidy: {@code case_flake_quarantine_complete} in {@code V38} refuses any other combination, so
     * a two-argument version of this could not compile a passing fixture. §121 records the
     * constraint catching its own first draft, which quarantined with a null
     * {@code quarantined_by}.
     *
     * @param by     null for a row that carries evidence and no decision — the one case the
     *               generator must not turn into a card
     * @param reason must be present exactly when {@code by} is
     */
    private void flake(Connection connection, UUID companyId, UUID projectId, UUID caseId,
            String score, UUID by, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_flake
                    (case_id, company_id, project_id, executions, transitions, alternations, cells,
                     flake_score, computed_at, quarantined, quarantined_at, quarantined_by,
                     quarantine_reason)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 8, 6, 3, 2,
                        CAST(? AS numeric), now(), ?,
                        CASE WHEN ? THEN now() END, CAST(? AS uuid), ?)
                ON CONFLICT (case_id) DO NOTHING
                """)) {
            statement.setString(1, caseId.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, score);
            statement.setBoolean(5, by != null);
            statement.setBoolean(6, by != null);
            statement.setString(7, by == null ? null : by.toString());
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private void health(Connection connection, UUID companyId, UUID projectId, UUID caseId,
            String signalColumn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_health (case_id, company_id, project_id, %s, computed_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), true, now())
                ON CONFLICT (case_id) DO NOTHING
                """.formatted(signalColumn))) {
            statement.setString(1, caseId.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.executeUpdate();
        }
    }
}
