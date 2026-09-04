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
            "POST /auth/logout",
            // The two external-sign-in legs, and they are the strongest members this set has had.
            // A browser mid-redirect has no client to read a status code, so BOTH answer 302 in
            // every case — an unknown provider, a disabled one, an unreachable issuer, a replayed
            // `state`, a refused identity — landing on the SPA with one opaque marker. Declaring a
            // 4xx here would be declaring a response the endpoint must never send: telling those
            // causes apart is exactly the oracle §6r forbids on a pre-authentication surface.
            "GET /auth/sso/{providerId}/authorize",
            "GET /auth/sso/callback",
            // The social flow's browser legs, V58 — the argument above verbatim: 302 with one
            // opaque marker in every case, unknown-provider path segments included, which is
            // why the controller parses {provider} itself instead of letting enum conversion
            // answer a 400 to a browser that cannot read it.
            "GET /auth/social/{provider}/authorize",
            "GET /auth/social/callback",
            // Instance configuration, identical for every caller: no auth, no parameters, no
            // body — a 200 with a list (possibly empty) is the only answer it has, exactly
            // like POST /auth/providers' always-200 rule but without even an email to validate.
            "GET /auth/social/providers");

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
            ".components.schemas.BulkPayload",

            // §245's argument to the execution bulk assign, and BulkPayload's case one resource
            // over. `assigneeId` is the ASSIGN payload — present and NULLABLE, because null is how
            // the act takes a row off everybody and is a value rather than an omission — and
            // `assigneeIds` is DISTRIBUTE's list. The sibling `action` decides which, so neither is
            // present across both and a `required` array here would misdescribe one of the two
            // actions. Enforced server-side by ExecutionBulkAssignRequest's validation, where the
            // fault lands on `payload.assigneeId` / `payload.assigneeIds`, and covered by
            // ExecutionBulkAssignTest. The `oneOf` discriminated on `action` was rejected for
            // BulkPayload's reason, unchanged by there being two actions here rather than eight.
            ".components.schemas.ExecutionBulkPayload");

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

        Set<String> wholeBodies = contract.schemasReferencedOutsideAllOf();
        for (String exempt : BRANCH_ONLY_SCHEMAS.keySet()) {
            if (!contract.objectSchemas().contains(exempt)) {
                inert.add("BRANCH_ONLY_SCHEMAS  " + exempt + "  — the schema is gone");
            } else if (wholeBodies.contains(exempt)) {
                inert.add("BRANCH_ONLY_SCHEMAS  " + exempt
                        + "  — something $refs it outside an allOf now, so a record does serve it "
                        + "whole and the pairing it is excused from has become checkable");
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
     * the distinction and did not act on it. ~~The set is 79 now~~ → <strong>the set is 125, and
     * §170 is why</strong>: 79 was every schema a <em>name match</em> proposed, which is a
     * different population from every schema, and the gap between the two was 45 schemas nothing
     * compared at all (see {@link #everyObjectSchemaIsPairedOrExempt}).
     *
     * <p><strong>It is still hand-written, and the class literals are why.</strong> A name match is
     * how {@link #everyNameMatchedRecordIsPairedOrExplained} <em>proposes</em> a pair; a line here
     * is how a human accepts it, and the compiler then holds it — rename or delete either side and
     * this file stops compiling. ~~{@code TeamRef} is the entry that shows the difference: three
     * production records carry that name, only one serves the schema, and no scan could have
     * decided which (see {@link #HOMONYMS}).~~ → §175 made that one record, so the example is now
     * {@code Attachment}, whose two owners no name match proposes as a pair and no scan could
     * confirm: they are in modules that may not import each other, and only a human knows the
     * contract means them to stay identical.
     *
     * <p><strong>A list value, not a single class</strong>, for {@code ContractEnumTest.OWNERS}'
     * reason: a feature module may not import another's types, so one schema can legitimately be
     * served by two records that must stay identical to each other and to it. ~~None does today —
     * that is a result, not an assumption, and it is worth knowing when the first one appears.~~ →
     * <strong>{@code Attachment} does, and it did on the day that sentence was written.</strong>
     * {@code repository.CaseAttachmentView} and {@code documentation.DocPageAttachmentView} are
     * seven identical components in two modules, neither of which may import the other, and both
     * serve this one schema. The list value was designed for that case and the claim that nothing
     * used it was made without looking, because the schema was outside the name-matched population
     * this map covered — the same blind spot §170 closes. See that entry's own note.
     */
    private static final Map<String, List<Class<?>>> RESPONSE_RECORDS = Map.ofEntries(
            Map.entry("AiProposal", List.of(com.tcms.repository.AiProposalView.class)),
            // ---- 015's agent block (M5) ----
            // An assistant is an api_token the caller owns carrying the Agent role (D26) — there is
            // no assistant table — so the record serving this schema is a projection of ApiToken
            // narrowed to what its owner needs to recognise one of their own machines.
            Map.entry("Assistant", List.of(com.tcms.identity.AssistantView.class)),
            Map.entry("AssistantInput", List.of(com.tcms.identity.AssistantInput.class)),
            Map.entry("IssuedAssistant", List.of(com.tcms.identity.IssuedAssistantView.class)),
            // NOT a name match, and deliberately: com.tcms.project.ProjectRepository is this
            // package's Spring Data interface for Project, so the entity and its view are
            // ProjectRepo / ProjectRepoView. Two types one import apart, one an entity and one a
            // repository, is how a wrong import survives review.
            Map.entry("ProjectRepository", List.of(com.tcms.project.ProjectRepoView.class)),
            Map.entry("ProjectRepositoryInput", List.of(com.tcms.project.ProjectRepoInput.class)),
            Map.entry("AgentTaskContext",
                    List.of(com.tcms.tracker.agent.AgentTaskContextView.class)),
            Map.entry("AgentStoryContext",
                    List.of(com.tcms.tracker.agent.AgentStoryContextView.class)),
            Map.entry("AgentEpicContext",
                    List.of(com.tcms.tracker.agent.AgentEpicContextView.class)),
            Map.entry("AgentSpecExcerpt",
                    List.of(com.tcms.tracker.agent.AgentSpecExcerptView.class)),
            // ---- 004's tracker block: the last unpaired schemas in the contract ----
            // Every one of them is public in com.tcms.integration.tracker, so they are class
            // literals rather than PACKAGE_PRIVATE_OWNERS strings. What is worth reading here is
            // which record serves which schema, because three of the pairings are not name matches:
            // the contract names a shape for the wire (TrackerBinding, TrackerIdentity,
            // TrackerIssueCreated) and this codebase names a record for what it is (…View).
            Map.entry("TrackerBinding",
                    List.of(com.tcms.integration.tracker.TrackerBindingView.class)),
            Map.entry("TrackerBindingInput",
                    List.of(com.tcms.integration.tracker.TrackerBindingInput.class)),
            Map.entry("TrackerBindingPatch",
                    List.of(com.tcms.integration.tracker.TrackerBindingPatch.class)),
            // A SEPARATE record from TrackerIdentityView, and the separation is the D19 decision
            // rather than a naming preference: that view carries secretHint, accountId, expiresAt,
            // lastError and lastCheckedAt, and this endpoint may carry none of them. Reusing it is
            // the natural implementation, which is why the pairing is pinned here and the response's
            // key set is closed by its own test.
            Map.entry("TrackerConnectedMember",
                    List.of(com.tcms.integration.tracker.TrackerConnectedMemberView.class)),
            Map.entry("TrackerIdentity",
                    List.of(com.tcms.integration.tracker.TrackerIdentityView.class)),
            Map.entry("TrackerIdentityInput",
                    List.of(com.tcms.integration.tracker.TrackerIdentityInput.class)),
            Map.entry("TrackerConnectionCheck",
                    List.of(com.tcms.integration.tracker.TrackerConnectionCheckView.class)),
            // The one tracker record with no `View` suffix, because it is not a view of anything
            // stored: it is what a tracker said this request, rendered and dropped (004 D15).
            Map.entry("TrackerIssue", List.of(com.tcms.integration.tracker.TrackerIssue.class)),
            Map.entry("TrackerIssueSearch",
                    List.of(com.tcms.integration.tracker.TrackerIssueSearchView.class)),
            Map.entry("TrackerIssueLookup",
                    List.of(com.tcms.integration.tracker.TrackerIssueLookupView.class)),
            Map.entry("TrackerIssueCreateInput",
                    List.of(com.tcms.integration.tracker.TrackerIssueCreateInput.class)),
            Map.entry("TrackerIssueCreated",
                    List.of(com.tcms.integration.tracker.TrackerIssueCreatedView.class)),
            // Served from `documentation`, not from `integration`: the row is a doc_link, and
            // `integration` may not write that table. IssueLinkController's header carries the
            // split, which is ExternalLinkController's verbatim.
            Map.entry("IssueLink", List.of(com.tcms.documentation.IssueLinkView.class)),
            Map.entry("IssueLinkInput", List.of(com.tcms.documentation.IssueLinkInput.class)),
            Map.entry("AiProposalInput", List.of(com.tcms.repository.AiProposalInput.class)),
            Map.entry("AiProposalSummary",
                    List.of(com.tcms.repository.AiProposalSummaryView.class)),
            Map.entry("ApiToken", List.of(com.tcms.identity.ApiTokenView.class)),
            Map.entry("AuditEvent", List.of(com.tcms.identity.AuditEventView.class)),
            Map.entry("CaseSearchHit",
                    List.of(com.tcms.repository.CaseSearchHitView.class)),
            Map.entry("ApiTokenInput", List.of(com.tcms.identity.ApiTokenIssueRequest.class)),
            Map.entry("EmailDomains",
                    List.of(com.tcms.identity.IdentityProviderController.EmailDomainsRequest.class,
                            com.tcms.identity.IdentityProviderController.EmailDomainsResponse.class)),
            Map.entry("IdentityProvider", List.of(com.tcms.identity.IdentityProviderView.class)),
            Map.entry("IdentityProviderInput",
                    List.of(com.tcms.identity.IdentityProviderInput.class)),
            Map.entry("SocialProviderList",
                    List.of(com.tcms.auth.SocialController.SocialProviderList.class)),
            Map.entry("SocialRedirect",
                    List.of(com.tcms.auth.SocialController.SocialRedirect.class)),
            Map.entry("SocialIdentity",
                    List.of(com.tcms.auth.SocialIdentityController.SocialIdentityView.class)),
            Map.entry("SocialIdentityList",
                    List.of(com.tcms.auth.SocialIdentityController.SocialIdentityList.class)),
            Map.entry("SsoProviderChoice",
                    List.of(com.tcms.auth.SsoLoginService.ProviderChoice.class)),
            Map.entry("SsoProviderDiscovery",
                    List.of(com.tcms.auth.SsoController.ProviderDiscoveryResponse.class)),
            Map.entry("Artifact", List.of(com.tcms.documentation.ArtifactView.class)),
            Map.entry("ArtifactFileInput", List.of(com.tcms.documentation.ArtifactFileInput.class)),
            Map.entry("ArtifactEditInput",
                    List.of(com.tcms.documentation.ArtifactEditInput.class)),
            Map.entry("ArtifactInput", List.of(com.tcms.documentation.ArtifactInput.class)),
            Map.entry("ArtifactStatusInput",
                    List.of(com.tcms.documentation.ArtifactStatusInput.class)),
            // THE TWO-OWNER CASE THE LIST VALUE WAS DESIGNED FOR, and it existed before the
            // comment above said nothing did. `repository` and `documentation` may not import each
            // other, so an attachment's wire shape is written twice; this entry is the only thing
            // comparing the two copies to each other, and it does so through the schema rather
            // than directly, which is the comparison that matters — a third module growing
            // attachments adds a third owner here and inherits the same check for free.
            Map.entry("Attachment", List.of(
                    com.tcms.repository.CaseAttachmentView.class,
                    com.tcms.documentation.DocPageAttachmentView.class)),
            Map.entry("AuthResult", List.of(com.tcms.auth.AuthController.LoginResponse.class)),
            Map.entry("AutomationMapping", List.of(com.tcms.execution.AutomationMappingView.class)),
            Map.entry("AutomationMappingInput",
                    List.of(com.tcms.execution.AutomationMappingRequest.class)),
            Map.entry("BlockLink", List.of(com.tcms.documentation.BlockLinkView.class)),
            Map.entry("BlockLinkInput", List.of(com.tcms.documentation.BlockLinkInput.class)),
            // BulkOperation, BulkPayload, BulkResult, ReportDefinition and ReportDefinitionInput
            // are in PACKAGE_PRIVATE_OWNERS — see there.
            Map.entry("CaseConditionInput", List.of(com.tcms.repository.CaseConditionInput.class)),
            Map.entry("CaseConditionRef", List.of(com.tcms.repository.CaseConditionRefView.class)),
            Map.entry("CaseDraft", List.of(com.tcms.repository.CaseDraftView.class)),
            Map.entry("CaseDraftInput", List.of(com.tcms.repository.CaseDraftInput.class)),
            Map.entry("CaseFlake", List.of(com.tcms.repository.CaseFlakeView.class)),
            Map.entry("CaseHistorySummary",
                    List.of(com.tcms.execution.CaseHistoryView.SummaryView.class)),
            Map.entry("CaseQuarantineInput",
                    List.of(com.tcms.repository.CaseQuarantineRequest.class)),
            Map.entry("CaseStatus", List.of(com.tcms.project.CaseStatusView.class)),
            Map.entry("ClassificationDimension",
                    List.of(com.tcms.project.ClassificationDimensionView.class)),
            Map.entry("ClassificationValue",
                    List.of(com.tcms.project.ClassificationValueView.class)),
            Map.entry("ClassificationValueInput",
                    List.of(com.tcms.project.ClassificationValueInput.class)),
            Map.entry("Condition", List.of(com.tcms.repository.ConditionView.class)),
            Map.entry("ConditionInput", List.of(com.tcms.repository.ConditionWriteRequest.class)),
            Map.entry("Configuration", List.of(com.tcms.execution.ConfigurationView.class)),
            Map.entry("Criterion", List.of(com.tcms.execution.CriterionView.class)),
            Map.entry("CriterionInput", List.of(com.tcms.execution.CriterionInput.class)),
            Map.entry("CurrentUser", List.of(com.tcms.auth.CurrentUserService.CurrentUser.class)),
            Map.entry("DashboardBreakdown",
                    List.of(com.tcms.repository.DashboardView.BreakdownView.class)),
            Map.entry("DashboardTile", List.of(com.tcms.repository.DashboardView.TileView.class)),
            Map.entry("DefectLink", List.of(com.tcms.execution.DefectLinkView.class)),
            Map.entry("DefectLinkInput", List.of(com.tcms.execution.DefectLinkInput.class)),
            Map.entry("DocBlock", List.of(com.tcms.documentation.DocBlockView.class)),
            Map.entry("DocBlockInput", List.of(com.tcms.documentation.DocBlockInput.class)),
            // In `repository` and not in `documentation`, which is where the row lives: this is the
            // link seen from the CASE end, off the case_doc_link read model, and the module that
            // owns doc_link may not be read from a request path in the module that serves this
            // (§167). BlockLinkView is the other end and pairs with `BlockLink`.
            Map.entry("DocLink", List.of(com.tcms.repository.DocLinkView.class)),
            Map.entry("DocPage", List.of(com.tcms.documentation.DocPageView.class)),
            Map.entry("DocPageInput", List.of(com.tcms.documentation.DocPageInput.class)),
            Map.entry("DocPageStatusInput",
                    List.of(com.tcms.documentation.DocPageStatusInput.class)),
            Map.entry("DocPageSearchHit",
                    List.of(com.tcms.documentation.DocPageSearchHitView.class)),
            Map.entry("DocPageSummary", List.of(com.tcms.documentation.DocPageSummaryView.class)),
            Map.entry("EffectivePermissions",
                    List.of(com.tcms.auth.MeController.PermissionsResponse.class)),
            Map.entry("EntityVersion", List.of(com.tcms.repository.EntityVersionView.class)),
            Map.entry("Evidence", List.of(com.tcms.execution.EvidenceView.class)),
            Map.entry("ExecutionHistoryEntry",
                    List.of(com.tcms.execution.CaseHistoryView.EntryView.class)),
            Map.entry("ExecutionAssigneeInput",
                    List.of(com.tcms.execution.ExecutionAssigneeRequest.class)),
            // Three names that deliberately do not match their schemas, so the name-matching pass
            // would compare all three to nothing in either direction — the hole §170 closed and
            // §242 hit again with AuditEvent. ApiTokenInput → ApiTokenIssueRequest above is the
            // precedent for an explicit entry.
            Map.entry("ExecutionBulkOperation",
                    List.of(com.tcms.execution.ExecutionBulkAssignRequest.class)),
            Map.entry("ExecutionBulkPayload",
                    List.of(com.tcms.execution.ExecutionBulkAssignPayload.class)),
            Map.entry("ExecutionBulkResult",
                    List.of(com.tcms.execution.ExecutionBulkAssignResult.class)),
            Map.entry("ExecutionResultInput",
                    List.of(com.tcms.execution.ExecutionResultRequest.class)),
            // §169. The third record over doc_link, and the second write shape: BlockLink is the
            // row seen from the block end, DocLink from the case end, this one from the create
            // that made it. ExternalLinkView's header says why none of the three can be the other.
            Map.entry("ExternalLink", List.of(com.tcms.documentation.ExternalLinkView.class)),
            Map.entry("ExternalLinkInput",
                    List.of(com.tcms.documentation.ExternalLinkInput.class)),
            Map.entry("Facet", List.of(com.tcms.repository.FacetView.class)),
            // §255 T3. The name matches the record, so this entry only confirms what the name
            // match would propose — kept for the nested one below, which it cannot propose.
            // §255 T6 — the import's eight, all nested in the two top-level records the endpoint
            // binds and serves, which is why no name match proposes six of them.
            Map.entry("FeatureImportRequest",
                    List.of(com.tcms.repository.FeatureImportRequest.class)),
            Map.entry("FeatureFile",
                    List.of(com.tcms.repository.FeatureImportRequest.FeatureFile.class)),
            Map.entry("FeatureImportPlan",
                    List.of(com.tcms.repository.FeatureImportPlan.class)),
            Map.entry("ImportRow",
                    List.of(com.tcms.repository.FeatureImportPlan.ImportRow.class)),
            Map.entry("SuiteAction",
                    List.of(com.tcms.repository.FeatureImportPlan.SuiteAction.class)),
            Map.entry("ConditionAction",
                    List.of(com.tcms.repository.FeatureImportPlan.ConditionAction.class)),
            Map.entry("Orphan",
                    List.of(com.tcms.repository.FeatureImportPlan.Orphan.class)),
            Map.entry("ImportTotals",
                    List.of(com.tcms.repository.FeatureImportPlan.ImportTotals.class)),
            Map.entry("GherkinParseResult",
                    List.of(com.tcms.repository.GherkinParseResult.class)),
            // Nested in the result that carries it: the schema is named for the wire and the
            // record for the field it sits on, so no name match reaches it.
            Map.entry("ParsedBackground",
                    List.of(com.tcms.repository.GherkinParseResult.ParsedBackground.class)),
            Map.entry("HeldPermission", List.of(com.tcms.auth.MeController.HeldPermission.class)),
            // §255's import provenance. Named for the wire and the record for being a view, so
            // no name match proposes it.
            Map.entry("ImportedFrom", List.of(com.tcms.repository.ImportedFromView.class)),
            Map.entry("IngestionInput", List.of(com.tcms.execution.IngestionRequest.class)),
            Map.entry("IngestionJob", List.of(com.tcms.execution.IngestionJobView.class)),
            Map.entry("IngestionJobSummary", List.of(com.tcms.execution.IngestionJobSummary.class)),
            Map.entry("InvitationPreview",
                    List.of(com.tcms.identity.InvitationService.InvitationPreview.class)),
            Map.entry("IssuedApiToken", List.of(com.tcms.identity.IssuedApiTokenView.class)),
            // ONE record for two endpoints (§174): the block end binds it under doc:edit and the
            // case end under case:edit, because correcting a link's claim is one act and §173 is
            // the section that paid for letting one act acquire two spellings.
            Map.entry("LinkRelabelInput", List.of(com.tcms.documentation.LinkRelabelInput.class)),
            // The refusal's "who to ask" (renders audit 7.1). The item is inline in the contract
            // because it has exactly one field and naming it there would suggest it has a future;
            // what keeps it honest is that the record has exactly one field too, and this pairing
            // is what compares them.
            Map.entry("GranteeList", List.of(com.tcms.identity.GranteeListView.class)),
            Map.entry("OrgChart", List.of(com.tcms.identity.OrgChartView.class)),
            Map.entry("OrgChartGap", List.of(com.tcms.identity.OrgChartGapView.class)),
            Map.entry("OrgChartAssignment",
                    List.of(com.tcms.identity.OrgChartAssignmentView.class)),
            // Flat, like `User`/`UserView`: the schema is an `allOf` of `UserRef` and that
            // serializes flat, so a nested record would put `ref.email` on the wire.
            Map.entry("OrgChartPerson", List.of(com.tcms.identity.OrgChartPersonView.class)),
            Map.entry("OrgChartProject", List.of(com.tcms.identity.OrgChartProjectView.class)),
            Map.entry("PlanCaseRef", List.of(com.tcms.execution.PlanCaseRefView.class)),
            Map.entry("PlanDocumentLinkInput",
                    List.of(com.tcms.documentation.PlanDocumentLinkInput.class)),
            Map.entry("PlanSection", List.of(com.tcms.execution.PlanSectionView.class)),
            Map.entry("PlanSectionInput", List.of(com.tcms.execution.PlanSectionInput.class)),
            Map.entry("PlanStatistics", List.of(com.tcms.execution.PlanStatisticsView.class)),
            Map.entry("PlanArchiveInput", List.of(com.tcms.execution.PlanArchiveRequest.class)),
            Map.entry("PlanStatusInput", List.of(com.tcms.execution.PlanStatusRequest.class)),
            Map.entry("Project", List.of(com.tcms.project.ProjectView.class)),
            Map.entry("ProjectArchiveInput", List.of(com.tcms.project.ProjectArchiveRequest.class)),
            Map.entry("ProjectInput", List.of(com.tcms.project.ProjectWriteRequest.class)),
            // Seven required properties, six of them primitives — so the failure this catches on
            // ProjectSettings is not an absent key but a renamed one, which reads to a client as the
            // setting having reverted to its default. §6g's opt-in defaulting to "off" is the safe
            // direction; author-may-approve defaulting to "false" is not, since the property is
            // stored in the positive and an absent key would read as the rule being ON when the
            // project turned it off.
            Map.entry("ProjectSettings", List.of(com.tcms.project.ProjectSettingsView.class)),
            Map.entry("ReviewImpact", List.of(com.tcms.project.ReviewImpactView.class)),
            Map.entry("PasswordChangeInput",
                    List.of(com.tcms.auth.MeController.PasswordChangeRequest.class)),
            // The update body is the seven settings and nothing else — no `projectId`, no
            // `updatedAt`, no `updatedBy`, which is why it is a schema of its own rather than the
            // view reused. `ProjectSettingsRequest` and `project.ReviewSettings` are the same seven
            // components; only the first is the wire type, and the second is why the entry names a
            // class literal rather than letting a shape match decide.
            Map.entry("ProjectSettingsUpdate",
                    List.of(com.tcms.project.ProjectSettingsRequest.class)),
            // ---- 014's import block. Every one of them is a record in
            // com.tcms.tracker.imports, and three of the pairings are not name matches because
            // this codebase names a record for what it is (…View) where the contract names a
            // shape for the wire.
            Map.entry("ImportConnection",
                    List.of(com.tcms.tracker.imports.ImportConnection.class)),
            Map.entry("SourceProject",
                    List.of(com.tcms.tracker.imports.SourceProjectView.class)),
            Map.entry("SourceProjectList",
                    List.of(com.tcms.tracker.imports.SourceProjectListView.class)),
            Map.entry("ImportPreviewInput",
                    List.of(com.tcms.tracker.imports.ImportPreviewInput.class)),
            // ONE schema referenced by both ImportPreview and ImportRun, served by ONE record out
            // of ONE derivation (DroppedFields, D-f). Two schemas that happen to match is the
            // defect that decision exists to prevent, and the module's done-when — "the
            // dropped-field list in the report matches the preview's word for word" — starts here.
            Map.entry("DroppedField",
                    List.of(com.tcms.tracker.imports.DroppedFieldView.class)),
            Map.entry("PreviewTypeRow", List.of(com.tcms.tracker.imports.PreviewTypeRow.class)),
            Map.entry("PreviewStatusRow", List.of(com.tcms.tracker.imports.PreviewStatusRow.class)),
            Map.entry("ImportPreviewTotals",
                    List.of(com.tcms.tracker.imports.ImportPreviewTotalsView.class)),
            Map.entry("ImportBlocker", List.of(com.tcms.tracker.imports.ImportBlockerView.class)),
            Map.entry("ImportPreview", List.of(com.tcms.tracker.imports.ImportPreviewView.class)),
            // T4's seven. The two `…Entry`/`…Options`/`…Input` records are name matches; the four
            // `…View`s are not, for the reason the tracker block above gives.
            Map.entry("StatusMappingEntry",
                    List.of(com.tcms.tracker.imports.StatusMappingEntry.class)),
            Map.entry("ImportOptions", List.of(com.tcms.tracker.imports.ImportOptions.class)),
            Map.entry("ImportInput", List.of(com.tcms.tracker.imports.ImportInput.class)),
            Map.entry("AppliedStatusMapping",
                    List.of(com.tcms.tracker.imports.AppliedStatusMappingView.class)),
            Map.entry("ImportedPerson",
                    List.of(com.tcms.tracker.imports.ImportedPersonView.class)),
            // A SEPARATE record from ImportRunView, and the separation is deliberate: the list is
            // served for a whole project, and the detail carries three JSON documents whose size
            // is unbounded in the number of statuses and people. Thirty rows of a table would
            // otherwise be thirty dropped-field lists nobody asked for.
            Map.entry("ImportRunSummary",
                    List.of(com.tcms.tracker.imports.ImportRunSummaryView.class)),
            Map.entry("ImportRun", List.of(com.tcms.tracker.imports.ImportRunView.class)),
            Map.entry("ProposalRejection",
                    List.of(com.tcms.repository.ProposalRejectionRequest.class)),
            // Three of its seven are required-AND-nullable (§87), which is exactly the shape this
            // check is for: a poller cannot tell "the server did not send `error`" from "there is
            // no error", so the component has to exist even though the value is usually null.
            Map.entry("RaisedSignal", List.of(com.tcms.repository.RaisedSignalView.class)),
            Map.entry("ReportJob", List.of(com.tcms.reporting.ReportJobView.class)),
            Map.entry("RepositoryDashboard", List.of(com.tcms.repository.DashboardView.class)),
            Map.entry("ResultCounts", List.of(com.tcms.execution.ResultCountsView.class)),
            Map.entry("Review", List.of(com.tcms.project.ReviewView.class)),
            Map.entry("ReviewAssignee", List.of(com.tcms.project.ReviewAssigneeView.class)),
            Map.entry("ReviewAssignmentInput",
                    List.of(com.tcms.project.ReviewAssignmentRequest.class)),
            Map.entry("ReviewComment", List.of(com.tcms.project.ReviewCommentView.class)),
            Map.entry("ReviewCommentInput", List.of(com.tcms.project.ReviewCommentRequest.class)),
            Map.entry("ReviewCommentResolutionInput",
                    List.of(com.tcms.project.ReviewCommentResolutionRequest.class)),
            Map.entry("ReviewParticipation",
                    List.of(com.tcms.project.ReviewParticipationView.class)),
            Map.entry("ReviewParticipationInput",
                    List.of(com.tcms.project.ReviewParticipationRequest.class)),
            Map.entry("Role", List.of(com.tcms.identity.RoleView.class)),
            Map.entry("RoleAssignment", List.of(com.tcms.identity.RoleAssignmentView.class)),
            Map.entry("RoleAssignmentInput", List.of(com.tcms.identity.RoleGrantRequest.class)),
            Map.entry("RunStatusInput", List.of(com.tcms.execution.RunStatusRequest.class)),
            Map.entry("StepResult", List.of(com.tcms.execution.StepResultView.class)),
            Map.entry("StepSuggestion", List.of(com.tcms.repository.StepSuggestionView.class)),
            Map.entry("Suite", List.of(com.tcms.repository.SuiteView.class)),
            Map.entry("SuiteHistoryResponse", List.of(com.tcms.execution.SuiteHistoryView.class)),
            Map.entry("SuiteInput", List.of(com.tcms.repository.SuiteWriteRequest.class)),
            Map.entry("SuiteRunSummary",
                    List.of(com.tcms.execution.SuiteHistoryView.EntryView.class)),
            Map.entry("CaseStatusInput", List.of(com.tcms.repository.CaseStatusRequest.class)),
            Map.entry("SuiteStatusInput", List.of(com.tcms.repository.SuiteStatusRequest.class)),
            Map.entry("TagUsage", List.of(com.tcms.repository.TagUsageView.class)),
            Map.entry("Team", List.of(com.tcms.identity.TeamView.class)),
            Map.entry("TeamInput", List.of(com.tcms.identity.TeamInput.class)),
            Map.entry("TeamMemberInput", List.of(com.tcms.identity.TeamMemberInput.class)),
            // ~~One of three records carrying this name and the only one serving the schema: it is
            // reached from `ReviewAssignee.team`, the schema's single $ref. See HOMONYMS.~~
            // → §175: there is one record and the schema has three $refs. The other two positions
            // were inline objects served by their own records; both are gone, and so is the reason
            // HOMONYMS had two entries.
            Map.entry("TeamRef", List.of(com.tcms.api.TeamRef.class)),
            // The one pairing no name match proposes, and the reason the map is hand-written:
            // `TestCase` is an allOf of `TestCaseSummary`, and the record serving it is named for
            // being the DETAIL rather than for the schema.
            Map.entry("TestCase", List.of(com.tcms.repository.TestCaseDetailView.class)),
            Map.entry("TestCaseCloneInput", List.of(com.tcms.repository.CaseCloneRequest.class)),
            // The create body and the update body are one schema and one record, which is §144's
            // subject seen from the other end: `PATCH /cases/{caseId}` takes the whole case, so
            // there is nothing for a second shape to hold.
            Map.entry("TestCaseInput", List.of(com.tcms.repository.CaseUpdateRequest.class)),
            Map.entry("TestCaseSummary", List.of(com.tcms.repository.TestCaseSummaryView.class)),
            Map.entry("TestExecution", List.of(com.tcms.execution.TestExecutionView.class)),
            Map.entry("TestExecutionSummary",
                    List.of(com.tcms.execution.TestExecutionSummaryView.class)),
            Map.entry("TestPlan", List.of(com.tcms.execution.TestPlanView.class)),
            Map.entry("TestPlanCloneInput", List.of(com.tcms.execution.PlanCloneRequest.class)),
            Map.entry("TestPlanInput", List.of(com.tcms.execution.PlanWriteRequest.class)),
            Map.entry("TestPlanUpdateInput", List.of(com.tcms.execution.PlanUpdateRequest.class)),
            Map.entry("TestRun", List.of(com.tcms.execution.TestRunView.class)),
            Map.entry("TestRunCloneInput", List.of(com.tcms.execution.RunCloneRequest.class)),
            Map.entry("TestRunDetail", List.of(com.tcms.execution.TestRunDetailView.class)),
            Map.entry("TestRunInput", List.of(com.tcms.execution.RunWriteRequest.class)),
            Map.entry("TestStep", List.of(com.tcms.repository.TestStepView.class)),
            Map.entry("TestStepInput",
                    List.of(com.tcms.repository.CaseUpdateRequest.StepInput.class)),
            Map.entry("UnmatchedTest", List.of(com.tcms.execution.UnmatchedTestView.class)),
            Map.entry("User", List.of(com.tcms.identity.UserView.class)),
            Map.entry("UserInvitation", List.of(com.tcms.identity.UserInvitationView.class)),
            Map.entry("UserInvitationInput",
                    List.of(com.tcms.identity.UserInvitationRequest.class)),
            Map.entry("UserRef", List.of(com.tcms.api.UserRef.class)),
            Map.entry("VersionMarker",
                    List.of(com.tcms.execution.CaseHistoryView.MarkerView.class)),
            // ---- 011's tracker block ----
            //
            // THE THREE WorkItem ENTRIES ARE GONE, and their absence is the first half of this
            // block rather than housekeeping. T1 removed `WorkItem`, `WorkItemInput` and
            // `WorkItemStatusUpdate` from the contract — POST and PATCH /work-items are replaced by
            // POST /issues and POST /issues/{key}/transitions (D-a) — and left the pairings behind,
            // so `pairedSchemasAndRecordsNameTheSameMembers` threw `Dangling $ref` before it
            // compared anything at all. That is worse than a failure: the rule was not reporting a
            // divergence, it was not running. The three records survive until T4 retires `work`,
            // and they serve no schema in the meantime, which is exactly what no entry means.
            //
            // Three of the ten below are not name matches, for the reason the tracker block above
            // gives: the contract names a shape for what a client reads (`IssueTypeRef`,
            // `IssueStatusRef`, `IssueCompletion`) and this codebase names a record for what it is.
            Map.entry("Issue", List.of(com.tcms.tracker.IssueView.class)),
            Map.entry("IssueSummary", List.of(com.tcms.tracker.IssueSummaryView.class)),
            // T4's three. `IssueComment` is served by `CommentView` and NOT by the entity of the
            // same name — the wire shape and the row are different things here, and pairing a
            // schema to an entity is how a column becomes a field nobody meant to publish.
            Map.entry("IssueComment", List.of(com.tcms.tracker.CommentView.class)),
            Map.entry("IssueCommentInput", List.of(com.tcms.tracker.IssueCommentInput.class)),
            Map.entry("IssueDocLink", List.of(com.tcms.tracker.IssueDocLinkView.class)),
            Map.entry("IssueTypeRef", List.of(com.tcms.tracker.IssueTypeView.class)),
            // TWO owners since T4, and it is the modulith property again: `work` may not import
            // `tracker`, so a shape both put on the wire is declared in both. This rule comparing
            // each to the schema is what keeps them identical — a rename on one side is a failure
            // here rather than a divergence somebody finds from the client end.
            Map.entry("IssueStatusRef",
                    List.of(com.tcms.tracker.IssueStatusView.class,
                            com.tcms.work.BoardStatusRef.class)),
            Map.entry("IssueCompletion", List.of(com.tcms.tracker.CompletionView.class)),
            Map.entry("IssueInput", List.of(com.tcms.tracker.IssueInput.class)),
            Map.entry("IssueUpdate", List.of(com.tcms.tracker.IssueUpdate.class)),
            Map.entry("IssueTransitionInput",
                    List.of(com.tcms.tracker.IssueTransitionInput.class)),
            Map.entry("ProjectWorkflow", List.of(com.tcms.tracker.WorkflowView.class)),
            Map.entry("StatusRemovalInput",
                    List.of(com.tcms.tracker.StatusRemovalInput.class)),
            // ---- T4's board block ----
            //
            // BoardCard replaces the WorkItem entry the first bullet above records removing, and
            // CardAnnotation is a schema for a table V1 shipped a year before it had an endpoint:
            // derived_item_annotation had no door at all until D11 said it mattered more, not less.
            Map.entry("BoardCard", List.of(com.tcms.work.BoardCardView.class)),
            Map.entry("CardAnnotation", List.of(com.tcms.work.CardAnnotationView.class)),
            Map.entry("CardAnnotationInput", List.of(com.tcms.work.CardAnnotationInput.class)),
            // ---- 012 T3's board-configuration block ----
            //
            // `Board` and `BoardColumn` are served by VIEW RECORDS and never by the entities of the
            // same name, which is the rule the header states: the wire shape and the row are
            // different things, and pairing a schema to an entity is how a column becomes a field
            // nobody meant to publish. `board.lock_version` is the instance — it is on the row and
            // it is not on the wire.
            Map.entry("Board", List.of(com.tcms.tracker.board.BoardView.class)),
            Map.entry("BoardColumn", List.of(com.tcms.tracker.board.BoardColumnView.class)),
            Map.entry("BoardSummary", List.of(com.tcms.tracker.board.BoardSummaryView.class)),
            Map.entry("BoardInput", List.of(com.tcms.tracker.board.BoardInput.class)),
            Map.entry("BoardColumnInput",
                    List.of(com.tcms.tracker.board.BoardColumnInput.class)),
            // ---- 012 T4's cards, rank and labels block ----
            //
            // The same rule as T3's above, and `Label` is the entry that shows it doing work: the
            // ENTITY is `IssueLabel` and carries company_id, created_at and created_by, none of
            // which is on the wire. `LabelView` is what serves the schema, and pairing the entity
            // instead is how a created_by becomes a field nobody meant to publish.
            //
            // BoardIssueCard is deliberately NOT `BoardCard` — that schema is work's composite card
            // and carries four kinds, a `derived` flag and a synthetic id, none of which means
            // anything about an issue with a real key.
            Map.entry("BoardContents", List.of(com.tcms.tracker.board.BoardContentsView.class)),
            Map.entry("BoardLane", List.of(com.tcms.tracker.board.BoardLaneView.class)),
            Map.entry("WipBreach", List.of(com.tcms.tracker.board.WipBreachView.class)),
            Map.entry("BoardIssueCard", List.of(com.tcms.tracker.board.BoardIssueCardView.class)),
            Map.entry("CardMoveInput", List.of(com.tcms.tracker.board.CardMoveInput.class)),
            Map.entry("CardMoveResult", List.of(com.tcms.tracker.board.CardMoveResultView.class)),
            Map.entry("BacklogRow", List.of(com.tcms.tracker.board.BacklogRowView.class)),
            Map.entry("RankInput", List.of(com.tcms.tracker.board.RankInput.class)),
            Map.entry("RankResult", List.of(com.tcms.tracker.board.RankResultView.class)),
            Map.entry("Label", List.of(com.tcms.tracker.board.LabelView.class)),
            Map.entry("LabelInput", List.of(com.tcms.tracker.board.LabelInput.class)),
            Map.entry("IssueLabelsInput",
                    List.of(com.tcms.tracker.board.IssueLabelsInput.class)),
            // ---- 013 M3's release block ----
            //
            // Same rule again, and `Release` is where it earns its keep twice over: the ENTITY
            // carries company_id, sort_key, created_by, updated_by and lock_version, and NONE of
            // those is on the wire — sort_key least of all, because it is a derived collation key
            // that would read as a second version string to anybody who saw it.
            //
            // ReleaseRef is a THIRD shape of the same row, beside Release and ReleaseSummary, and
            // the three are not an accretion: a ref is what an issue carries, a summary is a list
            // row with its readiness inline, and the full shape is what a write answers with.
            // IssueSummary/Issue make exactly this split one table over.
            Map.entry("Release", List.of(com.tcms.tracker.release.ReleaseView.class)),
            Map.entry("ReleaseRef", List.of(com.tcms.tracker.release.ReleaseRefView.class)),
            Map.entry("ReleaseSummary",
                    List.of(com.tcms.tracker.release.ReleaseSummaryView.class)),
            Map.entry("ReadinessSummary",
                    List.of(com.tcms.tracker.release.ReadinessSummaryView.class)),
            Map.entry("ReleaseStories",
                    List.of(com.tcms.tracker.release.ReleaseStoriesView.class)),
            Map.entry("ReleaseTests", List.of(com.tcms.tracker.release.ReleaseTestsView.class)),
            Map.entry("ReleaseBlocker",
                    List.of(com.tcms.tracker.release.ReleaseBlockerView.class)),
            Map.entry("RoadmapEpic", List.of(com.tcms.tracker.release.RoadmapEpicView.class)),
            Map.entry("ReleaseReadiness", List.of(com.tcms.tracker.release.ReadinessView.class)),
            Map.entry("RoadmapRelease",
                    List.of(com.tcms.tracker.release.RoadmapReleaseView.class)),
            Map.entry("Roadmap", List.of(com.tcms.tracker.release.RoadmapView.class)),
            Map.entry("ReleaseInput", List.of(com.tcms.tracker.release.ReleaseInput.class)),
            Map.entry("ReleaseUpdate", List.of(com.tcms.tracker.release.ReleaseUpdate.class)),
            // IssueRef is deliberately NOT IssueSummary. That schema is a row in a list OF issues
            // and carries a status, an assignee, an epic and an estimate; this is an issue as it
            // appears on something that is not one — a test case's Verifies column — and widening
            // the first to serve the second would put six unrendered fields on every case row.
            Map.entry("IssueRef", List.of(com.tcms.tracker.release.IssueRefView.class)),
            Map.entry("IssueTestCase", List.of(com.tcms.tracker.release.IssueTestCaseView.class)),
            Map.entry("IssueTestCasesInput",
                    List.of(com.tcms.tracker.release.IssueTestCasesInput.class)),
            Map.entry("CaseVerification",
                    List.of(com.tcms.tracker.release.CaseVerificationView.class)),
            // ---- 016 M6's time block ----
            //
            // Two entity/view splits again, and both entities carry more than the wire does:
            // TimeEntry holds company_id, person_id, created_by/updated_by and lock_version, none
            // of which is on TimeEntryView, and TimesheetPeriod holds decided_by as an id where the
            // view carries decidedByName. `hours` is a NUMERIC(5,2) on one side and a double on the
            // other, which is the estimate/points split this repo already makes on Issue.
            //
            // There is no rollup ENTITY paired here at all: IssueTimeRollup is this module's own
            // projection and never leaves it — what the two rollup operations serve is assembled
            // from it plus the tracker's projection, which is why TimeRollup and EpicTimeRollup are
            // views with no table of their own behind them.
            Map.entry("TimeEntry", List.of(com.tcms.time.TimeEntryView.class)),
            Map.entry("TimeEntryInput", List.of(com.tcms.time.TimeEntryInput.class)),
            Map.entry("TimesheetPeriod", List.of(com.tcms.time.TimesheetPeriodView.class)),
            Map.entry("TimesheetDecisionInput",
                    List.of(com.tcms.time.TimesheetDecisionInput.class)),
            Map.entry("TimesheetQueueEntry",
                    List.of(com.tcms.time.TimesheetQueueEntryView.class)),
            Map.entry("TimeRollup", List.of(com.tcms.time.TimeRollupView.class)),
            Map.entry("EpicTimeRollup", List.of(com.tcms.time.EpicTimeRollupView.class)));

    /**
     * The owners this file cannot name as class literals, because they are package-private.
     *
     * <p><strong>The visibility was left alone on purpose.</strong> No record here argues for being
     * package-private — they look incidental beside their public siblings — but widening a
     * production type so a test can name it is a production change made for a test, and this slice
     * is about arguments that drift, not about tidying. The cost is stated rather than hidden: these
     * lose the compile-time half of the guarantee the map's class literals give. A rename still
     * fails, as a {@code ClassNotFoundException} from the resolver below rather than as a compiler
     * error, which is later and noisier and is the whole difference.
     *
     * <p>§170 added three, and the list growing from two to five is the reason to say what would
     * change the decision: at the point where the <em>majority</em> of a schema's owners are here,
     * the compile-time guarantee is the exception rather than the rule and the visibility question
     * is worth reopening as its own slice. ~~Five of 125 is not that point.~~ → <strong>ten of
     * 130 after §218</strong>, and the five it added arrived differently from the first five: the
     * whole {@code integration} module is package-private by design rather than incidentally, on
     * {@code ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction}'s terms. That is
     * an argument for the visibility, which none of the earlier five had — so the trigger above
     * stands as written, and doubling the list has not moved it closer.
     */
    private static final Map<String, List<String>> PACKAGE_PRIVATE_OWNERS = Map.ofEntries(
            Map.entry("BulkOperation", List.of("com.tcms.repository.BulkOperationRequest")),
            Map.entry("BulkPayload", List.of("com.tcms.repository.BulkPayloadInput")),
            Map.entry("BulkResult", List.of("com.tcms.repository.BulkResultView")),
            Map.entry("NotificationChannel",
                    List.of("com.tcms.integration.NotificationChannelView")),
            Map.entry("NotificationChannelInput",
                    List.of("com.tcms.integration.NotificationChannelInput")),
            Map.entry("NotificationDelivery",
                    List.of("com.tcms.integration.NotificationDeliveryView")),
            Map.entry("NotificationSubscription",
                    List.of("com.tcms.integration.NotificationSubscriptionView")),
            // Nested in the input that carries it, so the name match cannot propose it — the
            // schema is named for the wire and the record for the field it sits on.
            Map.entry("NotificationSubscriptionInput",
                    List.of("com.tcms.integration.NotificationChannelInput$SubscriptionInput")),
            // Package-private, like every view in `reporting` — named as a string for that reason
            // and not because the pairing is in doubt.
            Map.entry("ReportCapabilities", List.of("com.tcms.reporting.ReportCapabilitiesView")),
            // B7's provenance record — package-private in `execution`, like every view there.
            // It serves THREE positions since §247: TestRunDetail.scopeRequest, and both halves of
            // a run schedule (RunSchedule.scope and RunScheduleInput.scope). That is the whole
            // argument for V68 reusing V61's document rather than declaring a second shape — one
            // record, one writer (RunScopeDocument), one reader (its own parse).
            Map.entry("RunScopeRequest", List.of("com.tcms.execution.RunScopeRequestView")),
            // §247. Package-private in `execution`, like every view and request record there. The
            // name match cannot propose either: RunSchedule is also the ENTITY's name, and a
            // schema's pairing is looked for among records.
            Map.entry("RunSchedule", List.of("com.tcms.execution.RunScheduleView")),
            Map.entry("RunScheduleInput", List.of("com.tcms.execution.RunScheduleWriteRequest")),
            Map.entry("ReportDefinition", List.of("com.tcms.reporting.ReportDefinitionView")),
            // Package-private inside a package-private controller, so it is doubly unnameable.
            Map.entry("ReportDefinitionInput",
                    List.of("com.tcms.reporting.ReportDefinitionController$SaveRequest")));

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
     * Named object schemas that <strong>no single record serves</strong>, and why each is not a
     * hole. §170's map, and the smallest of the three the slice produced.
     *
     * <p>Both entries are the same shape: <strong>a schema reachable only as an {@code allOf}
     * branch</strong>. Nothing sends one on its own, so there is no record whose components are
     * <em>its</em> members — the composed shape is what a handler returns, and that is what
     * {@link #pairedSchemasAndRecordsNameTheSameMembers} compares, because {@code propertiesOf}
     * collects through {@code allOf}. Pairing a branch with the record serving the composition
     * would report every property of the <em>other</em> branches as SERVER-ONLY, which is a rule
     * generating its own false positives.
     *
     * <p><strong>An entry here is falsifiable and the build checks it.</strong> The moment a branch
     * is {@code $ref}'d as a whole body — a list endpoint answering a bare {@code PageMeta}, say —
     * a record starts serving it alone and the entry is wrong. {@code everyExemptionStillExempts}
     * below re-derives the branch-only property from the document rather than trusting the sentence,
     * for §161's reason: a one-directional exemption that nothing re-derives is re-affirmed by every
     * green run it outlives.
     */
    private static final Map<String, String> BRANCH_ONLY_SCHEMAS = Map.of(
            "PageMeta",
            "the three paging fields, $ref'd only as the first allOf branch of the four list "
                    + "responses that page (cases, ai-proposals, and both audit trails since "
                    + "§242). CasePageView, AiProposalPageView and AuditEventPageView each carry "
                    + "all three AND the branch beside them, so they serve the composition — which "
                    + "§170 phase 2 compares at the inline location",
            "TestPlanSummary",
            "$ref'd only as TestPlan's first allOf branch since §100, which widened the plan list "
                    + "from the summary to the full plan and left the schema in place as the half "
                    + "of TestPlan that PlanController's list and detail share. TestPlanView serves "
                    + "the composition and is paired against `TestPlan`");

    /**
     * Production records that carry a contract schema's name without serving it, and the sentence
     * that tells them apart — {@code ContractEnumTest.HOMONYMS} at the object shape.
     *
     * <p><strong>It is empty, and §175 emptied it.</strong> ~~Both entries are {@code TeamRef}, and
     * together they correct a standing claim: §147 named it as a consolidation candidate on the
     * grounds that "it is two copies". There are <strong>three</strong> types, they do not all serve
     * the same schema, and one of them is not the same shape — so the argument that moved
     * {@code UserRef} does not transfer unchanged.~~ → The two inline object shapes those records
     * served are {@code $ref}s to the {@code TeamRef} schema now, {@code isLead} is off the wire,
     * and the three records are {@link com.tcms.api.TeamRef}. There is no name collision left to
     * resolve, so there is no entry.
     *
     * <p>~~<strong>Empty is a result, not a dead map</strong>~~ → <strong>and 014 is the day it
     * predicted.</strong> The paragraph below said "the day a second record is named for a schema
     * it does not serve, the build fails with this map named in the message, which is the moment
     * the sentence telling them apart is cheap to write". That happened on the import module's
     * first gate, the message named this map, and the sentence took one reading of two files. It is
     * the cheapest evidence in this class that a map kept empty on purpose is not a dead one.
     *
     * <p>An entry means "this record does not serve the schema its <em>name</em> matches". It has
     * never meant "this record is compared to nothing" — {@link #INLINE_RECORDS} pairs anonymous
     * shapes by location since §170, which is what made the difference between the two TeamRef
     * shapes a checked fact rather than a sentence here, and therefore what made §175's collapse
     * an edit rather than an argument.
     */
    private static final Map<String, String> HOMONYMS = Map.of(
            "com.tcms.tracker.imports.SourceProject",
            "what a PROVIDER's API handed back, not what this contract serves. The schema's server "
                    + "type is SourceProjectView, and the two are separate for the reason that "
                    + "record's own header gives: this one answers to whatever Jira or Azure "
                    + "DevOps happened to return, that one answers to openapi.yaml, and "
                    + "collapsing them would make an adapter's convenience field a wire change. "
                    + "The three components are identical today, which is exactly when a "
                    + "collapse looks free",

            "com.tcms.tracker.agent.AgentStoryContextView.Criterion",
            "an ACCEPTANCE criterion — one line of a story's checklist, text and a tick. The "
                    + "Criterion schema is a test plan's EXIT criterion (metric, op, value), "
                    + "machine-evaluated so the plan page is a gate rather than prose. Two "
                    + "unrelated shapes that English spells the same; this one is paired by "
                    + "location as AgentStoryContext.acceptanceCriteria[] in INLINE_RECORDS, "
                    + "which is where it is actually compared to the contract");

    /**
     * Members one side names and the other deliberately does not, with the reason each stays.
     *
     * <p>Pinned in <strong>both</strong> directions, like {@code ContractEnumTest}'s
     * {@code DIVERGENCE_BY_DECISION} and {@code ArchitectureRulesTest}'s two maps: an entry here
     * whose divergence has been closed fails the build, so an argument cannot outlive its gap. That
     * property is the whole reason those maps did not rot while §162's one-directional exemptions
     * did.
     *
     * <p><strong>Every entry is contract-only, which is the safe direction</strong> — a declared
     * property nothing sends costs a dead branch in a client, where a component nothing declares is
     * a value no generated union can name.
     *
     * <p><strong>The four this map opened with are down to one, and the three that left are the
     * argument for how it is written.</strong> All four were blocked by one rule — {@code repository}
     * and {@code documentation} may not import {@code execution} — and <em>three of them said
     * something else until §165: that the module "does not exist"</em>. {@code execution} is 119
     * files and {@code documentation} is 57. That is §160's shape exactly ({@code rawStorageKey}
     * arguing "there is no adapter" for twenty-nine sections after {@code storage} built one) and it
     * cost the same thing: an argument that misnames the obstacle deters the repair, because "the
     * module does not exist" reads as <em>wait</em> and "the module boundary forbids the join" reads
     * as <em>design a read model</em>.
     *
     * <p>§166 took the rewritten argument and did what it named, for both {@code lastResult} keys;
     * §167 did the same for {@code relatedDocuments} one section later, through a table rather than
     * a column; <strong>§168 closed the fourth and last of them</strong>, {@code DocBlock.coverage},
     * through neither — no table and no column, because §166's mirror had <em>already</em> reduced
     * its two-module join to one and nobody had gone back to look.
     *
     * <p><strong>That is the finding this map was built to produce, and it is worth stating plainly:
     * three of the four were closed by re-reading the argument rather than by removing an
     * obstacle.</strong> The obstacle in each case had already moved — {@code storage} shipped,
     * {@code case_health} gained the column — and the entry went on asserting a constraint that had
     * expired, because nothing re-reads a justification the day the thing it names changes. An entry
     * pinned in both directions cannot outlive its gap, but it can outlive its <em>reason</em>, and
     * the reason is what a reader acts on. When adding an entry here, name the obstacle precisely
     * enough that a later slice can tell it has gone.
     *
     * <p>~~<strong>The six {@code DocLink.external*} entries are a different kind</strong>…~~ →
     * <strong>they are gone too, and this map is now EMPTY.</strong> They were not a module-boundary
     * gap: they were a capability {@code V1} designed and no slice had built, so the divergence
     * recorded a decision not to fabricate values. §169 built it — {@code ExternalLinkService} writes
     * both target kinds — and the entries had to be deleted for the build to pass, which is the
     * mechanism they were pinned in both directions for. <strong>Seven of the ten entries this map
     * has ever held were removed by somebody closing the gap rather than by editing the argument</strong>,
     * and the remaining three were §166's, §167's and §168's, which is the same thing.
     *
     * <p><strong>An empty map is a result and not a hole.</strong> Every named object schema and the
     * record serving it now agree member for member, in both directions. The next entry added here
     * inherits everything above: name the obstacle precisely enough that a later slice can tell it
     * has gone, and expect to delete the entry rather than to revise it.
     *
     * <p><strong>§170: it serves two rules now</strong> — the named one and
     * {@link #inlineShapesAndTheirRecordsNameTheSameMembers}. One map because an entry is an
     * argument about a member and that argument does not change with whether the shape around it
     * has a name; {@link #isInlineKey} decides which rule owns a key. It is still empty, across
     * both populations and 144 pairings, which is a larger claim than the one §169 left.
     */
    private static final Map<String, String> MEMBER_DIVERGENCE_BY_DECISION = Map.of();

    // WHAT WAS HERE, ALL OF IT DELETED RATHER THAN EDITED, in the order it left:
    //   TestCaseSummary.lastResult, TestCase.lastResult   §166 — served through case_health
    //   TestCase.relatedDocuments                         §167 — served through case_doc_link
    //   DocBlock.coverage                                 §168 — served with no new storage at all
    //   DocLink.external{Repo,Path,LineStart,LineEnd,     §169 — the capability got a writer
    //                    CommitSha,Url}
    // Deleting the entry IS the build passing again; that is the property this map exists for, and
    // it has now fired four times. Do not reinstate one to "keep the history" — the history is here
    // and in the sections, and an entry in the map is a live claim about the code.
    //
    // §170 added 45 named pairings and 21 inline ones and produced ONE divergence between them,
    // `IngestionInput.branch` / `.commitSha`, which was fixed rather than recorded — so the map is
    // still empty. That is worth reading the right way round: it is not evidence that the
    // population was safe, it is the count of what was left after §164–§169 spent six slices
    // closing the same defect class where the rule could see it.

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
     * <strong>Every</strong> named object schema is paired with the record serving it, or carries
     * a reason it has none.
     *
     * <p>{@link #everyNameMatchedRecordIsPairedOrExplained} above asks a narrower question than its
     * name suggests, and §170 is the section that noticed. It reports a schema only when
     * {@code candidatesFor} finds a record called {@code Foo} or {@code FooView} — so the
     * population it can speak about is <em>schemas whose server type happens to share their
     * name</em>, and every schema outside that set was invisible to it and to the member
     * comparison below. That was <strong>45 of 129</strong>, a third of the contract's object
     * shapes, and the pattern in the misses is not random: it is almost exactly the write side.
     * A request body's schema is named for the wire ({@code TestCaseInput}, {@code SuiteInput},
     * {@code TestRunInput}) and its record is named for the act ({@code CaseUpdateRequest},
     * {@code SuiteWriteRequest}, {@code RunWriteRequest}), so <em>the naming convention this
     * codebase uses on purpose is what hid them</em> — and the write side is where an undeclared
     * component is not merely unnameable but silently accepted.
     *
     * <p>This rule asks the whole question: for each object schema, either a pairing exists, or the
     * schema is in {@link #PAIRED_ELSEWHERE} or {@link #BRANCH_ONLY_SCHEMAS} with the reason.
     * There is no "no record serves this" escape beyond those two, deliberately — every remaining
     * object schema in this contract is something a handler returns or a handler binds, and if one
     * ever genuinely is not, that is a schema nothing implements and the finding is the schema.
     */
    @Test
    @DisplayName("every object schema is paired with a record, or says why it has none")
    void everyObjectSchemaIsPairedOrExempt() {
        Set<String> accounted = new TreeSet<>(allPairings().keySet());
        accounted.addAll(PAIRED_ELSEWHERE.keySet());
        accounted.addAll(BRANCH_ONLY_SCHEMAS.keySet());

        List<String> unaccounted = contract.objectSchemas().stream()
                .filter(schema -> !accounted.contains(schema))
                .toList();

        assertThat(unaccounted)
                .as("""
                        An object schema is paired with nothing and says nothing about why.

                        A schema with no pairing is compared to no server type in either direction,
                        so a property the record never sends and a component the schema never
                        declares are both invisible — the two defect classes the rule below exists
                        for, on a schema it cannot see.

                        Do not reach for BRANCH_ONLY_SCHEMAS to make this pass. It means "nothing
                        sends this shape whole", the build re-derives that from the document, and
                        the honest answer for a request body is the record the handler binds — which
                        is named for the act and not for the schema, which is why nothing proposed
                        it.

                        Schemas accounted for by nothing:""")
                .isEmpty();

        assertThat(accounted)
                .as("an entry names a schema the contract does not declare as an object shape")
                .isSubsetOf(contract.objectSchemas());

        // The three ways of accounting for a schema are alternatives, and nothing above says so:
        // `accounted` is a union, so a schema claimed twice passes this rule while the two claims
        // contradict each other. Found by mutation `§170 PR-2` — pairing `PageMeta` with
        // CasePageView left its BRANCH_ONLY_SCHEMAS entry (which says nothing serves it whole)
        // standing, and only the member comparison reddened. This is the arm that names the
        // contradiction rather than one of its consequences.
        List<String> claimedTwice = contract.objectSchemas().stream()
                .filter(schema -> (allPairings().containsKey(schema) ? 1 : 0)
                        + (PAIRED_ELSEWHERE.containsKey(schema) ? 1 : 0)
                        + (BRANCH_ONLY_SCHEMAS.containsKey(schema) ? 1 : 0) > 1)
                .toList();

        assertThat(claimedTwice)
                .as("a schema is both paired and excused. Whatever the second claim says, one of "
                        + "the two is false: a record either serves this shape or nothing does")
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
            compared += compareMembers(pairing.getKey(), declared, pairing.getValue(), divergences);
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
                .containsExactlyInAnyOrderElementsOf(namedDivergencesAllowed());
    }

    /**
     * The same comparison at an <em>inline</em> shape — §170 phase 2, and the last position in this
     * document that no rule looked at.
     *
     * <p>Separate from the rule above only because the population is keyed differently: a location
     * rather than a schema name. The comparison itself is {@link #compareMembers}, called by both,
     * because two implementations of "do these two name the same members" is how the two answers
     * come to differ — {@code CLAUDE.md}'s shared-layer rule, at a test.
     *
     * <p><strong>An inline divergence is worse than a named one, not better.</strong> A named
     * schema becomes a named generated type a consumer imports, so a wrong member is at least
     * <em>nameable</em>. An inline object becomes a structural type written out at the property, so
     * the only way to notice is to read the generated file at that property — and the four
     * {@code lastResult} / {@code coverage} / {@code relatedDocuments} defects §166–§168 spent
     * three slices on were all at inline positions.
     */
    @Test
    @DisplayName("an inline shape and the record serving it name the same members, both ways")
    void inlineShapesAndTheirRecordsNameTheSameMembers() {
        Map<String, String> divergences = new TreeMap<>();
        int compared = 0;

        for (Map.Entry<String, Set<String>> shape : contract.inlineObjectSchemas().entrySet()) {
            List<Class<?>> owners = inlinePairings().get(shape.getKey());
            if (owners == null) {
                continue;
            }
            compared += compareMembers(shape.getKey(), shape.getValue(), owners, divergences);
        }

        assertThat(compared)
                .as("no inline pairing was compared, so this rule passes vacuously")
                .isGreaterThan(15);

        assertThat(divergences.keySet())
                .as("""
                        An inline shape and the record serving it disagree about their members.

                        Read `pairedSchemasAndRecordsNameTheSameMembers` above for what the two
                        directions cost. The one difference here: the generated type is structural
                        and inlined at the property, so nothing a consumer imports carries the name
                        of what is wrong.

                        Edit both sides, or add the member to MEMBER_DIVERGENCE_BY_DECISION keyed by
                        the location this rule reports.

                        Divergences:""")
                .containsExactlyInAnyOrderElementsOf(inlineDivergencesAllowed());
    }

    /**
     * Every inline location paired with a record, or exempt with the reason — {@code
     * RESPONSE_RECORDS} for the anonymous half.
     *
     * <p>Enforced by {@link #everyInlineShapeIsPairedOrExempt} below, which is the half that makes
     * the pairing rule a <em>population</em> rule rather than a spot check: adding an inline object
     * to the contract fails the build until somebody says which record serves it.
     */
    private static final Map<String, List<Class<?>>> INLINE_RECORDS = Map.ofEntries(
            Map.entry("AiProposal.acceptedCase",
                    List.of(com.tcms.repository.AiProposalView.AcceptedCaseView.class)),
            // 015. A THIRD record for the acceptance-criteria shape, beside Issue's and
            // IssueUpdate's: this one is what an assistant reads, so it carries no id (there is
            // nothing here it could tick) and no position (the list arrives in order).
            Map.entry("AgentStoryContext.acceptanceCriteria[]",
                    List.of(com.tcms.tracker.agent.AgentStoryContextView.Criterion.class)),
            // `CurrentUser.teams[]` and `RoleAssignment.team` were both here until §175, each with
            // its own record, and between them they were the whole of the TeamRef question §147
            // opened. They are `$ref`s to the TeamRef schema now, served by com.tcms.api.TeamRef —
            // so they belong to RESPONSE_RECORDS' entry for that schema and are absent here, which
            // is what everyInlineShapeIsPairedOrExempt asserts by deriving the population.
            Map.entry("DashboardBreakdown.segments[]",
                    List.of(com.tcms.repository.DashboardView.SegmentView.class)),
            Map.entry("DocBlock.coverage",
                    List.of(com.tcms.documentation.BlockCoverageView.class)),
            Map.entry("ExecutionResultInput.stepResults[]",
                    List.of(com.tcms.execution.ExecutionResultRequest.StepResultRequest.class)),
            Map.entry("ExecutionBulkResult.changed[]",
                    List.of(com.tcms.execution.ExecutionBulkAssignResult.AssignmentChange.class)),
            Map.entry("ExecutionBulkResult.skipped[]",
                    List.of(com.tcms.execution.ExecutionBulkAssignResult.SkippedRow.class)),
            Map.entry("Facet.values[]", List.of(com.tcms.repository.FacetView.Value.class)),
            // 011 T3. The checklist is JSONB on `issue` rather than a table, because nothing joins
            // to a criterion — so its shape exists only here and at the two records below, and this
            // pairing is the only thing comparing them.
            Map.entry("Issue.acceptanceCriteria[]",
                    List.of(com.tcms.tracker.AcceptanceCriterion.class)),
            // The UPDATE half carries no id: the list is written whole by one form in one request,
            // so a stable id would be a promise the wire cannot keep. That is why it is a second
            // record rather than the one above, and why the two are compared separately.
            Map.entry("IssueUpdate.acceptanceCriteria[]",
                    List.of(com.tcms.tracker.IssueUpdate.CriterionUpdate.class)),
            Map.entry("ProjectWorkflow.transitions[]",
                    List.of(com.tcms.tracker.WorkflowView.TransitionView.class)),
            // 011 T4. A one-field body, and it is inline rather than a named schema because there
            // is nothing else in the product it could be shared with: it says which of the star's
            // two states the caller wants.
            Map.entry("PUT /issues/{key}/watchers (request)",
                    List.of(com.tcms.tracker.IssueWatchInput.class)),
            // 011 T4, D-c. A card's status is an object for EVERY card kind — an issue's status is
            // a per-project row, so a fixed five-member enum could not spell it and a card carrying
            // both spellings would be the two-contradictory-numbers failure this repo has shipped
            // once. Inline rather than a named schema because M2 replaces it with real columns.
            Map.entry("BoardCard.status", List.of(com.tcms.work.CardStatus.class)),
            Map.entry("GranteeList.grantees[]", List.of(com.tcms.identity.GranteeView.class)),
            Map.entry("PlanStatistics.byConfiguration[]",
                    List.of(com.tcms.execution.PlanStatisticsView.ConfigurationCountsView.class)),
            Map.entry("PlanStatistics.bySuite[]",
                    List.of(com.tcms.execution.PlanStatisticsView.SuiteCountsView.class)),
            Map.entry("PlanStatistics.trend[]",
                    List.of(com.tcms.execution.PlanStatisticsView.TrendPointView.class)),
            Map.entry("RepositoryDashboard.automationCoverage",
                    List.of(com.tcms.repository.DashboardView.AutomationCoverageView.class)),
            Map.entry("RepositoryDashboard.recentActivity[]",
                    List.of(com.tcms.repository.DashboardView.ActivityPointView.class)),
            Map.entry("ReviewImpact.plansBlockedFromRunning[]",
                    List.of(com.tcms.project.ReviewImpactSource.BlockedPlan.class)),
            // 004 D19. Nested in the record that carries it, and it is the whole of what the
            // endpoint may say about a person's relationship to one host: a provider, a base URL
            // and a state. Anything else added here is a widening of a privacy decision, not a
            // field.
            Map.entry("TrackerConnectedMember.hosts[]",
                    List.of(com.tcms.integration.tracker.TrackerConnectedMemberView.HostState.class)),
            Map.entry("Team.projects[]", List.of(com.tcms.identity.TeamView.ProjectRole.class)),
            // 016 M6's three, and T1 declared `required` on each of them for the reason this map
            // exists: `required` does not descend, so a nested object without one generates every
            // property optional in both clients and the whole shape goes soft with nothing failing.
            //
            // TimeEntry.subject is denormalised from the tracker's projection rather than $ref'd to
            // IssueRef or IssueSummary: it carries the project's NAME, which neither of those does,
            // because a timesheet row is read across projects and "ACME-124" alone does not say
            // whose fortnight this is.
            Map.entry("TimeEntry.subject", List.of(com.tcms.time.TimeEntryView.SubjectView.class)),
            Map.entry("EpicTimeRollup.children[]",
                    List.of(com.tcms.time.EpicTimeRollupView.ChildView.class)),
            Map.entry("TimesheetQueueEntry.projectTotals[]",
                    List.of(com.tcms.time.TimesheetQueueEntryView.ProjectTotalView.class)),
            Map.entry("TestCaseSummary.lastResult",
                    List.of(com.tcms.repository.LastResultView.class)),
            Map.entry("POST /auth/login (request)",
                    List.of(com.tcms.auth.AuthController.LoginRequest.class)),
            Map.entry("POST /auth/invitations/{token}/accept (request)",
                    List.of(com.tcms.auth.InvitationController.AcceptRequest.class)),
            Map.entry("POST /auth/providers (request)",
                    List.of(com.tcms.auth.SsoController.ProviderDiscoveryRequest.class)),
            Map.entry("POST /auth/ldap/{providerId}/login (request)",
                    List.of(com.tcms.auth.LdapController.DirectoryLoginRequest.class)),
            Map.entry("GET /projects/{projectId}/cases (200)",
                    List.of(com.tcms.repository.CasePageView.class)),
            // §249's cross-project search. A separate record from CasePageView rather than a reuse:
            // its items are CaseSearchHit, which is a waypoint and deliberately not
            // TestCaseSummary, so the two envelopes agree on the paging fields and on nothing else.
            Map.entry("GET /cases/search (200)",
                    List.of(com.tcms.repository.CaseSearchPageView.class)),
            Map.entry("GET /projects/{projectId}/ai-proposals (200)",
                    List.of(com.tcms.repository.AiProposalPageView.class)),
            // Both audit trails, one record — they differ in their gate and in nothing else, so a
            // second record would be a shape nothing could tell apart from the first and a second
            // place the paging fields can be renamed by half.
            Map.entry("GET /projects/{projectId}/audit-events (200)",
                    List.of(com.tcms.identity.AuditEventPageView.class)),
            Map.entry("GET /audit-events (200)",
                    List.of(com.tcms.identity.AuditEventPageView.class)),
            Map.entry("GET /projects/{projectId}/cases/{caseId}/history (200)",
                    List.of(com.tcms.execution.CaseHistoryView.class)));

    /** {@link #PACKAGE_PRIVATE_OWNERS} for the inline half — same reason, same cost. */
    private static final Map<String, List<String>> PACKAGE_PRIVATE_INLINE_OWNERS = Map.of(
            "BulkResult.skipped[]", List.of("com.tcms.repository.BulkResultView$SkippedCase"),
            "POST /projects/{projectId}/reports/generate (request)",
            List.of("com.tcms.reporting.ReportController$GenerateRequest"));

    /**
     * Inline shapes <strong>no record serves</strong>, because nothing deserializes them into one.
     *
     * <p>All four are {@code multipart/form-data}, and that is the whole class: Spring binds a
     * multipart part to a {@code MultipartFile} parameter and the text parts to
     * {@code @RequestParam}s, so the "shape" exists only as a handler's parameter list. Declaring a
     * record for it would be a type invented to satisfy this rule.
     *
     * <p>~~<strong>The check these forgo is a real one and it is worth naming rather than eliding:
     * nothing compares a form's declared parts to the parameters the handler binds.</strong> A part
     * renamed on one side is a 400 at runtime and nothing before it — the shape
     * {@code ContractParameterBindingTest} already fixes for query parameters, one media type over.
     * That test is where it would go, and it is a slice rather than a line, which is why this is a
     * recorded gap and not a silent one.~~ → <strong>closed 2026-08-12 (§171), in the class that
     * paragraph named.</strong>
     *
     * <p>{@code ContractParameterBindingTest} now asks these four forms the three questions the
     * named lane asks of a record: every declared part is bound, no bound part is undeclared, and
     * the two sides agree on {@code required}. So an entry here still excuses a <em>pairing</em> —
     * there is genuinely no record — and it no longer excuses the parts from being compared. Which
     * is the whole difference between an exemption and a hole.
     *
     * <p>~~{@code BOUND_AS_REQUEST_PARAMETERS}~~: the old name was wrong about all four of them.
     * Every {@code file} part is a {@code @RequestPart}, as is {@code artifact}; only
     * {@code caption} and {@code stepId} are {@code @RequestParam}s, and writing the rule made the
     * mechanism worth spelling correctly, because the rule has to read both annotations and a
     * reader of this map would have looked for one.
     */
    private static final Map<String, String> BOUND_AS_FORM_PARTS = Map.of(
            "POST /projects/{projectId}/cases/{caseId}/attachments (request)",
            "multipart — CaseAttachmentController binds @RequestPart MultipartFile file",
            "POST /projects/{projectId}/doc-pages/{pageId}/attachments (request)",
            "multipart — DocPageAttachmentController binds @RequestPart MultipartFile file",
            "POST /projects/{projectId}/runs/{runId}/executions/{executionId}/evidence (request)",
            "multipart — EvidenceController binds @RequestPart MultipartFile file plus "
                    + "@RequestParam caption and stepId",
            "POST /projects/{projectId}/artifacts/files (request)",
            "multipart — ArtifactFileController binds @RequestPart MultipartFile file and "
                    + "@RequestPart ArtifactFileInput artifact");

    @Test
    @DisplayName("every inline shape is paired with a record, or says why it has none")
    void everyInlineShapeIsPairedOrExempt() {
        Set<String> locations = contract.inlineObjectSchemas().keySet();

        assertThat(locations)
                .as("no inline shape was found at all — the traversal has stopped seeing them, and "
                        + "both rules over this population would pass over an empty set")
                .hasSizeGreaterThan(20);

        Set<String> accounted = new TreeSet<>(inlinePairings().keySet());
        accounted.addAll(BOUND_AS_FORM_PARTS.keySet());

        assertThat(locations.stream().filter(at -> !accounted.contains(at)).toList())
                .as("""
                        An inline object shape is paired with nothing.

                        Somebody added an anonymous object to the contract and no rule knows what
                        serves it. Name the record — a nested one is normal here, most of this map
                        is nested — or, for a multipart form, add it to BOUND_AS_FORM_PARTS.

                        Inline shapes accounted for by nothing:""")
                .isEmpty();

        assertThat(accounted)
                .as("an entry names an inline location the contract no longer has — the property "
                        + "was renamed, given a named schema, or deleted, and the pairing outlived "
                        + "it")
                .isSubsetOf(locations);
    }

    /** {@link #allPairings()} for the inline half. */
    private static Map<String, List<Class<?>>> inlinePairings() {
        Map<String, List<Class<?>>> pairings = new TreeMap<>(INLINE_RECORDS);
        PACKAGE_PRIVATE_INLINE_OWNERS.forEach((at, owners) -> pairings.put(at, owners.stream()
                .map(ContractConventionTest::resolve)
                .collect(Collectors.toList())));
        return pairings;
    }

    /**
     * One member comparison, both directions, for one pairing — returns how many records it
     * compared, so a caller can prove it did not pass vacuously.
     */
    private static int compareMembers(
            String subject,
            Set<String> declared,
            List<Class<?>> owners,
            Map<String, String> divergences) {
        for (Class<?> record : owners) {
            Set<String> components = Stream.of(record.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            for (String property : declared) {
                if (!components.contains(property)) {
                    divergences.put(subject + "." + property,
                            "CONTRACT-ONLY — declared, and " + record.getSimpleName()
                                    + " has no component to send it");
                }
            }
            for (String component : components) {
                if (!declared.contains(component)) {
                    divergences.put(subject + "." + component,
                            "SERVER-ONLY — " + record.getSimpleName() + " sends it and the "
                                    + "contract declares it nowhere");
                }
            }
        }
        return owners.size();
    }

    /** The allowed divergences at a named schema — every key whose subject is not a location. */
    private static Set<String> namedDivergencesAllowed() {
        return MEMBER_DIVERGENCE_BY_DECISION.keySet().stream()
                .filter(key -> !isInlineKey(key))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> inlineDivergencesAllowed() {
        return MEMBER_DIVERGENCE_BY_DECISION.keySet().stream()
                .filter(ContractConventionTest::isInlineKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Whether a {@link #MEMBER_DIVERGENCE_BY_DECISION} key belongs to the inline rule.
     *
     * <p>One map serves both rules because an entry is an argument about a member and the argument
     * does not change with where the shape is declared. The two rules then have to split it, and
     * they split on the shape of the key rather than on a second map: an inline key is a location,
     * so it carries a {@code /} (an operation) or a {@code []} (an array item) or a second dot (a
     * property of a named schema, plus the member) — and a named key is exactly
     * {@code Schema.member}.
     */
    private static boolean isInlineKey(String key) {
        return key.contains("/") || key.contains("[]") || key.chars().filter(c -> c == '.').count() > 1;
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
