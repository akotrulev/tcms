package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule deciding which shared-step ids a case may reference — {@link CaseEdit#validate}'s
 * {@code acceptableSharedStepIds}.
 *
 * <p>A plain unit test, with no Spring context and no database, because the rule is a pure function
 * of its inputs and that is the whole reason it takes the set rather than looking it up. The
 * <em>wiring</em> — that the two write paths resolve the right set — is asserted over HTTP in
 * {@link SharedStepEndpointTest}; this class asserts what the rule says once the set has arrived.
 *
 * <h2>What was wrong before this rule existed</h2>
 *
 * <p>{@code sharedStepId} was accepted and persisted unchecked from M1 until this slice, while the
 * neighbouring {@code suiteId} and step {@code id} were both validated against a set and each
 * carried a paragraph explaining why. Three failures came in through that gap, and the first two
 * are the reason the check exists:
 *
 * <ol>
 *   <li><strong>A sibling project's entry.</strong> {@code test_step.shared_step_id} references
 *       {@code shared_step(id)} with no project predicate, so the foreign key is satisfied and
 *       nothing fails. Every later read of the case then resolves a library entry through a project
 *       the reader holds no grant on.</li>
 *   <li><strong>An id naming nothing.</strong> Fails the foreign key, so the endpoint answers 500
 *       where a field error belongs — a validation failure disguised as an outage.</li>
 *   <li><strong>A retired entry the case already references.</strong> This one is why the rule is a
 *       union and not "live in this project". Retiring is a soft delete precisely because
 *       references survive it, so the naive check would make every case using a retired entry
 *       unsaveable — a title fix on an untouched step refused over a reference the author never
 *       made and cannot see. Strictly worse than the defect being closed, and only avoided by
 *       carrying the stored half.</li>
 * </ol>
 */
@DisplayName("A step's shared-step reference — live in this project, or already on this case")
class CaseSharedStepReferenceTest {

    private static final UUID SUITE = UUID.fromString("ab000000-0000-4000-8000-000000000001");
    private static final UUID IN_LIBRARY = UUID.fromString("ab000000-0000-4000-8000-000000000002");
    private static final UUID RETIRED = UUID.fromString("ab000000-0000-4000-8000-000000000003");
    private static final UUID SIBLING_PROJECT =
            UUID.fromString("ab000000-0000-4000-8000-000000000004");
    private static final UUID NOTHING = UUID.fromString("ab000000-0000-4000-8000-000000000005");
    private static final UUID EXISTING_STEP =
            UUID.fromString("ab000000-0000-4000-8000-000000000006");

    @Nested
    @DisplayName("The library half — an id the caller is introducing")
    class LibraryHalf {

        @Test
        @DisplayName("a live entry of this project is accepted, and survives into the edit")
        void aLiveEntryIsAccepted() {
            CaseEdit.Outcome outcome = validate(newStep("Do the thing", IN_LIBRARY), Set.of(IN_LIBRARY));

            assertThat(outcome).isInstanceOf(CaseEdit.Outcome.Valid.class);
            // Asserted through to the normalized edit rather than stopping at "not invalid": a
            // rule that accepts the id and then drops it on the way through would pass the first
            // assertion and store a step with no reference at all.
            assertThat(((CaseEdit.Outcome.Valid) outcome).edit().steps())
                    .singleElement()
                    .extracting(CaseEdit.StepEdit::sharedStepId)
                    .isEqualTo(IN_LIBRARY);
        }

        @Test
        @DisplayName("a sibling project's entry is refused — the case the foreign key cannot catch")
        void aSiblingProjectsEntryIsRefused() {
            // THE assertion of this class. The FK is satisfied by this id, so before the check
            // nothing failed: the save succeeded and the cross-project reference was stored.
            assertThat(refusals(validate(newStep("Do the thing", SIBLING_PROJECT), Set.of(IN_LIBRARY))))
                    .containsKey("steps");
        }

        @Test
        @DisplayName("an id naming nothing is a field error, not the 500 the foreign key would raise")
        void anUnknownIdIsAFieldError() {
            assertThat(refusals(validate(newStep("Do the thing", NOTHING), Set.of(IN_LIBRARY))))
                    .containsKey("steps");
        }

        @Test
        @DisplayName("the message names the step's position, so the form can land on it")
        void theMessageNamesTheStep() {
            // Second of two steps, so a message hard-coded to "step 1" fails here. The position is
            // the only thing that distinguishes one row of a step list from another on screen.
            CaseEdit.Outcome outcome = validate(
                    List.of(newStep("Open the app", null).getFirst(),
                            newStep("Sign in", SIBLING_PROJECT).getFirst()),
                    Set.of(IN_LIBRARY));

            assertThat(refusals(outcome).get("steps"))
                    .singleElement()
                    .asString()
                    .contains("Step 2")
                    .contains("library");
        }

        @Test
        @DisplayName("a refused step is not ALSO reported as 'add at least one step'")
        void theRefusalDoesNotContradictItself() {
            // Found by driving the real server. A rejected step is dropped from the normalized
            // list, so the structured-format emptiness check used to fire on the emptied list and
            // tell the author to add a step they had just added — directly under the sentence
            // explaining why the one they added was refused. Two messages on one field that argue
            // with each other, and the wrong one is the actionable-looking one.
            assertThat(refusals(validate(newStep("Do the thing", SIBLING_PROJECT), Set.of(IN_LIBRARY)))
                    .get("steps"))
                    .singleElement()
                    .asString()
                    .contains("library");
        }

        @Test
        @DisplayName("but a payload that really sent no steps still says so")
        void anEmptyStructuredCaseIsStillRefused() {
            // The other half, and the reason the fix is "nothing was SENT" rather than deleting
            // the check: a structured case with no steps at all has to be refused, and that
            // refusal has no per-step message to carry it.
            assertThat(refusals(validate(List.of(), Set.of())).get("steps"))
                    .singleElement()
                    .asString()
                    .contains("at least one step");
        }

        @Test
        @DisplayName("a blank action is refused without the contradictory second message too")
        void theSameFixCoversTheOlderRefusals() {
            // This pair predates the shared-step check — a blank action has always been dropped
            // the same way — so the fix is swept across the class rather than applied to the one
            // refusal that exposed it.
            assertThat(refusals(validate(newStep("   ", null), Set.of())).get("steps"))
                    .singleElement()
                    .asString()
                    .contains("Describe the action");
        }

        @Test
        @DisplayName("a null sharedStepId is the ordinary case and is never checked")
        void anUnreferencedStepIsFine() {
            // Most steps reference nothing. If the check treated null as "not in the set" every
            // case in the product would become unsaveable, which is the loudest possible failure
            // and therefore not the one worth guarding — but the empty acceptable set is what a
            // caller passes when the payload references nothing, so this pins that pairing.
            assertThat(validate(newStep("Do the thing", null), Set.of())).isInstanceOf(CaseEdit.Outcome.Valid.class);
        }
    }

    @Nested
    @DisplayName("The stored half — an id this case already carries")
    class StoredHalf {

        @Test
        @DisplayName("a RETIRED entry the case already references is still accepted")
        void aRetiredEntryAlreadyReferencedIsAccepted() {
            // The union's reason, stated as a test. RETIRED is deliberately absent from the live
            // library and present in the accepted set because the caller unioned in the stored
            // references — which is exactly what CaseUpdateService does.
            CaseEdit.Outcome outcome =
                    validate(keptStep(EXISTING_STEP, RETIRED), Set.of(IN_LIBRARY, RETIRED));

            assertThat(outcome)
                    .as("retiring one library entry must not make every case using it unsaveable")
                    .isInstanceOf(CaseEdit.Outcome.Valid.class);
        }

        @Test
        @DisplayName("without the stored half the same save is refused — the union is load-bearing")
        void theSameSaveFailsWithoutTheStoredHalf() {
            // The mutation guard for the test above. If `acceptableSharedStepIds` were only ever
            // the live library, the previous test would still pass whenever the fixture happened
            // to leave the entry live — so this drives the same input with the stored half removed
            // and requires it to fail. The two together pin the union rather than either half.
            assertThat(refusals(validate(keptStep(EXISTING_STEP, RETIRED), Set.of(IN_LIBRARY))))
                    .containsKey("steps");
        }

        @Test
        @DisplayName("the stored half does not license a NEW step to reference a retired entry")
        void theConcessionIsToReferencesAlreadyMade() {
            // The concession is to references already made, not to making new ones — but the rule
            // is expressed as a set, so it cannot tell a new step from an old one carrying the
            // same id. Recorded as the deliberate edge it is: a caller who copies a retired id
            // from one of the case's own steps onto a new step of the SAME case succeeds. It
            // widens nothing — the reference already exists on that case and is already rendered
            // — and refusing it would need per-step provenance the payload does not carry.
            CaseEdit.Outcome outcome =
                    validate(newStep("Do the thing", RETIRED), Set.of(IN_LIBRARY, RETIRED));

            assertThat(outcome)
                    .as("documented consequence of a set-shaped rule, not an oversight")
                    .isInstanceOf(CaseEdit.Outcome.Valid.class);
        }
    }

    @Nested
    @DisplayName("Reading the payload's references, which is what the caller looks up")
    class Referenced {

        @Test
        @DisplayName("only the non-null ids, de-duplicated")
        void collectsTheDistinctReferences() {
            assertThat(CaseEdit.referencedSharedStepIds(List.of(
                    newStep("a", IN_LIBRARY).getFirst(),
                    newStep("b", null).getFirst(),
                    newStep("c", IN_LIBRARY).getFirst())))
                    .containsExactly(IN_LIBRARY);
        }

        @Test
        @DisplayName("no steps and no references both answer an empty set, so no query is made")
        void nothingToLookUp() {
            // The empty answer is what keeps an empty IN () from ever being bound — the failure
            // SharedStepRepository#countUsage documents. It is a syntax error, not an empty result.
            assertThat(CaseEdit.referencedSharedStepIds(null)).isEmpty();
            assertThat(CaseEdit.referencedSharedStepIds(List.of())).isEmpty();
            assertThat(CaseEdit.referencedSharedStepIds(newStep("Do the thing", null))).isEmpty();
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static CaseEdit.Outcome validate(
            List<CaseUpdateRequest.StepInput> steps, Set<UUID> acceptableSharedStepIds) {

        return CaseEdit.validate(
                request(steps),
                Set.of(SUITE),
                Set.of(EXISTING_STEP),
                acceptableSharedStepIds,
                // This class is about step references. The request below sends no classification,
                // so an empty vocabulary is never consulted and cannot colour a refusal under test.
                ClassificationVocabulary.none(),
                Set.of(),
                // This class is about step references too — the request sends no conditions, so an
                // empty acceptable set and two empty stored lists are never consulted either.
                Set.of(),
                List.of(),
                List.of(),
                key -> false);
    }

    /** A minimal valid case, so any refusal reported is the one under test. */
    private static CaseUpdateRequest request(List<CaseUpdateRequest.StepInput> steps) {
        return new CaseUpdateRequest(
                "A case",
                SUITE,
                null,
                null,
                null,
                StepFormat.STRUCTURED,
                null,
                Priority.MEDIUM,
                null,
                AutomationStatus.MANUAL,
                null,
                null,
                null,
                steps,
                // classificationValueIds — null means "leave it alone", which for a request this
                // class never applies is the value that makes the field invisible to it.
                null,
                null,
                null, null, null, null, null);
    }

    /** A step being KEPT — it carries the id of a step already on the case. */
    private static List<CaseUpdateRequest.StepInput> keptStep(UUID stepId, UUID sharedStepId) {
        return List.of(new CaseUpdateRequest.StepInput(
                stepId, null, "Do the thing", null, null, sharedStepId));
    }

    /** A step being ADDED — no id, so nothing on the case claims it yet. */
    private static List<CaseUpdateRequest.StepInput> newStep(String action, UUID sharedStepId) {
        return List.of(new CaseUpdateRequest.StepInput(
                null, null, action, null, null, sharedStepId));
    }

    private static Map<String, List<String>> refusals(CaseEdit.Outcome outcome) {
        assertThat(outcome)
                .as("expected a refusal, got %s", outcome)
                .isInstanceOf(CaseEdit.Outcome.Invalid.class);
        return ((CaseEdit.Outcome.Invalid) outcome).errors();
    }
}
