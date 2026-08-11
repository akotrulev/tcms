package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract's own convention checks — the {@code SchemaConventionTest} of
 * {@code docs/specs/openapi.yaml}.
 *
 * <p>These were recorded as "not done" in five consecutive handoff sections (§42–§47) and
 * hand-applied instead, once per slice, by whoever remembered. That is the failure mode the
 * working agreement names outright: <strong>a catalogue-wide invariant needs a computed
 * check, not a careful reading.</strong> The evidence had accumulated to the point of being
 * unarguable — §47's own defect was this exact shape, an invariant across a catalogue that
 * reading cannot verify, and it shipped a link that silently opened the wrong list.
 *
 * <p>Each check below is a rule the contract already states in prose, or a defect it has
 * already been repaired for by hand. None of them is new policy. What is new is that
 * violating one now fails the build instead of waiting for a consumer to notice.
 *
 * <p>This test boots no Spring context and needs no database: every rule is a property of
 * the source text. It runs in milliseconds, which matters — a check this cheap has no
 * excuse for being skipped, and the four it replaces were all skipped for being expensive
 * to do by hand.
 */
@DisplayName("Contract conventions — openapi.yaml checked against itself")
class ContractConventionTest {

    private static OpenApiContract contract;

    @BeforeAll
    static void parse() {
        contract = OpenApiContract.load();
    }

    // ------------------------------------------------------------------ 4xx ----

    /**
     * Operations that legitimately declare no 4xx. <strong>Every entry is an operation
     * that cannot fail with a client error</strong> — not one whose errors are merely
     * uninteresting. Adding a row here is a claim about the endpoint, so it carries its
     * reason.
     */
    private static final Set<String> NO_CLIENT_ERROR_POSSIBLE = Set.of(
            // Idempotent by design and deliberately answers 204 to an absent or already-expired
            // session: telling an unauthenticated caller that their logout "failed" is both
            // useless and a probe for whether a token was ever valid.
            "POST /auth/logout");

    @Test
    @DisplayName("every operation declares at least one 4xx response")
    void everyOperationDeclaresAClientErrorResponse() {
        List<String> offenders = new ArrayList<>();

        for (OpenApiContract.Operation operation : contract.operations()) {
            if (NO_CLIENT_ERROR_POSSIBLE.contains(operation.describe())) {
                continue;
            }
            boolean declaresClientError =
                    operation.responseCodes().stream().anyMatch(code -> code.startsWith("4"));
            if (!declaresClientError) {
                offenders.add(operation.describe() + "  (declares only " + operation.responseCodes() + ")");
            }
        }

        assertThat(offenders)
                .as("""
                        Every operation must declare the client errors it can answer with.

                        This is not documentation polish. `schema.d.ts` is generated from this file and
                        the SPA's error handling is written against it: an undeclared 401 is a response
                        the generated types say cannot happen, on endpoints where it is the single most
                        likely failure. The 404-not-403 rule (plan §6t) is also stated *per operation* —
                        an endpoint that declares no 404 is claiming an unreachable id is impossible.

                        Declare the honest set: 401 wherever authentication is required, 404 for a
                        project-scoped path whose target may be invisible to the caller, 403 for a write
                        past the read gate, 400 where a body or parameter is validated.

                        Operations missing one:""")
                .isEmpty();
    }

    // ------------------------------------------------------------- required ----

    /**
     * Object schemas that deliberately declare no {@code required} array.
     *
     * <p><strong>An entry here means "no property of this schema is always present"</strong>,
     * which is a strong claim and almost always wrong. The usual repair is to list the
     * subset that <em>is</em> always present and mark the rest nullable — not to add a row
     * here.
     */
    private static final Set<String> NO_ALWAYS_PRESENT_PROPERTY = Set.of(
            // ~~Genuinely exclusive: a case condition is EITHER a library reference
            // (`conditionId`) OR an inline body, never both and never neither.~~
            // → REMOVED §162. The exclusivity was real when this was written and §95 ended it:
            // only the reference form is accepted, `inlineBody` is declared solely so it can be
            // REFUSED with a field error, and the schema has carried `required: [conditionId]`
            // ever since. The entry exempted nothing for sixty-seven sections and a green build
            // re-affirmed it every run, which is the property §161 named on its way out and the
            // reason `everyExemptionStillExemptsSomething` below now exists.

            // Accepting an invitation carries a password ONLY when the invitee has no account
            // yet — `passwordRequired` on the preceding GET says which. A returning user
            // accepting an invitation to a second company sends an empty body, deliberately:
            // holding an invitation must not confer the ability to overwrite an existing
            // credential. So the schema's single property is genuinely sometimes absent, and
            // `required: [password]` would describe the more dangerous of the two flows.
            ".paths./auth/invitations/{token}/accept.post.requestBody.content.application/json.schema",

            // The argument to a bulk operation. Which property is required is decided by the
            // sibling `action` — `lifecycleStatus` for SET_STATUS, `suiteId` for MOVE_TO_SUITE —
            // so there is no property present across all eight actions and any `required` array
            // here would be a lie about seven of them. The per-action requirement is enforced
            // server-side by BulkOperations.parse and covered by CaseBulkEndpointTest, which
            // asserts the 400 names the missing field. Expressing it as a `oneOf` discriminated
            // on `action` states it in the schema and was rejected for CaseConditionInput's
            // reason plus one of its own: the server must validate the combination anyway, being
            // the only side that knows the action, so the union would cost every consumer a
            // narrow to buy a check the client cannot use. See BulkPayload's own description.
            ".components.schemas.BulkPayload");

    @Test
    @DisplayName("every object schema with properties declares a required array")
    void everyObjectSchemaDeclaresRequired() {
        List<String> offenders = new ArrayList<>();

        contract.walk((location, node) -> {
            if (!isObjectSchema(node) || NO_ALWAYS_PRESENT_PROPERTY.contains(location)) {
                return;
            }
            if (!node.containsKey("required")) {
                offenders.add(location + "  " + propertyNames(node));
            }
        });

        assertThat(offenders)
                .as("""
                        Every object schema with properties must declare `required`.

                        `required` does NOT descend — not into a nested object, not into an `allOf`
                        branch, not into an array's `items`. Without it *every* generated property is
                        optional, and an optional property is one no consumer can use without a null
                        check on a value the server always sends. The contract already carries three
                        hand-written comments about having hit exactly this (CurrentUser, TestCase,
                        TestPlan), each added after `tsc` caught it at a call site.

                        List the properties that are always present. A property that is sometimes
                        absent stays out of the array; a property that is always present but sometimes
                        empty belongs IN the array with `nullable: true` — "always present, sometimes
                        null" is what nullable+required says, and it is what the server does.

                        Schemas missing one:""")
                .isEmpty();
    }

    // ------------------------------------------------------------ exemptions ----

    /**
     * Every exemption above still exempts something.
     *
     * <p><strong>This is the rule §161 named on its way out, built for the two populations in this
     * file.</strong> An ignore list is an argument, and an argument a green build re-affirms every
     * run is the one that survives the constraint it was about: §160's {@code rawStorageKey} said
     * "there is no adapter" for twenty-nine slices after {@code storage} built one, and §161 found
     * the reference entry of {@code READERLESS_BY_DECISION} had never been true at all. Both were
     * caught by a human re-deriving them, which does not scale and did not happen for two days
     * either time.
     *
     * <p>The two lists above are <em>one-directional</em>, and that is what makes them rot
     * silently: a location that stops violating the rule it is exempt from goes on being skipped,
     * and skipping something that would have passed anyway costs nothing and reports nothing. The
     * maps in {@code ArchitectureRulesTest} and {@code ContractEnumTest} do not have this problem —
     * each is pinned in both directions, and {@code DIVERGENCE_BY_DECISION}'s entries must
     * <em>still diverge</em> or the build fails. This gives the same property to the two here.
     *
     * <p>§162 found one dead on the first run: {@code CaseConditionInput} had declared
     * {@code required: [conditionId]} since §95 made the reference form the only accepted one, so
     * the exclusivity its comment argued from no longer existed.
     */
    @Test
    @DisplayName("every exemption is still exempting something")
    void everyExemptionStillExemptsSomething() {
        List<String> inert = new ArrayList<>();

        Set<String> withoutAClientError = new java.util.LinkedHashSet<>();
        for (OpenApiContract.Operation operation : contract.operations()) {
            if (operation.responseCodes().stream().noneMatch(code -> code.startsWith("4"))) {
                withoutAClientError.add(operation.describe());
            }
        }
        for (String exempt : NO_CLIENT_ERROR_POSSIBLE) {
            if (!withoutAClientError.contains(exempt)) {
                inert.add("NO_CLIENT_ERROR_POSSIBLE  " + exempt
                        + "  — the operation is gone, or it now declares a 4xx");
            }
        }

        Map<String, Boolean> objectSchemasMissingRequired = new java.util.LinkedHashMap<>();
        contract.walk((location, node) -> {
            if (isObjectSchema(node)) {
                objectSchemasMissingRequired.put(location, !node.containsKey("required"));
            }
        });
        for (String exempt : NO_ALWAYS_PRESENT_PROPERTY) {
            Boolean missing = objectSchemasMissingRequired.get(exempt);
            if (missing == null) {
                inert.add("NO_ALWAYS_PRESENT_PROPERTY  " + exempt
                        + "  — no object schema at that location any more");
            } else if (!missing) {
                inert.add("NO_ALWAYS_PRESENT_PROPERTY  " + exempt
                        + "  — it declares `required` now, so nothing is being excused");
            }
        }

        assertThat(inert)
                .as("""
                        These exemptions no longer exempt anything.

                        Each is an argument for why some location may break a rule, and the location
                        either does not break it any more or is gone. Nothing failed — that is the
                        whole problem. A one-directional ignore list cannot report a member that
                        stopped needing to be on it, so the argument outlives the constraint and the
                        next reader takes it for a live one. §160 and §161 are both instances, two
                        days apart, in three different files.

                        Delete the row. If the constraint has merely MOVED — the schema was renamed,
                        the operation re-pathed — the row's location is what to update, and the
                        comment beside it is what to re-read while you are there.

                        Inert:""")
                .isEmpty();
    }

    // ------------------------------------------------------------- nullable ----

    @Test
    @DisplayName("a nullable schema reference is wrapped in allOf, never a $ref sibling")
    void nullableReferencesAreWrapped() {
        List<String> offenders = new ArrayList<>();

        contract.walk((location, node) -> {
            if (Boolean.TRUE.equals(node.get("nullable")) && node.containsKey("$ref")) {
                offenders.add(location + "  -> " + node.get("$ref"));
            }
        });

        assertThat(offenders)
                .as("""
                        `nullable: true` next to a `$ref` is silently ignored by the generator.

                        A `$ref` sibling is not merged, so the property generates as NON-nullable and
                        the type claims a value the server sends as null can never be null. Nothing
                        fails at build time; it fails at the call site, at runtime, for one caller.

                        This contract has been repaired for it three times by hand — TestCaseSummary.owner,
                        CaseVersion.author (§35) and Execution.assignee/status — each with a comment
                        explaining the fix and none of them preventing the next occurrence.

                        Write it as:  allOf: [{ $ref: '...' }]  with  nullable: true  alongside.

                        Offending nodes:""")
                .isEmpty();
    }

    // ------------------------------------------------------ path parameters ----

    /**
     * A path item's shared {@code parameters} block must declare only path parameters.
     *
     * <p>Query parameters placed there are inherited by <strong>every</strong> operation under
     * that path, including the ones that have no use for them. It reads as a tidy way to avoid
     * repeating a filter block and it is not: it is a claim, in the generated types, that a
     * create accepts the list's paging and filtering.
     *
     * <p>This is the third face of the same defect family as the two checks above and
     * {@link com.tcms.repository.DashboardEndpointTest}'s filter-key assertion. The mechanism is
     * always <em>a query parameter and its handler disagreeing silently</em>: §47 emitted a key
     * no operation declared, §49 sent one the contract had not declared, and here the contract
     * declares one no handler will ever bind. None of the three throws anywhere.
     */
    @Test
    @DisplayName("a path item declares no query parameters — they belong to the operation")
    void pathItemsDeclareOnlyPathParameters() {
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : contract.pathLevelQueryParameters().entrySet()) {
            offenders.add(entry.getKey() + "  declares " + entry.getValue()
                    + " for all of " + contract.methodsOf(entry.getKey()));
        }

        assertThat(offenders)
                .as("""
                        A query parameter on the path item is inherited by every operation under it.

                        `parameters` at path level applies to the whole path item, so a filter block
                        written once for the list is also declared on the POST beside it. The generated
                        types then offer `?page=2&ownerId=…` on a create, `openapi-typescript` renders
                        it as a legitimate query shape, and nothing rejects it at runtime either —
                        Spring ignores a parameter no handler binds.

                        The path item is the right home for `{projectId}` and `{caseId}`, which every
                        operation under it genuinely takes. Move query parameters down into the
                        operation that reads them, even at the cost of repeating a block.

                        Path items declaring query parameters:""")
                .isEmpty();
    }

    // ------------------------------------------------ the schema vs the record ----

    /**
     * Which Java record serves each named object schema — the contract↔server correspondence for
     * object shapes, as {@code ContractEnumTest.OWNERS} is for the twenty enums.
     *
     * <p><strong>This was four entries and one direction until §165.</strong> The four were the
     * ones somebody had already been bitten by, and the argument for keeping the list short was
     * that "the pairing between a record and a schema is a fact only a human knows, and a check
     * that guessed it would fail on the first view whose name does not match its schema". That is
     * true of the <em>pairing</em> and says nothing about the <em>size of the set</em> — §164 named
     * the distinction and did not act on it. The set is 79 now, and the three defects it found on
     * its first run had been on the wire for between two and sixty-five sections.
     *
     * <p><strong>It is still hand-written, and the class literals are why.</strong> A name match is
     * how {@link #everyNameMatchedRecordIsPairedOrExplained} <em>proposes</em> a pair; a line here
     * is how a human accepts it, and the compiler then holds it — rename or delete either side and
     * this file stops compiling. {@code TeamRef} is the entry that shows the difference: three
     * production records carry that name, only one serves the schema, and no scan could have
     * decided which (see {@link #HOMONYMS}).
     *
     * <p><strong>A list value, not a single class</strong>, for {@code ContractEnumTest.OWNERS}'
     * reason: a feature module may not import another's types, so one schema can legitimately be
     * served by two records that must stay identical to each other and to it. None does today —
     * that is a result, not an assumption, and it is worth knowing when the first one appears.
     */
    private static final Map<String, List<Class<?>>> RESPONSE_RECORDS = Map.ofEntries(
            Map.entry("AiProposal", List.of(com.tcms.repository.AiProposalView.class)),
            Map.entry("AiProposalInput", List.of(com.tcms.repository.AiProposalInput.class)),
            Map.entry("AiProposalSummary",
                    List.of(com.tcms.repository.AiProposalSummaryView.class)),
            Map.entry("ApiToken", List.of(com.tcms.identity.ApiTokenView.class)),
            Map.entry("Artifact", List.of(com.tcms.documentation.ArtifactView.class)),
            Map.entry("ArtifactFileInput", List.of(com.tcms.documentation.ArtifactFileInput.class)),
            Map.entry("ArtifactInput", List.of(com.tcms.documentation.ArtifactInput.class)),
            Map.entry("AutomationMapping", List.of(com.tcms.execution.AutomationMappingView.class)),
            Map.entry("BlockLink", List.of(com.tcms.documentation.BlockLinkView.class)),
            Map.entry("BlockLinkInput", List.of(com.tcms.documentation.BlockLinkInput.class)),
            // BulkResult and ReportDefinition are in PACKAGE_PRIVATE_OWNERS — see there.
            Map.entry("CaseConditionInput", List.of(com.tcms.repository.CaseConditionInput.class)),
            Map.entry("CaseConditionRef", List.of(com.tcms.repository.CaseConditionRefView.class)),
            Map.entry("CaseDraft", List.of(com.tcms.repository.CaseDraftView.class)),
            Map.entry("CaseDraftInput", List.of(com.tcms.repository.CaseDraftInput.class)),
            Map.entry("CaseFlake", List.of(com.tcms.repository.CaseFlakeView.class)),
            Map.entry("CaseStatus", List.of(com.tcms.project.CaseStatusView.class)),
            Map.entry("ClassificationDimension",
                    List.of(com.tcms.project.ClassificationDimensionView.class)),
            Map.entry("ClassificationValue",
                    List.of(com.tcms.project.ClassificationValueView.class)),
            Map.entry("ClassificationValueInput",
                    List.of(com.tcms.project.ClassificationValueInput.class)),
            Map.entry("Condition", List.of(com.tcms.repository.ConditionView.class)),
            Map.entry("Configuration", List.of(com.tcms.execution.ConfigurationView.class)),
            Map.entry("Criterion", List.of(com.tcms.execution.CriterionView.class)),
            Map.entry("CriterionInput", List.of(com.tcms.execution.CriterionInput.class)),
            Map.entry("CurrentUser", List.of(com.tcms.auth.CurrentUserService.CurrentUser.class)),
            Map.entry("DefectLink", List.of(com.tcms.execution.DefectLinkView.class)),
            Map.entry("DocBlock", List.of(com.tcms.documentation.DocBlockView.class)),
            Map.entry("DocBlockInput", List.of(com.tcms.documentation.DocBlockInput.class)),
            Map.entry("DocPage", List.of(com.tcms.documentation.DocPageView.class)),
            Map.entry("DocPageInput", List.of(com.tcms.documentation.DocPageInput.class)),
            Map.entry("DocPageStatusInput",
                    List.of(com.tcms.documentation.DocPageStatusInput.class)),
            Map.entry("DocPageSummary", List.of(com.tcms.documentation.DocPageSummaryView.class)),
            Map.entry("EntityVersion", List.of(com.tcms.repository.EntityVersionView.class)),
            Map.entry("Evidence", List.of(com.tcms.execution.EvidenceView.class)),
            Map.entry("Facet", List.of(com.tcms.repository.FacetView.class)),
            Map.entry("HeldPermission", List.of(com.tcms.auth.MeController.HeldPermission.class)),
            Map.entry("IngestionJob", List.of(com.tcms.execution.IngestionJobView.class)),
            Map.entry("IngestionJobSummary", List.of(com.tcms.execution.IngestionJobSummary.class)),
            Map.entry("InvitationPreview",
                    List.of(com.tcms.identity.InvitationService.InvitationPreview.class)),
            Map.entry("IssuedApiToken", List.of(com.tcms.identity.IssuedApiTokenView.class)),
            Map.entry("PlanCaseRef", List.of(com.tcms.execution.PlanCaseRefView.class)),
            Map.entry("PlanDocumentLinkInput",
                    List.of(com.tcms.documentation.PlanDocumentLinkInput.class)),
            Map.entry("PlanSection", List.of(com.tcms.execution.PlanSectionView.class)),
            Map.entry("PlanSectionInput", List.of(com.tcms.execution.PlanSectionInput.class)),
            Map.entry("PlanStatistics", List.of(com.tcms.execution.PlanStatisticsView.class)),
            Map.entry("Project", List.of(com.tcms.project.ProjectView.class)),
            // Seven required properties, six of them primitives — so the failure this catches on
            // ProjectSettings is not an absent key but a renamed one, which reads to a client as the
            // setting having reverted to its default. §6g's opt-in defaulting to "off" is the safe
            // direction; author-may-approve defaulting to "false" is not, since the property is
            // stored in the positive and an absent key would read as the rule being ON when the
            // project turned it off.
            Map.entry("ProjectSettings", List.of(com.tcms.project.ProjectSettingsView.class)),
            // Three of its seven are required-AND-nullable (§87), which is exactly the shape this
            // check is for: a poller cannot tell "the server did not send `error`" from "there is
            // no error", so the component has to exist even though the value is usually null.
            Map.entry("ReportJob", List.of(com.tcms.reporting.ReportJobView.class)),
            Map.entry("ResultCounts", List.of(com.tcms.execution.ResultCountsView.class)),
            Map.entry("Review", List.of(com.tcms.project.ReviewView.class)),
            Map.entry("ReviewAssignee", List.of(com.tcms.project.ReviewAssigneeView.class)),
            Map.entry("ReviewComment", List.of(com.tcms.project.ReviewCommentView.class)),
            Map.entry("ReviewParticipation",
                    List.of(com.tcms.project.ReviewParticipationView.class)),
            Map.entry("Role", List.of(com.tcms.identity.RoleView.class)),
            Map.entry("RoleAssignment", List.of(com.tcms.identity.RoleAssignmentView.class)),
            Map.entry("SharedStep", List.of(com.tcms.repository.SharedStepView.class)),
            Map.entry("StepResult", List.of(com.tcms.execution.StepResultView.class)),
            Map.entry("StepSuggestion", List.of(com.tcms.repository.StepSuggestionView.class)),
            Map.entry("Suite", List.of(com.tcms.repository.SuiteView.class)),
            Map.entry("TagUsage", List.of(com.tcms.repository.TagUsageView.class)),
            Map.entry("Team", List.of(com.tcms.identity.TeamView.class)),
            Map.entry("TeamInput", List.of(com.tcms.identity.TeamInput.class)),
            Map.entry("TeamMemberInput", List.of(com.tcms.identity.TeamMemberInput.class)),
            // One of three records carrying this name and the only one serving the schema: it is
            // reached from `ReviewAssignee.team`, the schema's single $ref. See HOMONYMS.
            Map.entry("TeamRef", List.of(com.tcms.project.TeamRefView.class)),
            // The one pairing no name match proposes, and the reason the map is hand-written:
            // `TestCase` is an allOf of `TestCaseSummary`, and the record serving it is named for
            // being the DETAIL rather than for the schema.
            Map.entry("TestCase", List.of(com.tcms.repository.TestCaseDetailView.class)),
            Map.entry("TestCaseSummary", List.of(com.tcms.repository.TestCaseSummaryView.class)),
            Map.entry("TestExecution", List.of(com.tcms.execution.TestExecutionView.class)),
            Map.entry("TestExecutionSummary",
                    List.of(com.tcms.execution.TestExecutionSummaryView.class)),
            Map.entry("TestPlan", List.of(com.tcms.execution.TestPlanView.class)),
            Map.entry("TestRun", List.of(com.tcms.execution.TestRunView.class)),
            Map.entry("TestRunDetail", List.of(com.tcms.execution.TestRunDetailView.class)),
            Map.entry("TestStep", List.of(com.tcms.repository.TestStepView.class)),
            Map.entry("UnmatchedTest", List.of(com.tcms.execution.UnmatchedTestView.class)),
            Map.entry("User", List.of(com.tcms.identity.UserView.class)),
            Map.entry("UserInvitation", List.of(com.tcms.identity.UserInvitationView.class)),
            Map.entry("UserRef", List.of(com.tcms.api.UserRef.class)),
            Map.entry("WorkItem", List.of(com.tcms.work.WorkItemView.class)));

    /**
     * The two owners this file cannot name as class literals, because they are package-private.
     *
     * <p><strong>The visibility was left alone on purpose.</strong> Neither record argues for being
     * package-private — both look incidental beside their public siblings — but widening a
     * production type so a test can name it is a production change made for a test, and this slice
     * is about arguments that drift, not about tidying. The cost is stated rather than hidden: these
     * two lose the compile-time half of the guarantee the map's class literals give. A rename still
     * fails, as a {@code ClassNotFoundException} from the resolver below rather than as a compiler
     * error, which is later and noisier and is the whole difference.
     */
    private static final Map<String, List<String>> PACKAGE_PRIVATE_OWNERS = Map.of(
            "BulkResult", List.of("com.tcms.repository.BulkResultView"),
            "ReportDefinition", List.of("com.tcms.reporting.ReportDefinitionView"));

    /**
     * Object schemas whose record pairing is asserted somewhere else, and where.
     *
     * <p>{@code ContractEnumTest.NO_SERVER_ENUM}'s shape: the population rule below still has to
     * account for the schema, and duplicating a rule that landed one section earlier would mean two
     * assertions drifting apart over the same fact.
     */
    private static final Map<String, String> PAIRED_ELSEWHERE = Map.of(
            "Problem",
            "ErrorBodyPolicyTest.theContractsProblemIsThisRecord (§164) already pins it against "
                    + "com.tcms.api.Problem in both directions, and that file also compares the two "
                    + "envelopes as serialized bytes — a thing this rule cannot do, because a "
                    + "record-component comparison is static and ProblemDetail is Spring's type");

    /**
     * Production records that carry a contract schema's name without serving it, and the sentence
     * that tells them apart — {@code ContractEnumTest.HOMONYMS} at the object shape.
     *
     * <p>Both entries are {@code TeamRef}, and together they correct a standing claim: §147 named
     * {@code TeamRef} as a consolidation candidate on the grounds that "it is two copies". There
     * are <strong>three</strong> types, they do not all serve the same schema, and one of them is
     * not the same shape — so the argument that moved {@code UserRef} does not transfer unchanged.
     * The named {@code TeamRef} schema has exactly one {@code $ref} ({@code ReviewAssignee.team});
     * the other two records serve <em>inline</em> object shapes, which {@code objectSchemas()}
     * deliberately does not cover because an anonymous shape has no name to correspond by.
     */
    private static final Map<String, String> HOMONYMS = Map.of(
            "com.tcms.identity.RoleAssignmentView.TeamRef",
            "serves RoleAssignment.team, which is an INLINE {id, name} object rather than a $ref to "
                    + "the TeamRef schema. Identical shape, no correspondence — collapsing the two "
                    + "would be a contract change (the inline object becoming a $ref) before it "
                    + "was a Java one",
            "com.tcms.auth.CurrentUserService.TeamRef",
            "serves CurrentUser.teams, also inline, and it is NOT the same shape: it carries a "
                    + "third component, isLead, which the TeamRef schema does not declare and this "
                    + "one's inline schema does. A consolidation has to answer what happens to it");

    /**
     * Members one side names and the other deliberately does not, with the reason each stays.
     *
     * <p>Pinned in <strong>both</strong> directions, like {@code ContractEnumTest}'s
     * {@code DIVERGENCE_BY_DECISION} and {@code ArchitectureRulesTest}'s two maps: an entry here
     * whose divergence has been closed fails the build, so an argument cannot outlive its gap. That
     * property is the whole reason those maps did not rot while §162's one-directional exemptions
     * did.
     *
     * <p><strong>All four are contract-only, which is the safe direction</strong> — a declared
     * property nothing sends costs a dead branch in a client, where a component nothing declares is
     * a value no generated union can name. And all four are blocked by <em>the same</em> rule, which
     * is what makes them one decision rather than four: {@code repository} and {@code documentation}
     * may not import {@code execution}, so a value sourced from {@code test_execution} has to arrive
     * as a read model fed by events (§3 lesson 4) rather than as a join.
     *
     * <p><strong>Three of the four said something else until §165, and what they said was that the
     * module "does not exist".</strong> {@code execution} is 119 files and {@code documentation} is
     * 57. This is §160's shape exactly — {@code rawStorageKey} arguing "there is no adapter" for
     * twenty-nine sections after {@code storage} built one — and it cost the same thing: an
     * argument that misnames the obstacle deters the repair, because "the module does not exist"
     * reads as *wait* and "the module boundary forbids the join" reads as *design a read model*.
     */
    private static final Map<String, String> MEMBER_DIVERGENCE_BY_DECISION = Map.ofEntries(
            Map.entry("TestCaseSummary.lastResult",
                    "CONTRACT-ONLY. Sourced from test_execution, which is `execution` — a module "
                            + "`repository` may not import (see its package-info: the same rule "
                            + "that keeps suite_run_summary a read model in `reporting`). Not "
                            + "required, so omitting the key is legal, and the SPA renders its "
                            + "absence as \"Never run\". WHAT THAT COSTS TODAY: every case in the "
                            + "product reads \"Never run\", including ones with recorded "
                            + "executions — CaseGrid's Last result column has been a confident "
                            + "falsehood since `execution` shipped"),
            Map.entry("TestCase.lastResult",
                    "CONTRACT-ONLY, inherited through the allOf from TestCaseSummary and absent "
                            + "from TestCaseDetailView for the same reason; the case detail page "
                            + "renders it the same way and is wrong the same way"),
            Map.entry("TestCase.relatedDocuments",
                    "CONTRACT-ONLY. Sourced from doc_link, which is `documentation` — the other "
                            + "module `repository` may not import. Its old argument added that "
                            + "\"nothing can create a link, so no case has one\": DocBlockView's "
                            + "header struck that clause when POST .../blocks/{blockId}/links "
                            + "shipped, and this side never heard. WHAT THAT COSTS TODAY: a link "
                            + "made from a doc block to a case is never shown on the case — "
                            + "CaseDetailPage's DocLinksPanel gets `?? []` and renders empty"),
            Map.entry("DocBlock.coverage",
                    "CONTRACT-ONLY, and the control group: this is the one of the four whose "
                            + "argument was REVISED when its constraint moved rather than left to "
                            + "expire. DocBlockView's header strikes the old reason (\"nothing can "
                            + "create a link\") and states the live one — passed/failed/notRun are "
                            + "test_execution joined to test_case, two modules `documentation` may "
                            + "not name — and all five members are required, so a partial object is "
                            + "not available and a zeroed one would print \"Not tested\" against a "
                            + "statement whose cases may all be green"));

    /**
     * Every production record whose simple name matches a named object schema, by that name.
     *
     * <p>Read from the source text rather than from a class graph, and the narrowing is the point:
     * this rule needs <em>names</em>, not types, and an ArchUnit import of this codebase is the
     * largest single object a test here allocates — a third one held live is what took the worker's
     * 512m heap to 522685K and hung the build with nothing failing and nothing printed (§161). The
     * map's values are class literals, so the compiler checks the part that matters.
     */
    private static Map<String, List<String>> productionRecordsByName() {
        Pattern declaration = Pattern.compile("\\brecord\\s+(\\w+)\\s*\\(");
        Pattern packageName = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
        Map<String, List<String>> byName = new TreeMap<>();
        for (Path file : mainSources()) {
            String source = read(file);
            Matcher declared = packageName.matcher(source);
            if (!declared.find()) {
                continue;
            }
            String prefix = declared.group(1);
            String outer = file.getFileName().toString().replace(".java", "");
            declaration.matcher(source).results().forEach(match -> {
                String simple = match.group(1);
                String canonical = simple.equals(outer)
                        ? prefix + "." + simple
                        : prefix + "." + outer + "." + simple;
                byName.computeIfAbsent(simple, key -> new ArrayList<>()).add(canonical);
            });
        }
        return byName;
    }

    /**
     * Every pairing this file asserts — {@link #RESPONSE_RECORDS} plus the two resolved by name.
     *
     * <p>One map at the point of use, two at the point of declaration, because the reason the
     * second exists is a property of those two types and belongs beside them.
     */
    private static Map<String, List<Class<?>>> allPairings() {
        Map<String, List<Class<?>>> pairings = new TreeMap<>(RESPONSE_RECORDS);
        PACKAGE_PRIVATE_OWNERS.forEach((schema, owners) -> pairings.put(schema, owners.stream()
                .map(ContractConventionTest::resolve)
                .collect(Collectors.toList())));
        return pairings;
    }

    private static Class<?> resolve(String canonical) {
        try {
            return Class.forName(canonical);
        } catch (ClassNotFoundException gone) {
            throw new AssertionError(
                    canonical + " is named as a schema's owner and is not a class — it was renamed "
                            + "or deleted while the contract still declares the schema it served",
                    gone);
        }
    }

    /** The records a name match proposes for a schema: {@code Foo} and {@code FooView}. */
    private static List<String> candidatesFor(String schema, Map<String, List<String>> records) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(records.getOrDefault(schema, List.of()));
        candidates.addAll(records.getOrDefault(schema + "View", List.of()));
        return candidates;
    }

    @Test
    @DisplayName("every schema a record is named for is paired, and every pairing is a real schema")
    void everyNameMatchedRecordIsPairedOrExplained() {
        Map<String, List<String>> records = productionRecordsByName();
        Set<String> schemas = contract.objectSchemas();

        assertThat(schemas)
                .as("no object schema was found at all — components.schemas has moved, and every "
                        + "rule below would pass over an empty set")
                .hasSizeGreaterThan(100);
        assertThat(records)
                .as("no production record was found — the source scan or the layout has moved")
                .hasSizeGreaterThan(200);

        Map<String, List<Class<?>>> pairings = allPairings();
        Set<String> claimed = new TreeSet<>(pairings.keySet());
        claimed.addAll(PAIRED_ELSEWHERE.keySet());

        List<String> unpaired = schemas.stream()
                .filter(schema -> !claimed.contains(schema))
                .filter(schema -> !candidatesFor(schema, records).isEmpty())
                .toList();

        assertThat(unpaired)
                .as("""
                        A production record is named for this schema and nothing says it serves it.

                        The pairing is a human's call, which is why this rule reports rather than
                        assumes — but "nobody wrote it down" is how a schema and the record serving
                        it drift with no gate between them. Add the entry, or PAIRED_ELSEWHERE with
                        the rule that covers it, or HOMONYMS if the name is a coincidence.

                        Schemas with a same-named record and no pairing:""")
                .isEmpty();

        assertThat(claimed)
                .as("an entry here names a schema the contract does not declare — the schema was "
                        + "renamed or deleted and the pairing outlived it, which is the shape §152, "
                        + "§153, §154, §156, §160 and §162 each found in turn")
                .isSubsetOf(schemas);

        List<String> unexplained = new ArrayList<>();
        pairings.forEach((schema, owners) -> {
            Set<String> named = owners.stream()
                    .map(Class::getCanonicalName)
                    .collect(Collectors.toCollection(TreeSet::new));
            for (String candidate : candidatesFor(schema, records)) {
                if (!named.contains(candidate) && !HOMONYMS.containsKey(candidate)) {
                    unexplained.add(candidate + "  (named for contract schema " + schema + ")");
                }
            }
        });

        assertThat(unexplained)
                .as("""
                        A record carries a paired schema's name and is neither one of its owners nor
                        a recorded homonym.

                        This is how a second copy across a module boundary goes uncompared — and it
                        is how §147 came to describe TeamRef as "two copies" when there are three.
                        Add it to that schema's entry, or to HOMONYMS with the sentence that tells
                        the two apart.

                        Records:""")
                .isEmpty();

        List<String> stale = HOMONYMS.keySet().stream()
                .filter(name -> records.values().stream().noneMatch(all -> all.contains(name)))
                .toList();

        assertThat(stale)
                .as("a HOMONYMS entry no longer names a production record — the collision it "
                        + "resolves is gone, and an argument cannot outlive its ambiguity")
                .isEmpty();
    }

    /**
     * The schema and the record name the same members, both ways.
     *
     * <p>Supersedes {@code requiredPropertiesHaveAComponent}, which walked the {@code required}
     * array only and is strictly contained in this: a required property with no component is a
     * schema-only member. Its history is the reason this rule exists at all and is kept in the
     * failure message.
     */
    @Test
    @DisplayName("a schema and the record serving it name the same members, both ways")
    void pairedSchemasAndRecordsNameTheSameMembers() {
        Map<String, String> divergences = new TreeMap<>();
        int compared = 0;

        for (Map.Entry<String, List<Class<?>>> pairing : allPairings().entrySet()) {
            Set<String> declared = contract.propertiesOf(
                    "#/components/schemas/" + pairing.getKey());
            for (Class<?> record : pairing.getValue()) {
                compared++;
                Set<String> components = Stream.of(record.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toCollection(TreeSet::new));
                for (String property : declared) {
                    if (!components.contains(property)) {
                        divergences.put(pairing.getKey() + "." + property,
                                "CONTRACT-ONLY — declared, and " + record.getSimpleName()
                                        + " has no component to send it");
                    }
                }
                for (String component : components) {
                    if (!declared.contains(component)) {
                        divergences.put(pairing.getKey() + "." + component,
                                "SERVER-ONLY — " + record.getSimpleName() + " sends it and the "
                                        + "schema declares it nowhere");
                    }
                }
            }
        }

        assertThat(compared)
                .as("no pairing was compared, so this rule passes vacuously")
                .isGreaterThan(70);

        assertThat(divergences.keySet())
                .as("""
                        A schema and the record serving it disagree about their members.

                        SERVER-ONLY is the defect. Jackson writes the component's name, the
                        generated type has no such property, and every TypeScript consumer reads it
                        as absent while it sits in the body — `Problem.instance` for sixteen
                        sections (§162, §163) and `TestPlan.keySeq` on seven operations, found by
                        this rule's first run.

                        CONTRACT-ONLY is a promise nothing keeps. It is usually deliberate and
                        sometimes not: §66 added `createdBy` to TestCaseSummary — which `TestCase`
                        is an `allOf` of — TestCaseDetailView never grew the component, and
                        `GET /cases/{caseId}` omitted the key for two slices. The case detail is the
                        only screen with a delete affordance, its gate is `case:delete` qualified
                        OWN, and the SPA read `data.createdBy?.id ?? null` — an absent key became
                        `null`, null is a SUPPLIED author matching nobody, and a Test Engineer was
                        refused their own case. Nothing could see it: `tsc` allows optional chaining
                        on a required field, the page's test supplies a fixture that has the key,
                        and the endpoint's field test enumerated the required list BY HAND.

                        Edit both sides, or add the member to MEMBER_DIVERGENCE_BY_DECISION with the
                        argument written where the absence is — and name the real obstacle, not a
                        module's existence.

                        Divergences:""")
                .containsExactlyInAnyOrderElementsOf(MEMBER_DIVERGENCE_BY_DECISION.keySet());
    }

    // -------------------------------------------------------------- sources ----

    /**
     * Every Java file under the backend's main source set.
     *
     * <p>Located by walking up from the working directory for the same reason
     * {@link OpenApiContract#load()} does: Gradle runs with it set to {@code backend/} and an IDE
     * does not, and a relative literal fails as a {@code NoSuchFileException} that reads like the
     * source tree is missing.
     */
    private static List<Path> mainSources() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("src/main/java/com/tcms"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "backend/src/main/java not found above " + Path.of("").toAbsolutePath());
        }
        try (Stream<Path> tree = Files.walk(dir.resolve("src/main/java/com/tcms"))) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -------------------------------------------------------------- helpers ----

    /**
     * An object schema for the purposes of these rules: a node that declares properties.
     *
     * <p>Keyed on {@code properties} rather than {@code type: object} on purpose — the
     * contract routinely omits {@code type} on an {@code allOf} branch and on inline
     * response bodies, and those are precisely the positions where {@code required} gets
     * forgotten, so a check that demanded the type keyword would skip the cases it exists
     * to catch.
     */
    private static boolean isObjectSchema(Map<String, Object> node) {
        return node.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty();
    }

    private static List<String> propertyNames(Map<String, Object> node) {
        return ((Map<?, ?>) node.get("properties"))
                .keySet().stream().map(String::valueOf).toList();
    }
}
