package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
 * {@link AzureDevOpsImportSource} against a bound {@link MockRestServiceServer} — no Spring
 * context, no container, and <strong>no Azure DevOps</strong>.
 *
 * <h2>The binding is the part that goes wrong, so it is asserted rather than assumed</h2>
 *
 * <p>{@code MockRestServiceServer} binds by installing its own request factory on a
 * {@code RestClient.Builder}. {@link ImportHttp}'s production constructor installs the real one
 * over the top, which silently un-binds it — and the equivalent mistake one module over had a
 * transport suite dialling the public internet on every run, where the failures read as parse
 * errors and somebody else's 400 rather than as "this never reached the double". That is what
 * {@link ImportHttp}'s second, package-private constructor exists for, and it is what this class
 * uses. {@code server.verify()} is the check that every expectation was actually met.
 *
 * <h2>What is pinned here is the three things ADO does differently</h2>
 *
 * <p>The empty-username Basic header, the two-call WIQL-then-batch read, and the batch's order not
 * being the query's. Each of the three looks like a defect on the way past, and each of them would
 * be a 401, a wrong report or a silently reordered backlog against a real organization while this
 * suite stayed green if it were only assumed.
 */
@DisplayName("AzureDevOpsImportSource — through the same port, against a bound double")
class AzureDevOpsImportSourceTest {

    private static final String SITE = "https://dev.azure.com/northwind";
    private static final String PROJECT = "Storefront";

    private MockRestServiceServer server;
    private AzureDevOpsImportSource source;

    @BeforeEach
    void bindTheDouble() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ImportHttp http = new ImportHttp(
                builder.build(),
                new ImportUrlGuard(new ImportProperties(
                        5000, Duration.ofSeconds(30), Duration.ofMinutes(30), false)));
        source = new AzureDevOpsImportSource(http, new ObjectMapper());
    }

    @Test
    @DisplayName("this source claims AZURE_DEVOPS and nothing else — the whole of the registration")
    void itClaimsOneProvider() {
        assertThat(source.supports(ImportProvider.AZURE_DEVOPS))
                .as("""
                    ImportSources filters the injected list on supports() rather than switching \
                    over the provider, so this method IS the registration and ImportSources was \
                    not edited to add ADO.""")
                .isTrue();
        assertThat(source.supports(ImportProvider.JIRA_CLOUD)).isFalse();
        assertThat(source.supports(ImportProvider.JIRA_DATA_CENTER)).isFalse();
    }

    @Test
    @DisplayName("the credential is Basic base64(\":\" + pat) — a colon and an EMPTY username")
    void theEmptyUsernameIsAdosOwnSpelling() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString(":secret".getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo(Matchers.containsString("/_apis/projects")))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess(PROJECTS, MediaType.APPLICATION_JSON));

        assertThat(source.projects(connection())).hasSize(1);
        assertThat(expected)
                .as("""
                    Pinned to the byte because it looks wrong three ways and is right: no user \
                    half, a leading colon, and a token in the password position. A "correction" \
                    to Bearer or to email:pat is a 401 against a real organization and a green \
                    suite.""")
                .isEqualTo("Basic OnNlY3JldA==");
        server.verify();
    }

    @Test
    @DisplayName("a team project's name is its key — ADO has no second identifier")
    void projectsAreRead() {
        server.expect(requestTo(Matchers.containsString("/_apis/projects")))
                .andRespond(withSuccess(PROJECTS, MediaType.APPLICATION_JSON));

        SourceProject project = source.projects(connection()).get(0);

        assertThat(project.key()).isEqualTo("Storefront");
        assertThat(project.name()).isEqualTo("Storefront");
        assertThat(project.issueCount())
                .as("zero means NOT DISCLOSED: ADO carries no work item count on the project "
                        + "document, and the real number arrives with the snapshot")
                .isZero();
    }

    @Test
    @DisplayName("statusNames unions every work item type's states, in the source's own order")
    void statusNamesAreUnionedByName() {
        expectWorkflow();

        List<String> names = source.statusNames(connection(), PROJECT);

        assertThat(names)
                .as("""
                    ADO defines states per work item type, so a project's workflow is the union: \
                    "Active" is reached by both types and is one row, and "Resolved" is reached \
                    only by Bug and is still a state an issue can be in. Insertion-ordered, so the \
                    400 that names an unmapped status names them the same way twice.""")
                .containsExactly("New", "Active", "Resolved", "Closed");
        server.verify();
    }

    @Test
    @DisplayName("the read is WIQL for the ids, then one batch — there is no search-by-project")
    void theReadIsTwoCalls() {
        expectSnapshot();

        SourceProjectSnapshot snapshot = source.snapshot(connection(), PROJECT, count -> { });

        assertThat(snapshot.issues()).extracting(SourceIssue::key)
                .as("""
                    The WIQL's order, not the batch's — ADO does not promise the ids come back in \
                    the order they were asked for, and this order IS rankPosition. The batch \
                    fixture below deliberately answers 4812 before 4811 to prove the re-ordering \
                    happens.""")
                .containsExactly("4811", "4812", "4813");
        server.verify();
    }

    @Test
    @DisplayName("the id is the key verbatim, and it survives the import")
    void theIdIsTheKey() {
        expectSnapshot();

        assertThat(source.snapshot(connection(), PROJECT, count -> { }).issues())
                .extracting(SourceIssue::key)
                .as("ADO has no second identifier: the id IS the key people say aloud and the "
                        + "only one /_workitems/edit/{id} accepts. Inventing \"PROJ-4812\" would "
                        + "be a key no ADO user recognises and no URL accepts.")
                .contains("4812");
    }

    @Test
    @DisplayName("Task is the leaf level; Epic, Feature, User Story and Bug are not")
    void theTypeMapIsByLevelAndNeverByName() {
        expectSnapshot();

        List<SourceType> types = source.snapshot(connection(), PROJECT, count -> { }).types();

        assertThat(types).extracting(SourceType::name)
                .containsExactly("Feature", "Bug", "Task");
        assertThat(types).filteredOn(type -> "Task".equals(type.name()))
                .allMatch(SourceType::subtask, "Task is ADO's leaf in Agile, Scrum and CMMI alike");
        assertThat(types).filteredOn(type -> !"Task".equals(type.name()))
                .as("""
                    Feature and Bug are NOT leaves, so ImportPreviewService.matchType lands them \
                    at STORY — ADO's Feature sitting at story level is deliberate: the depth rule \
                    is about levels, and a fourth story-level type is a row (M1), not a \
                    migration.""")
                .noneMatch(SourceType::subtask);
    }

    @Test
    @DisplayName("Iteration Path is ADO's sprint, and it is the last segment of the path")
    void theIterationPathIsTheSprint() {
        expectSnapshot();

        List<SourceIssue> issues = source.snapshot(connection(), PROJECT, count -> { }).issues();

        assertThat(issues.get(1).sprintNames())
                .as("""
                    "Storefront\\\\Release 1\\\\Sprint 4" is a tree this product has no place for; \
                    the sprint is its leaf. It lands on sprintNames so it takes the same \
                    labelPerSprint option Jira's Sprint does (D-h).""")
                .containsExactly("Sprint 4");
        assertThat(issues.get(0).sprintNames())
                .as("a path that is only the project root is an item in no sprint, not a sprint "
                        + "named after the project")
                .isEmpty();
    }

    @Test
    @DisplayName("a field with a value is a loss; ADO's own bookkeeping is not")
    void onlyRealLossesAreReportedAsDropped() {
        expectSnapshot();

        List<SourceField> fields = source.snapshot(connection(), PROJECT, count -> { }).fields();

        assertThat(fields).extracting(SourceField::name)
                .as("""
                    $expand=all is genuinely ALL, so the filtering is this product's to do: \
                    System.Rev and System.CommentCount are ADO's record of its own storage and \
                    not the customer's data, while Iteration Path, Area Path, Board Column and \
                    the Custom.* field are losses somebody has to be told about.""")
                .containsExactlyInAnyOrder(
                        "Story Points", "Iteration Path", "Area Path", "Board Column", "Squad")
                .doesNotContain("Rev", "Comment Count", "Title", "State");
        assertThat(fields).filteredOn(field -> "Squad".equals(field.name()))
                .allMatch(SourceField::custom, "Custom. is ADO's own namespace for a customer's "
                        + "field, and it is what decides the report's Custom: \"Squad\" row");
        assertThat(fields).filteredOn(field -> "Story Points".equals(field.name()))
                .allMatch(field -> field.valueCount() == 1
                        && !field.custom(), "one value across three items, and a built-in");
    }

    @Test
    @DisplayName("comments are fetched only for a work item that says it has some")
    void commentsCostNothingWhereThereAreNone() {
        expectSnapshot();

        List<SourceIssue> issues = source.snapshot(connection(), PROJECT, count -> { }).issues();

        SourceComment comment = issues.get(0).comments().get(0);
        assertThat(comment.authorName()).isEqualTo("Someone Else");
        assertThat(comment.authorEmail()).isEqualTo("someone@northwind.test");
        assertThat(comment.body())
                .as("ADO stores comments as HTML and this product has no document model, so the "
                        + "tags are stripped and the loss is said out loud rather than quietly done")
                .isEqualTo("A comment the import keeps.");
        assertThat(issues.get(1).comments())
                .as("""
                    System.CommentCount is on the batch document already, so a project whose \
                    items mostly have no discussion pays for none of those calls — and \
                    server.verify() is what proves no request was made for them.""")
                .isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("the revision count is read per item and only for the preview's total")
    void revisionsAreCountedAndNeverImported() {
        expectSnapshot();

        assertThat(source.snapshot(connection(), PROJECT, count -> { }).historyEntryCount())
                .as("counted and never imported — there is no table here for somebody else's "
                        + "audit trail, so the preview says the number out loud rather than "
                        + "letting it be discovered as an absence")
                .isEqualTo(9);
        server.verify();
    }

    @Test
    @DisplayName("the parent comes from System.Parent, and from the REVERSE hierarchy link")
    void theParentIsResolvedBothWays() {
        expectSnapshot();

        List<SourceIssue> issues = source.snapshot(connection(), PROJECT, count -> { }).issues();

        assertThat(issues.get(1).parentKey())
                .as("System.Parent first: one lookup, present on every recent organization")
                .isEqualTo("4811");
        assertThat(issues.get(2).parentKey())
                .as("""
                    Hierarchy-REVERSE is ADO's word for "points at my parent"; following the \
                    forward link would collect children and hand the writer a hierarchy upside \
                    down.""")
                .isEqualTo("4812");
        assertThat(issues.get(0).epicKey())
                .as("ADO has no separate epic link — Epic is an ordinary parent in the one "
                        + "hierarchy, where Jira needs two fields because its epic link predates "
                        + "its parent field")
                .isNull();
    }

    @Test
    @DisplayName("tags become one label each, and assignees are matched on uniqueName")
    void tagsAndPeopleAreRead() {
        expectSnapshot();

        SourceProjectSnapshot snapshot = source.snapshot(connection(), PROJECT, count -> { });

        assertThat(snapshot.issues().get(0).labels())
                .as("a tag string is one column in ADO and a row per label here; \"imported; "
                        + "migration\" arriving as one label is a picker nobody can use")
                .containsExactly("imported", "migration");
        assertThat(snapshot.people()).extracting(SourcePerson::email)
                .as("""
                    uniqueName is the email on a hosted organization and DOMAIN\\\\user on an \
                    on-premises Server, so anything without an @ is "not disclosed" — the null \
                    that makes the report's matched flag honest rather than an assumption.""")
                .contains("dev@northwind.test", "someone@northwind.test")
                .containsNull();
        assertThat(snapshot.versions())
                .as("ADO has no fix versions, so releases (D6) come from nothing here — and no "
                        + "dropped-field row claims otherwise, there being no field to have lost")
                .isEmpty();
    }

    @Test
    @DisplayName("an organization that answers 500 is one ImportSourceUnavailableException")
    void aFailingOrganizationIsOneException() {
        server.expect(ExpectedCount.once(), requestTo(Matchers.containsString("/_apis/projects")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> source.projects(connection()))
                .isInstanceOf(ImportSourceUnavailableException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("the guard refuses a private site before the double is ever asked")
    void aPrivateSiteIsRefusedBeforeTheCall() {
        ImportConnection internal = new ImportConnection(
                ImportProvider.AZURE_DEVOPS, "https://localhost/northwind", null, "secret");

        assertThatThrownBy(() -> source.projects(internal))
                .isInstanceOf(ImportUrlRefusedException.class);
        server.verify();
    }

    // =====================================================================

    private static ImportConnection connection() {
        return new ImportConnection(ImportProvider.AZURE_DEVOPS, SITE, null, "secret");
    }

    /** The type list and one states call per type — {@code statusNames}' two-level read. */
    private void expectWorkflow() {
        server.expect(requestTo(Matchers.containsString("/_apis/wit/workitemtypes?")))
                .andRespond(withSuccess(WORK_ITEM_TYPES, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("workitemtypes/Bug/states")))
                .andRespond(withSuccess(BUG_STATES, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("workitemtypes/Task/states")))
                .andRespond(withSuccess(TASK_STATES, MediaType.APPLICATION_JSON));
    }

    /**
     * The whole read pass, in the order the source makes it: WIQL, the field catalogue, one batch,
     * then the per-item comment and revision calls.
     *
     * <p>Ordered expectations, so a source that made them in another order — or made one twice —
     * fails here rather than passing on a fixture that happened to answer.
     */
    private void expectSnapshot() {
        server.expect(requestTo(Matchers.containsString("/_apis/wit/wiql")))
                .andRespond(withSuccess(WIQL, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/_apis/wit/fields")))
                .andRespond(withSuccess(FIELDS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/_apis/wit/workitems?ids=4811,4812,4813")))
                .andExpect(requestTo(Matchers.containsString("$expand=all")))
                .andExpect(requestTo(Matchers.containsString("errorPolicy=omit")))
                .andRespond(withSuccess(BATCH, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("workItems/4811/comments")))
                .andRespond(withSuccess(COMMENTS, MediaType.APPLICATION_JSON));
        for (String id : List.of("4811", "4812", "4813")) {
            server.expect(requestTo(Matchers.containsString("workItems/" + id + "/revisions")))
                    .andRespond(withSuccess(revisions(id), MediaType.APPLICATION_JSON));
        }
    }

    /** Three, four and two — nine, which is what the preview reports. */
    private static String revisions(String id) {
        return switch (id) {
            case "4811" -> "{\"count\": 3, \"value\": []}";
            case "4812" -> "{\"count\": 4, \"value\": []}";
            default -> "{\"count\": 2, \"value\": []}";
        };
    }

    private static final String PROJECTS = """
            {"count": 1, "value": [
              {"id": "9f2c-…", "name": "Storefront", "state": "wellFormed"}
            ]}""";

    private static final String WORK_ITEM_TYPES = """
            {"count": 2, "value": [
              {"name": "Bug", "referenceName": "Microsoft.VSTS.WorkItemTypes.Bug"},
              {"name": "Task", "referenceName": "Microsoft.VSTS.WorkItemTypes.Task"}
            ]}""";

    private static final String BUG_STATES = """
            {"count": 3, "value": [
              {"name": "New", "category": "Proposed"},
              {"name": "Active", "category": "InProgress"},
              {"name": "Resolved", "category": "Resolved"}
            ]}""";

    /** Overlaps Bug's on New and Active, which is what makes this a union rather than a list. */
    private static final String TASK_STATES = """
            {"count": 3, "value": [
              {"name": "New", "category": "Proposed"},
              {"name": "Active", "category": "InProgress"},
              {"name": "Closed", "category": "Completed"}
            ]}""";

    /** Ids only — that is the API, not an omission, and it is why the read is two calls. */
    private static final String WIQL = """
            {"queryType": "flat", "workItems": [
              {"id": 4811, "url": "https://dev.azure.com/northwind/_apis/wit/workItems/4811"},
              {"id": 4812, "url": "https://dev.azure.com/northwind/_apis/wit/workItems/4812"},
              {"id": 4813, "url": "https://dev.azure.com/northwind/_apis/wit/workItems/4813"}
            ]}""";

    /**
     * {@code Custom.Squad} is populated on one item and {@code Custom.Unused} on none — the pair
     * the dropped-field rule is about. {@code System.Rev} and {@code System.CommentCount} are
     * populated on every item and are still not losses.
     */
    private static final String FIELDS = """
            {"count": 9, "value": [
              {"referenceName": "System.Title", "name": "Title", "type": "string"},
              {"referenceName": "System.State", "name": "State", "type": "string"},
              {"referenceName": "System.Rev", "name": "Rev", "type": "integer"},
              {"referenceName": "System.CommentCount", "name": "Comment Count",
               "type": "integer"},
              {"referenceName": "System.IterationPath", "name": "Iteration Path",
               "type": "treePath"},
              {"referenceName": "System.AreaPath", "name": "Area Path", "type": "treePath"},
              {"referenceName": "System.BoardColumn", "name": "Board Column", "type": "string"},
              {"referenceName": "Microsoft.VSTS.Scheduling.StoryPoints", "name": "Story Points",
               "type": "double"},
              {"referenceName": "Custom.Squad", "name": "Squad", "type": "string"},
              {"referenceName": "Custom.Unused", "name": "Unused", "type": "string"}
            ]}""";

    /**
     * <strong>4812 comes back before 4811</strong>, deliberately: ADO does not promise the batch
     * arrives in the order the ids were asked for, and that order is {@code rankPosition}.
     *
     * <p>4812 carries {@code System.Parent}; 4813 carries the reverse hierarchy relation instead,
     * which is the older shape and the one a forward-link reader would get upside down.
     */
    private static final String BATCH = """
            {"count": 3, "value": [
              {"id": 4812,
               "fields": {
                 "System.Id": 4812,
                 "System.Title": "Second work item",
                 "System.WorkItemType": "Bug",
                 "System.State": "Active",
                 "System.Reason": "Approved",
                 "System.Rev": 4,
                 "System.CommentCount": 0,
                 "System.Parent": 4811,
                 "System.CreatedDate": "2026-01-03T00:00:00.87Z",
                 "System.CreatedBy": {"displayName": "Nobody Disclosed",
                                      "uniqueName": "NORTHWIND\\\\nobody"},
                 "System.AreaPath": "Storefront\\\\Payments",
                 "System.IterationPath": "Storefront\\\\Release 1\\\\Sprint 4",
                 "System.BoardColumn": "Doing",
                 "System.AreaLevel1": "Storefront",
                 "Custom.Unused": ""
               }},
              {"id": 4811,
               "fields": {
                 "System.Id": 4811,
                 "System.Title": "First work item",
                 "System.Description": "<div>As read from <b>the source</b>.</div>",
                 "System.WorkItemType": "Feature",
                 "System.State": "New",
                 "System.Rev": 3,
                 "System.CommentCount": 1,
                 "System.Tags": "imported; migration",
                 "System.CreatedDate": "2026-01-01T00:00:00.0000000Z",
                 "System.CreatedBy": {"displayName": "A Developer",
                                      "uniqueName": "dev@northwind.test"},
                 "System.AssignedTo": {"displayName": "A Developer",
                                       "uniqueName": "dev@northwind.test"},
                 "System.AreaPath": "Storefront",
                 "System.IterationPath": "Storefront",
                 "Microsoft.VSTS.Scheduling.StoryPoints": 8,
                 "Custom.Squad": "Payments"
               }},
              {"id": 4813,
               "relations": [
                 {"rel": "System.LinkTypes.Hierarchy-Reverse",
                  "url": "https://dev.azure.com/northwind/_apis/wit/workItems/4812"},
                 {"rel": "System.LinkTypes.Hierarchy-Forward",
                  "url": "https://dev.azure.com/northwind/_apis/wit/workItems/9999"}
               ],
               "fields": {
                 "System.Id": 4813,
                 "System.Title": "Third work item",
                 "System.WorkItemType": "Task",
                 "System.State": "Closed",
                 "System.Rev": 2,
                 "System.CommentCount": 0,
                 "System.CreatedDate": "2026-01-04T00:00:00Z",
                 "System.AreaPath": "Storefront",
                 "System.IterationPath": "Storefront"
               }}
            ]}""";

    private static final String COMMENTS = """
            {"totalCount": 1, "comments": [
              {"id": 1,
               "text": "<div>A comment the import keeps.</div>",
               "createdDate": "2026-01-02T00:00:00.0000000Z",
               "createdBy": {"displayName": "Someone Else",
                             "uniqueName": "someone@northwind.test"}}
            ]}""";
}
