package com.tcms.integration.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Jira, both hostings — {@code /rest/api/{3|2}/search/jql}, {@code /issue/{id}}, {@code /issue}.
 *
 * <h2>One class for two providers</h2>
 *
 * <p>Cloud and Data Center differ in the REST version in the path and in how the token is presented
 * ({@link TrackerAuthHeader}), and in nothing this product asks for: the issue document's
 * {@code id}, {@code key}, {@code fields.summary} and {@code fields.status.statusCategory.key} are
 * the same shape on both. Two classes would be two copies of one parser kept in step by intention.
 *
 * <h2>The id is what is fetched, and the key is what is returned</h2>
 *
 * <p>{@code GET /issue/{issueIdOrKey}} takes either, and this adapter only ever sends the stable
 * numeric id (004 D17). What comes back carries <strong>today's</strong> key, which is the whole
 * argument for storing the id: an issue moved between projects displays its new key with no write
 * to any row of ours.
 *
 * <p>The browse URL is built from the returned key rather than from the id, because Jira's is
 * key-addressable and there is no id-based equivalent. That is the one place a fetched value becomes
 * a URL, and it is fine precisely because it is fetched — the stored {@code external_url} is allowed
 * to be historical and is never a label.
 */
/*
 * Absent under `tracker-fake`: FakeTrackerAdapter answers for every provider, and two adapters
 * claiming one provider would make which of them serves a request a property of bean ordering.
 */
@Profile("!tracker-fake")
@Component
class JiraAdapter implements TrackerPort {

    private final TrackerClient client;
    private final ObjectMapper json;

    JiraAdapter(TrackerClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public boolean supports(TrackerProvider provider) {
        return provider == TrackerProvider.JIRA_CLOUD || provider == TrackerProvider.JIRA_DC;
    }

    /**
     * Cloud is on v3 and Data Center is on v2, and the difference is real rather than cosmetic:
     * v3 answers descriptions in Atlassian Document Format and v2 in wiki markup. This product reads
     * neither — it asks for {@code summary} and {@code status} — so the version is only ever the
     * path segment each host actually serves.
     */
    private static String api(TrackerProvider provider) {
        return provider == TrackerProvider.JIRA_CLOUD ? "/rest/api/3" : "/rest/api/2";
    }

    @Override
    public List<TrackerIssue> search(
            TrackerCredential credential, TrackerBindingTarget target, String query, int limit) {

        String url = target.baseUrl() + api(credential.provider()) + "/search/jql"
                + "?maxResults=" + limit
                + "&fields=summary,status"
                + "&jql=" + URLEncoder.encode(jql(target, query), StandardCharsets.UTF_8);

        JsonNode body = get(credential, url);
        List<TrackerIssue> issues = new ArrayList<>();
        for (JsonNode issue : body.path("issues")) {
            issues.add(toIssue(target, issue));
        }
        return issues;
    }

    @Override
    public Optional<TrackerIssue> get(
            TrackerCredential credential, TrackerBindingTarget target, String issueId) {

        String url = target.baseUrl() + api(credential.provider()) + "/issue/"
                + URLEncoder.encode(issueId, StandardCharsets.UTF_8) + "?fields=summary,status";
        try {
            return Optional.of(toIssue(target, get(credential, url)));
        } catch (TrackerUnavailableException notVisible) {
            // A 404 and a 403 are the same answer here, deliberately (D16): Jira answers 404 for an
            // issue the caller cannot see, and distinguishing the two would make this an existence
            // oracle for a tracker the viewer has no access to.
            return Optional.empty();
        }
    }

    @Override
    public TrackerIssue create(
            TrackerCredential credential, TrackerBindingTarget target, TrackerDraft draft) {

        ObjectNode fields = json.createObjectNode();
        fields.putObject("project").put("key", target.externalProject());
        fields.put("summary", draft.title());
        String issueType = draft.issueType() != null ? draft.issueType() : target.setting("issueType");
        fields.putObject("issuetype").put("name", issueType == null ? "Bug" : issueType);
        if (draft.body() != null && credential.provider() == TrackerProvider.JIRA_DC) {
            // v2 takes a plain string. v3 wants Atlassian Document Format, and composing one here
            // would be this product owning a document model for a field it never reads back — so
            // Cloud gets the body through the description-less path below rather than a half-ADF
            // guess that Jira rejects with a message nobody can act on.
            fields.put("description", draft.body());
        }
        if (draft.body() != null && credential.provider() == TrackerProvider.JIRA_CLOUD) {
            fields.set("description", adf(draft.body()));
        }
        ObjectNode payload = json.createObjectNode();
        payload.set("fields", fields);

        String url = target.baseUrl() + api(credential.provider()) + "/issue";
        JsonNode created = client.exchange(url, rest -> body(rest.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, TrackerAuthHeader.of(credential))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload.toString())
                .retrieve()
                .toEntity(byte[].class)
                .getBody()));

        // The create answers id and key and nothing else, so the issue is re-read to get its title
        // and status — one extra call on the one path where a person is waiting for a link anyway,
        // and the alternative is a chip that renders "no status" for its first sixty seconds.
        String id = created.path("id").asText();
        return get(credential, target, id).orElseGet(() -> new TrackerIssue(
                credential.provider(),
                id,
                created.path("key").asText(),
                draft.title(),
                TrackerIssueStatusCategory.TODO,
                "",
                browseUrl(target, created.path("key").asText()),
                target.bindingId()));
    }

    @Override
    public void verify(TrackerCredential credential) {
        // The cheapest authenticated call Jira has: it says who the token belongs to and reads
        // nothing. A search would also prove the credential and would additionally fail for a
        // person with no browse permission anywhere, which is a different fact.
        String url = credential.baseUrl() + api(credential.provider()) + "/myself";
        get(credential, url);
    }

    /**
     * The binding's scope clause, and the person's text.
     *
     * <p>The clause is the operator's — a project, a component, a label — and the text is quoted
     * into {@code text ~ "…"} rather than interpolated, so a query containing a quote is a search
     * that finds nothing rather than a JQL fragment somebody's typing composed.
     */
    private static String jql(TrackerBindingTarget target, String query) {
        String scope = target.setting("jql");
        String base = scope != null && !scope.isBlank()
                ? "(" + scope + ")"
                : "project = \"" + target.externalProject().replace("\"", "") + "\"";
        String text = query.replace("\"", "").replace("\\", "");
        return base + " AND text ~ \"" + text + "\" ORDER BY updated DESC";
    }

    private JsonNode get(TrackerCredential credential, String url) {
        return client.exchange(url, rest -> body(rest.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, TrackerAuthHeader.of(credential))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(byte[].class)
                .getBody()));
    }

    private JsonNode body(byte[] raw) {
        try {
            return json.readTree(TrackerClient.withinCap(raw));
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new TrackerUnavailableException(
                    "The tracker's answer was not JSON this product could read.", unreadable);
        }
    }

    private TrackerIssue toIssue(TrackerBindingTarget target, JsonNode issue) {
        JsonNode status = issue.path("fields").path("status");
        String key = issue.path("key").asText();
        return new TrackerIssue(
                target.provider(),
                issue.path("id").asText(),
                key,
                issue.path("fields").path("summary").asText(""),
                category(status.path("statusCategory").path("key").asText("")),
                status.path("name").asText(""),
                browseUrl(target, key),
                target.bindingId());
    }

    /**
     * Jira's three category keys, mapped onto ours.
     *
     * <p>An unknown key is {@link TrackerIssueStatusCategory#TODO} rather than an exception: this is
     * a colour on a chip, and a tracker that grows a fourth category must not make a case page fail
     * to render.
     */
    private static TrackerIssueStatusCategory category(String key) {
        return switch (key) {
            case "done" -> TrackerIssueStatusCategory.DONE;
            case "indeterminate" -> TrackerIssueStatusCategory.IN_PROGRESS;
            default -> TrackerIssueStatusCategory.TODO;
        };
    }

    private static String browseUrl(TrackerBindingTarget target, String key) {
        return target.baseUrl() + "/browse/" + key;
    }

    /** The minimal Atlassian Document Format a v3 description will accept: one paragraph of text. */
    private ObjectNode adf(String text) {
        ObjectNode doc = json.createObjectNode();
        doc.put("type", "doc").put("version", 1);
        ArrayNode content = doc.putArray("content");
        ObjectNode paragraph = content.addObject();
        paragraph.put("type", "paragraph");
        paragraph.putArray("content").addObject().put("type", "text").put("text", text);
        return doc;
    }
}
