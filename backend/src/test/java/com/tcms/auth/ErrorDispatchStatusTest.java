package com.tcms.auth;

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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The status a caller is actually given when Spring answers through {@code response.sendError} —
 * and the reason this class cannot use MockMvc.
 *
 * <h2>Why a real container</h2>
 *
 * <p>An unreadable body, an unknown enum value, a wrong {@code Content-Type}, a wrong method and an
 * unmapped path are all resolved by {@code DefaultHandlerExceptionResolver}, which calls
 * {@code sendError}. In a servlet container that begins a second trip through the whole filter
 * chain — the <strong>ERROR dispatch</strong> — against {@code /error}, which sits behind
 * {@code .anyRequest().authenticated()} like every other path. <strong>MockMvc performs no such
 * dispatch.</strong> It records the status the resolver set and stops, so every one of the 2300
 * tests in this suite agrees about a status the deployed application does not produce.
 *
 * <p>That is not a hypothetical: it hid a real defect for the length of the MCP plan.
 * {@link ApiTokenAuthenticationFilter} is a {@code OncePerRequestFilter}, whose
 * {@code shouldNotFilterErrorDispatch()} defaults to <em>true</em>, so on the ERROR dispatch the
 * token was not re-authenticated, {@code /error} was denied, and the entry point replaced the real
 * status with <strong>401</strong>. Every 4xx an API token could provoke arrived as *"the
 * credential was not accepted"* — the one diagnosis whose remedy cannot work, handed to the CLI,
 * to CI jobs and to the MCP server's model callers. A session was unaffected, which is exactly why
 * neither the SPA nor a browser pass could ever have shown it.
 *
 * <p>So the assertions below are about the <em>container</em>, not about a handler. Each one fails
 * if that override is removed, and none of them can be written with MockMvc.
 *
 * <p>Ids use the {@code e4…} prefix, which no other class in the suite uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("The ERROR dispatch — a token keeps its identity, so a 4xx stays a 4xx")
class ErrorDispatchStatusTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("e4100000-0000-4000-8000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("e4101000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("e4103000-0000-4000-8000-000000000001");
    private static final UUID GRANT = UUID.fromString("e4105000-0000-4000-8000-000000000001");

    /**
     * A body whose {@code automationStatus} is not one of the four. Jackson refuses it before any
     * handler runs, which is the whole point — this never reaches the validation that would have
     * answered a {@code Problem}.
     */
    private static final String UNREADABLE_CASE = """
            {"title":"t","stepFormat":"STRUCTURED","priority":"HIGH",\
            "automationStatus":"NONE","steps":[{"action":"a","expectedResult":"b"}]}""";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionTokens sessions;

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
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "acme-error", "Acme", now));
            }
            if (users.findById(ADMIN).isEmpty()) {
                AppUser user =
                        AppUser.invite(ADMIN, "admin@acme-error.test", "admin@acme-error.test", now);
                user.activate("hash", now);
                users.save(user);
            }
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "ACE", "Acme Error", null, now));
            }
            if (memberships.findByCompanyIdAndUserId(ACME, ADMIN).isEmpty()) {
                CompanyMembership membership =
                        CompanyMembership.invite(ACME, ADMIN, "A. Admin", null, now);
                membership.accept(now);
                memberships.save(membership);
            }
            if (roleAssignments.findById(GRANT).isEmpty()) {
                roleAssignments.save(RoleAssignment.forUserOnCompany(
                        GRANT, ACME, Role.COMPANY_ADMIN_ID, ADMIN, null, now));
            }
        }));
    }

    @Test
    @DisplayName("an unreadable body is 400 for a token, not 401")
    void anUnreadableBodyIsBadRequestForAToken() throws Exception {
        ResponseEntity<String> response =
                post("/api/v1/projects/" + PROJECT + "/cases", UNREADABLE_CASE, issueToken());

        // The defect this class exists for. 401 here means the ERROR dispatch ran without the
        // token's authentication and the entry point overwrote the status — and the caller has
        // been told to replace a credential that is working.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("a session gets the same 400 — the two ways in agree")
    void aSessionGetsTheSameStatus() throws Exception {
        ResponseEntity<String> response =
                post("/api/v1/projects/" + PROJECT + "/cases", UNREADABLE_CASE, sessionFor(ADMIN));

        // The control. This passed before the fix and is what made the token's 401 look like a
        // property of the request rather than of the credential presenting it.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("an unmapped path is 404 for a token, not 401")
    void anUnmappedPathIsNotFoundForAToken() throws Exception {
        // Not a body problem at all, and it took the same route: nothing dispatches, the container
        // errors, and the second trip decided the status. A 401 here would tell a client that its
        // credential is bad when its URL is.
        assertThat(get("/api/v1/no-such-thing", issueToken()).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a wrong method is 405 for a token, not 401")
    void aWrongMethodIsMethodNotAllowedForAToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken());

        ResponseEntity<String> response = http.exchange(
                url("/api/v1/projects/" + PROJECT + "/suites"),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
    }

    @Test
    @DisplayName("a credential that really is bad is still 401")
    void arejectedCredentialIsStillUnauthorized() {
        // The other side of the fix, and the one a careless correction breaks: running the filter
        // on the ERROR dispatch must not make an unknown secret authenticate. `tcms_` is the
        // prefix the bearer resolver hands to this filter rather than to the resource server, so
        // this exercises the real branch and not the "no token at all" one.
        assertThat(get("/api/v1/projects/" + PROJECT + "/cases", "tcms_not-a-real-secret")
                .getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the body is a Problem, not the container's whitelabel shape")
    void theBodyIsAProblem() throws Exception {
        // §145's live pass found this residue: §143 fixed the STATUS on these five and left the
        // SHAPE the container's — `{timestamp,status,error,path}`, which this contract declares
        // nowhere, on the paths a machine caller hits most. A client cannot read `detail` out of
        // it, so the one refusal a model is least able to interpret arrived in the one shape it
        // could not parse.
        //
        // Asserted on a token, deliberately: the token is the caller that reaches these paths, and
        // this is the assertion that fails if ProblemErrorAttributes is removed while every
        // MockMvc test in the suite stays green.
        var refusal = post("/api/v1/projects/" + PROJECT + "/cases", UNREADABLE_CASE, issueToken());

        // §162. `BasicErrorController`'s mapping is hard-coded to `produces = application/json`,
        // so until `ProblemEnvelope` set the header this was the last refusal in the API still
        // announcing itself as ordinary JSON — on the paths a machine caller hits most, which is
        // the same reason §146 shaped the body here in the first place. Asserted over real HTTP
        // because the header is written on the ERROR dispatch, which MockMvc does not run.
        assertThat(refusal.getHeaders().getContentType())
                .as("every refusal in this API is application/problem+json, the container's too")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        JsonNode body = json.readTree(refusal.getBody());

        assertThat(body.has("timestamp")).as("the container's shape must be gone entirely").isFalse();
        assertThat(body.has("path")).as("`path` echoes the request back into the body").isFalse();
        // §163. `instance` is `path` under Spring's name, and for sixteen sections this assertion
        // held here while twenty-one controllers shipped the identical string one layer over. The
        // rule is now the same rule on every writer, so it is asserted in the same words.
        assertThat(body.has("instance"))
                .as("`instance` echoes the request back into the body under Spring's name for it")
                .isFalse();
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("detail").asText())
                .as("`detail` is what @tcms/cli prints verbatim and what a model acts on")
                .isNotBlank();
    }

    @Test
    @DisplayName("a controller's own ProblemDetail carries no instance either — §163")
    void aControllerProblemDetailCarriesNoInstance() throws Exception {
        // The other twenty-one writers, over real HTTP. The test above covers the container's
        // /error body; this covers a refusal a controller composed and returned itself, which is
        // where `instance` actually came from — not from anything in this repository, but from
        // HttpEntityMethodProcessor, which sets it from getRequestURI() on the way to the
        // converter. `setInstance` occurs zero times in src/main and the property arrived on all
        // twenty-one anyway, so a source scan is structurally unable to observe this and the
        // assertion has to be on a body that came back over a socket.
        //
        // The site: an Automation Service token cannot issue API tokens (`token:issue` is Company
        // Admin, COMPANY scope), and ApiTokenController answers that with a ProblemDetail.
        var refusal = post("/api/v1/api-tokens",
                "{\"name\":\"nope\",\"roleId\":\"" + Role.AUTOMATION_SERVICE_ID
                        + "\",\"projectId\":\"" + PROJECT + "\",\"expiresAt\":null}",
                issueToken());

        assertThat(refusal.getStatusCode().value())
                .as("the fixture must still reach a controller-written refusal, not a 401 or a 404")
                .isEqualTo(403);

        JsonNode body = json.readTree(refusal.getBody());
        assertThat(body.has("instance"))
                .as("a refusal does not hand the caller back the URL it just sent (§146, §163)")
                .isFalse();
        assertThat(body.get("detail").asText())
                .as("and it still says what the caller cannot do — the member that earns its place")
                .isNotBlank();
    }

    @Test
    @DisplayName("the detail never carries the exception's own message")
    void theDetailIsCuratedRatherThanForwarded() throws Exception {
        // The tempting implementation forwards getError().getMessage(), and for THIS request that
        // message names a Jackson type, the enum's fully-qualified class and a JSON pointer. The
        // caller is a CI job or a model; none of that is actionable and some of it is internal.
        String detail = json.readTree(
                post("/api/v1/projects/" + PROJECT + "/cases", UNREADABLE_CASE, issueToken())
                        .getBody())
                .get("detail").asText();

        assertThat(detail)
                .doesNotContain("com.tcms")
                .doesNotContain("com.fasterxml")
                .doesNotContain("Exception")
                .doesNotContain("AutomationStatus");
    }

    @Test
    @DisplayName("an unmapped path and a wrong method are Problems too")
    void theOtherContainerFailuresAreShapedAlike() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken());

        JsonNode unmapped = json.readTree(get("/api/v1/no-such-thing", issueToken()).getBody());
        assertThat(unmapped.get("status").asInt()).isEqualTo(404);
        assertThat(unmapped.get("detail").asText()).isNotBlank();

        JsonNode wrongMethod = json.readTree(http.exchange(
                url("/api/v1/projects/" + PROJECT + "/suites"),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class).getBody());
        assertThat(wrongMethod.get("status").asInt()).isEqualTo(405);
        assertThat(wrongMethod.get("detail").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a deliberate empty 404 from a controller stays empty — §6t is untouched")
    void aControllersOwn404IsStillBodiless() throws Exception {
        // The rule this fix must not run over. "Ungranted", "another company's" and "does not
        // exist" answer identically and with NO body, because a body explaining a 404 is an oracle
        // even when the explanation is generic. Those are ResponseEntity.notFound().build(), which
        // writes the status directly and never calls sendError — so they never reach /error at
        // all. Asserted rather than reasoned, because the reasoning is exactly the kind that is
        // right until Boot changes how a bodiless response is committed.
        ResponseEntity<String> response =
                get("/api/v1/projects/" + UUID.randomUUID() + "/suites", issueToken());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    /** Issues through the API, as an operator does. */
    private String issueToken() throws Exception {
        String body = "{\"name\":\"CI " + UUID.randomUUID() + "\""
                + ",\"roleId\":\"" + Role.AUTOMATION_SERVICE_ID + "\""
                + ",\"projectId\":\"" + PROJECT + "\""
                + ",\"expiresAt\":null}";

        ResponseEntity<String> issued = post("/api/v1/api-tokens", body, sessionFor(ADMIN));
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        return json.readTree(issued.getBody()).get("secret").asText();
    }

    private String sessionFor(UUID userId) {
        return tx.execute(status -> sessions.issue(
                userId, ACME,
                users.findById(userId).orElseThrow().getPermissionVersion(), false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
