package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link JiraImportSource} against a bound {@link MockRestServiceServer} — no Spring context, no
 * container, and <strong>no Jira</strong>.
 *
 * <h2>The binding is the part that goes wrong, so it is asserted rather than assumed</h2>
 *
 * <p>{@code MockRestServiceServer} binds by installing its own request factory on a
 * {@code RestClient.Builder}. {@link ImportHttp}'s production constructor installs the real one
 * over the top, which silently un-binds it — and the equivalent mistake one module over had the
 * tracker transport suite dialling {@code acme.atlassian.net} over the public internet on every
 * run, reading as parse errors and a real Jira 400 rather than as "this never reached the double".
 * That is what {@link ImportHttp}'s second, package-private constructor exists for, and it is what
 * this class uses. {@code server.verify()} is the check that every expectation was actually met.
 *
 * <h2>Cloud and Data Center differ in the auth header and in nothing else this class reads</h2>
 *
 * <p>Cloud's API token is the password half of HTTP Basic with the account's email as the user
 * half; Data Center's is a Bearer PAT. Both are pinned below, because getting one wrong is a 401
 * against a real site and a green suite.
 */
@DisplayName("JiraImportSource — both hostings, against a bound double")
class JiraImportSourceTest {

    private static final String SITE = "https://northwind.atlassian.net";

    private MockRestServiceServer server;
    private JiraImportSource source;

    @BeforeEach
    void bindTheDouble() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ImportHttp http = new ImportHttp(
                builder.build(),
                new ImportUrlGuard(new ImportProperties(5000, Duration.ofSeconds(30), false)));
        source = new JiraImportSource(http, new ObjectMapper());
    }

    @Test
    @DisplayName("Cloud authenticates with Basic base64(email:token)")
    void cloudUsesBasicAuth() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("ops@northwind.test:secret".getBytes());

        server.expect(requestTo(Matchers.containsString("/rest/api/3/project/search")))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess(PROJECTS, MediaType.APPLICATION_JSON));

        assertThat(source.projects(cloud())).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("Data Center authenticates with a Bearer PAT, on the v2 API")
    void dataCentreUsesBearerAuth() {
        server.expect(requestTo(Matchers.containsString("/rest/api/2/project/search")))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(PROJECTS, MediaType.APPLICATION_JSON));

        assertThat(source.projects(dataCentre())).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("a project's key, name and disclosed issue count come back")
    void projectsAreRead() {
        server.expect(requestTo(Matchers.containsString("/project/search")))
                .andRespond(withSuccess(PROJECTS, MediaType.APPLICATION_JSON));

        SourceProject project = source.projects(cloud()).get(0);

        assertThat(project.key()).isEqualTo("PROJ");
        assertThat(project.name()).isEqualTo("Northwind Storefront");
        assertThat(project.issueCount()).isEqualTo(1482);
    }

    @Test
    @DisplayName("a snapshot reads the field catalogue once and then pages the search")
    void aSnapshotPagesAndCountsWhatItWillDrop() {
        server.expect(requestTo(Matchers.endsWith("/rest/api/3/field")))
                .andRespond(withSuccess(FIELDS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/search/jql")))
                .andRespond(withSuccess(FIRST_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("nextPageToken=more")))
                .andRespond(withSuccess(LAST_PAGE, MediaType.APPLICATION_JSON));

        SourceProjectSnapshot snapshot = source.snapshot(cloud(), "PROJ", count -> { });

        assertThat(snapshot.issues()).extracting(SourceIssue::key)
                .as("both pages, in the source's own order — which is what rankPosition means")
                .containsExactly("PROJ-1", "PROJ-2");
        assertThat(snapshot.statuses()).extracting(SourceStatus::name)
                .containsExactlyInAnyOrder("Backlog", "In Review");
        assertThat(snapshot.types()).extracting(SourceType::name).contains("Story");
        assertThat(snapshot.versions()).extracting(SourceVersion::name).containsExactly("1.4.0");
        assertThat(snapshot.historyEntryCount())
                .as("counted from expand=changelog and never imported — there is no table here "
                        + "for somebody else's audit trail")
                .isEqualTo(9);
        server.verify();
    }

    @Test
    @DisplayName("a field with a value is a loss, and one nobody filled in is not")
    void onlyPopulatedFieldsAreReportedAsDropped() {
        server.expect(requestTo(Matchers.endsWith("/field")))
                .andRespond(withSuccess(FIELDS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/search/jql")))
                .andRespond(withSuccess(FIRST_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("nextPageToken=more")))
                .andRespond(withSuccess(LAST_PAGE, MediaType.APPLICATION_JSON));

        List<SourceField> fields = source.snapshot(cloud(), "PROJ", count -> { }).fields();

        assertThat(fields).extracting(SourceField::name)
                .as("""
                    the preview's promise is about what will be LOST, so a field defined on the \
                    site and filled in by nobody is not a row — twenty of those on a screen whose \
                    whole job is to be believed is worse than none.""")
                .containsExactly("Story Points")
                .doesNotContain("Squad", "Summary");
        assertThat(fields.get(0).valueCount()).isEqualTo(1);
        assertThat(fields.get(0).custom()).isTrue();
    }

    @Test
    @DisplayName("comments come with the issue, keeping their author and their timestamp")
    void commentsAreReadWithTheIssue() {
        server.expect(requestTo(Matchers.endsWith("/field")))
                .andRespond(withSuccess(FIELDS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/search/jql")))
                .andRespond(withSuccess(FIRST_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("nextPageToken=more")))
                .andRespond(withSuccess(LAST_PAGE, MediaType.APPLICATION_JSON));

        SourceComment comment = source.snapshot(cloud(), "PROJ", count -> { })
                .issues().get(0).comments().get(0);

        assertThat(comment.authorName()).isEqualTo("Someone Else");
        assertThat(comment.authorEmail()).isEqualTo("someone@northwind.test");
        assertThat(comment.body()).isEqualTo("A comment the import keeps.");
    }

    @Test
    @DisplayName("a site that answers 500 is one ImportSourceUnavailableException")
    void aFailingSiteIsOneException() {
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("/project/search")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> source.projects(cloud()))
                .isInstanceOf(ImportSourceUnavailableException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("the guard refuses a private site before the double is ever asked")
    void aPrivateSiteIsRefusedBeforeTheCall() {
        ImportConnection internal = new ImportConnection(
                ImportProvider.JIRA_CLOUD, "https://localhost", "ops@northwind.test", "secret");

        assertThatThrownBy(() -> source.projects(internal))
                .isInstanceOf(ImportUrlRefusedException.class);
        server.verify();
    }

    private static ImportConnection cloud() {
        return new ImportConnection(
                ImportProvider.JIRA_CLOUD, SITE, "ops@northwind.test", "secret");
    }

    private static ImportConnection dataCentre() {
        return new ImportConnection(ImportProvider.JIRA_DATA_CENTER, SITE, null, "secret");
    }

    private static final String PROJECTS = """
            {"values": [
              {"key": "PROJ", "name": "Northwind Storefront",
               "insight": {"totalIssueCount": 1482}}
            ]}""";

    /**
     * {@code customfield_10016} is populated on one issue and {@code customfield_10099} on none —
     * the pair the dropped-field rule is about.
     */
    private static final String FIELDS = """
            [
              {"id": "summary", "name": "Summary", "custom": false,
               "schema": {"type": "string"}},
              {"id": "customfield_10016", "name": "Story Points", "custom": true,
               "schema": {"type": "number"}},
              {"id": "customfield_10099", "name": "Squad", "custom": true,
               "schema": {"type": "option"}}
            ]""";

    private static final String FIRST_PAGE = """
            {"nextPageToken": "more", "isLast": false, "issues": [
              {"key": "PROJ-1",
               "changelog": {"total": 5},
               "fields": {
                 "summary": "First issue",
                 "description": "As read from the source.",
                 "created": "2026-01-01T00:00:00.000+0000",
                 "issuetype": {"name": "Story", "subtask": false},
                 "status": {"name": "Backlog", "statusCategory": {"key": "new"}},
                 "labels": ["imported"],
                 "fixVersions": [{"name": "1.4.0", "released": false}],
                 "customfield_10016": 8,
                 "customfield_10099": null,
                 "comment": {"comments": [
                   {"author": {"displayName": "Someone Else",
                               "emailAddress": "someone@northwind.test"},
                    "body": "A comment the import keeps.",
                    "created": "2026-01-02T00:00:00.000+0000"}
                 ]}
               }}
            ]}""";

    private static final String LAST_PAGE = """
            {"isLast": true, "issues": [
              {"key": "PROJ-2",
               "changelog": {"total": 4},
               "fields": {
                 "summary": "Second issue",
                 "created": "2026-01-03T00:00:00.000+0000",
                 "issuetype": {"name": "Story", "subtask": false},
                 "status": {"name": "In Review", "statusCategory": {"key": "indeterminate"}},
                 "labels": [],
                 "fixVersions": [],
                 "customfield_10099": [],
                 "comment": {"comments": []}
               }}
            ]}""";
}
