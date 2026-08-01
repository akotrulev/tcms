package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code DELETE /users/{userId}} — offboarding a colleague (§56), the other half of §55.
 *
 * <h2>The four failures this exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The session that outlives the removal.</strong> A signed token is valid until it
 *       expires and nothing in the request path re-reads membership, so a removal that does not
 *       bump the {@code pv} epoch leaves the person authenticated inside the company for hours.
 *       {@link Revocation} drives their real token before and after.</li>
 *   <li><strong>The link that outlives the removal.</strong> Removing somebody who has not
 *       accepted leaves a live invitation row; a later re-invite finds it outstanding, mints
 *       nothing, and re-admits them on a token issued before they were offboarded. The mirror of
 *       §55's dead link — there the status killed a live token, here the token survives the
 *       status. {@link Pending} asserts the row is gone and the next invitation is a new one.</li>
 *   <li><strong>Offboarding by deletion.</strong> Their grants must go inert rather than away, or
 *       "removed" stops being one status update and re-inviting a returning contractor silently
 *       loses their project access. {@link Removing} asserts the rows survive <em>and</em> that
 *       they convey nothing.</li>
 *   <li><strong>The company with no administrator.</strong> Self-removal is refused, which is the
 *       whole of that rule — see {@link MembershipRemovalService}. {@link SelfRemoval} also pins
 *       the order: an ordinary member naming themselves is a 403, not a 400, because the gate runs
 *       before the target is looked at.</li>
 * </ol>
 *
 * <h2>Nothing here is mocked</h2>
 *
 * <p>Unlike {@code UserInvitationEndpointTest}, which needs the mail message to obtain a link this
 * suite otherwise cannot see. The invitation assertions below are about <em>rows</em> — deleted,
 * and replaced by a different one — which is observable without the secret, so this class does not
 * take that precedent any further (§55).
 *
 * <p>These tests write through HTTP and therefore commit; nothing rolls back between them, so
 * everybody who gets removed is minted per test by {@link #joiner} / {@link #invitee}. The seeded
 * fixture is limited to rows no test mutates. Ids and slugs are unique to this class
 * ({@code 2d…}, {@code rm-}) — §19's collision defect, four times now.
 */
@AutoConfigureMockMvc
@DisplayName("Removing a person from the company")
class UserRemovalEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("2d000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("2d000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("2d001000-0000-4000-8000-000000000001");

    /** Company Admin in Acme — holds {@code company:members}. The caller in most tests. */
    private static final UUID ADMIN = UUID.fromString("2d003000-0000-4000-8000-000000000001");
    /** A second Company Admin, so one of them can be removed by the other. */
    private static final UUID DEPUTY = UUID.fromString("2d003000-0000-4000-8000-000000000002");
    /** Test Lead on PROJECT: holds {@code project:members} and not {@code company:members}. */
    private static final UUID LEAD = UUID.fromString("2d003000-0000-4000-8000-000000000003");
    /** An ordinary active member of Acme, never removed by any test. */
    private static final UUID MEMBER = UUID.fromString("2d003000-0000-4000-8000-000000000004");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

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
    private MembershipInvitationRepository invitations;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rm-acme", "Acme", now);
            company(UMBRA, "rm-umbra", "Umbra", now);
            activeUser(ADMIN, "admin@rm-acme.test", "Ada Admin", now);
            activeUser(DEPUTY, "deputy@rm-acme.test", "Dev Deputy", now);
            activeUser(LEAD, "lead@rm-acme.test", "Leonora Lead", now);
            activeUser(MEMBER, "member@rm-acme.test", "Mo Member", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "RMA", "Removal Project", now);
            member(ACME, ADMIN, "A. Admin (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, DEPUTY, "D. Deputy (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, LEAD, "L. Lead (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, MEMBER, "M. Member (Acme)", now, MembershipStatus.ACTIVE);

            grant("admin", RoleAssignment.forUserOnCompany(
                    id("admin"), ACME, Role.COMPANY_ADMIN_ID, ADMIN, null, now));
            grant("deputy", RoleAssignment.forUserOnCompany(
                    id("deputy"), ACME, Role.COMPANY_ADMIN_ID, DEPUTY, null, now));
            grant("lead", RoleAssignment.forUserOnProject(
                    id("lead"), ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, null, now));
        }));
    }

    // =====================================================================

    @Nested
    @DisplayName("The gate — company:members, and nothing weaker")
    class Gate {

        @Test
        @DisplayName("a Company Admin may remove")
        void companyAdminsMayRemove() throws Exception {
            assertThat(remove(ADMIN, joiner()).getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        @DisplayName("a Test Lead may not — project:members staffs a project, it does not offboard")
        void projectMembersIsNotEnough() throws Exception {
            // The same one-word distinction the invite gate turns on, in the direction that costs
            // more: a Test Lead who could remove could empty the client's directory from a screen
            // scoped to one engagement.
            assertThat(remove(LEAD, joiner()).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("an ordinary member may not")
        void membersMayNotRemove() throws Exception {
            assertThat(remove(MEMBER, joiner()).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a refused caller changes nothing — not the membership, not the session")
        void refusalIsInert() throws Exception {
            UUID target = joiner();
            int before = permissionVersionOf(target);

            remove(LEAD, target);

            assertThat(statusOf(ACME, target)).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(permissionVersionOf(target))
                    .as("a refused removal signed somebody out")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("no session at all is 401")
        void anonymousIsRejected() throws Exception {
            int status = mvc.perform(
                            MockMvcRequestBuilders.delete("/api/v1/users/" + joiner()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Removing an active member")
    class Removing {

        @Test
        @DisplayName("204, and they are gone from the directory")
        void theyLeaveTheDirectory() throws Exception {
            UUID target = joiner();

            assertThat(remove(ADMIN, target).getResponse().getStatus()).isEqualTo(204);

            assertThat(statusOf(ACME, target)).isEqualTo(MembershipStatus.REMOVED);
            assertThat(directoryIds()).doesNotContain(target);
        }

        @Test
        @DisplayName("the account survives — one company's admin does not offboard the instance")
        void theAccountIsUntouched() throws Exception {
            UUID target = joiner();

            remove(ADMIN, target);

            // app_user is instance-level (§6s). Deleting it here would remove the person from
            // every other client on this instance and orphan the authorship Acme's own history
            // depends on.
            AppUser account = tx.execute(status -> users.findById(target).orElse(null));
            assertThat(account).isNotNull();
            assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("their grants stay, and convey nothing")
        void grantsGoInertRatherThanAway() throws Exception {
            UUID target = joiner();
            grantViewerOnProject(target);
            assertThat(projectPermissionsOf(target)).isNotEmpty();

            remove(ADMIN, target);

            // Both halves matter. Deleting the rows would make offboarding a hunt across grants
            // and would silently drop a returning contractor's project access; leaving them
            // effective would make "removed" mean "still working here".
            assertThat(grantsFor(target))
                    .as("offboarding deleted grants instead of making them inert")
                    .isNotEmpty();
            assertThat(projectPermissionsOf(target))
                    .as("a removed member still holds permissions in this company")
                    .isEmpty();
        }

        @Test
        @DisplayName("removing somebody who is already removed is 204 and audits nothing twice")
        void removalIsIdempotent() throws Exception {
            UUID target = joiner();
            remove(ADMIN, target);

            assertThat(remove(ADMIN, target).getResponse().getStatus()).isEqualTo(204);

            assertThat(removalAuditsFor(target))
                    .as("a repeated click buried the removal that mattered under one that did nothing")
                    .hasSize(1);
        }

        @Test
        @DisplayName("an id that names nobody here is 204, and another company's member is untouched")
        void anUnknownIdIsNotAnError() throws Exception {
            UUID elsewhere = umbraOnlyMember();

            assertThat(remove(ADMIN, elsewhere).getResponse().getStatus()).isEqualTo(204);

            // Isolation and idempotence give the same answer, which is the one §6t wants: a
            // Company Admin cannot tell a non-member from a member of a company they cannot see.
            assertThat(statusOf(UMBRA, elsewhere)).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("the removal is audited once, with who left and what they had")
        void theRemovalIsAudited() throws Exception {
            UUID target = joiner();

            remove(ADMIN, target);

            List<AuditEvent> recorded = removalAuditsFor(target);
            assertThat(recorded).hasSize(1);
            AuditEvent event = recorded.getFirst();
            assertThat(event.getActorUserId()).isEqualTo(ADMIN);
            assertThat(event.getTargetIds()).containsExactly(target);
            // Joining a company belongs to the company; inventing a project to fill the column
            // would file a departure under one client engagement.
            assertThat(event.getProjectId()).isNull();

            JsonNode changes = json.readTree(event.getChanges());
            // The display name is recorded because the directory no longer shows it — an audit row
            // naming only a uuid answers "who left?" with a lookup the reader cannot make.
            assertThat(changes.get("displayName").asText()).isEqualTo("J. Joiner (Acme)");
            assertThat(changes.get("wasPending").asBoolean()).isFalse();
            assertThat(changes.get("invitationCancelled").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("The session — revoked, not left to expire")
    class Revocation {

        @Test
        @DisplayName("their existing token stops working on the next request")
        void theSessionIsRevoked() throws Exception {
            UUID target = joiner();
            String theirToken = tokenFor(target, ACME);
            assertThat(statusUsing(theirToken, "/api/v1/me/permissions"))
                    .as("the fixture's token does not work, so the assertion below proves nothing")
                    .isEqualTo(200);

            remove(ADMIN, target);

            // The defect this catches is silent: their permissions resolve empty either way, so
            // every screen looks broken rather than closed, and the session itself — inside a
            // company they are no longer in — survives until the token lapses.
            assertThat(statusUsing(theirToken, "/api/v1/me/permissions")).isEqualTo(401);
        }

        @Test
        @DisplayName("the epoch moves exactly once, so a repeat does not sign them out again")
        void theEpochMovesOnlyOnARealRemoval() throws Exception {
            UUID target = joiner();
            int before = permissionVersionOf(target);

            remove(ADMIN, target);
            int afterFirst = permissionVersionOf(target);
            remove(ADMIN, target);

            assertThat(afterFirst).isEqualTo(before + 1);
            assertThat(permissionVersionOf(target)).isEqualTo(afterFirst);
        }
    }

    @Nested
    @DisplayName("Removing somebody who never accepted")
    class Pending {

        @Test
        @DisplayName("the outstanding invitation is cancelled")
        void theLinkIsCancelled() throws Exception {
            UUID target = invitee();
            assertThat(outstandingInvitationIdFor(target)).isNotNull();

            remove(ADMIN, target);

            assertThat(outstandingInvitationIdFor(target))
                    .as("a live activation link survived the offboarding")
                    .isNull();
        }

        @Test
        @DisplayName("re-inviting them mints a NEW link rather than reviving the old one")
        void reInvitingMintsFresh() throws Exception {
            UUID target = invitee();
            UUID firstInvitation = outstandingInvitationIdFor(target);

            remove(ADMIN, target);
            MvcResult reInvited = invite(ADMIN, emailOf(target), "J. Joiner (Acme)");

            // Without the cancellation this is a 200 — issueIfPending finds the old row
            // outstanding, mints nothing, and reinvite() has already put the membership back to
            // INVITED, so the token that then works is the one issued before the offboarding and
            // nobody was told. 201 is the difference.
            assertThat(reInvited.getResponse().getStatus()).isEqualTo(201);
            assertThat(outstandingInvitationIdFor(target))
                    .isNotNull()
                    .isNotEqualTo(firstInvitation);
        }

        @Test
        @DisplayName("the cancellation is recorded, because it is a different act")
        void theAuditSaysTheyNeverJoined() throws Exception {
            UUID target = invitee();

            remove(ADMIN, target);

            JsonNode changes = json.readTree(removalAuditsFor(target).getFirst().getChanges());
            // Withdrawing an invitation and offboarding a colleague look identical in the
            // directory and are not the same event to whoever reads this later.
            assertThat(changes.get("wasPending").asBoolean()).isTrue();
            assertThat(changes.get("invitationCancelled").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("Removing yourself")
    class SelfRemoval {

        @Test
        @DisplayName("an administrator may not remove themselves — 400 on userId")
        void administratorsMayNotLeave() throws Exception {
            MvcResult result = remove(ADMIN, ADMIN);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("errors").has("userId"))
                    .as("the refusal must land on the control that caused it")
                    .isTrue();
            assertThat(statusOf(ACME, ADMIN)).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("so the company always keeps an administrator")
        void theCompanyCannotBeEmptiedOfAdministrators() throws Exception {
            // Not a count of administrators, deliberately: only a holder of company:members
            // reaches this endpoint and they cannot be the one leaving, so the caller is always
            // still there afterwards. One admin removing the other is fine; the last one is stuck
            // with themselves.
            assertThat(remove(ADMIN, DEPUTY).getResponse().getStatus()).isEqualTo(204);
            assertThat(remove(ADMIN, ADMIN).getResponse().getStatus()).isEqualTo(400);
            assertThat(statusOf(ACME, ADMIN)).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("an ordinary member naming themselves is 403, not 400")
        void theGateRunsBeforeTheTargetIsLookedAt() throws Exception {
            // Pins the order. A 400 here would tell a caller who may not use this endpoint that
            // the endpoint examined their payload, and would answer a question about the target
            // for somebody with no right to ask one.
            assertThat(remove(MEMBER, MEMBER).getResponse().getStatus()).isEqualTo(403);
        }
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================

    private MvcResult remove(UUID callerId, UUID targetId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/users/" + targetId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn();
    }

    private MvcResult invite(UUID callerId, String email, String displayName) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"%s"}""".formatted(email, displayName)))
                .andReturn();
    }

    private int statusUsing(String token, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private List<UUID> directoryIds() throws Exception {
        JsonNode people = json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME)))
                .andReturn().getResponse().getContentAsString());
        List<UUID> ids = new ArrayList<>();
        people.forEach(person -> ids.add(UUID.fromString(person.get("id").asText())));
        return ids;
    }

    /** A token carrying the account's current epoch — as the login path would mint it. */
    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> {
            int pv = users.findById(userId).map(AppUser::getPermissionVersion).orElse(1);
            return tokens.issue(userId, companyId, pv, false).token();
        });
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    /** An active member of Acme, minted per test because every test that uses one mutates it. */
    private UUID joiner() {
        int n = SEQUENCE.incrementAndGet();
        UUID id = UUID.nameUUIDFromBytes(("rm:joiner:" + n).getBytes());
        Instant now = Instant.now();

        tx.executeWithoutResult(status ->
                activeUser(id, "joiner-" + n + "@rm-acme.test", "Jo Joiner", now));
        inCompany(ACME, () -> tx.executeWithoutResult(status ->
                member(ACME, id, "J. Joiner (Acme)", now, MembershipStatus.ACTIVE)));
        return id;
    }

    /** Somebody invited through the real endpoint, so their invitation row is a real one. */
    private UUID invitee() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        MvcResult invited =
                invite(ADMIN, "invitee-" + n + "@rm-acme.test", "J. Joiner (Acme)");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(invited.getResponse().getContentAsString())
                .get("user").get("id").asText());
    }

    /** An account that exists on this instance and belongs only to Umbra. */
    private UUID umbraOnlyMember() {
        int n = SEQUENCE.incrementAndGet();
        UUID id = UUID.nameUUIDFromBytes(("rm:foreign:" + n).getBytes());
        Instant now = Instant.now();

        tx.executeWithoutResult(status ->
                activeUser(id, "contractor-" + n + "@rm-elsewhere.test", "Cass Contractor", now));
        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                member(UMBRA, id, "C. Contractor (Umbra)", now, MembershipStatus.ACTIVE)));
        return id;
    }

    private void grantViewerOnProject(UUID userId) {
        Instant now = Instant.now();
        inCompany(ACME, () -> tx.executeWithoutResult(status -> roleAssignments.save(
                RoleAssignment.forUserOnProject(
                        UUID.randomUUID(), ACME, Role.VIEWER_ID, userId, PROJECT, ADMIN, now))));
    }

    private String emailOf(UUID userId) {
        return tx.execute(status -> users.findById(userId).map(AppUser::getEmail).orElseThrow());
    }

    private MembershipStatus statusOf(UUID companyId, UUID userId) {
        return inCompanyGet(companyId, () -> tx.execute(status ->
                memberships.findByCompanyIdAndUserId(companyId, userId)
                        .map(CompanyMembership::getStatus)
                        .orElse(null)));
    }

    private int permissionVersionOf(UUID userId) {
        return tx.execute(status ->
                users.findById(userId).map(AppUser::getPermissionVersion).orElseThrow());
    }

    /**
     * What they may do on PROJECT — asked over HTTP as their own session, so it is the answer the
     * product would give them rather than a re-derivation of the gate inside the test.
     */
    private List<String> projectPermissionsOf(UUID userId) throws Exception {
        JsonNode held = json.readTree(mvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/me/permissions?projectId=" + PROJECT)
                                .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        held.get("permissions").forEach(key -> keys.add(key.asText()));
        return keys;
    }

    private List<RoleAssignment> grantsFor(UUID userId) {
        return inCompanyGet(ACME, () -> tx.execute(status -> {
            List<RoleAssignment> held = new ArrayList<>();
            for (RoleAssignment assignment : roleAssignments.findAllByOrderByGrantedAtDesc()) {
                if (userId.equals(assignment.getUserId())
                        && !Role.COMPANY_MEMBER_ID.equals(assignment.getRoleId())) {
                    held.add(assignment);
                }
            }
            return held;
        }));
    }

    private UUID outstandingInvitationIdFor(UUID userId) {
        return tx.execute(status -> invitations.findAll().stream()
                .filter(invitation -> invitation.getUserId().equals(userId))
                .filter(invitation -> invitation.getConsumedAt() == null)
                .map(MembershipInvitation::getId)
                .findFirst()
                .orElse(null));
    }

    private List<AuditEvent> removalAuditsFor(UUID userId) {
        return inCompanyGet(ACME, () -> tx.execute(status ->
                auditEvents.findByActionOrderByOccurredAtDesc("company_membership.removed").stream()
                        .filter(event -> event.getTargetIds().contains(userId))
                        .toList()));
    }

    private static UUID id(String marker) {
        return UUID.nameUUIDFromBytes(("rm:" + marker).getBytes());
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

    private void member(
            UUID companyId, UUID userId, String displayName, Instant now, MembershipStatus status) {

        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            if (status == MembershipStatus.ACTIVE) {
                membership.accept(now);
            }
            memberships.save(membership);
        }
    }

    private void grant(String marker, RoleAssignment assignment) {
        if (roleAssignments.findById(id(marker)).isEmpty()) {
            roleAssignments.save(assignment);
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

    private <T> T inCompanyGet(UUID companyId, Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.get();
        }
    }
}
