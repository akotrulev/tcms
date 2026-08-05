package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized edit to one case — everything {@link TestCase#applyEdit} needs and nothing
 * it has to check.
 *
 * <h2>Validation lives here, once, at the edge</h2>
 *
 * <p>Same shape as {@link ConditionQuery} and for the same reason: the request is normalized in one
 * place before it reaches anything that acts on it, so no service, entity or query re-derives
 * whether the input was acceptable. A rule enforced half here and half in the entity is a rule with
 * two answers, and the one that diverges is whichever was edited second.
 *
 * <h2>Every rule here is also in the SPA, deliberately</h2>
 *
 * <p>The editor's Zod schema enforces the same four cross-field rules, and that is duplication with
 * a purpose rather than an accident: the client's copy exists so the message lands on the field that
 * caused it without a round trip, and this copy exists because <strong>the client is not a security
 * boundary</strong> and {@code curl} does not run Zod. Two of these rules are additionally in the
 * database ({@code ck_not_automatable_reason}, the {@code step_format} check), which is the layer
 * that holds when both of the others are wrong.
 *
 * <p>The messages are the SPA's messages, word for word. A server message that says the same thing
 * differently makes the same failure read as two different problems depending on which layer caught
 * it first.
 */
record CaseEdit(
        String title,
        UUID suiteId,
        String description,
        String objective,
        String expectedOutcome,
        StepFormat stepFormat,
        String gherkinSource,
        Priority priority,
        Priority severity,
        AutomationStatus automationStatus,
        String automationKey,
        String notAutomatableReason,
        Integer estimatedDurationSec,
        List<StepEdit> steps,
        // Sorted and de-duplicated by #validate, and that is a HASH requirement rather than tidiness.
        // CaseVersions#hash digests this record's JSON, and its javadoc rests the whole determinism
        // argument on there being no map in the shape and every collection preserving order — which
        // holds for `steps`, where order IS the content, and would have been false the moment a SET
        // arrived in a list. Two saves choosing the same three values in a different order are the
        // same classification, so they must hash the same or the version list fills with entries
        // whose diff is empty. Canonicalising here means the ordering question is answered once,
        // before anything can observe it.
        List<UUID> classificationValueIds,
        // The case's OWN attachments, in the author's order — position IS the content, so these are
        // lists and never sets, and they are deliberately not sorted the way classification is.
        //
        // Being on this record means they are part of CaseVersions#hash, which is correct: a
        // condition is authored content and V1's test_case_version snapshots it. It also has a
        // ONE-TIME consequence worth stating rather than discovering — every stored hash was
        // computed without these fields, so the first save of each existing case after §95 writes a
        // version whose diff looks empty. That is the price of conditions joining the pinned
        // content, it happens once per case, and it is the opposite of the recurring empty-diff
        // defect the comment above warns about.
        List<UUID> preconditionIds,
        List<UUID> postconditionIds,
        String changeComment) {

    /** The longest title the SPA's editor accepts. The column itself is unbounded {@code TEXT}. */
    private static final int MAX_TITLE = 300;

    /** One step, normalized. {@code position} is assigned by payload order, never sent. */
    record StepEdit(
            UUID id,
            StepKeyword keyword,
            String action,
            String testData,
            String expectedResult,
            UUID sharedStepId) {
    }

    /**
     * The outcome of validating a request — the edit, or the reasons it was refused.
     *
     * <p>Sealed so the controller's mapping is exhaustive, like {@link com.tcms.project.GateResult}.
     */
    sealed interface Outcome {

        /** Field name (as spelled on {@code TestCaseInput}) to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(CaseEdit edit) implements Outcome {
        }
    }

    /**
     * Validates and normalizes a submitted update against the case it targets.
     *
     * @param liveSuiteIds the project's live suite ids — passed in rather than looked up here so
     *                     this stays a pure function of its inputs and the caller keeps its one
     *                     suite query. A suite id outside this set is rejected: an unchecked
     *                     {@code suiteId} moves the case into another <em>project's</em> folder (or
     *                     a deleted one), which no read then filters it out of, so the case simply
     *                     disappears from both projects' trees.
     * @param existingStepIds the case's current step ids. An id outside this set is rejected rather
     *                        than inserted — it names a step belonging to another case, and honoring
     *                        it would move that step's identity, and therefore its recorded results,
     *                        onto this one.
     * @param acceptableSharedStepIds the shared-step ids a step on this case may reference:
     *                        <strong>this project's live library entries, unioned with the ids this
     *                        case's stored steps already reference.</strong> Resolved by the caller,
     *                        which is the only party holding both halves — {@link
     *                        SharedStepService#liveLibraryIds} answers the first and the stored
     *                        steps answer the second.
     *                        <p>The library half is the check that was missing entirely.
     *                        {@code test_step.shared_step_id} carries a foreign key, so an id
     *                        naming nothing fails at the constraint and answers 500 where a field
     *                        error belongs — but an id naming a <em>sibling project's</em> entry
     *                        satisfies the key and is stored, and every later read of the case then
     *                        resolves a library entry through a project the reader was never
     *                        granted. The same argument {@code suiteId} above makes, on a column
     *                        nobody made it for.
     *                        <p>The stored half is why this is not simply "live in this project".
     *                        Retiring a library entry is a soft delete <em>because references to it
     *                        survive</em> ({@link SharedStep#getDeletedAt}), so without it, the day
     *                        an entry is retired every case using it becomes unsaveable — a title
     *                        fix on an untouched step would be refused over a reference the author
     *                        never made and cannot see. That is a worse defect than the one the
     *                        check closes, and it is only avoided by resolving the union.
     * @param vocabulary the classification this project offers — the same list the editor was
     *                   served, so a value the client could pick is a value this accepts. Absence
     *                   from it is refused for the three reasons {@link ClassificationVocabulary}
     *                   sets out, of which the sibling-project one is the reason this parameter
     *                   exists at all: that id satisfies the foreign key, the company check and the
     *                   V2 trigger, so nothing below this line would have caught it.
     *                   <p><strong>A null {@code classificationValueIds} on the request means
     *                   "leave it alone" and is not validated against this</strong> — see
     *                   {@link CaseUpdateRequest} — so the bulk path may pass
     *                   {@link ClassificationVocabulary#none()} rather than resolving a vocabulary
     *                   it has no field to check against.
     * @param storedClassificationValueIds what the case is classified as <em>now</em>, restricted
     *                   to values the project still offers — exactly the set {@code GET} serves.
     *                   Used only when the request omits the field, and then it becomes the edit's
     *                   value verbatim, <strong>unvalidated</strong>: it describes what is already
     *                   stored rather than something a caller asked for, and re-litigating it here
     *                   would let a vocabulary change refuse a save that does not touch
     *                   classification. §64's rule about stored references, arrived at from the
     *                   other direction.
     *                   <p>It is not merely a default. {@link CaseVersions#hash} digests this
     *                   record, so an edit that left classification alone while reporting an empty
     *                   list would hash as though the caller had cleared it — and the next save
     *                   that <em>did</em> send the unchanged classification would then look like a
     *                   change and write a version whose diff is empty.
     * @param automationKeyTaken tests whether a non-null automation key is already used by a
     *                           <em>different</em> case in this company. {@code automation_key} is
     *                           unique per company via a partial index that deliberately does
     *                           <strong>not</strong> exclude soft-deleted rows, so a retired case
     *                           keeps its CI identifier reserved — which is correct (results still
     *                           arrive for it) and surprising, hence a checked message instead of
     *                           the 500 a constraint violation would produce.
     */
    static Outcome validate(
            CaseUpdateRequest request,
            Set<UUID> liveSuiteIds,
            Set<UUID> existingStepIds,
            Set<UUID> acceptableSharedStepIds,
            ClassificationVocabulary vocabulary,
            Set<UUID> storedClassificationValueIds,
            Set<UUID> acceptableConditionIds,
            List<UUID> storedPreconditionIds,
            List<UUID> storedPostconditionIds,
            java.util.function.Predicate<String> automationKeyTaken) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        refuseUnsupported(request, errors);

        List<UUID> classification = normalizeClassification(
                request.classificationValueIds(), vocabulary, storedClassificationValueIds, errors);

        List<UUID> preconditions = normalizeConditions(
                request.preconditions(), "preconditions", acceptableConditionIds,
                storedPreconditionIds, errors);
        List<UUID> postconditions = normalizeConditions(
                request.postconditions(), "postconditions", acceptableConditionIds,
                storedPostconditionIds, errors);

        String title = trimToNull(request.title());
        if (title == null) {
            errors.computeIfAbsent("title", k -> new ArrayList<>()).add("Enter a title.");
        } else if (title.length() > MAX_TITLE) {
            errors.computeIfAbsent("title", k -> new ArrayList<>())
                    .add("Keep the title under " + MAX_TITLE + " characters.");
        }

        if (request.suiteId() == null) {
            errors.computeIfAbsent("suiteId", k -> new ArrayList<>()).add("Choose a suite.");
        } else if (!liveSuiteIds.contains(request.suiteId())) {
            // Not 404: the case and project resolved fine, and the caller submitted a value this
            // project does not offer. Naming it as a field error puts the message on the picker.
            errors.computeIfAbsent("suiteId", k -> new ArrayList<>())
                    .add("That suite is not in this project.");
        }

        StepFormat format = request.stepFormat();
        if (format == null) {
            errors.computeIfAbsent("stepFormat", k -> new ArrayList<>()).add("Choose a step format.");
        }

        String gherkin = trimToNull(request.gherkinSource());
        List<StepEdit> steps =
                normalizeSteps(request.steps(), existingStepIds, acceptableSharedStepIds, errors);

        if (format == StepFormat.GHERKIN && gherkin == null) {
            errors.computeIfAbsent("gherkinSource", k -> new ArrayList<>())
                    .add("Write the scenario, or switch to structured steps.");
        }
        // "Nothing was SENT", not "nothing survived validation" — and the difference is a message
        // that contradicts the one beside it. `normalizeSteps` drops a step it has already
        // complained about, so a payload whose only step was rejected arrives here with an empty
        // list, and the reader is told to add a step they demonstrably just added, underneath the
        // sentence explaining why the one they added was refused. Found by driving a refused
        // sharedStepId against the real server; the same pair was already reachable through a
        // blank action and an unknown step id, so this predates the reference check and is fixed
        // for all three.
        boolean noStepsSubmitted = request.steps() == null || request.steps().isEmpty();
        if (format == StepFormat.STRUCTURED && noStepsSubmitted) {
            errors.computeIfAbsent("steps", k -> new ArrayList<>())
                    .add("Add at least one step, or switch to Gherkin.");
        }

        if (request.priority() == null) {
            errors.computeIfAbsent("priority", k -> new ArrayList<>()).add("Choose a priority.");
        }

        AutomationStatus automation = request.automationStatus();
        String automationKey = trimToNull(request.automationKey());
        String notAutomatable = trimToNull(request.notAutomatableReason());

        if (automation == null) {
            errors.computeIfAbsent("automationStatus", k -> new ArrayList<>())
                    .add("Choose an automation status.");
        }
        if (automation == AutomationStatus.NOT_AUTOMATABLE && notAutomatable == null) {
            errors.computeIfAbsent("notAutomatableReason", k -> new ArrayList<>())
                    .add("Say why this case cannot be automated.");
        }
        if (automation == AutomationStatus.AUTOMATED && automationKey == null) {
            errors.computeIfAbsent("automationKey", k -> new ArrayList<>())
                    .add("Give the automated test that covers this case, so results can be "
                            + "matched to it.");
        }
        if (automationKey != null && automationKeyTaken.test(automationKey)) {
            errors.computeIfAbsent("automationKey", k -> new ArrayList<>())
                    .add("Another case in this company already reports under that key.");
        }

        Integer duration = request.estimatedDurationSec();
        if (duration != null && duration < 0) {
            errors.computeIfAbsent("estimatedDurationSec", k -> new ArrayList<>())
                    .add("Enter a whole number of seconds.");
        }

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        return new Outcome.Valid(new CaseEdit(
                title,
                request.suiteId(),
                trimToNull(request.description()),
                trimToNull(request.objective()),
                trimToNull(request.expectedOutcome()),
                format,
                // Only the chosen format's content is kept, matching what the SPA sends and for the
                // reason its comment gives: a case holding both leaves the next reader unable to
                // tell which one is authoritative.
                format == StepFormat.GHERKIN ? gherkin : null,
                request.priority(),
                request.severity(),
                automation,
                // Cleared unless the status is one that means something reports here — otherwise a
                // case switched to MANUAL keeps reserving a CI identifier no runner will ever use,
                // and the company-unique index refuses it to the case that needs it.
                automation == AutomationStatus.AUTOMATED ? automationKey : null,
                automation == AutomationStatus.NOT_AUTOMATABLE ? notAutomatable : null,
                duration,
                format == StepFormat.GHERKIN ? List.of() : steps,
                classification,
                preconditions,
                postconditions,
                trimToNull(request.changeComment())));
    }

    /**
     * The authored content a <em>stored</em> case currently has, as an edit.
     *
     * <h2>Why this exists, and why it goes back through {@link #validate}</h2>
     *
     * <p>{@link CaseBulkService} changes {@code suiteId} and {@code automationStatus}, both of which
     * {@link CaseVersions#hash} digests. Writing a version row for those changes needs a
     * {@code CaseEdit} describing the case after the change — but the caller never sent one, because
     * a bulk request carries one field, not a whole case.
     *
     * <p>The tempting shortcut is to build the record directly from the row's getters. That is a
     * <strong>second normalizer</strong>, and the failure is silent and total: if it trims where
     * {@link #validate} does not, or keeps a Gherkin source where {@code validate} drops it, then
     * every hash it produces disagrees with every hash the edit path produces, and the next save of
     * every touched case writes a spurious version whose diff is empty. Round-tripping through
     * {@code validate} means there is exactly one normalizer and the two paths cannot disagree by
     * construction.
     *
     * <p>{@code changeComment} is deliberately null. It is hashed, so putting "changed in a bulk
     * edit" here would make the next ordinary save — which sends no comment — differ by the comment
     * alone and write a version with an empty diff, which is precisely the noise plan §6j added the
     * hash to keep out. What happened in bulk is recorded in the audit event, which is where "who
     * did what to how many" belongs.
     *
     * @param storedClassificationValueIds the case's live classification, which the reconstruction
     *                                     carries so the hash describes the whole case. A bulk
     *                                     action changes none of it; leaving it out would make
     *                                     every bulk-written version hash as though the case had
     *                                     been un-classified, and the next ordinary save would then
     *                                     read as a change that restored it.
     * @return empty when the stored row does not validate — a case with no steps in structured
     *         format, or one whose suite has since been deleted. Rare and real, so the caller skips
     *         that case with a reason rather than this throwing and failing the whole operation.
     *         Applying the change without a version row is not an option: it is exactly the silent
     *         mis-attribution this method exists to prevent.
     */
    static Optional<CaseEdit> of(
            TestCase row,
            List<TestStep> steps,
            Set<UUID> liveSuiteIds,
            Set<UUID> storedClassificationValueIds,
            List<UUID> storedPreconditionIds,
            List<UUID> storedPostconditionIds,
            java.util.function.Predicate<String> automationKeyTaken) {

        CaseUpdateRequest asRequest = new CaseUpdateRequest(
                row.getTitle(),
                row.getSuiteId(),
                row.getDescription(),
                row.getObjective(),
                row.getExpectedOutcome(),
                row.getStepFormat(),
                row.getGherkinSource(),
                row.getPriority(),
                row.getSeverity(),
                row.getAutomationStatus(),
                row.getAutomationKey(),
                row.getNotAutomatableReason(),
                row.getEstimatedDurationSec(),
                steps.stream()
                        .map(step -> new CaseUpdateRequest.StepInput(
                                step.getId(),
                                // The contract spells a keyword as a nullable string while the
                                // column is an enum, so the reconstruction spells it back — name(),
                                // which is exactly what the parser upper-cases and looks up.
                                step.getKeyword() == null ? null : step.getKeyword().name(),
                                step.getAction(),
                                step.getTestData(),
                                step.getExpectedResult(),
                                step.getSharedStepId()))
                        .toList(),
                // Null means "leave the classification alone", which is exactly what a bulk action
                // does — none of the eight touches it. The case's stored values are supplied
                // separately, below, so the reconstructed edit still HASHES as the whole case;
                // passing them here instead would re-validate a stored classification against a
                // live vocabulary, and a case carrying a since-retired value would fail to
                // reconstruct and be skipped by a MOVE_TO_SUITE that has nothing to do with it.
                // §64 made the identical argument for a retired shared step.
                null,
                // See the header. Null, not a description of the bulk edit.
                null,
                // The refused three stay null: they are refused on the way IN, and this is a
                // reconstruction of what is already stored, so passing any of them would make a
                // stored row fail validation for a rule about requests.
                null, null, null,
                // The two condition lists are null for the SAME reason classification is above —
                // "leave them alone", which is exactly what all eight bulk actions do. The stored
                // attachments are supplied separately, below, so the reconstructed edit still
                // HASHES as the whole case. Passing them here instead would re-validate a stored
                // attachment against the live library, and a case attaching a since-retired
                // condition would fail to reconstruct and be skipped by a MOVE_TO_SUITE that has
                // nothing to do with it. §64's argument, and §68's, for the third time.
                null, null);

        Set<UUID> existingStepIds = new HashSet<>();
        Set<UUID> storedSharedStepIds = new HashSet<>();
        for (TestStep step : steps) {
            existingStepIds.add(step.getId());
            if (step.getSharedStepId() != null) {
                storedSharedStepIds.add(step.getSharedStepId());
            }
        }

        // The stored references, and deliberately NOT the project's live library. This method
        // reconstructs what is already on the row; it is not an authoring act, so there is nothing
        // here for a library check to protect. Resolving the live set instead would mean a bulk
        // MOVE_TO_SUITE over a case referencing a RETIRED entry failed to reconstruct, and the
        // case would be skipped as "cannot validate its own content" — a case the caller can see,
        // refused for a reason it did not cause, by a check added for a different path entirely.
        // It also keeps this method free of a query, which is what lets it stay a pure function.
        Outcome outcome = validate(
                asRequest,
                liveSuiteIds,
                existingStepIds,
                storedSharedStepIds,
                // Never consulted: the request's classification is null above, so nothing is
                // checked against a vocabulary. Passing `none()` rather than resolving one is what
                // keeps this a pure function — the caller would otherwise need a project read to
                // reconstruct a field the bulk actions do not change.
                ClassificationVocabulary.none(),
                storedClassificationValueIds,
                // Never consulted either, for the same reason: both condition lists are null on the
                // request above, so nothing is checked against the library. An empty acceptable set
                // is therefore correct AND keeps this a pure function with no query in it.
                Set.of(),
                storedPreconditionIds,
                storedPostconditionIds,
                automationKeyTaken);
        return outcome instanceof Outcome.Valid valid
                ? Optional.of(valid.edit())
                : Optional.empty();
    }

    /**
     * The submitted classification, checked against what the project offers and canonicalized.
     *
     * <p>Returns the sorted, de-duplicated set the edit carries. A refusal still returns a list —
     * the caller collects every field's errors before deciding, so returning early here would make
     * a bad classification hide a bad title.
     *
     * <h2>The two rules, and why the second is a message rather than a constraint</h2>
     *
     * <p>A value must be one this project offers. A value must not be the second one on a
     * SINGLE-cardinality axis — Test Type and Test Level, per plan §6d. The database enforces the
     * second too, in {@code V2}'s {@code case_classification_integrity} trigger, and that is the
     * backstop rather than the check: the trigger raises {@code 23505}, which reaches the client as
     * a 500 on a form that has a perfectly good field to put a sentence on. The layered pair is the
     * same arrangement {@code ck_not_automatable_reason} already has, and the trigger stays because
     * this method is not the only way a row could ever be written.
     *
     * <p>Duplicates are de-duplicated rather than refused. The set is the target state, and asking
     * for the same value twice is not a contradiction — the primary key {@code (case_id, value_id)}
     * already says a case carries a value once or not at all, so refusing would be this layer
     * inventing a rule the schema does not have. A second <em>different</em> value on a SINGLE axis
     * is the opposite: two contradictory instructions with no way to choose between them, which is
     * why that one is refused instead of, say, keeping the first.
     */
    private static List<UUID> normalizeClassification(
            List<UUID> submitted,
            ClassificationVocabulary vocabulary,
            Set<UUID> stored,
            Map<String, List<String>> errors) {

        if (submitted == null) {
            // "Leave it alone" — see CaseUpdateRequest. Sorted for the same hash reason as below;
            // the input is a Set, so it has no order of its own to preserve.
            return stored.stream().sorted().toList();
        }

        Set<UUID> distinct = new LinkedHashSet<>();
        Set<UUID> singleAxesTaken = new HashSet<>();
        for (UUID valueId : submitted) {
            if (valueId == null || !distinct.add(valueId)) {
                continue;
            }
            UUID dimensionId = vocabulary.dimensionOf(valueId);
            if (dimensionId == null) {
                // Deliberately one message for three causes — retired, another project's, or
                // nothing at all. See ClassificationVocabulary: distinguishing them would confirm
                // the existence of a value the caller may not use.
                errors.computeIfAbsent("classificationValueIds", k -> new ArrayList<>())
                        .add("That value is not one this project offers.");
                continue;
            }
            if (vocabulary.allowsOnlyOneValue(dimensionId) && !singleAxesTaken.add(dimensionId)) {
                errors.computeIfAbsent("classificationValueIds", k -> new ArrayList<>())
                        .add("Choose one " + vocabulary.nameOf(dimensionId) + ", not several.");
            }
        }

        // Sorted, so the same three values chosen in two different orders are one classification
        // and hash identically — see the field's comment on the record above.
        return distinct.stream().sorted().toList();
    }

    /**
     * Normalizes one of the two condition lists.
     *
     * <h2>Absent means "leave it alone", and that is not the same as empty</h2>
     *
     * <p>{@link #normalizeClassification}'s rule, and it has to be: a bulk action reconstructs a
     * request without touching conditions, so a null must resolve to what is already stored rather
     * than to "detach everything". An empty list <em>is</em> "detach everything", which is a thing
     * an author can legitimately ask for by removing the last row.
     *
     * <p>The stored value is taken verbatim and <strong>unvalidated</strong> when the field is
     * absent, for {@link #validate}'s {@code storedClassificationValueIds} reason: it describes what
     * is already on the row rather than something a caller asked for, so re-litigating it would let
     * a library change refuse a save that does not touch conditions.
     *
     * <h2>The membership rule is §64's union, arrived at for the third time</h2>
     *
     * <p>{@code acceptableConditionIds} is <em>this project's live library entries, unioned with the
     * ids this case already attaches</em>. Both halves are load-bearing and they fail in opposite
     * directions. Without the library half, an id naming a sibling project's condition satisfies the
     * foreign key and is stored, and every later read resolves a condition through a project the
     * reader was never granted — the argument {@code suiteId} and {@code sharedStepId} each make on
     * a column nobody made it for. Without the stored half, the day a condition is retired every
     * case attaching it becomes unsaveable, and a title fix on an untouched case would be refused
     * over an attachment the author never made and cannot see.
     *
     * <h2>Duplicates within one list are collapsed; the two lists are independent</h2>
     *
     * <p>Attaching the same condition twice as a precondition is a list with a repeated entry and no
     * meaning, so it is de-duplicated rather than refused — {@link #normalizeClassification}'s
     * argument, where asking the author to remove something they cannot see is worse than resolving
     * it. Attaching it as <em>both</em> a pre- and a postcondition is legitimate and common ("reset
     * the payment data" is setup and cleanup), so the two lists are normalized separately and never
     * checked against each other.
     *
     * <p>Order is preserved and never sorted. Position is the content here, unlike classification,
     * which is why these fields are {@code List} on a record whose hash is order-sensitive.
     *
     * @param field the name to report errors under, as spelled on {@code TestCaseInput}
     */
    private static List<UUID> normalizeConditions(
            List<CaseConditionInput> submitted,
            String field,
            Set<UUID> acceptableConditionIds,
            List<UUID> stored,
            Map<String, List<String>> errors) {

        if (submitted == null) {
            return stored == null ? List.of() : List.copyOf(stored);
        }

        Set<UUID> distinct = new LinkedHashSet<>();
        for (CaseConditionInput entry : submitted) {
            if (entry == null) {
                continue;
            }
            if (entry.inlineBody() != null) {
                // Refused rather than ignored, and refused HERE rather than in refuseUnsupported,
                // because it is a rule about one entry's shape. CaseConditionInput carries the
                // reason: nothing renders an inline attachment, so accepting one would be a save
                // that succeeds and a condition the author can never see again.
                errors.computeIfAbsent(field, k -> new ArrayList<>())
                        .add("Attach a condition from the library. Inline conditions are not "
                                + "supported yet.");
                continue;
            }
            if (entry.conditionId() == null) {
                errors.computeIfAbsent(field, k -> new ArrayList<>())
                        .add("Choose a condition.");
                continue;
            }
            if (!acceptableConditionIds.contains(entry.conditionId())) {
                // One message for three causes — retired, another project's, or nothing at all —
                // on ClassificationVocabulary's terms: distinguishing them would confirm the
                // existence of a condition the caller may not use.
                errors.computeIfAbsent(field, k -> new ArrayList<>())
                        .add("That condition is not one this project offers.");
                continue;
            }
            distinct.add(entry.conditionId());
        }

        return List.copyOf(distinct);
    }

    /**
     * Rejects the three contract fields this endpoint does not implement — see
     * {@link CaseUpdateRequest}'s header for why refusing beats ignoring.
     *
     * <p>It was five. {@code preconditions} and {@code postconditions} left in §95 and are
     * validated by {@link #normalizeConditions} below; the inline half of each is still refused,
     * but there rather than here, because it is a rule about an entry's shape and not about a field
     * nothing implements.
     */
    private static void refuseUnsupported(
            CaseUpdateRequest request, Map<String, List<String>> errors) {

        String message = "This field cannot be changed here yet.";
        if (request.ownerId() != null) {
            errors.computeIfAbsent("ownerId", k -> new ArrayList<>()).add(message);
        }
        if (request.tags() != null) {
            errors.computeIfAbsent("tags", k -> new ArrayList<>()).add(message);
        }
        if (request.automationTags() != null) {
            errors.computeIfAbsent("automationTags", k -> new ArrayList<>()).add(message);
        }
    }

    /**
     * Normalizes the submitted steps, dropping nothing silently.
     *
     * <p>Position is the payload's order and is never taken from the request: a client-supplied
     * position can duplicate or skip, and {@code test_step} has no constraint that would refuse it —
     * so two steps could share position 3 and the execution screen would order them arbitrarily.
     */
    private static List<StepEdit> normalizeSteps(
            List<CaseUpdateRequest.StepInput> submitted,
            Set<UUID> existingStepIds,
            Set<UUID> acceptableSharedStepIds,
            Map<String, List<String>> errors) {

        if (submitted == null || submitted.isEmpty()) {
            return List.of();
        }

        List<StepEdit> normalized = new ArrayList<>();
        for (int i = 0; i < submitted.size(); i++) {
            CaseUpdateRequest.StepInput step = submitted.get(i);
            String action = trimToNull(step.action());
            if (action == null) {
                errors.computeIfAbsent("steps", k -> new ArrayList<>())
                        .add("Describe the action for step " + (i + 1) + ".");
                continue;
            }
            if (step.id() != null && !existingStepIds.contains(step.id())) {
                errors.computeIfAbsent("steps", k -> new ArrayList<>())
                        .add("Step " + (i + 1) + " refers to a step that is not on this case.");
                continue;
            }
            if (step.sharedStepId() != null
                    && !acceptableSharedStepIds.contains(step.sharedStepId())) {
                errors.computeIfAbsent("steps", k -> new ArrayList<>())
                        .add("Step " + (i + 1) + " refers to a shared step that is not in this "
                                + "project's library.");
                continue;
            }
            normalized.add(new StepEdit(
                    step.id(),
                    keyword(step.keyword(), i, errors),
                    action,
                    trimToNull(step.testData()),
                    trimToNull(step.expectedResult()),
                    step.sharedStepId()));
        }
        return normalized;
    }

    /**
     * The shared-step ids a submitted payload references — what the caller resolves the library
     * half of {@link #validate}'s {@code acceptableSharedStepIds} from.
     *
     * <p>Here rather than at each call site so the two write paths cannot disagree about which ids
     * they looked up, and so the empty case — the overwhelmingly common one, since most cases
     * reference no library entry at all — is answered once. An empty result means the caller makes
     * no query.
     */
    /**
     * Every condition id the payload names, across both lists — what the caller resolves the live
     * library half of {@code acceptableConditionIds} from.
     *
     * <p>{@link #referencedSharedStepIds}'s twin, and empty for most cases, which is what keeps the
     * membership query from running at all on a save that attaches nothing.
     */
    static Set<UUID> referencedConditionIds(
            List<CaseConditionInput> preconditions, List<CaseConditionInput> postconditions) {

        Set<UUID> ids = new HashSet<>();
        for (List<CaseConditionInput> list : java.util.Arrays.asList(preconditions, postconditions)) {
            if (list == null) {
                continue;
            }
            for (CaseConditionInput entry : list) {
                if (entry != null && entry.conditionId() != null) {
                    ids.add(entry.conditionId());
                }
            }
        }
        return ids;
    }

    static Set<UUID> referencedSharedStepIds(List<CaseUpdateRequest.StepInput> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return Set.of();
        }
        Set<UUID> referenced = new HashSet<>();
        for (CaseUpdateRequest.StepInput step : submitted) {
            if (step.sharedStepId() != null) {
                referenced.add(step.sharedStepId());
            }
        }
        return referenced;
    }

    /**
     * Parses a step keyword, which arrives as a string on the contract rather than as an enum.
     *
     * <p>{@code TestStepInput.keyword} is {@code type: string, nullable: true} while
     * {@code TestStep.keyword} is an enum of the same five values — a contract asymmetry that is
     * left as it is, because the input form is what the SPA sends and it sends null. Parsed rather
     * than trusted: an unrecognized keyword bound straight through would fail at the database's
     * check constraint as a 500.
     */
    private static StepKeyword keyword(String raw, int index, Map<String, List<String>> errors) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return StepKeyword.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notAKeyword) {
            errors.computeIfAbsent("steps", k -> new ArrayList<>())
                    .add("Step " + (index + 1) + " has an unrecognized keyword.");
            return null;
        }
    }

    /**
     * Blank is the same as absent for every optional text field here.
     *
     * <p>The form submits {@code ''} for a field the user cleared, and storing that would make
     * "cleared" and "never filled in" two different values that render identically — so the next
     * query looking for unfilled objectives finds half of them.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
