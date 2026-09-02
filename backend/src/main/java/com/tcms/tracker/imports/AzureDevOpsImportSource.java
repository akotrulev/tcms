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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Azure DevOps, behind the same {@link ImportSource} port Jira uses (D-k).
 *
 * <p>This class is the whole of the registration: {@link ImportSources} filters the injected list
 * on {@link #supports}, so a provider arrives as a new {@code @Component} and no edit to the
 * selector. Everything above the port — {@link ImportPreviewService}, {@link ImportWriter},
 * {@link DroppedFields} — is a pure function of the {@link SourceProjectSnapshot} this returns and
 * was not touched to add ADO.
 *
 * <h2>Three things ADO does differently, each of which reads as a bug until you check it</h2>
 *
 * <p><strong>1. The credential is {@code Basic base64(":" + pat)} — a colon and an EMPTY
 * username.</strong> Not a Bearer token, and not the PAT as the password half of a real account's
 * Basic pair. ADO's own documented spelling puts the PAT in the password position and leaves the
 * user position blank, and the leading colon is therefore load-bearing rather than a bug somebody
 * should tidy away. {@link #authorization} says so again at the point somebody would edit it.
 *
 * <p><strong>2. There is no search-by-project endpoint.</strong> WIQL
 * ({@code POST _apis/wit/wiql}) answers <em>ids only</em> — that is the API, not an omission — so a
 * whole-project read is one query followed by {@code GET _apis/wit/workitems?ids=…&$expand=all} in
 * batches of {@link #BATCH_SIZE}. The batch limit is ADO's, not a tuning choice, and unlike Jira's
 * {@code nextPageToken} it is a hard refusal rather than a slower page.
 *
 * <p><strong>3. The batch does not promise the WIQL's order back</strong>, and the WIQL's order is
 * what {@link SourceIssue#rankPosition} means. {@link #snapshot} indexes each batch by id and emits
 * in the order the query named — see the comment there, because a run that quietly reordered the
 * backlog would still look completely correct in the report.
 *
 * <h2>The type map is by LEVEL and never by name</h2>
 *
 * <p>{@code Epic} → {@code EPIC}; {@code Feature}, {@code User Story},
 * {@code Product Backlog Item} and {@code Bug} → {@code STORY}; {@code Task} → {@code TASK}. That
 * map is not written out as a table here, because it already exists once, in
 * {@link ImportPreviewService#matchType} — name first, then level — and a second copy would be a
 * preview that promised one type and a run that wrote another. What this class supplies is the one
 * input that derivation cannot read off a name: {@link SourceType#subtask}, set from
 * {@link #LEAF_TYPES}.
 *
 * <p><strong>ADO's {@code Feature} sits at {@code STORY} deliberately.</strong> The depth rule is
 * about levels (scope §4) and this product has three, so a fourth story-level type in a customer's
 * process is a row in {@code issue_type} (M1) and never a migration. A reader who expected
 * {@code Feature} between {@code Epic} and {@code User Story} is reading ADO's hierarchy, which
 * this product does not have and does not gain by importing from it.
 *
 * <h2>Built and unverified against a real organization</h2>
 *
 * <p>Every fixture {@code AzureDevOpsImportSourceTest} runs against is a document this repository
 * wrote, so it pins the requests sent and the way an answer is read and says nothing about whether
 * ADO would send that answer. The field reference names, the WIQL shape, the {@code $expand=all}
 * envelope and the empty-username Basic header stay <em>built and unverified</em> until one real
 * person has connected and previewed against a real organization.
 */
@Component
class AzureDevOpsImportSource implements ImportSource {

    /**
     * Pinned rather than latest. {@code api-version} is how ADO versions its wire shapes, and
     * omitting it makes every response's shape a property of the day the request was made —
     * {@code integration.tracker.AzureDevOpsAdapter}'s argument, one module over, for the same
     * reason.
     */
    private static final String API_VERSION = "7.1";

    /**
     * Comments are on a preview version of their own route and there is no stable one.
     *
     * <p>Stated rather than hidden behind a constant somebody assumes is {@link #API_VERSION}: a
     * preview version can change shape, and when it does, this is the line that explains why the
     * comment read broke while everything else kept working.
     */
    private static final String COMMENTS_API_VERSION = "7.1-preview.3";

    /**
     * <strong>200, and it is ADO's limit rather than a choice.</strong>
     *
     * <p>{@code GET _apis/wit/workitems?ids=} refuses more than two hundred ids outright, so this
     * is not a page size to tune against {@link ImportHttp}'s response cap the way Jira's is — a
     * larger value is a 400 from the source, not a bigger answer.
     */
    private static final int BATCH_SIZE = 200;

    /**
     * A hard stop on the id list, independent of {@code max-issues}.
     *
     * <p>WIQL is asked with {@code $top}, so this is what that number is. It is deliberately far
     * above any legitimate team project: it exists so a query that matched an organization does not
     * become a hundred batch calls before {@link ImportPreviewService} gets to refuse the project
     * by its size, which is where an over-large import is turned away with a sentence.
     */
    private static final int MAX_IDS = 100_000;

    /** One page of the connect step's dropdown — {@link JiraImportSource#projects}'s argument. */
    private static final int MAX_PROJECTS = 100;

    /**
     * The work item types that sit at the leaf of ADO's hierarchy, which is what {@code subtask}
     * means to {@link SourceType}.
     *
     * <p>ADO has no {@code subtask} flag: its hierarchy is a graph of link types rather than a
     * two-level "is this a sub-task" boolean, so the fact this port asks for has to be supplied
     * from the process templates' shared shape. {@code Task} is the leaf in Agile, Scrum and CMMI
     * alike. Matched without regard to case because a customer can rename a type and keep its
     * position.
     */
    private static final Set<String> LEAF_TYPES = Set.of("task");

    /**
     * The fields the run actually writes — everything else with a value is a loss the preview
     * reports, unless {@link #BOOKKEEPING} excuses it.
     *
     * <p>Reference names and not display names: {@code System.Title} is the same string on a
     * German organization, while its display name is not.
     */
    private static final Set<String> IMPORTED_FIELDS = Set.of(
            "System.Id", "System.Title", "System.Description", "System.WorkItemType",
            "System.State", "System.Parent", "System.AssignedTo", "System.CreatedBy",
            "System.CreatedDate", "System.Tags", "System.TeamProject");

    /**
     * ADO's record of its own storage, which is not the customer's data and therefore not a loss.
     *
     * <p>This list exists because {@code $expand=all} is genuinely <em>all</em> — revision
     * counters, link tallies, watermarks, the internal ids behind Area and Iteration Path. Jira's
     * equivalent asks for {@code *navigable} and gets a shorter answer; here the filtering is this
     * product's to do. Reporting these would put twenty rows on a screen whose whole job is to be
     * believed, which is the same argument {@link JiraImportSource#countFieldValues} makes about
     * fields nobody filled in.
     *
     * <p>What is <strong>not</strong> here is as deliberate as what is: {@code System.BoardColumn}
     * and {@code System.BoardLane} are somebody's board arriving nowhere, and
     * {@code System.AreaPath} is how a lot of organizations record ownership. Those are losses and
     * the report says so.
     */
    private static final Set<String> BOOKKEEPING = Set.of(
            "System.Rev", "System.RevisedDate", "System.ChangedDate", "System.ChangedBy",
            "System.AuthorizedDate", "System.AuthorizedAs", "System.PersonId", "System.Watermark",
            "System.Reason", "System.CommentCount", "System.History", "System.AreaId",
            "System.IterationId", "System.NodeName", "System.IsDeleted", "System.BoardId",
            "System.BoardColumnDone", "System.ExternalLinkCount", "System.RelatedLinkCount",
            "System.HyperLinkCount", "System.AttachedFileCount", "System.RemoteLinkCount",
            "System.CommentVersionRef");

    /**
     * The flattened Area and Iteration levels, which ADO emits one field per depth.
     *
     * <p>A prefix rather than seven entries in {@link #BOOKKEEPING}: the depth is the customer's,
     * so the set of names is not knowable here. The paths themselves are read from
     * {@code System.AreaPath} and {@code System.IterationPath} and reported as losses; these are
     * the same value sliced, and listing them would be one field reported seven times.
     */
    private static final String LEVEL_PREFIXES = "System.AreaLevel|System.IterationLevel";

    /** ADO's own namespace for a field a customer added — {@link SourceField#custom}'s answer. */
    private static final String CUSTOM_PREFIX = "Custom.";

    private final ImportHttp http;
    private final ObjectMapper json;

    AzureDevOpsImportSource(ImportHttp http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public boolean supports(ImportProvider provider) {
        return provider == ImportProvider.AZURE_DEVOPS;
    }

    /**
     * {@code GET _apis/projects} — the team projects this PAT can see.
     *
     * <p>{@link SourceProject#key} is the team project's <em>name</em>, because ADO has no second
     * short identifier: the name is what WIQL's {@code [System.TeamProject]} takes and what every
     * URL below is scoped by. The count is zero, meaning <em>not disclosed</em> rather than empty —
     * ADO does not carry a work item count on the project document, and the real number arrives
     * with the snapshot the preview then reads.
     */
    @Override
    public List<SourceProject> projects(ImportConnection connection) {
        String url = base(connection) + "/_apis/projects?api-version=" + API_VERSION
                + "&$top=" + MAX_PROJECTS;
        JsonNode body = get(connection, url);

        List<SourceProject> projects = new ArrayList<>();
        for (JsonNode project : body.path("value")) {
            String name = project.path("name").asText("");
            if (!name.isBlank()) {
                projects.add(new SourceProject(name, name, 0));
            }
        }
        return List.copyOf(projects);
    }

    /**
     * The workflow's state names — one call for the type list, then one per type for its states,
     * unioned by name in the order the source gave them.
     *
     * <p>ADO defines states <em>per work item type</em> rather than per project, so there is no one
     * endpoint that answers this: a project's process states are the union of its types' states,
     * and a state reached by {@code Bug} and not by {@code Task} is still a state an issue can be
     * in. Insertion order rather than a sorted or hashed one, so the {@code 400} that names an
     * unmapped status names them the same way twice against one organization.
     *
     * <p>Names only, no counts — the port's contract, and the reason is that only
     * {@link #snapshot} has read the work items.
     */
    @Override
    public List<String> statusNames(ImportConnection connection, String projectKey) {
        String scope = scope(connection, projectKey);
        JsonNode types = get(connection,
                scope + "/_apis/wit/workitemtypes?api-version=" + API_VERSION);

        Set<String> names = new LinkedHashSet<>();
        for (JsonNode type : types.path("value")) {
            String typeName = type.path("name").asText("");
            if (typeName.isBlank()) {
                continue;
            }
            JsonNode states = get(connection, scope + "/_apis/wit/workitemtypes/"
                    + URLEncoder.encode(typeName, StandardCharsets.UTF_8)
                    + "/states?api-version=" + API_VERSION);
            for (JsonNode state : states.path("value")) {
                String name = state.path("name").asText("");
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

        List<String> ids = workItemIds(connection, projectKey);
        // Read once, before any work item: the batch answers field REFERENCE names and the report
        // has to show display names, and a name resolved per issue would be one call per issue for
        // an answer that cannot change during a read pass.
        Map<String, FieldMeta> catalogue = fieldCatalogue(connection);

        List<SourceIssue> issues = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, SourcePerson> people = new LinkedHashMap<>();
        Map<String, Integer> fieldValues = new LinkedHashMap<>();
        int historyEntries = 0;

        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<String> batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            // Indexed and re-emitted in the query's order rather than the answer's: ADO does not
            // promise the batch comes back in the order the ids were asked for, and the query's
            // order IS rankPosition. A quietly reordered backlog is the failure that would look
            // completely correct in the report.
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode item : get(connection, batchUrl(connection, projectKey, batch))
                    .path("value")) {
                byId.put(item.path("id").asText(), item);
            }

            for (String id : batch) {
                JsonNode item = byId.get(id);
                if (item == null) {
                    // errorPolicy=omit drops an id deleted between the query and this call. A gap
                    // is not a failure: it is a work item that no longer exists to import.
                    continue;
                }
                JsonNode fields = item.path("fields");

                String statusName = fields.path("System.State").asText("");
                statusCounts.merge(statusName, 1, Integer::sum);

                String typeName = fields.path("System.WorkItemType").asText("");
                typeCounts.merge(typeName, 1, Integer::sum);

                remember(people, fields.path("System.AssignedTo"));
                remember(people, fields.path("System.CreatedBy"));

                List<SourceComment> comments = comments(connection, projectKey, fields, id, people);
                historyEntries += revisionCount(connection, projectKey, id);
                countFieldValues(fields, fieldValues);

                issues.add(new SourceIssue(
                        // The id verbatim, because ADO has no second identifier: the id IS the key
                        // people say aloud and the only one /_workitems/edit/{id} accepts. The
                        // alternative is composing "PROJ-4812" out of a name and a number, which no
                        // ADO user would recognise — AzureDevOpsAdapter makes the same call one
                        // module over, for a link rather than for a key.
                        id,
                        typeName,
                        statusName,
                        fields.path("System.Title").asText(""),
                        plainText(fields.path("System.Description")),
                        parentKey(item, fields),
                        // ADO has no separate epic link. Epic is an ordinary parent in the one
                        // hierarchy, so parentKey above already carries it — where Jira needs two
                        // fields because its epic link predates its parent field.
                        null,
                        emailOf(fields.path("System.AssignedTo")),
                        emailOf(fields.path("System.CreatedBy")),
                        tags(fields.path("System.Tags")),
                        // ADO has no fix versions. Releases (D6) therefore come from nothing on
                        // this provider, and no dropped-field row claims otherwise: there is no
                        // field to have lost.
                        List.of(),
                        iterations(fields, projectKey),
                        issues.size(),
                        timestamp(fields.path("System.CreatedDate")),
                        comments));
            }
            onProgress.accept(issues.size());
        }

        return new SourceProjectSnapshot(
                List.copyOf(issues),
                statusCounts.entrySet().stream()
                        // The category is blank, and that is this provider's honest answer: ADO
                        // carries a state's category only on the per-type states document, not on
                        // the work item, so filling it would be one call per work item type for a
                        // field nothing above this port reads. SourceStatus documents it as a
                        // suggestion seed; the suggestion is made on the name.
                        .map(entry -> new SourceStatus(entry.getKey(), "", entry.getValue()))
                        .toList(),
                typeCounts.entrySet().stream()
                        .map(entry -> new SourceType(
                                entry.getKey(), isLeaf(entry.getKey()), entry.getValue()))
                        .toList(),
                List.of(),
                List.copyOf(people.values()),
                droppedCandidates(catalogue, fieldValues),
                historyEntries);
    }

    // =====================================================================

    /** The organization-scoped site, {@code https://dev.azure.com/northwind}, without its slash. */
    private static String base(ImportConnection connection) {
        String site = connection.siteUrl() == null ? "" : connection.siteUrl();
        return site.replaceAll("/+$", "");
    }

    /** {@code {site}/{teamProject}} — every work item route is scoped to the team project. */
    private static String scope(ImportConnection connection, String projectKey) {
        return base(connection) + "/"
                + URLEncoder.encode(projectKey, StandardCharsets.UTF_8);
    }

    /**
     * <strong>{@code Basic base64(":" + pat)} — a colon and an EMPTY username, which is ADO's own
     * spelling and not a defect.</strong>
     *
     * <p>It looks wrong in three separate ways and each of them is fine: there is no user half; the
     * string therefore begins with a colon; and a PAT that is obviously a token is being sent as a
     * password. ADO's documented scheme puts the PAT in the password position of HTTP Basic and
     * leaves the user position blank, and a "correction" to {@code Bearer <pat>} or to
     * {@code accountEmail:pat} is a 401 against a real organization and a green suite — which is
     * why {@code AzureDevOpsImportSourceTest} pins the exact header bytes.
     *
     * <p>{@link ImportConnection#accountEmail} is ignored here for the same reason it is ignored
     * for Jira Data Center: nothing in this scheme has a place for it.
     */
    private static String authorization(ImportConnection connection) {
        String pair = ":" + connection.apiToken();
        return "Basic " + Base64.getEncoder()
                .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * WIQL for the ids, which is the only whole-project read ADO offers.
     *
     * <p>{@code ORDER BY [System.CreatedDate] ASC} rather than by a rank field: ADO's backlog order
     * lives in {@code Microsoft.VSTS.Common.StackRank} on Agile and CMMI and in
     * {@code Microsoft.VSTS.Common.BacklogPriority} on Scrum, and neither is present on a process
     * that does not define it — a WIQL naming a field the project lacks is a 400 for the whole
     * read. Creation order is what every process has, and it is what {@link JiraImportSource}
     * falls back to for the same reason.
     *
     * <p>The project name is quoted into the WIQL with its own single quotes stripped rather than
     * escaped. WIQL's escape is a doubled quote and getting that subtly wrong is a query that
     * silently matches something else; a query that finds nothing is a recoverable disappointment.
     */
    private List<String> workItemIds(ImportConnection connection, String projectKey) {
        String url = scope(connection, projectKey) + "/_apis/wit/wiql?api-version=" + API_VERSION
                + "&$top=" + MAX_IDS;
        String query = "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = '"
                + projectKey.replace("'", "").replace("\\", "")
                + "' ORDER BY [System.CreatedDate] ASC";

        JsonNode result = post(connection, url,
                json.createObjectNode().put("query", query).toString());

        List<String> ids = new ArrayList<>();
        for (JsonNode item : result.path("workItems")) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * One batch of work items, whole.
     *
     * <p>{@code $expand=all} rather than a named field list, and it is the expensive choice taken
     * for {@link JiraImportSource#FIELDS}' reason: the preview's central promise is the
     * dropped-field list, and counting values for a field means seeing the field. A named list
     * would make the report a subset — a list of losses that omits the ones nobody predicted reads
     * as complete and is worse than no list.
     *
     * <p>{@code errorPolicy=omit} because the default fails the <em>whole batch</em> when one id in
     * it is no longer readable, which on a two-hundred-item batch is a read that dies over a work
     * item somebody deleted while the import was running.
     */
    private static String batchUrl(
            ImportConnection connection, String projectKey, List<String> ids) {

        return scope(connection, projectKey) + "/_apis/wit/workitems?ids=" + String.join(",", ids)
                + "&$expand=all&errorPolicy=omit&api-version=" + API_VERSION;
    }

    /**
     * The comments on one work item, and <strong>only when the item says it has some</strong>.
     *
     * <p>Comments are a route of their own here — Jira delivers them inside the issue — so this is
     * one call per work item that has any. {@code System.CommentCount} is on the batch document
     * already, so a project whose issues mostly have no discussion pays for none of them.
     */
    private List<SourceComment> comments(
            ImportConnection connection,
            String projectKey,
            JsonNode fields,
            String id,
            Map<String, SourcePerson> people) {

        if (fields.path("System.CommentCount").asInt(0) <= 0) {
            return List.of();
        }
        JsonNode body = get(connection, scope(connection, projectKey) + "/_apis/wit/workItems/"
                + id + "/comments?api-version=" + COMMENTS_API_VERSION);

        List<SourceComment> comments = new ArrayList<>();
        for (JsonNode comment : body.path("comments")) {
            JsonNode author = comment.path("createdBy");
            remember(people, author);
            comments.add(new SourceComment(
                    author.path("displayName").asText(""),
                    emailOf(author),
                    plainText(comment.path("text")),
                    timestamp(comment.path("createdDate"))));
        }
        return List.copyOf(comments);
    }

    /**
     * How many revisions one work item has, for the preview's {@code historyEntries} total <em>and
     * nothing else</em>.
     *
     * <p>Counted and never imported — there is no table here for somebody else's audit trail — so
     * the preview can say the number out loud rather than letting it be discovered as an absence.
     *
     * <p>A failure to read it is <strong>not</strong> a failure of the import: a revision count is
     * a number on a screen, and dying on it would fail a whole migration over a statistic. Zero is
     * the honest answer when the source will not say.
     */
    private int revisionCount(ImportConnection connection, String projectKey, String id) {
        String url = scope(connection, projectKey) + "/_apis/wit/workItems/" + id
                + "/revisions?$top=0&api-version=" + API_VERSION;
        try {
            return get(connection, url).path("count").asInt(0);
        } catch (ImportSourceUnavailableException | ImportUrlRefusedException unreadable) {
            return 0;
        }
    }

    /**
     * The parent, from the hierarchy link or from {@code System.Parent}.
     *
     * <p>The field first, because it is one lookup and is present on every recent organization; the
     * relations are the fallback, which is what {@code $expand=all} was already going to return and
     * what an older on-premises Server answers with instead. The link is
     * {@code System.LinkTypes.Hierarchy-Reverse} — <em>reverse</em> being ADO's word for "points at
     * my parent", so following the forward one would collect children and hand the writer a
     * hierarchy upside down.
     */
    private static String parentKey(JsonNode item, JsonNode fields) {
        int parent = fields.path("System.Parent").asInt(0);
        if (parent > 0) {
            return String.valueOf(parent);
        }
        for (JsonNode relation : item.path("relations")) {
            if ("System.LinkTypes.Hierarchy-Reverse".equals(relation.path("rel").asText())) {
                String url = relation.path("url").asText("");
                String tail = url.substring(url.lastIndexOf('/') + 1);
                return tail.isBlank() ? null : tail;
            }
        }
        return null;
    }

    /**
     * ADO's semicolon-separated {@code System.Tags}, as this product's labels.
     *
     * <p>Split and stripped rather than passed through: a tag string is one column in ADO and a row
     * per label here, and {@code "imported; migration"} arriving as a single label called
     * {@code imported; migration} is a label picker nobody can use.
     */
    private static List<String> tags(JsonNode node) {
        String raw = node.asText("");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : raw.split(";")) {
            String stripped = tag.strip();
            if (!stripped.isBlank()) {
                tags.add(stripped);
            }
        }
        return List.copyOf(tags);
    }

    /**
     * {@code System.IterationPath} — <strong>ADO's sprint</strong>, which is why it lands on
     * {@link SourceIssue#sprintNames} and takes the same {@code labelPerSprint} option Jira's
     * {@code Sprint} does (D-h).
     *
     * <p>The path is {@code Northwind\Release 1\Sprint 4} and the sprint is its last segment: the
     * segments above it are the iteration <em>tree</em>, which this product has no place for, and a
     * label called {@code Northwind\Release 1\Sprint 4} is a label nobody would pick. A path that is
     * only the project root is an item in no sprint at all rather than a sprint named after the
     * project.
     *
     * <p>One name at most, unlike Jira's list: an ADO work item is in exactly one iteration.
     */
    private static List<String> iterations(JsonNode fields, String projectKey) {
        String path = fields.path("System.IterationPath").asText("");
        if (path.isBlank() || path.equals(projectKey)) {
            return List.of();
        }
        String leaf = path.substring(path.lastIndexOf('\\') + 1).strip();
        return leaf.isBlank() || leaf.equals(projectKey) ? List.of() : List.of(leaf);
    }

    /** {@link SourceType#subtask}'s answer for ADO — see {@link #LEAF_TYPES}. */
    private static boolean isLeaf(String typeName) {
        return LEAF_TYPES.contains(typeName.toLowerCase(Locale.ROOT).strip());
    }

    /**
     * One field's identity, as {@code _apis/wit/fields} reports it.
     *
     * @param name   the display name, localised to the organization — which is why
     *               {@link #IMPORTED_FIELDS} is written in reference names and this is not
     * @param type   the source's own word for the shape, passed through to the report
     * @param custom whether ADO's own {@code Custom.} namespace claims it
     */
    private record FieldMeta(String name, String type, boolean custom) {
    }

    /**
     * {@code GET _apis/wit/fields} — every field the organization defines, by reference name.
     *
     * <p>Organization-wide rather than project-wide, and that is fine because it is only ever
     * consulted for a reference name a <em>this project's</em> work item really carried: the
     * population that reaches the report is bounded by {@link #countFieldValues}, not by this map.
     */
    private Map<String, FieldMeta> fieldCatalogue(ImportConnection connection) {
        Map<String, FieldMeta> catalogue = new LinkedHashMap<>();
        JsonNode body = get(connection,
                base(connection) + "/_apis/wit/fields?api-version=" + API_VERSION);
        for (JsonNode field : body.path("value")) {
            String reference = field.path("referenceName").asText("");
            if (reference.isBlank()) {
                continue;
            }
            catalogue.put(reference, new FieldMeta(
                    field.path("name").asText(""),
                    field.path("type").asText("unknown"),
                    reference.startsWith(CUSTOM_PREFIX)));
        }
        return catalogue;
    }

    /**
     * How many work items hold a value for each field this product does not import.
     *
     * <p>Counted from the work item documents rather than asked of the source, because the question
     * the preview answers is "how much would be lost", and a field defined on the organization with
     * no value on any item in <em>this</em> project loses nothing. Empty strings, empty arrays and
     * empty objects count as no value.
     */
    private static void countFieldValues(JsonNode fields, Map<String, Integer> into) {
        for (Map.Entry<String, JsonNode> property : fields.properties()) {
            String reference = property.getKey();
            JsonNode value = property.getValue();
            if (IMPORTED_FIELDS.contains(reference)
                    || BOOKKEEPING.contains(reference)
                    || reference.matches("(" + LEVEL_PREFIXES + ")\\d+")
                    || value.isNull() || value.isMissingNode()
                    || (value.isContainerNode() && value.isEmpty())
                    || (value.isTextual() && value.asText().isBlank())) {
                continue;
            }
            into.merge(reference, 1, Integer::sum);
        }
    }

    /**
     * The counted reference names, resolved to display names through the catalogue.
     *
     * <p>A reference name the catalogue does not know is skipped rather than reported raw. A row
     * reading {@code Custom.a1b2c3 — 412 values} tells the person reading the report nothing they
     * can act on, and the report is the artifact that has to still make sense when the source
     * system is gone.
     */
    private static List<SourceField> droppedCandidates(
            Map<String, FieldMeta> catalogue, Map<String, Integer> counts) {

        List<SourceField> fields = new ArrayList<>();
        counts.forEach((reference, count) -> {
            FieldMeta meta = catalogue.get(reference);
            if (meta != null && !meta.name().isBlank()) {
                fields.add(new SourceField(meta.name(), meta.type(), meta.custom(), count));
            }
        });
        return List.copyOf(fields);
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

    private JsonNode post(ImportConnection connection, String url, String payload) {
        return body(http.exchange(url, rest -> rest.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, authorization(connection))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
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
                            + "address is the organization's — https://dev.azure.com/your-org — and "
                            + "not a sign-in page in front of it.",
                    unreadable);
        }
    }

    /**
     * ADO's HTML descriptions and comments, as plain text.
     *
     * <p>ADO stores both as HTML and this product has no document model, so the tags are stripped
     * and the structure is lost. That is a lossy read and it is <em>said out loud</em> rather than
     * quietly done, exactly as {@link JiraImportSource#text} is about Atlassian Document Format:
     * {@link DroppedFields} lists no row for it because the description itself is imported — what
     * is lost is its formatting. Rebuilding the HTML into Markdown here would be this product
     * owning a document model for a field it round-trips nowhere.
     */
    private static String plainText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String stripped = node.asText("")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                // Last, so an entity spelled &amp;lt; does not become a tag this already stripped.
                .replace("&amp;", "&")
                .strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * ADO's identities, which carry the email in {@code uniqueName}.
     *
     * <p>On an on-premises Server that field is {@code DOMAIN\\user} instead of an address, so
     * anything without an {@code @} is treated as "not disclosed" — the same null Jira Cloud
     * produces when a profile hides its email, and the reason the report has a {@code matched} flag
     * rather than an assumption. An unmatched author is preserved as text (D-j).
     */
    private static String emailOf(JsonNode person) {
        String unique = person.path("uniqueName").asText("");
        return unique.contains("@") ? unique : null;
    }

    private static void remember(Map<String, SourcePerson> people, JsonNode person) {
        String name = person.path("displayName").asText("");
        if (!name.isBlank()) {
            people.putIfAbsent(name, new SourcePerson(name, emailOf(person)));
        }
    }

    /**
     * ADO's {@code 2026-03-04T11:22:33.87Z}, and an on-premises Server's offset form.
     *
     * <p>An unparseable timestamp is null rather than an exception, for
     * {@link JiraImportSource#timestamp}'s reason: failing a whole migration on one malformed field
     * would be the least useful outcome available.
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
