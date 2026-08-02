package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
            java.util.function.Predicate<String> automationKeyTaken) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        refuseUnsupported(request, errors);

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
        List<StepEdit> steps = normalizeSteps(request.steps(), existingStepIds, errors);

        if (format == StepFormat.GHERKIN && gherkin == null) {
            errors.computeIfAbsent("gherkinSource", k -> new ArrayList<>())
                    .add("Write the scenario, or switch to structured steps.");
        }
        if (format == StepFormat.STRUCTURED && steps.isEmpty()) {
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
                // See the header. Null, not a description of the bulk edit.
                null,
                // The refused six stay null: they are refused on the way IN, and this is a
                // reconstruction of what is already stored, so passing any of them would make a
                // stored row fail validation for a rule about requests.
                null, null, null, null, null, null);

        Set<UUID> existingStepIds = new HashSet<>();
        for (TestStep step : steps) {
            existingStepIds.add(step.getId());
        }

        Outcome outcome = validate(asRequest, liveSuiteIds, existingStepIds, automationKeyTaken);
        return outcome instanceof Outcome.Valid valid
                ? Optional.of(valid.edit())
                : Optional.empty();
    }

    /**
     * Rejects the six contract fields this endpoint does not implement — see
     * {@link CaseUpdateRequest}'s header for why refusing beats ignoring.
     */
    private static void refuseUnsupported(
            CaseUpdateRequest request, Map<String, List<String>> errors) {

        String message = "This field cannot be changed here yet.";
        if (request.ownerId() != null) {
            errors.computeIfAbsent("ownerId", k -> new ArrayList<>()).add(message);
        }
        if (request.classificationValueIds() != null) {
            errors.computeIfAbsent("classificationValueIds", k -> new ArrayList<>()).add(message);
        }
        if (request.tags() != null) {
            errors.computeIfAbsent("tags", k -> new ArrayList<>()).add(message);
        }
        if (request.automationTags() != null) {
            errors.computeIfAbsent("automationTags", k -> new ArrayList<>()).add(message);
        }
        if (request.preconditions() != null) {
            errors.computeIfAbsent("preconditions", k -> new ArrayList<>()).add(message);
        }
        if (request.postconditions() != null) {
            errors.computeIfAbsent("postconditions", k -> new ArrayList<>()).add(message);
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
