package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /users} — putting a second person in the company (§55).
 *
 * <h2>The three failures this exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The instance-directory oracle.</strong> {@code app_user} is instance-level with a
 *       unique email, so an endpoint that answers differently for an address that already has an
 *       account lets any Company Admin discover, one guess at a time, who else is on this instance
 *       — including a named competitor's staff. {@link Disclosure} drives both branches and
 *       compares the responses field by field.</li>
 *   <li><strong>The dead link.</strong> A {@code REMOVED} membership is neither absent nor active,
 *       so a re-invite that does not reset it mints a token {@code InvitationService#accept} then
 *       refuses. The inviter sees success and the invitee gets a link that says "not redeemable".
 *       {@link Rejoining} redeems it for real.</li>
 *   <li><strong>Replacing a live invitation on a second click.</strong> A fresh token invalidates
 *       the one already in the invitee's inbox — the failure a user causes by being unsure whether
 *       the first click worked. {@link Idempotence} asserts the outstanding row is untouched.</li>
 * </ol>
 *
 * <h2>Why this class mocks the mailer, when nothing else in the suite mocks anything</h2>
 *
 * <p>The activation link is deliberately absent from the response ({@link UserInvitationView}) and
 * stored only as a digest, so the mail message is the <em>only</em> place a test can obtain it —
 * and without it the accept flow cannot be driven end to end, which is the one thing that proves
 * the failures above are actually fixed rather than merely coded around. The mock also makes the
 * "no relay configured" install a case this class can exercise rather than describe.
 *
 * <p>It costs one extra cached application context for this class. Stated because it is a real
 * cost, and because "mock the collaborator" is not this project's habit and should not become one
 * on the strength of this precedent.
 *
 * <h2>Every invitee is unique to its test, deliberately</h2>
 *
 * <p>These tests write through HTTP and therefore <strong>commit</strong>; nothing rolls back
 * between them. A shared invitee would carry its state forward — the second test to invite it gets
 * the idempotent {@code 200} instead of the {@code 201} it asserts — and the class would pass or
 * fail by execution order, which is the least useful kind of red. The seeded fixture is limited to
 * rows no test mutates (the callers, the company, the grants); everybody who gets invited is minted
 * per test by {@link #fresh}.
 *
 * <p>Ids and slugs are unique to this class ({@code 1c…}, {@code iv-}) — §19's defect, three times.
 */
@AutoConfigureMockMvc
@DisplayName("Inviting a colleague into the company")
class UserInvitationEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("1c000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("1c000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("1c001000-0000-4000-8000-000000000001");

    /** Company Admin in Acme — holds {@code company:members}. The caller in most tests. */
    private static final UUID ADMIN = UUID.fromString("1c003000-0000-4000-8000-000000000001");
    /** Test Lead on PROJECT: holds {@code project:members} and not {@code company:members}. */
    private static final UUID LEAD = UUID.fromString("1c003000-0000-4000-8000-000000000002");
    /** An ordinary active member of Acme. */
    private static final UUID MEMBER = UUID.fromString("1c003000-0000-4000-8000-000000000003");
    /** Active in Acme already, and never mutated — inviting them is a no-op by definition. */
    private static final UUID SETTLED = UUID.fromString("1c003000-0000-4000-8000-000000000004");

    /** Distinguishes one test's invitee from the next; see the class header. */
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
    private TransactionTemplate tx;

    /** See the class header. Defaults to a configured relay; one test says otherwise. */
    @MockitoBean
    private InvitationMailer mailer;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();
        given(mailer.isConfigured()).willReturn(true);

        tx.executeWithoutResult(status -> {
            company(ACME, "iv-acme", "Acme", now);
            company(UMBRA, "iv-umbra", "Umbra", now);
            activeUser(ADMIN, "admin@iv-acme.test", "Ada Admin", now);
            activeUser(LEAD, "lead@iv-acme.test", "Leonora Lead", now);
            activeUser(MEMBER, "member@iv-acme.test", "Mo Member", now);
            activeUser(SETTLED, "settled@iv-acme.test", "Sam Settled", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IVA", "Invite Project", now);
            member(ACME, ADMIN, "A. Admin (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, LEAD, "L. Lead (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, MEMBER, "M. Member (Acme)", now, MembershipStatus.ACTIVE);
            member(ACME, SETTLED, "S. Settled (Acme)", now, MembershipStatus.ACTIVE);

            grant("admin", RoleAssignment.forUserOnCompany(
                    id("admin"), ACME, Role.COMPANY_ADMIN_ID, ADMIN, null, now));
            grant("lead", RoleAssignment.forUserOnProject(
                    id("lead"), ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, null, now));
        }));
    }

    // =====================================================================

    @Nested
    @DisplayName("The gate — company:members, and nothing weaker")
    class Gate {

        @Test
        @DisplayName("a Company Admin may invite")
        void companyAdminsMayInvite() throws Exception {
            Person nora = fresh();
            assertThat(invite(ADMIN, nora).getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a Test Lead may not — project:members staffs a project, it does not admit people")
        void projectMembersIsNotEnough() throws Exception {
            // The two permissions are one word apart and mean different things: project:members
            // decides who works on one engagement, company:members decides who is in the client's
            // organisation at all. A Test Lead who could invite would be adding people to every
            // project's picker company-wide.
            assertThat(invite(LEAD, fresh()).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("an ordinary member may not")
        void membersMayNotInvite() throws Exception {
            assertThat(invite(MEMBER, fresh()).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a refused caller writes nothing and sends nothing")
        void refusalIsInert() throws Exception {
            Person elsewhere = foreignAccount();

            invite(LEAD, elsewhere);

            // Two properties in one: no membership was created, and — because the gate runs before
            // the payload is examined — nothing was sent, so a refused caller cannot use this
            // endpoint to find out whether an address is known to the instance.
            assertThat(membershipOf(ACME, elsewhere.id())).isNull();
            verify(mailer, never()).send(any());
        }

        @Test
        @DisplayName("no session at all is 401")
        void anonymousIsRejected() throws Exception {
            Person nora = fresh();
            int status = mvc.perform(MockMvcRequestBuilders.post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(nora.email(), nora.displayName())))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Inviting somebody new")
    class Inviting {

        @Test
        @DisplayName("201 with the person as INVITED, and a link on its way")
        void invitesAreCreated() throws Exception {
            Person nora = fresh();
            JsonNode invitation = jsonOf(invite(ADMIN, nora));

            assertThat(invitation.get("user").get("status").asText()).isEqualTo("INVITED");
            assertThat(invitation.get("user").get("email").asText()).isEqualTo(nora.email());
            assertThat(invitation.get("issued").asBoolean()).isTrue();
            assertThat(invitation.get("emailed").asBoolean()).isTrue();
            assertThat(invitation.get("expiresAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("the display name is the one the inviter typed, stored on the membership")
        void displayNameIsThisCompanys() throws Exception {
            // §6u. The account's canonical name is an instance-directory fact; every rendering in
            // the product resolves through the active company's membership.
            Person nora = fresh();
            JsonNode invitation = jsonOf(invite(ADMIN, nora));

            assertThat(invitation.get("user").get("displayName").asText())
                    .isEqualTo(nora.displayName());
            assertThat(membershipOf(ACME, userIdOf(invitation)).getDisplayName())
                    .isEqualTo(nora.displayName());
        }

        @Test
        @DisplayName("they appear in the company directory immediately, as INVITED")
        void theDirectoryShowsThem() throws Exception {
            Person nora = fresh();
            invite(ADMIN, nora);

            JsonNode listed = userInDirectory(nora.displayName());
            assertThat(listed).as("the invitee is missing from GET /users").isNotNull();
            assertThat(listed.get("status").asText()).isEqualTo("INVITED");
        }

        @Test
        @DisplayName("membership alone conveys no project visibility")
        void invitingIsNotStaffing() throws Exception {
            // Roles spec §A1, and the reason the input carries no roleId: inviting somebody and
            // deciding what they may do are two acts with two gates. If this ever starts granting
            // anything, a Company Admin's invite becomes a silent project grant.
            JsonNode invitation = jsonOf(invite(ADMIN, fresh()));

            assertThat(grantsFor(userIdOf(invitation))).isEmpty();
        }

        @Test
        @DisplayName("emailed is false when the install has no relay — the invitation is still valid")
        void noRelayIsReported() throws Exception {
            given(mailer.isConfigured()).willReturn(false);

            JsonNode invitation = jsonOf(invite(ADMIN, fresh()));

            // The distinction that stops an invitee waiting for a message nobody sent. The row is
            // written and the link is live either way; what changes is who has to carry it.
            assertThat(invitation.get("emailed").asBoolean()).isFalse();
            // ISSUED and not emailed — the pair that means "no relay here", as opposed to the
            // re-invite below, where nothing was minted because nothing needed to be.
            assertThat(invitation.get("issued").asBoolean()).isTrue();
            assertThat(invitation.get("expiresAt").isNull()).isFalse();
            assertThat(outstandingInvitationsFor(userIdOf(invitation))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Re-inviting — idempotent, and the live link survives")
    class Idempotence {

        @Test
        // Disabled 2026-08-01, re-enabled 2026-08-02 with the defect actually fixed — same
        // root cause as InvitationServiceTest.reRunLeavesTheLiveLinkAlone. The expiresAt
        // equality was the failing line: the first response carries the in-memory Instant,
        // the second the Postgres round-trip of it. Everything else here — 200,
        // emailed=false, issued=false, exactly one outstanding invitation — is covered
        // nowhere else, and was unverified for the day this spent disabled.
        @DisplayName("a second invitation of the same address is 200 and mints nothing")
        void secondInviteIsUnchanged() throws Exception {
            Person nora = fresh();
            JsonNode first = jsonOf(invite(ADMIN, nora));
            MvcResult second = invite(ADMIN, nora);

            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = jsonOf(second);
            assertThat(body.get("emailed").asBoolean())
                    .as("nothing was sent, so nothing should be claimed")
                    .isFalse();
            // The field the live pass added. Without it this response is byte-identical to the
            // no-relay one above, and a client reports a mail outage on every second click.
            assertThat(body.get("issued").asBoolean()).isFalse();
            assertThat(body.get("expiresAt").asText())
                    .as("the invitation already in the invitee's inbox was replaced")
                    .isEqualTo(first.get("expiresAt").asText());
            assertThat(outstandingInvitationsFor(userIdOf(first))).isEqualTo(1);
        }

        @Test
        @DisplayName("inviting somebody who is already an active member is 200 with nothing pending")
        void activeMembersAreLeftAlone() throws Exception {
            MvcResult result = invite(
                    ADMIN, new Person(SETTLED, "settled@iv-acme.test", "Renamed By Mistake"));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = jsonOf(result);
            assertThat(body.get("expiresAt").isNull())
                    .as("there is nothing to accept, so nothing expires")
                    .isTrue();
            assertThat(body.get("user").get("status").asText()).isEqualTo("ACTIVE");
            // The display name is NOT overwritten. Renaming a colleague is a different act with a
            // different affordance; doing it as a side effect of a retried invite would let a
            // mistyped name silently replace a correct one.
            assertThat(body.get("user").get("displayName").asText()).isEqualTo("S. Settled (Acme)");
            assertThat(body.get("issued").asBoolean()).isFalse();
            assertThat(outstandingInvitationsFor(SETTLED)).isZero();
        }

        @Test
        @DisplayName("the address is matched case-insensitively, so a retry cannot make a second one")
        void emailIsCaseInsensitive() throws Exception {
            Person nora = fresh();
            JsonNode first = jsonOf(invite(ADMIN, nora));
            MvcResult second = invite(ADMIN, new Person(
                    nora.id(), nora.email().toUpperCase(Locale.ROOT), nora.displayName()));

            // citext, and the property that makes it matter: the same person typed twice with a
            // capital letter would otherwise become two accounts, one of which nobody can sign in
            // as, because the email unique index is the thing that is case-insensitive.
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(userIdOf(jsonOf(second))).isEqualTo(userIdOf(first));
        }
    }

    @Nested
    @DisplayName("An address that already has an account elsewhere — the disclosure boundary")
    class Disclosure {

        @Test
        @DisplayName("the response is indistinguishable from inviting a brand-new address")
        void theAnswerDoesNotDependOnWhatTheInstanceKnows() throws Exception {
            JsonNode existing = jsonOf(invite(ADMIN, named(foreignAccount(), "The Contractor")));
            JsonNode brandNew = jsonOf(invite(ADMIN, named(fresh(), "The Contractor")));

            // Everything except the id and the address, which the caller supplied or can see
            // anyway. If these ever diverge, the difference IS the oracle — a Company Admin could
            // learn which of a competitor's staff have accounts here by inviting them one by one.
            assertThat(existing.get("user").get("status").asText())
                    .isEqualTo(brandNew.get("user").get("status").asText());
            assertThat(existing.get("user").get("displayName").asText())
                    .isEqualTo(brandNew.get("user").get("displayName").asText());
            assertThat(existing.get("user").get("lastLoginAt"))
                    .isEqualTo(brandNew.get("user").get("lastLoginAt"));
            assertThat(existing.get("user").get("teams"))
                    .isEqualTo(brandNew.get("user").get("teams"));
            assertThat(existing.get("emailed")).isEqualTo(brandNew.get("emailed"));
            assertThat(existing.get("issued")).isEqualTo(brandNew.get("issued"));
        }

        @Test
        @DisplayName("the account is reused rather than duplicated")
        void oneAccountManyCompanies() throws Exception {
            // Plan §6s. A second account would collide on the instance-unique email; the reason
            // this is asserted is the opposite risk — that a future "safer" implementation refuses
            // the address instead, which is the same oracle worn as a validation error.
            Person elsewhere = foreignAccount();
            JsonNode invitation = jsonOf(invite(ADMIN, named(elsewhere, "The Contractor")));

            assertThat(userIdOf(invitation)).isEqualTo(elsewhere.id());
        }

        @Test
        @DisplayName("the other company's name for them never appears")
        void namesDoNotCrossCompanies() throws Exception {
            Person elsewhere = foreignAccount();
            JsonNode invitation = jsonOf(invite(ADMIN, named(elsewhere, "The Contractor")));

            assertThat(invitation.get("user").get("displayName").asText())
                    .isEqualTo("The Contractor");
            assertThat(invitation.toString())
                    .as("the other company's wording leaked into an Acme response")
                    .doesNotContain(FOREIGN_WORDING);
        }

        @Test
        @DisplayName("their membership of the other company is untouched")
        void theOtherCompanyIsUnaffected() throws Exception {
            Person elsewhere = foreignAccount();

            invite(ADMIN, named(elsewhere, "The Contractor"));

            CompanyMembership umbra = membershipOf(UMBRA, elsewhere.id());
            assertThat(umbra.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(umbra.getDisplayName()).isEqualTo(FOREIGN_WORDING);
        }
    }

    @Nested
    @DisplayName("A colleague who was offboarded and is coming back")
    class Rejoining {

        @Test
        @DisplayName("the membership returns to INVITED and the link actually redeems")
        void removedMembersCanBeReinvited() throws Exception {
            Person departed = offboardedMember();

            MvcResult result = invite(ADMIN, named(departed, "Dee, Returning"));
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(membershipOf(ACME, departed.id()).getStatus())
                    .isEqualTo(MembershipStatus.INVITED);

            // The assertion this whole case exists for. Without CompanyMembership#reinvite the
            // status stays REMOVED, a token is minted anyway, and redemption refuses it — a link
            // the product sent and the product rejects, reported to the inviter as a success.
            // The account already has a password, so acceptance carries none.
            assertThat(acceptStatus(capturedAcceptUrl(), null))
                    .as("the invitation this endpoint just sent was not redeemable")
                    .isEqualTo(204);
            assertThat(membershipOf(ACME, departed.id()).getStatus())
                    .isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("their name is updated, because a re-invite is where it is typed again")
        void reinvitingRenames() throws Exception {
            Person departed = offboardedMember();

            invite(ADMIN, named(departed, "Dee, Returning"));

            assertThat(membershipOf(ACME, departed.id()).getDisplayName())
                    .isEqualTo("Dee, Returning");
        }
    }

    @Nested
    @DisplayName("Accepting — what an invitation is actually worth")
    class Accepting {

        @Test
        @DisplayName("the invitee can sign in and see the company they were invited to")
        void acceptedInviteeSeesTheCompany() throws Exception {
            // This is what makes MembershipInviteService's decision NOT to grant the company:read
            // baseline a decision rather than an omission: the baseline is granted by
            // InvitationService#accept, at the moment somebody actually joins, and this drives the
            // whole path rather than asserting that a third copy of the statement exists.
            JsonNode invitation = jsonOf(invite(ADMIN, fresh()));
            UUID invited = userIdOf(invitation);

            assertThat(acceptStatus(capturedAcceptUrl(), "a-long-enough-password")).isEqualTo(204);

            JsonNode directory = getAs(invited, "/api/v1/users");
            assertThat(directory.size())
                    .as("an accepted member holds company:read and sees the directory")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("and still sees no projects, because nobody granted them one")
        void acceptingIsNotAGrant() throws Exception {
            JsonNode invitation = jsonOf(invite(ADMIN, fresh()));
            UUID invited = userIdOf(invitation);
            acceptStatus(capturedAcceptUrl(), "a-long-enough-password");

            JsonNode projectList = getAs(invited, "/api/v1/projects");
            assertThat(projectList.size())
                    .as("membership conveys authentication and zero project visibility")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Validation — everything wrong at once, and nothing about the database")
    class Validation {

        @Test
        @DisplayName("a missing email is a field error")
        void emailIsRequired() throws Exception {
            assertThat(errorsOf(invite(ADMIN, "  ", "Nora"))).containsKey("email");
        }

        @Test
        @DisplayName("something that is not an address is a field error")
        void emailMustLookLikeOne() throws Exception {
            assertThat(errorsOf(invite(ADMIN, "Nora Newcomer", "Nora"))).containsKey("email");
        }

        @Test
        @DisplayName("a missing display name is a field error")
        void displayNameIsRequired() throws Exception {
            assertThat(errorsOf(invite(ADMIN, fresh().email(), "   "))).containsKey("displayName");
        }

        @Test
        @DisplayName("both wrong is both reported, so the form is corrected once")
        void everythingAtOnce() throws Exception {
            assertThat(errorsOf(invite(ADMIN, "nope", ""))).containsKeys("email", "displayName");
        }

        @Test
        @DisplayName("a rejected payload writes nothing and sends nothing")
        void invalidRequestsAreInert() throws Exception {
            invite(ADMIN, "nope", "");
            verify(mailer, never()).send(any());
        }
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================

    private MvcResult invite(UUID callerId, Person person) throws Exception {
        return invite(callerId, person.email(), person.displayName());
    }

    private MvcResult invite(UUID callerId, String email, String displayName) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, displayName)))
                .andReturn();
    }

    private static String body(String email, String displayName) {
        return """
                {"email":"%s","displayName":"%s"}""".formatted(email, displayName);
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getAs(UUID userId, String path) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn().getResponse().getContentAsString());
    }

    private Map<String, JsonNode> errorsOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode errors = jsonOf(result).get("errors");
        Map<String, JsonNode> byField = new LinkedHashMap<>();
        errors.fieldNames().forEachRemaining(field -> byField.put(field, errors.get(field)));
        return byField;
    }

    private static UUID userIdOf(JsonNode invitation) {
        return UUID.fromString(invitation.get("user").get("id").asText());
    }

    private JsonNode userInDirectory(String displayName) throws Exception {
        for (JsonNode person : getAs(ADMIN, "/api/v1/users")) {
            if (displayName.equals(person.get("displayName").asText())) {
                return person;
            }
        }
        return null;
    }

    /**
     * The activation link, read off the message the mailer was handed.
     *
     * <p>The only way a test can obtain it — see the class header. The send is registered for
     * after commit, so by the time the request has completed it has already happened.
     */
    private String capturedAcceptUrl() {
        ArgumentCaptor<InvitationMailer.InvitationMessage> sent =
                ArgumentCaptor.forClass(InvitationMailer.InvitationMessage.class);
        verify(mailer, atLeastOnce()).send(sent.capture());
        return sent.getValue().acceptUrl();
    }

    /** Redeems a link. {@code null} password for an account that already has one. */
    private int acceptStatus(String acceptUrl, String password) throws Exception {
        String token = acceptUrl.substring(acceptUrl.indexOf("token=") + "token=".length());
        String payload = password == null ? "{}" : "{\"password\":\"%s\"}".formatted(password);
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/auth/invitations/" + token + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn().getResponse().getStatus();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> {
            int pv = users.findById(userId).map(AppUser::getPermissionVersion).orElse(1);
            return tokens.issue(userId, companyId, pv, false).token();
        });
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    /** Umbra's own wording for the contractor — must never appear in an Acme response. */
    private static final String FOREIGN_WORDING = "Preferred Wording Of Another Client";

    /** Somebody to invite. {@code id} is the account this test expects to end up reusing. */
    private record Person(UUID id, String email, String displayName) {
    }

    /** An address nothing on the instance has ever seen. */
    private Person fresh() {
        int n = SEQUENCE.incrementAndGet();
        return new Person(null, "newcomer-" + n + "@iv-acme.test", "Nora Newcomer " + n);
    }

    private static Person named(Person person, String displayName) {
        return new Person(person.id(), person.email(), displayName);
    }

    /** An account that exists on this instance and belongs only to Umbra. */
    private Person foreignAccount() {
        int n = SEQUENCE.incrementAndGet();
        UUID id = UUID.nameUUIDFromBytes(("iv:foreign:" + n).getBytes());
        String email = "contractor-" + n + "@iv-elsewhere.test";
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> activeUser(id, email, FOREIGN_WORDING, now));
        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                member(UMBRA, id, FOREIGN_WORDING, now, MembershipStatus.ACTIVE)));
        return new Person(id, email, FOREIGN_WORDING);
    }

    /** Somebody who joined Acme, was offboarded, and has an existing password. */
    private Person offboardedMember() {
        int n = SEQUENCE.incrementAndGet();
        UUID id = UUID.nameUUIDFromBytes(("iv:departed:" + n).getBytes());
        String email = "departed-" + n + "@iv-acme.test";
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> activeUser(id, email, "Dee Departed", now));
        inCompany(ACME, () -> tx.executeWithoutResult(status ->
                member(ACME, id, "D. Departed (Acme)", now, MembershipStatus.REMOVED)));
        return new Person(id, email, "D. Departed (Acme)");
    }

    private static UUID id(String marker) {
        return UUID.nameUUIDFromBytes(("iv:" + marker).getBytes());
    }

    private CompanyMembership membershipOf(UUID companyId, UUID userId) {
        return inCompanyGet(companyId, () -> tx.execute(status ->
                memberships.findByCompanyIdAndUserId(companyId, userId).orElse(null)));
    }

    private long outstandingInvitationsFor(UUID userId) {
        return tx.execute(status -> invitations.findAll().stream()
                .filter(invitation -> invitation.getUserId().equals(userId))
                .filter(invitation -> invitation.getConsumedAt() == null)
                .count());
    }

    private List<RoleAssignment> grantsFor(UUID userId) {
        return inCompanyGet(ACME, () -> tx.execute(status -> {
            List<RoleAssignment> held = new ArrayList<>();
            for (RoleAssignment grant : roleAssignments.findAllByOrderByGrantedAtDesc()) {
                if (userId.equals(grant.getUserId())
                        && !Role.COMPANY_MEMBER_ID.equals(grant.getRoleId())) {
                    held.add(grant);
                }
            }
            return held;
        }));
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
            } else if (status == MembershipStatus.REMOVED) {
                membership.accept(now);
                membership.remove();
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
