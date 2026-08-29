package com.tcms.integration.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.integration.OutboundProperties;
import com.tcms.integration.OutboundUrlGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The two real adapters, against {@link MockRestServiceServer} — <strong>our parsing, not their
 * API</strong>.
 *
 * <h2>What this proves and what it cannot</h2>
 *
 * <p>Every fixture below is a document this repository wrote. So these tests pin the request we
 * send and the way we read an answer, and they say <em>nothing</em> about whether Jira or Azure
 * DevOps would send that answer. Plan 004 states the residual honestly and it is worth repeating at
 * the class that could be mistaken for closing it: field names, pagination shapes, JQL/WIQL
 * dialects, PAT header forms and error bodies are asserted against our own fixtures, and
 * <strong>"built and unverified" stays the honest sentence until one real person has connected,
 * previewed and filed once against each of the three</strong>.
 *
 * <p>The untested surface is <em>larger</em> under per-user credentials, because auth differs per
 * provider in ways a fixture cannot expose — Jira Cloud's Basic-with-email against Jira DC's Bearer
 * against ADO's org-scoped PAT semantics.
 *
 * <h2>{@code MockRestServiceServer} and no new dependency</h2>
 *
 * <p>{@code backend/build.gradle.kts} has no WireMock and no MockWebServer, and needs none:
 * {@code MockRestServiceServer.bindTo(RestClient.Builder)} intercepts at the request factory, which
 * is exactly where {@link TrackerClient} installs its timeouts and its redirect policy.
 *
 * <p><strong>What that costs, stated:</strong> binding to the builder replaces the request factory,
 * so the timeouts and {@code DONT_FOLLOW} this suite exercises are the ones the mock installs, not
 * the ones {@link TrackerClient} configures. Those are asserted by reading the class rather than by
 * driving it — a real hung socket is not something a unit test can hold open honestly.
 */
@DisplayName("Tracker transport — what we send, and how we read what comes back")
class TrackerTransportTest {

    private static final UUID BINDING = UUID.fromString("da001000-0000-4000-8000-000000000001");

    private final ObjectMapper json = new ObjectMapper();

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private TrackerClient client;

    @BeforeEach
    void bind() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // The BUILT client, not the builder. MockRestServiceServer binds by installing its own
        // request factory on the builder, and TrackerClient's production constructor installs the
        // real one over the top — so the mock was silently un-bound and this whole class dialled
        // acme.atlassian.net for real. `*.atlassian.net` resolves, which is why the failures read
        // as parse errors and a live Jira 400 rather than as "nothing reached the double".
        //
        // allow-private-hosts: the mock's URLs are ordinary https hosts that never resolve, and the
        // guard resolves before it permits. Turning it on is what lets the transport be exercised
        // without asserting anything about somebody's DNS — OutboundUrlGuardTest owns that half.
        client = new TrackerClient(
                builder.build(), new OutboundUrlGuard(new OutboundProperties(true)));
    }

    private static TrackerBindingTarget jiraTarget() {
        return new TrackerBindingTarget(
                BINDING, TrackerProvider.JIRA_CLOUD, "https://acme.atlassian.net", "PAY", Map.of());
    }

    private static TrackerCredential jiraCredential() {
        return new TrackerCredential(
                TrackerProvider.JIRA_CLOUD, "https://acme.atlassian.net",
                "tester@acme.test", "api-token-value");
    }

    private static TrackerBindingTarget adoTarget() {
        return new TrackerBindingTarget(
                BINDING, TrackerProvider.AZURE_DEVOPS, "https://dev.azure.com",
                "acme/payments", Map.of());
    }

    private static TrackerCredential adoCredential() {
        return new TrackerCredential(
                TrackerProvider.AZURE_DEVOPS, "https://dev.azure.com", null, "pat-value");
    }

    @Nested
    @DisplayName("Jira")
    class Jira {

        private JiraAdapter adapter;

        @BeforeEach
        void adapter() {
            adapter = new JiraAdapter(client, json);
        }

        @Test
        @DisplayName("a fetch is by the STABLE ID and the key comes back from the answer")
        void aFetchIsByIdAndTheKeyIsRead() {
            // 004 D17, at the transport. The URL carries 10042 and never PAY-991 — and the key the
            // chip renders is the one this response names, so an issue moved between projects
            // displays its NEW key with no write to any row of ours.
            server.expect(requestTo(Matchers.containsString("/rest/api/3/issue/10042")))
                    .andExpect(method(org.springframework.http.HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"id":"10042","key":"CHECKOUT-7","fields":{
                               "summary":"Fix the rounding",
                               "status":{"name":"In Progress",
                                         "statusCategory":{"key":"indeterminate"}}}}
                            """, MediaType.APPLICATION_JSON));

            Optional<TrackerIssue> issue = adapter.get(jiraCredential(), jiraTarget(), "10042");

            assertThat(issue).isPresent();
            assertThat(issue.get().id()).isEqualTo("10042");
            assertThat(issue.get().key())
                    .as("today's key, from the fetch — the row still says 10042")
                    .isEqualTo("CHECKOUT-7");
            assertThat(issue.get().title()).isEqualTo("Fix the rounding");
            assertThat(issue.get().statusCategory())
                    .isEqualTo(TrackerIssueStatusCategory.IN_PROGRESS);
            assertThat(issue.get().statusName())
                    .as("carried for display and branched on by nothing — status names are per "
                            + "project and unbounded")
                    .isEqualTo("In Progress");
            assertThat(issue.get().url())
                    .as("built from the key the fetch returned, which is the only place a fetched "
                            + "value becomes a URL — and it is fine because it is fetched")
                    .isEqualTo("https://acme.atlassian.net/browse/CHECKOUT-7");
            server.verify();
        }

        @Test
        @DisplayName("Jira Cloud authenticates with Basic email:token")
        void jiraCloudSendsBasic() {
            // Not a style question: Jira Cloud's API token is useless without the account's email,
            // which is why tracker_identity has an account_id and why the connect form asks for one
            // on this provider and not the others.
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "tester@acme.test:api-token-value".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));

            server.expect(requestTo(Matchers.containsString("/rest/api/3/issue/10042")))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
                    .andRespond(withSuccess("""
                            {"id":"10042","key":"PAY-1","fields":{"summary":"t",
                             "status":{"name":"To Do","statusCategory":{"key":"new"}}}}
                            """, MediaType.APPLICATION_JSON));

            adapter.get(jiraCredential(), jiraTarget(), "10042");
            server.verify();
        }

        @Test
        @DisplayName("Jira Data Center authenticates with Bearer, and is on the v2 API")
        void jiraDataCentreSendsBearer() {
            TrackerBindingTarget target = new TrackerBindingTarget(
                    BINDING, TrackerProvider.JIRA_DC, "https://jira.acme.test", "PAY", Map.of());
            TrackerCredential credential = new TrackerCredential(
                    TrackerProvider.JIRA_DC, "https://jira.acme.test", null, "pat-value");

            server.expect(requestTo(Matchers.containsString("/rest/api/2/issue/10042")))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat-value"))
                    .andRespond(withSuccess("""
                            {"id":"10042","key":"PAY-1","fields":{"summary":"t",
                             "status":{"name":"Done","statusCategory":{"key":"done"}}}}
                            """, MediaType.APPLICATION_JSON));

            assertThat(adapter.get(credential, target, "10042"))
                    .map(TrackerIssue::statusCategory)
                    .contains(TrackerIssueStatusCategory.DONE);
            server.verify();
        }

        @Test
        @DisplayName("a refusal reads as no issue, not as an error, and says nothing about which")
        void aRefusalIsAnEmptyAnswer() {
            // 004 D16. Jira answers 404 for an issue this credential cannot see and 401 for a token
            // it will not accept. Both are one empty answer here, so nothing downstream can turn
            // the preview into an oracle for what exists in a tracker the viewer cannot read.
            server.expect(requestTo(Matchers.containsString("/issue/10042")))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertThat(adapter.get(jiraCredential(), jiraTarget(), "10042")).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("a search quotes the person's text and scopes it by the binding")
        void aSearchIsScoped() {
            server.expect(requestTo(Matchers.allOf(
                            Matchers.containsString("/rest/api/3/search/jql"),
                            Matchers.containsString("project"),
                            Matchers.containsString("rounding"))))
                    .andRespond(withSuccess("""
                            {"issues":[
                               {"id":"10042","key":"PAY-991","fields":{"summary":"Fix the rounding",
                                "status":{"name":"To Do","statusCategory":{"key":"new"}}}}]}
                            """, MediaType.APPLICATION_JSON));

            List<TrackerIssue> found =
                    adapter.search(jiraCredential(), jiraTarget(), "rounding", 20);

            assertThat(found).singleElement()
                    .satisfies(issue -> assertThat(issue.bindingId())
                            .as("the binding the caller asked through, not one the tracker named")
                            .isEqualTo(BINDING));
            server.verify();
        }

        @Test
        @DisplayName("a tracker that is down is one exception, carrying its own words")
        void anOutageThrows() {
            server.expect(requestTo(Matchers.containsString("/myself")))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> adapter.verify(jiraCredential()))
                    .isInstanceOf(TrackerUnavailableException.class);
            server.verify();
        }

        @Test
        @DisplayName("an unrecognised status category renders TODO rather than failing the page")
        void anUnknownCategoryIsTodo() {
            // A colour on a chip. A tracker that grows a fourth category must not make a case page
            // fail to render, which is what an exhaustive switch here would do.
            server.expect(requestTo(Matchers.containsString("/issue/10042")))
                    .andRespond(withSuccess("""
                            {"id":"10042","key":"PAY-1","fields":{"summary":"t",
                             "status":{"name":"Whatever","statusCategory":{"key":"brand-new"}}}}
                            """, MediaType.APPLICATION_JSON));

            assertThat(adapter.get(jiraCredential(), jiraTarget(), "10042"))
                    .map(TrackerIssue::statusCategory)
                    .contains(TrackerIssueStatusCategory.TODO);
            server.verify();
        }
    }

    @Nested
    @DisplayName("Azure DevOps")
    class AzureDevOps {

        private AzureDevOpsAdapter adapter;

        @BeforeEach
        void adapter() {
            adapter = new AzureDevOpsAdapter(client, json);
        }

        @Test
        @DisplayName("a PAT goes as Basic with an EMPTY username, not as Bearer")
        void aPatIsBasicWithNoUser() {
            // The one line of plan 004's provider table this code does not follow, recorded at
            // TrackerAuthHeader: Authorization: Bearer on ADO carries an ENTRA token, and a PAT sent
            // that way is refused. Microsoft's documented form for a PAT is Basic with a blank user.
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    ":pat-value".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            server.expect(requestTo(Matchers.containsString("/_apis/wit/workitems/42")))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, expected))
                    .andRespond(withSuccess("""
                            {"id":42,"fields":{"System.Title":"Rounding",
                             "System.State":"Active"}}
                            """, MediaType.APPLICATION_JSON));

            assertThat(adapter.get(adoCredential(), adoTarget(), "42")).isPresent();
            server.verify();
        }

        @Test
        @DisplayName("the URL is id-addressable, so it cannot go stale")
        void theUrlIsIdAddressable() {
            // The easy half of D17: a work item moved between team projects in one organization
            // keeps its id, and /_workitems/edit/{id} keeps resolving. Jira's browse URL is
            // key-based and can go stale, which is why external_url is a pointer and never a label.
            server.expect(requestTo(Matchers.containsString("/_apis/wit/workitems/42")))
                    .andRespond(withSuccess("""
                            {"id":42,"fields":{"System.Title":"Rounding",
                             "System.State":"Closed"}}
                            """, MediaType.APPLICATION_JSON));

            Optional<TrackerIssue> issue = adapter.get(adoCredential(), adoTarget(), "42");

            assertThat(issue).isPresent();
            assertThat(issue.get().url())
                    .isEqualTo("https://dev.azure.com/acme/_workitems/edit/42");
            assertThat(issue.get().key())
                    .as("ADO has no second identifier — the id IS what people say aloud, and "
                            + "inventing PROJECT-123 would name something no URL accepts")
                    .isEqualTo("42");
            assertThat(issue.get().statusCategory()).isEqualTo(TrackerIssueStatusCategory.DONE);
            server.verify();
        }

        @Test
        @DisplayName("a search is WIQL then a batch fetch — two calls, and it cannot be one")
        void aSearchIsTwoCalls() {
            // WIQL returns ids only. That is the API rather than an omission, so the adapter absorbs
            // it and no caller has to know; the batch is capped by the same limit the query is.
            server.expect(requestTo(Matchers.containsString("/_apis/wit/wiql")))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andRespond(withSuccess(
                            "{\"workItems\":[{\"id\":42},{\"id\":43}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(Matchers.containsString("ids=42,43")))
                    .andRespond(withSuccess("""
                            {"value":[
                              {"id":42,"fields":{"System.Title":"Rounding","System.State":"Active"}},
                              {"id":43,"fields":{"System.Title":"Rounding two","System.State":"New"}}]}
                            """, MediaType.APPLICATION_JSON));

            assertThat(adapter.search(adoCredential(), adoTarget(), "rounding", 20))
                    .extracting(TrackerIssue::id)
                    .containsExactly("42", "43");
            server.verify();
        }

        @Test
        @DisplayName("a create is a JSON Patch document, not a body of fields")
        void aCreateIsAPatchDocument() {
            server.expect(requestTo(Matchers.containsString("/_apis/wit/workitems/$Bug")))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(header(HttpHeaders.CONTENT_TYPE,
                            Matchers.startsWith("application/json-patch+json")))
                    .andRespond(withSuccess("""
                            {"id":44,"fields":{"System.Title":"Filed","System.State":"New"}}
                            """, MediaType.APPLICATION_JSON));

            TrackerIssue created = adapter.create(adoCredential(), adoTarget(),
                    new TrackerDraft("Filed", "a body", null, null));

            assertThat(created.id()).isEqualTo("44");
            server.verify();
        }

        @Test
        @DisplayName("no work items is an empty list and no second call")
        void anEmptyQueryMakesNoSecondCall() {
            // A batch fetch with `ids=` and nothing after it is a 400 from ADO — a search for a word
            // nobody used would otherwise report the tracker as unavailable.
            server.expect(requestTo(Matchers.containsString("/_apis/wit/wiql")))
                    .andRespond(withSuccess("{\"workItems\":[]}", MediaType.APPLICATION_JSON));

            assertThat(adapter.search(adoCredential(), adoTarget(), "nothing", 20)).isEmpty();
            server.verify();
        }
    }

    @Nested
    @DisplayName("The client")
    class Client {

        @Test
        @DisplayName("a refused destination never reaches the transport")
        void theGuardRunsAtCallTime() {
            // Enforced here as well as at the write, and the second check is not belt-and-braces:
            // DNS can start answering differently after the row was written, and a row can predate
            // the guard entirely.
            TrackerClient enforcing =
                    new TrackerClient(
                            builder.build(), new OutboundUrlGuard(new OutboundProperties(false)));

            assertThatThrownBy(() -> enforcing.exchange(
                            "http://acme.atlassian.net/rest/api/3/myself",
                            rest -> rest.get().uri("http://acme.atlassian.net").retrieve()
                                    .toBodilessEntity()))
                    .isInstanceOf(TrackerUnavailableException.class);

            // The mock server was never called, which is the actual claim: a refusal is not a
            // failed request, it is a request that did not happen.
            server.verify();
        }

        @Test
        @DisplayName("a refusal names the host and not the path somebody typed")
        void aRefusalNamesTheHostOnly() {
            TrackerClient enforcing =
                    new TrackerClient(
                            builder.build(), new OutboundUrlGuard(new OutboundProperties(false)));

            assertThatThrownBy(() -> enforcing.exchange(
                            "http://acme.atlassian.net/rest/api/3/issue/10042?jql=secret",
                            rest -> null))
                    .hasMessageContaining("acme.atlassian.net")
                    .hasMessageNotContaining("jql=secret");
        }

        @Test
        @DisplayName("an over-long body is refused rather than read into the heap")
        void theResponseIsCapped() {
            byte[] tooLarge = new byte[1024 * 1024 + 1];

            assertThatThrownBy(() -> TrackerClient.withinCap(tooLarge))
                    .isInstanceOf(TrackerUnavailableException.class);
            assertThat(TrackerClient.withinCap("{}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8))).isEqualTo("{}");
        }
    }
}
