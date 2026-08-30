package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /permissions/{permission}/grantees} — the endpoint a <strong>refusal</strong> reads.
 *
 * <p>Design language rule 7 requires a refusal to name the permission and <em>a person in this
 * company who holds it</em>. What this class pins is the boundary that lets a refused caller ask
 * that question at all without turning a permission error into a directory:
 *
 * <ol>
 *   <li><strong>Isolation.</strong> Never a person from another company. A permission error that
 *       leaked a name across the tenancy boundary would be worse than the missing name it fixes.</li>
 *   <li><strong>Shape.</strong> Display names and nothing else — no id, no email, no role. Asserted
 *       against the RAW JSON, because a field the server sends and the client declines to render is
 *       invisible to any assertion that reads by name.</li>
 *   <li><strong>Availability.</strong> A caller who holds nothing still gets an answer. That is the
 *       whole point: the reader is somebody who has just been refused.</li>
 * </ol>
 *
 * <p>Ids use the {@code 0e…} prefix — checked against every {@code UUID.fromString} literal under
 * {@code src/test/java} rather than assumed, because a prefix collision merges clean and fails at
 * runtime on a primary key.
 */
@AutoConfigureMockMvc
@DisplayName("Who to ask — the names a refusal is allowed to print")
class GranteeEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("0e000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("0e000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("0e001000-0000-4000-8000-000000000001");

    /** Company Admin of NORTH — holds `token:issue`, and is the name a refusal should print. */
    private static final UUID ADMIN = UUID.fromString("0e003000-0000-4000-8000-000000000001");
    /** Viewer on ATLAS. Holds nothing administrative, and is who reads the refusal. */
    private static final UUID VIEWER = UUID.fromString("0e003000-0000-4000-8000-000000000002");
    /** Company Admin of SOUTH. Holds the same key, in another company. Must never be named. */
    private static final UUID OUTSIDER = UUID.fromString("0e003000-0000-4000-8000-000000000003");

    private static final UUID GRANT_ADMIN = UUID.fromString("0e005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_VIEWER = UUID.fromString("0e005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_OUTSIDER = UUID.fromString("0e005000-0000-4000-8000-000000000003");

    private static final String ADMIN_NAME = "Dana Whitfield";
    private static final String ADMIN_EMAIL = "admin@north-grantee.test";
    private static final String OUTSIDER_NAME = "Someone Else";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private com.tcms.auth.SessionTokens tokens;
    @Autowired private CompanyRepository companies;
    @Autowired private ProjectRepository projects;
    @Autowired private AppUserRepository users;
    @Autowired private CompanyMembershipRepository memberships;
    @Autowired private RoleAssignmentRepository roleAssignments;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-grantee", "Northwind", now);
            provisionCompany(SOUTH, "south-grantee", "Southwind", now);

            activeUser(ADMIN, ADMIN_EMAIL, now);
            activeUser(VIEWER, "viewer@north-grantee.test", now);
            activeUser(OUTSIDER, "admin@south-grantee.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, NORTH, "ATL", "Atlas", now);
            member(NORTH, ADMIN, ADMIN_NAME, now);
            member(NORTH, VIEWER, "V. Viewer", now);
            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_VIEWER, NORTH, Role.VIEWER_ID, VIEWER, ATLAS, now);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            member(SOUTH, OUTSIDER, OUTSIDER_NAME, now);
            grantOnCompany(GRANT_OUTSIDER, SOUTH, Role.COMPANY_ADMIN_ID, OUTSIDER, now);
        }));
    }

    @Test
    @DisplayName("names somebody who holds it, to a caller who does not")
    void namesAHolderToACallerWhoHoldsNothing() throws Exception {
        JsonNode body = get(VIEWER, "token:issue");

        assertThat(body.get("permission").asText()).isEqualTo("token:issue");
        assertThat(names(body)).containsExactly(ADMIN_NAME);
    }

    @Test
    @DisplayName("never names somebody from another company")
    void neverNamesSomebodyFromAnotherCompany() throws Exception {
        // The isolation property, asserted on the RAW body: a name the server sends and the client
        // declines to render is invisible to an assertion that reads a field by name.
        String raw = rawGet(VIEWER, "token:issue");

        assertThat(raw).doesNotContain(OUTSIDER_NAME);
        assertThat(raw).contains(ADMIN_NAME);
    }

    @Test
    @DisplayName("returns a name and nothing else — no id, no email, no role")
    void returnsANameAndNothingElse() throws Exception {
        String raw = rawGet(VIEWER, "token:issue");
        JsonNode body = json.readTree(raw);

        assertThat(body.get("grantees").get(0).fieldNames()).toIterable().containsExactly("displayName");
        assertThat(raw).doesNotContain(ADMIN_EMAIL);
        assertThat(raw).doesNotContain(ADMIN.toString());
        assertThat(raw).doesNotContain("Company Admin");
    }

    @Test
    @DisplayName("answers empty rather than guessing, for a key nobody here holds")
    void answersEmptyForAKeyNobodyHolds() throws Exception {
        // An unknown key and a key nobody holds are the same answer on purpose: a client on a newer
        // deployment asking about a key this one has never heard of should be told "nobody here",
        // not handed a 400 it cannot act on. The screen must then promise nobody to ask.
        assertThat(names(get(VIEWER, "permission:that:nobody:holds"))).isEmpty();
    }

    private JsonNode get(UUID caller, String permission) throws Exception {
        return json.readTree(rawGet(caller, permission));
    }

    private String rawGet(UUID caller, String permission) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/permissions/" + permission + "/grantees")
                        .header("Authorization", "Bearer " + tokenFor(caller)))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private List<String> names(JsonNode body) {
        return body.get("grantees").findValuesAsText("displayName");
    }

    private String tokenFor(UUID userId) {
        UUID companyId = userId.equals(OUTSIDER) ? SOUTH : NORTH;
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    private void provisionCompany(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
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

    private void grantOnCompany(UUID id, UUID companyId, UUID roleId, UUID userId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private void grantOnProject(
            UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }
}
