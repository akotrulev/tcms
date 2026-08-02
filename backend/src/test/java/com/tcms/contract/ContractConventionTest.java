package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            // Genuinely exclusive: a case condition is EITHER a library reference
            // (`conditionId`) OR an inline body, never both and never neither. There is no
            // property common to both shapes, so any `required` array here would be a lie
            // about one of them. The exclusivity itself is enforced server-side and covered
            // by CaseWriteEndpointTest; expressing it as `oneOf` was rejected because
            // openapi-typescript renders that as a union the SPA's form state cannot hold.
            ".components.schemas.CaseConditionInput",

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

    // ------------------------------------------------ required vs the record ----

    /**
     * The response records this check pins, and the schema each one serves.
     *
     * <p>Deliberately a short, explicit list rather than a scan of every {@code *View} in the
     * codebase: the pairing between a record and a schema is a fact only a human knows, and a
     * check that guessed it would fail on the first view whose name does not match its schema.
     * What is <em>computed</em> is the part reading cannot do — the required set, resolved
     * through {@code allOf}, compared against the record's actual components.
     */
    private static final Map<String, Class<?>> RESPONSE_RECORDS = Map.of(
            "TestCaseSummary", com.tcms.repository.TestCaseSummaryView.class,
            "TestCase", com.tcms.repository.TestCaseDetailView.class,
            // Seven required properties, six of them primitives — so the failure this catches on
            // ProjectSettings is not an absent key but a renamed one, which reads to a client as the
            // setting having reverted to its default. §6g's opt-in defaulting to "off" is the safe
            // direction; author-may-approve defaulting to "false" is not, since the property is
            // stored in the positive and an absent key would read as the rule being ON when the
            // project turned it off.
            "ProjectSettings", com.tcms.project.ProjectSettingsView.class);

    @Test
    @DisplayName("a response record has a component for every property its schema requires")
    void requiredPropertiesHaveAComponent() {
        List<String> offenders = new ArrayList<>();

        RESPONSE_RECORDS.forEach((schema, record) -> {
            Set<String> components = new java.util.LinkedHashSet<>();
            for (var component : record.getRecordComponents()) {
                components.add(component.getName());
            }
            for (String required : contract.requiredOf("#/components/schemas/" + schema)) {
                if (!components.contains(required)) {
                    offenders.add(schema + "." + required
                            + "  is required by the contract and absent from "
                            + record.getSimpleName());
                }
            }
        });

        assertThat(offenders)
                .as("""
                        A record that omits a required property serves a response the contract says
                        cannot happen — Jackson emits no key at all, and every consumer reads the
                        absence as whatever its language's "missing" is.

                        This check exists because that shipped. §66 added `createdBy` to
                        TestCaseSummary — which `TestCase` is an `allOf` of — and TestCaseDetailView
                        never grew the component, so `GET /cases/{caseId}` omitted the key for two
                        slices. The case detail is the only screen with a delete affordance, its gate
                        is `case:delete` qualified OWN, and the SPA reads `data.createdBy?.id ?? null`
                        — an absent key became `null`, null is a SUPPLIED author matching nobody, and
                        a Test Engineer was refused their own case. The exact inverse of the feature.

                        Nothing could see it. `tsc` allows optional chaining on a required field, the
                        page's own test supplies a fixture that has the key, and the detail endpoint's
                        field test enumerated the required list BY HAND — written before `createdBy`
                        existed and never revisited. That hand-written list is what this replaces.

                        Missing components:""")
                .isEmpty();
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
