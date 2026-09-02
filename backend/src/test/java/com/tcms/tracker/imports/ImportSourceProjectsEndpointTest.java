package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code POST /imports/source-projects} — the connect step's dropdown, over the wire.
 *
 * <h2>{@code POST}, and it writes nothing</h2>
 *
 * <p>The verb is about the body: a credential in a query string is in the access log, the proxy log
 * and the browser history. What the operation does is read somebody else's Jira and say what it
 * saw.
 *
 * <h2>The three refusals this class pins are three different people's problems</h2>
 *
 * <p>A Test Engineer holds no {@code issue:import} and gets <strong>403</strong> (D-e). A site that
 * is not HTTPS, or that resolves inside this network, gets <strong>400</strong> before anything is
 * dialled — that is {@link ImportUrlGuard}, and the credential never leaves this process. A source
 * that refuses the credential gets <strong>502</strong> with one message for every source-side
 * cause, so the endpoint is not an oracle for what exists in a Jira the caller cannot read.
 */
@AutoConfigureMockMvc
@DisplayName("POST /imports/source-projects — issue:import, the URL guard, and one 502")
class ImportSourceProjectsEndpointTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14001000-0000-4000-8000-000000000001");

    /** Project Manager. {@code Role} names only the roles production code branches on. */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("14002000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("14002000-0000-4000-8000-000000000002");

    private static final UUID MANAGER_GRANT = UUID.fromString("14003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER_GRANT = UUID.fromString("14003000-0000-4000-8000-000000000002");

    @Autowired
    private FakeImportSource source;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-projects-acme", "Acme", now);
            activeUser(MANAGER, "pm@import-projects.test", "Paula Manager", now);
            activeUser(ENGINEER, "te@import-projects.test", "Terry Engineer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMPP", "Import Projects", now);
            member(ACME, MANAGER, "Paula Manager", now);
            member(ACME, ENGINEER, "Terry Engineer", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        grantOnProject(ENGINEER_GRANT, ACME, Role.TEST_ENGINEER_ID, ENGINEER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);

        source.reset()
                .holding(new SourceProject("PROJ", "Northwind Storefront", 1482),
                        snapshot("PROJ", 4));
    }

    @Test
    @DisplayName("a project manager sees what the credential can see")
    void aManagerListsTheSourceProjects() throws Exception {
        JsonNode body = post(MANAGER, connection(), 200);

        assertThat(body.path("projects")).hasSize(1);
        assertThat(body.path("projects").get(0).path("key").asText()).isEqualTo("PROJ");
        assertThat(body.path("projects").get(0).path("issueCount").asInt()).isEqualTo(1482);
    }

    @Test
    @DisplayName("a test engineer is refused — issue:import is Project Manager and above (D-e)")
    void aTestEngineerIsRefused() throws Exception {
        JsonNode body = post(ENGINEER, connection(), 403);

        assertThat(body.path("permission").asText()).isEqualTo("issue:import");
        assertThat(body.path("scope").asText()).isEqualTo("PROJECT");
    }

    @Test
    @DisplayName("plain http is refused before the token is sent anywhere")
    void plainHttpIsRefused() throws Exception {
        ImportConnection insecure = new ImportConnection(
                ImportProvider.JIRA_CLOUD, "http://northwind.atlassian.net", "ops@x.test", "t");

        JsonNode body = post(MANAGER, insecure, 400);

        assertThat(body.path("detail").asText()).contains("https://");
        assertThat(source.snapshotCalls())
                .as("the guard refuses before anything is dialled — a credential sent in "
                        + "cleartext is already spent by the time a redirect to TLS arrives")
                .isZero();
    }

    @Test
    @DisplayName("a site resolving into this network is refused, and the reason names the switch")
    void aPrivateDestinationIsRefused() throws Exception {
        ImportConnection internal = new ImportConnection(
                ImportProvider.JIRA_CLOUD, "https://localhost", "ops@x.test", "t");

        JsonNode body = post(MANAGER, internal, 400);

        assertThat(body.path("detail").asText())
                .contains("tcms.tracker.imports.allow-private-hosts");
    }

    @Test
    @DisplayName("a refused credential is one 502 with the source's own message")
    void aRefusedCredentialIsOne502() throws Exception {
        source.rejecting(401);

        JsonNode body = post(MANAGER, connection(), 502);

        assertThat(body.path("title").asText()).isEqualTo("The source system did not answer");
    }

    private JsonNode post(UUID userId, ImportConnection connection, int expected) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports/source-projects")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(connection)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(expected);

        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }
}
