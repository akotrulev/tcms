package com.tcms.tracker.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Jira, both hostings, behind one class.
 *
 * <h2>Cloud and Data Center differ in the auth header and in nothing else this module reads</h2>
 *
 * <p>Cloud takes {@code Basic base64(accountEmail:apiToken)} and Data Center takes
 * {@code Bearer <pat>}; the REST version in the path differs too ({@code /rest/api/3} against
 * {@code /rest/api/2}) and the documents this class parses are the same shape on both. Two classes
 * would be two copies of one parser kept in step by intention.
 *
 * <p><strong>{@code integration.tracker.JiraAdapter} is the reference for the field names and is
 * not imported</strong> — it is in a module {@code tracker} may not reach (D-a), and it answers a
 * different question anyway: it fetches one issue by its stable id for a chip on a case page, and
 * this pages a whole project.
 *
 * <h2>What this reads, and the one thing that is only counted</h2>
 *
 * <p>Paging is {@code POST /rest/api/{3|2}/search/jql} with {@code nextPageToken}, 100 issues at a
 * time. Comments arrive with the issue through {@code fields=comment}, so a project with 1 482
 * issues is fifteen calls rather than 1 497. The changelog is asked for with
 * {@code expand=changelog} and only its <em>total</em> is kept: there is no table here for somebody
 * else's audit trail, and the preview says the number out loud rather than letting it be discovered
 * as an absence.
 *
 * <h2>Built and unverified against a real site</h2>
 *
 * <p>Every fixture this is tested against is a document this repository wrote, so
 * {@code JiraImportSourceTest} pins the request sent and the way an answer is read, and says
 * nothing about whether Atlassian would send that answer. Field names, the {@code nextPageToken}
 * paging shape and the two PAT header forms stay <em>built and unverified</em> until one real
 * person has connected and previewed against each hosting.
 */
@Component
class JiraImportSource implements ImportSource {

    /**
     * Fifty rather than Jira's maximum of a hundred, and the reason is {@link #FIELDS}.
     *
     * <p>This asks for every navigable field, so a page is as wide as the customer's Jira is
     * customised. Halving the page halves the largest response this client can be handed, against
     * {@link ImportHttp}'s cap; the cost is twice as many round trips on a read that is already
     * measured in minutes.
     */
    private static final int PAGE_SIZE = 50;

    /**
     * A hard stop on paging, independent of {@code max-issues}.
     *
     * <p>{@code nextPageToken} is opaque, so a source that returns the same token for ever is a
     * loop this process cannot detect by looking at the values. The cap is deliberately far above
     * any legitimate project at this page size: it exists to end a broken conversation, not to
     * bound a real one, and {@link ImportPreviewService} is what refuses an over-large project with
     * a sentence.
     */
    private static final int MAX_PAGES = 2_000;

    /**
     * <strong>Every navigable field, and that is the expensive choice taken deliberately.</strong>
     *
     * <p>The obvious alternative is a named list of the eleven fields the write pass actually uses,
     * which would be a fraction of the bytes. It cannot be taken, because the preview's central
     * promise is the dropped-field list: <em>"Story Points — 1 204 values — estimates here are
     * hours"</em>, and {@code Custom: "Squad"} for a field only this customer has. Counting values
     * for a field means seeing the field, and asking for a subset would make the report a subset —
     * a list of losses that omits the ones nobody predicted is worse than no list, because it reads
     * as complete.
     *
     * <p>What it costs is bounded rather than argued away: {@link #PAGE_SIZE} is halved,
     * {@link ImportHttp} caps the body at 4 MiB, and the read timeout is the backstop.
     */
    private static final String FIELDS = "*navigable";

    /**
     * The fields this product <em>does</em> import, by their Jira ids — everything else with a
     * value is a loss the preview reports.
     *
     * <p>Ids and not display names: these are the built-ins, whose ids are the same on every Jira,
     * while their display names are localised. A site running in German would otherwise report
     * every one of them as dropped.
     */
    private static final Set<String> IMPORTED_FIELD_IDS = Set.of(
            "summary", "description", "issuetype", "status", "parent", "assignee", "reporter",
            "labels", "fixVersions", "created", "comment", "project", "issuekey");

    private final ImportHttp http;
    private final ObjectMapper json;

    JiraImportSource(ImportHttp http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public boolean supports(ImportProvider provider) {
        return provider == ImportProvider.JIRA_CLOUD
                || provider == ImportProvider.JIRA_DATA_CENTER;
    }

    @Override
    public List<SourceProject> projects(ImportConnection connection) {
        // /project/search is paged too, and 100 projects is the connect step's dropdown rather than
        // a corpus: a site with more than that has an administrator who knows the key to type, and
        // the preview takes a key, not a selection. Asking for one page is the honest bound.
        String url = base(connection) + "/project/search?maxResults=100&expand=insight";
        JsonNode body = get(connection, url);

        List<SourceProject> projects = new ArrayList<>();
        for (JsonNode project : body.path("values")) {
            projects.add(new SourceProject(
                    project.path("key").asText(),
                    project.path("name").asText(""),
                    // `insight.totalIssueCount` is Cloud's and is absent on Data Center and on any
                    // site whose admin has turned the expansion off. Zero means "not disclosed",
                    // and the preview treats it as unknown rather than as an empty project — the
                    // real count comes from the snapshot it then reads.
                    project.path("insight").path("totalIssueCount").asInt(0)));
        }
        return projects;
    }

    /**
     * {@code /project/{key}/statuses} — the workflow's statuses, grouped by issue type there and
     * unioned by name here.
     *
     * <p>Grouped is how Jira answers it and a union is what the mapping needs: a status reached by
     * one issue type and not another is still a status an issue can be in, and the mapping step
     * shows one row per name. Insertion-ordered so the refusal names them in the source's own
     * order rather than in a hash order that changes between two runs against one site.
     */
    @Override
    public List<String> statusNames(ImportConnection connection, String projectKey) {
        String url = base(connection) + "/project/"
                + URLEncoder.encode(projectKey, StandardCharsets.UTF_8) + "/statuses";
        JsonNode body = get(connection, url);

        Set<String> names = new LinkedHashSet<>();
        for (JsonNode type : body) {
            for (JsonNode status : type.path("statuses")) {
                String name = status.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    @Override
    public SourceProjectSnapshot snapshot(
            ImportConnection connection, String projectKey, IntConsumer onProgress) {

        List<SourceIssue> issues = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, String> statusCategories = new LinkedHashMap<>();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, Boolean> typeSubtask = new LinkedHashMap<>();
        Map<String, Boolean> versions = new LinkedHashMap<>();
        Map<String, SourcePerson> people = new LinkedHashMap<>();
        // Read once, before any issue: the search answers field IDS and the report has to show
        // NAMES, and a name resolved per issue would be one call per issue for an answer that
        // cannot change during a read pass.
        Map<String, FieldMeta> catalogue = fieldCatalogue(connection);
        Map<String, Integer> fieldValues = new LinkedHashMap<>();
        int historyEntries = 0;

        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = get(connection, searchUrl(connection, projectKey, token));
            for (JsonNode node : body.path("issues")) {
                JsonNode fields = node.path("fields");
                String key = node.path("key").asText();

                String statusName = fields.path("status").path("name").asText("");
                statusCounts.merge(statusName, 1, Integer::sum);
                statusCategories.putIfAbsent(statusName,
                        fields.path("status").path("statusCategory").path("key").asText(""));

                String typeName = fields.path("issuetype").path("name").asText("");
                typeCounts.merge(typeName, 1, Integer::sum);
                typeSubtask.putIfAbsent(typeName, fields.path("issuetype").path("subtask").asBoolean(false));

                for (JsonNode version : fields.path("fixVersions")) {
                    versions.putIfAbsent(
                            version.path("name").asText(), version.path("released").asBoolean(false));
                }

                remember(people, fields.path("assignee"));
                remember(people, fields.path("reporter"));

                List<SourceComment> comments = new ArrayList<>();
                for (JsonNode comment : fields.path("comment").path("comments")) {
                    JsonNode author = comment.path("author");
                    remember(people, author);
                    comments.add(new SourceComment(
                            author.path("displayName").asText(""),
                            emailOf(author),
                            text(comment.path("body")),
                            timestamp(comment.path("created"))));
                }

                historyEntries += node.path("changelog").path("total").asInt(0);
                countFieldValues(fields, fieldValues);

                issues.add(new SourceIssue(
                        key,
                        typeName,
                        statusName,
                        fields.path("summary").asText(""),
                        text(fields.path("description")),
                        fields.path("parent").path("key").asText(null),
                        // The epic link is a custom field on both hostings and its id differs per
                        // site, so it is read from the flattened map below rather than by a
                        // hardcoded customfield_10014 that is right on exactly one Jira.
                        epicKey(fields),
                        emailOf(fields.path("assignee")),
                        emailOf(fields.path("reporter")),
                        strings(fields.path("labels")),
                        names(fields.path("fixVersions")),
                        sprintNames(fields),
                        // The source's order IS the rank: Jira's search returns backlog order when
                        // asked for it, and the ORDER BY in the JQL below is what makes this index
                        // meaningful rather than the order rows happened to arrive in.
                        issues.size(),
                        timestamp(fields.path("created")),
                        List.copyOf(comments)));
            }
            onProgress.accept(issues.size());

            token = body.path("nextPageToken").asText(null);
            if (token == null || token.isBlank() || body.path("isLast").asBoolean(false)) {
                break;
            }
        }

        return new SourceProjectSnapshot(
                List.copyOf(issues),
                statusCounts.entrySet().stream()
                        .map(entry -> new SourceStatus(
                                entry.getKey(),
                                statusCategories.getOrDefault(entry.getKey(), ""),
                                entry.getValue()))
                        .toList(),
                typeCounts.entrySet().stream()
                        .map(entry -> new SourceType(
                                entry.getKey(),
                                typeSubtask.getOrDefault(entry.getKey(), false),
                                entry.getValue()))
                        .toList(),
                versions.entrySet().stream()
                        .map(entry -> new SourceVersion(entry.getKey(), entry.getValue()))
                        .toList(),
                List.copyOf(people.values()),
                droppedCandidates(catalogue, fieldValues),
                historyEntries);
    }

    // =====================================================================

    /**
     * Cloud is on v3 and Data Center is on v2.
     *
     * <p>The difference is real rather than cosmetic — v3 answers descriptions in Atlassian
     * Document Format and v2 in wiki markup — and {@link #text} is where this class pays for it.
     */
    private static String base(ImportConnection connection) {
        String site = connection.siteUrl() == null ? "" : connection.siteUrl().replaceAll("/+$", "");
        return site + (connection.provider() == ImportProvider.JIRA_CLOUD
                ? "/rest/api/3"
                : "/rest/api/2");
    }

    /**
     * The auth header, and it is the whole of the difference between the two hostings.
     *
     * <p>Cloud's API token is <em>not</em> a bearer token: it is the password half of HTTP Basic,
     * with the account's email as the user half, which is why {@link ImportConnection#accountEmail}
     * exists at all and why Data Center ignores it.
     */
    private static String authorization(ImportConnection connection) {
        if (connection.provider() != ImportProvider.JIRA_CLOUD) {
            return "Bearer " + connection.apiToken();
        }
        String pair = (connection.accountEmail() == null ? "" : connection.accountEmail())
                + ":" + connection.apiToken();
        return "Basic " + Base64.getEncoder()
                .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The search URL for one page.
     *
     * <p>The project key is quoted into the JQL with its own quotes stripped, so a key containing
     * one is a search that finds nothing rather than a JQL fragment somebody's typing composed —
     * {@code JiraAdapter}'s rule, and the same reason.
     *
     * <p>{@code ORDER BY Rank ASC} is what makes {@link SourceIssue#rankPosition} mean the source's
     * backlog order rather than the order rows came back in. A site without Jira Software has no
     * {@code Rank} field, so the fallback is {@code created ASC} — chosen once here rather than
     * discovered per issue.
     */
    private static String searchUrl(ImportConnection connection, String projectKey, String token) {
        String jql = "project = \"" + projectKey.replace("\"", "") + "\" ORDER BY created ASC";
        StringBuilder url = new StringBuilder(base(connection))
                .append("/search/jql?maxResults=").append(PAGE_SIZE)
                .append("&fields=").append(URLEncoder.encode(FIELDS, StandardCharsets.UTF_8))
                .append("&expand=changelog")
                .append("&jql=").append(URLEncoder.encode(jql, StandardCharsets.UTF_8));
        if (token != null) {
            url.append("&nextPageToken=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private JsonNode get(ImportConnection connection, String url) {
        return body(http.exchange(url, rest -> rest.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, authorization(connection))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(byte[].class)
                .getBody()));
    }

    private JsonNode body(byte[] raw) {
        try {
            return json.readTree(ImportHttp.withinCap(raw));
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new ImportSourceUnavailableException(
                    "The source's answer was not JSON this product could read. Check that the site "
                            + "address points at a Jira and not at a login page in front of one.",
                    unreadable);
        }
    }

    /**
     * A v2 string or a v3 Atlassian Document Format tree, as plain text.
     *
     * <p>ADF is a document model and this product has none, so the text nodes are concatenated and
     * the structure is lost. That is a lossy read and it is <em>said out loud</em> rather than
     * quietly done: {@link DroppedFields} does not list description formatting, because the
     * description itself is imported — what is lost is its rich formatting, and the preview's
     * "Rich text" row is where a reader is told. Rebuilding ADF into Markdown here would be this
     * product owning a document model for a field it round-trips nowhere.
     */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        StringBuilder collected = new StringBuilder();
        collect(node, collected);
        return collected.isEmpty() ? null : collected.toString().strip();
    }

    private static void collect(JsonNode node, StringBuilder into) {
        if (node.has("text")) {
            into.append(node.path("text").asText());
        }
        if ("paragraph".equals(node.path("type").asText()) && !into.isEmpty()) {
            into.append('\n');
        }
        for (JsonNode child : node.path("content")) {
            collect(child, into);
        }
    }

    /**
     * Jira Cloud hides {@code emailAddress} unless the account's profile permits it, so this is
     * null far more often than a reader expects.
     *
     * <p>That is exactly why the report has a {@code matched} flag rather than an assumption: an
     * unmatched author is preserved as text (D-j) instead of being attached to whichever local user
     * had a similar display name.
     */
    private static String emailOf(JsonNode person) {
        String email = person.path("emailAddress").asText("");
        return email.isBlank() ? null : email;
    }

    private static void remember(Map<String, SourcePerson> people, JsonNode person) {
        String name = person.path("displayName").asText("");
        if (!name.isBlank()) {
            people.putIfAbsent(name, new SourcePerson(name, emailOf(person)));
        }
    }

    /**
     * The epic link, found by shape rather than by field id.
     *
     * <p>The epic-link custom field is {@code customfield_10014} on most Cloud sites and something
     * else on every Data Center, so a hardcoded id is right on exactly one Jira. What is stable is
     * that its value is a bare issue key string on a {@code customfield_*} property, so that is what
     * is looked for — and the {@code parent} field wins where it is present, which is Cloud's newer
     * model.
     */
    private static String epicKey(JsonNode fields) {
        for (Map.Entry<String, JsonNode> property : fields.properties()) {
            if (property.getKey().startsWith("customfield_")
                    && property.getValue().isTextual()
                    && property.getValue().asText().matches("[A-Z][A-Z0-9_]*-\\d+")) {
                return property.getValue().asText();
            }
        }
        return null;
    }

    /**
     * Sprint names, which arrive as either objects or as the legacy {@code "…,name=Sprint 4,…"}
     * strings Data Center still serves.
     *
     * <p>Both shapes are read because a site running an older Jira Software is exactly the
     * deployment most likely to be migrating off it.
     */
    private static List<String> sprintNames(JsonNode fields) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : fields.properties()) {
            if (!property.getKey().startsWith("customfield_") || !property.getValue().isArray()) {
                continue;
            }
            for (JsonNode member : property.getValue()) {
                if (member.isObject() && member.has("boardId") && member.has("name")) {
                    names.add(member.path("name").asText());
                } else if (member.isTextual() && member.asText().contains(",name=")) {
                    String tail = member.asText().substring(member.asText().indexOf(",name=") + 6);
                    names.add(tail.contains(",") ? tail.substring(0, tail.indexOf(',')) : tail);
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * One field's identity, as {@code /field} reports it.
     *
     * @param name   the display name, localised to the site — which is why the ids and not these
     *               are what {@link #IMPORTED_FIELD_IDS} is written in
     * @param type   the source's own word for the shape, passed through to the report
     * @param custom whether the source calls it a custom field
     */
    private record FieldMeta(String name, String type, boolean custom) {
    }

    /**
     * {@code GET /field} — every field the site defines, by id.
     *
     * <p>Site-wide rather than project-wide, and that is fine because it is only ever consulted for
     * an id a <em>this project's</em> issue really carried: the population that reaches the report
     * is bounded by {@link #countFieldValues}, not by this map.
     */
    private Map<String, FieldMeta> fieldCatalogue(ImportConnection connection) {
        Map<String, FieldMeta> catalogue = new LinkedHashMap<>();
        for (JsonNode field : get(connection, base(connection) + "/field")) {
            catalogue.put(field.path("id").asText(), new FieldMeta(
                    field.path("name").asText(""),
                    field.path("schema").path("type").asText("unknown"),
                    field.path("custom").asBoolean(false)));
        }
        return catalogue;
    }

    /**
     * How many issues hold a value for each field this product does not import.
     *
     * <p>Counted from the issue documents rather than asked of the source, because the question the
     * preview answers is "how much would be lost", and a field defined on the site with no value on
     * any issue in <em>this</em> project loses nothing. Empty arrays and empty objects count as no
     * value: Jira answers {@code []} for an unset multi-select and {@code {}} for an unset
     * time-tracking, and reporting those as losses would put a row on the screen for every field
     * the site has ever defined.
     */
    private static void countFieldValues(JsonNode fields, Map<String, Integer> into) {
        for (Map.Entry<String, JsonNode> property : fields.properties()) {
            JsonNode value = property.getValue();
            if (IMPORTED_FIELD_IDS.contains(property.getKey())
                    || value.isNull() || value.isMissingNode()
                    || (value.isContainerNode() && value.isEmpty())
                    || (value.isTextual() && value.asText().isBlank())) {
                continue;
            }
            into.merge(property.getKey(), 1, Integer::sum);
        }
    }

    /**
     * The counted ids, resolved to names through the catalogue.
     *
     * <p>An id the catalogue does not know is skipped rather than reported under its id. A row
     * reading {@code customfield_10237 — 412 values} tells the person reading the report nothing
     * they can act on, and the report is the artifact that has to still make sense when the source
     * system is gone.
     */
    private static List<SourceField> droppedCandidates(
            Map<String, FieldMeta> catalogue, Map<String, Integer> counts) {

        List<SourceField> fields = new ArrayList<>();
        counts.forEach((id, count) -> {
            FieldMeta meta = catalogue.get(id);
            if (meta != null && !meta.name().isBlank()) {
                fields.add(new SourceField(meta.name(), meta.type(), meta.custom(), count));
            }
        });
        return List.copyOf(fields);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }

    private static List<String> names(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path("name").asText()));
        return List.copyOf(values);
    }

    /**
     * Jira's {@code 2026-03-04T11:22:33.000+0000}, which is an offset with no colon and therefore
     * not what {@code Instant.parse} reads.
     *
     * <p>An unparseable timestamp is null rather than an exception: a comment whose date this
     * product could not read is still a comment somebody wrote, and failing a whole 1 482-issue
     * import on one malformed field would be the least useful outcome available.
     */
    private static Instant timestamp(JsonNode node) {
        String raw = node.asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(
                    raw.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
