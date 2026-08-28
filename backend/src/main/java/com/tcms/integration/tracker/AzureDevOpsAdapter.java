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
import java.util.stream.StreamSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Azure DevOps — {@code _apis/wit/wiql}, {@code _apis/wit/workitems?ids=}, {@code _apis/wit/workitems/${type}}.
 *
 * <h2>The org is in {@code externalProject}, not in the base URL</h2>
 *
 * <p>{@code tracker_binding.external_project} holds {@code org/teamProject} for this provider, and
 * {@code base_url} is the site ({@code https://dev.azure.com}, or a self-hosted Server address). It
 * is arranged that way because {@link TrackerIdentity} is keyed to the <em>host</em>: one PAT serves
 * every project in an organization, and often several organizations on the same site, so keying the
 * credential to the org would make a person connect once per board.
 *
 * <h2>Search is two calls and cannot be one</h2>
 *
 * <p>WIQL returns <strong>ids only</strong> — that is the API, not an omission — so a typeahead is a
 * query followed by a batch fetch of the ids it named. Both are inside one {@code search} call so no
 * caller has to know, and the batch is capped by the same limit the query is: an unbounded second
 * call is how a search for a common word becomes a megabyte of work items.
 *
 * <h2>The id is unique per organization, and the URL is id-addressable</h2>
 *
 * <p>Which makes ADO the easy half of D17: {@code /_workitems/edit/{id}} cannot go stale, and a work
 * item moved between team projects in one org keeps its id. Jira's browse URL is key-based and
 * therefore can; the stored {@code external_url} is a pointer allowed to be historical for exactly
 * that reason, and neither provider's URL is ever read as a label.
 */
/*
 * Absent under `tracker-fake`: FakeTrackerAdapter answers for every provider, and two adapters
 * claiming one provider would make which of them serves a request a property of bean ordering.
 */
@Profile("!tracker-fake")
@Component
class AzureDevOpsAdapter implements TrackerPort {

    /**
     * Pinned rather than latest. {@code api-version} is how ADO versions its wire shapes, and
     * omitting it makes every response's shape a property of the day the request was made.
     */
    private static final String API_VERSION = "7.1";

    private final TrackerClient client;
    private final ObjectMapper json;

    AzureDevOpsAdapter(TrackerClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public boolean supports(TrackerProvider provider) {
        return provider == TrackerProvider.AZURE_DEVOPS;
    }

    @Override
    public List<TrackerIssue> search(
            TrackerCredential credential, TrackerBindingTarget target, String query, int limit) {

        String wiqlUrl = scope(target) + "/_apis/wit/wiql?api-version=" + API_VERSION
                + "&$top=" + limit;
        ObjectNode payload = json.createObjectNode().put("query", wiql(target, query));

        JsonNode result = client.exchange(wiqlUrl, rest -> body(rest.post()
                .uri(wiqlUrl)
                .header(HttpHeaders.AUTHORIZATION, TrackerAuthHeader.of(credential))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload.toString())
                .retrieve()
                .toEntity(byte[].class)
                .getBody()));

        List<String> ids = StreamSupport.stream(result.path("workItems").spliterator(), false)
                .map(item -> item.path("id").asText())
                .filter(id -> !id.isBlank())
                .limit(limit)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        String batchUrl = target.baseUrl() + "/" + org(target) + "/_apis/wit/workitems"
                + "?ids=" + String.join(",", ids)
                + "&fields=System.Title,System.State"
                + "&api-version=" + API_VERSION;
        JsonNode batch = get(credential, batchUrl);

        List<TrackerIssue> issues = new ArrayList<>();
        for (JsonNode item : batch.path("value")) {
            issues.add(toIssue(target, item));
        }
        return issues;
    }

    @Override
    public Optional<TrackerIssue> get(
            TrackerCredential credential, TrackerBindingTarget target, String issueId) {

        String url = target.baseUrl() + "/" + org(target) + "/_apis/wit/workitems/"
                + URLEncoder.encode(issueId, StandardCharsets.UTF_8)
                + "?fields=System.Title,System.State&api-version=" + API_VERSION;
        try {
            return Optional.of(toIssue(target, get(credential, url)));
        } catch (TrackerUnavailableException notVisible) {
            // 404 and 401 are one answer here, per D16 — see JiraAdapter#get.
            return Optional.empty();
        }
    }

    @Override
    public TrackerIssue create(
            TrackerCredential credential, TrackerBindingTarget target, TrackerDraft draft) {

        String type = draft.issueType() != null ? draft.issueType() : target.setting("workItemType");
        String url = scope(target) + "/_apis/wit/workitems/$"
                + URLEncoder.encode(type == null ? "Bug" : type, StandardCharsets.UTF_8)
                + "?api-version=" + API_VERSION;

        // A JSON Patch document, which is ADO's create shape rather than a body of fields. The
        // media type is application/json-patch+json and a plain application/json is refused with a
        // message about the document rather than about the type, which is why it is spelled out.
        ArrayNode patch = json.createArrayNode();
        patch.addObject().put("op", "add").put("path", "/fields/System.Title")
                .put("value", draft.title());
        if (draft.body() != null) {
            patch.addObject().put("op", "add").put("path", "/fields/System.Description")
                    .put("value", draft.body());
        }
        String areaPath = draft.areaPath() != null ? draft.areaPath() : target.setting("areaPath");
        if (areaPath != null && !areaPath.isBlank()) {
            patch.addObject().put("op", "add").put("path", "/fields/System.AreaPath")
                    .put("value", areaPath);
        }

        JsonNode created = client.exchange(url, rest -> body(rest.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, TrackerAuthHeader.of(credential))
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .accept(MediaType.APPLICATION_JSON)
                .body(patch.toString())
                .retrieve()
                .toEntity(byte[].class)
                .getBody()));

        return toIssue(target, created);
    }

    @Override
    public void verify(TrackerCredential credential) {
        // Lists the organizations this token can see. Cheap, authenticated, and reads no work item —
        // so it proves the credential without also failing for somebody with no board access.
        String url = credential.baseUrl() + "/_apis/projects?api-version=" + API_VERSION + "&$top=1";
        get(credential, url);
    }

    /** {@code org} from the binding's {@code org/teamProject}. */
    private static String org(TrackerBindingTarget target) {
        String value = target.externalProject();
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    /** {@code base/org/teamProject}, or {@code base/org} when the binding names no team project. */
    private static String scope(TrackerBindingTarget target) {
        return target.baseUrl() + "/" + target.externalProject();
    }

    /**
     * The binding's WIQL scope clause and the person's text.
     *
     * <p>Single quotes are stripped from the text rather than escaped: WIQL's escape is a doubled
     * quote and getting that subtly wrong is a query that silently matches something else. A search
     * that finds nothing is a recoverable disappointment; a search that runs somebody's fragment is
     * not.
     */
    private static String wiql(TrackerBindingTarget target, String query) {
        String scope = target.setting("wiql");
        String where = scope != null && !scope.isBlank()
                ? "(" + scope + ")"
                : "[System.TeamProject] = @project";
        String text = query.replace("'", "").replace("\\", "");
        return "SELECT [System.Id] FROM WorkItems WHERE " + where
                + " AND [System.Title] CONTAINS '" + text + "'"
                + " ORDER BY [System.ChangedDate] DESC";
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

    private TrackerIssue toIssue(TrackerBindingTarget target, JsonNode item) {
        String id = item.path("id").asText();
        JsonNode fields = item.path("fields");
        String state = fields.path("System.State").asText("");
        return new TrackerIssue(
                TrackerProvider.AZURE_DEVOPS,
                id,
                // ADO has no second identifier: the id IS the key people say aloud. Returning it in
                // both places is honest — the alternative is inventing "PROJECT-123", which no ADO
                // user would recognise and no URL would accept.
                id,
                fields.path("System.Title").asText(""),
                category(state),
                state,
                target.baseUrl() + "/" + org(target) + "/_workitems/edit/" + id,
                target.bindingId());
    }

    /**
     * ADO's states, mapped onto the three categories.
     *
     * <p>State <em>names</em> are per process template and per team project — "Active" in Agile,
     * "Committed" in Scrum, whatever a customer renamed them to — so this is a best effort over the
     * common ones and everything unrecognised is {@code TODO}. Nothing branches on the name itself;
     * it is carried as {@code statusName} for display, which is the whole reason the category exists
     * as a separate, closed field.
     */
    private static TrackerIssueStatusCategory category(String state) {
        return switch (state) {
            case "Done", "Closed", "Completed", "Resolved", "Removed" ->
                    TrackerIssueStatusCategory.DONE;
            case "Active", "Committed", "In Progress", "Doing" ->
                    TrackerIssueStatusCategory.IN_PROGRESS;
            default -> TrackerIssueStatusCategory.TODO;
        };
    }
}
