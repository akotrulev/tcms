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
        // The INHERITED attachments this case declines (§187) — `suite_condition` ids, not library
        // `condition` ids, because the row they produce is keyed on the attachment.
        //
        // Sorted and de-duplicated like `classificationValueIds` and NOT like the two lists above,
        // and the split is the same one those two comments draw: position is content for an
        // attachment the case made, and a decline has no position at all. Two saves turning the
        // same two rules off in a different order are one state and must hash alike.
        //
        // Being on this record makes it part of CaseVersions#hash, which is correct and is the
        // reason it is here rather than beside `tags`: declining "must be logged in" changes the
        // procedure a tester performs, so it is authored content and belongs in the history. It
        // carries the same ONE-TIME consequence §95 recorded for the conditions — every hash stored
        // before §187 was computed without this field, so the first save of each existing case
        // writes a version whose diff looks empty. Once per case, and unlike the recurring
        // empty-diff defect, it ends.
        List<UUID> declinedConditionIds,
        String changeComment) {

    /** The longest title the SPA's editor accepts. The column itself is unbounded {@code TEXT}. */
    private static final int MAX_TITLE = 300;

    /**
     * The longest tag. The column is unbounded {@code TEXT}; this is a bound on a label somebody
     * types into a chip and later has to match exactly in a suite's tag rules.
     */
    private static final int MAX_TAG = 60;

    /** One step, normalized. {@code position} is assigned by payload order, never sent. */
    record StepEdit(
            UUID id,
            StepKeyword keyword,
            String action,
            String testData,
            String expectedResult) {
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

        /**
         * @param edit the authored content — everything {@link CaseVersions#hash} digests
         * @param tags the case's tags after this save, or <strong>null for "leave them alone"</strong>
         *             (the request omitted the field). Beside the edit rather than on it, and that
         *             is the slice's one deliberate departure from §96's plan line.
         *
         *             <p>A tag is not authored content. {@code CaseBulkService}'s {@code AddTags}
         *             already says so in the only way that was observable before this slice — it
         *             deliberately does not stamp {@code updatedAt}, because "a tag is not authored
         *             content and moving the timestamp would make every 'edited recently' list a
         *             list of who ran a bulk tag". Putting tags on {@link CaseEdit} would put them
         *             in the content hash, so labelling a case {@code sprint-42} would write a new
         *             version of the case whose diff shows nothing about the test changing — which
         *             is precisely the empty-diff noise plan §6j added the hash to keep out.
         *
         *             <p>It also makes §95's bulk-hash trap structurally unavailable: a bulk action
         *             reconstructs a request without mentioning tags, and since tags are not hashed
         *             there is nothing for that omission to mis-hash.
         */
        record Valid(CaseEdit edit, List<String> tags) implements Outcome {
        }
    }

    /**
     * Whether this payload has to state the case's <strong>procedure</strong> — {@code steps} for a
     * {@code STRUCTURED} case, {@code gherkinSource} for a {@code GHERKIN} one.
     *
     * <p>The two are one rule with two spellings, not two rules: {@link StepFormat} makes them
     * alternatives, so a case states its procedure in exactly one of them and asking a caller for
     * the other is a category error. {@link CaseCompleteness} already reads them as one question
     * for the same reason. They are therefore deferred together and enforced together; nothing else
     * validated here differs by path.
     *
     * <h2>Why a case may exist before its procedure does</h2>
     *
     * <p>A plan's Test cases tab lists what the plan covers by title and creates a case per row
     * (§97 D2). Requiring a step there leaves two options and both are worse than the stub:
     * <em>refuse the save</em> — which is what it did from §97 to §199, on fields the author was
     * never shown and could not have filled — or <em>invent a step</em> the author did not write,
     * which is authored content with nobody's name on it.
     *
     * <p>So a case whose procedure is not written yet is a first-class state, and the product
     * already had the observer for it before it had a producer: {@code case_health.incomplete}
     * fires on an <strong>approved</strong> case with no steps or an empty scenario (§182), and
     * {@link CaseCompleteness} recorded that as a term nothing could reach. A draft missing its
     * procedure is ordinary work in progress; one signed off still missing it is the fault. That
     * line is drawn there, and this enum is what lets a case get to the side of it where somebody
     * can see it.
     */
    enum ProcedureRule {

        /**
         * The payload must state it — the <strong>update</strong> path, <em>for a case that already
         * has a procedure</em>.
         *
         * <p>~~A {@code PATCH} body is the whole editable set (§144) sent by an author with the
         * field in front of them, so an empty one is a statement that this case has no procedure.
         * It is also the funnel: a stub reaches its steps by being opened and saved, and this is
         * the rule that makes saving it mean writing it.~~ → <strong>narrowed by B2, and the first
         * clause stopped being true at B1</strong>: a body is no longer the whole editable set, so
         * an absent {@code steps} is a field nobody mentioned rather than a statement about the
         * case.
         *
         * <p>What this rule now says is the destructive half of it: a case that says how it is run
         * may not have that unsaid by a {@code steps: []}. Which cases it applies to is
         * {@code CaseUpdateService.hasProcedure}'s answer, read off the STORED row.
         *
         * <p>The funnel argument survives where it was ever really enforced — the SPA's editor,
         * whose own schema requires a procedure before it will save, with the author looking at the
         * field. What it cost on the API was the round trip: §200 made a stepless case a legal
         * stored row and this rule refused to store it back.
         */
        REQUIRED,

        /**
         * The payload need not — the <strong>create</strong> path, and {@link #of} reconstructing a
         * stored row.
         *
         * <p>Those two are one reason, not two. A stub is a legal row once create admits it, so a
         * reconstruction that refused one would have the validator disagreeing with the database
         * about which cases exist — and the case would be skipped by a bulk action that has nothing
         * to do with its steps, under a message telling its author to save it once, which the
         * update path would then refuse. §64's argument about stored references, reached from the
         * far end.
         */
        DEFERRED
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
     * @param procedure whether this payload must state how the case is run — see
     *                  {@link ProcedureRule}, which is the one rule here that differs by path.
     */
    static Outcome validate(
            CaseUpdateRequest request,
            Set<UUID> liveSuiteIds,
            Set<UUID> existingStepIds,
            ClassificationVocabulary vocabulary,
            Set<UUID> storedClassificationValueIds,
            Set<UUID> acceptableConditionIds,
            List<UUID> storedPreconditionIds,
            List<UUID> storedPostconditionIds,
            Set<UUID> declinableAttachmentIds,
            Set<UUID> retainedAttachmentIds,
            List<UUID> storedDeclinedIds,
            java.util.function.Predicate<String> automationKeyTaken,
            UUID storedOwnerId,
            List<String> storedAutomationTags,
            ProcedureRule procedure) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        refuseUnsupported(request, storedOwnerId, storedAutomationTags, errors);

        List<UUID> classification = normalizeClassification(
                request.classificationValueIds(), vocabulary, storedClassificationValueIds, errors);

        List<UUID> preconditions = normalizeConditions(
                request.preconditions(), "preconditions", acceptableConditionIds,
                storedPreconditionIds, errors);
        List<UUID> postconditions = normalizeConditions(
                request.postconditions(), "postconditions", acceptableConditionIds,
                storedPostconditionIds, errors);

        List<UUID> declined = normalizeDeclines(
                request.declinedConditionIds(),
                declinableAttachmentIds,
                retainedAttachmentIds,
                storedDeclinedIds,
                errors);

        List<String> tags = normalizeTags(request.tags(), errors);

        String title = trimToNull(request.title());
        if (title == null) {
            errors.computeIfAbsent("title", k -> new ArrayList<>()).add("Enter a title.");
        } else if (title.length() > MAX_TITLE) {
            errors.computeIfAbsent("title", k -> new ArrayList<>())
                    .add("Keep the title under " + MAX_TITLE + " characters.");
        }

        // A null suiteId is an UNFILED case since §96, not a missing field. A suite is a resolvable
        // collection — filed ∪ added ∪ tagged — so filing is one way of belonging rather than a
        // precondition for existing, and refusing the null here is what made a case impossible to
        // write before anybody had decided where it goes. The check that remains is the one that
        // was always the point: a suite id must name a live suite OF THIS PROJECT.
        if (request.suiteId() != null && !liveSuiteIds.contains(request.suiteId())) {
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
                normalizeSteps(request.steps(), existingStepIds, errors);

        boolean statesItsProcedure = procedure == ProcedureRule.REQUIRED;

        if (statesItsProcedure && format == StepFormat.GHERKIN && gherkin == null) {
            errors.computeIfAbsent("gherkinSource", k -> new ArrayList<>())
                    .add("Write the scenario, or switch to structured steps.");
        }
        // "Nothing was SENT", not "nothing survived validation" — and the difference is a message
        // that contradicts the one beside it. `normalizeSteps` drops a step it has already
        // complained about, so a payload whose only step was rejected arrives here with an empty
        // list, and the reader is told to add a step they demonstrably just added, underneath the
        // sentence explaining why the one they added was refused. Found by driving a refused
        // shared-step reference against the real server (§64, before §239 retired the library);
        // the same pair was already reachable through a blank action and an unknown step id, so it
        // predated that reference check and outlives it.
        boolean noStepsSubmitted = request.steps() == null || request.steps().isEmpty();
        if (statesItsProcedure && format == StepFormat.STRUCTURED && noStepsSubmitted) {
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
                declined,
                trimToNull(request.changeComment())),
                tags);
    }

    /**
     * Normalizes the submitted tags: trimmed, blanks dropped, duplicates collapsed, order kept.
     *
     * <p><strong>Null in, null out</strong> — "leave them alone", and the one instruction a reconcile
     * must be able to tell from "clear them". Everywhere else on this record the two are the same
     * Java value; here, as for classification and the conditions, the field is a collection and the
     * difference survives.
     *
     * <p>Collapsed rather than refused, for {@link #normalizeClassification}'s reason:
     * {@code case_tag}'s primary key is {@code (case_id, tag)}, so the schema says a tag list is a
     * <em>set</em>, and sending one twice is not a mistake to report — it is the same request.
     *
     * <p>Order is the payload's and is not sorted, unlike classification. Classification is sorted
     * because it is hashed and two orderings of one set must hash alike; tags are not hashed at all
     * (see {@link Outcome.Valid}), and {@code case_tag} has no position column, so the reader sorts
     * them alphabetically and nothing here needs an opinion.
     *
     * <p>A tag longer than {@value #MAX_TAG} is a field error rather than a truncation: a silently
     * shortened tag stops matching the suite tag rule the author wrote it for, and the case
     * disappears from a collection nobody can see it should be in.
     */
    private static List<String> normalizeTags(
            List<String> submitted, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }

        List<String> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : submitted) {
            String tag = trimToNull(raw);
            if (tag == null) {
                // A blank is dropped rather than refused. The editor's tag input can hold an empty
                // chip mid-typing, and refusing the save would report an error about a value the
                // author does not think they entered.
                continue;
            }
            if (tag.length() > MAX_TAG) {
                errors.computeIfAbsent("tags", k -> new ArrayList<>())
                        .add("Keep each tag under " + MAX_TAG + " characters.");
                continue;
            }
            if (seen.add(tag)) {
                distinct.add(tag);
            }
        }
        return List.copyOf(distinct);
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
     * @return empty when the stored row does not validate. The caller skips that case with a reason
     *         rather than this throwing and failing the whole operation; applying the change without
     *         a version row is not an option, since that is exactly the silent mis-attribution this
     *         method exists to prevent.
     *         <p><strong>What can still land here is narrower since §200, and worth stating because
     *         the two examples this line used to give were both of them.</strong> "A case with no
     *         steps in structured format" is now a legal stored row and reconstructs — see
     *         {@link ProcedureRule#DEFERRED}, passed below. "One whose suite has since been deleted"
     *         cannot be produced either: {@link SuiteWriteService#delete} refuses a suite with live
     *         cases filed in it. So the empty arm is defensive rather than reachable through any
     *         endpoint today — §181's shape, kept for §181's reason, and the caller's
     *         {@code NEEDS_A_SAVE} skip is a sentence no test has ever produced.
     */
    static Optional<CaseEdit> of(
            TestCase row,
            List<TestStep> steps,
            Set<UUID> liveSuiteIds,
            Set<UUID> storedClassificationValueIds,
            List<UUID> storedPreconditionIds,
            List<UUID> storedPostconditionIds,
            List<UUID> storedDeclinedIds,
            java.util.function.Predicate<String> automationKeyTaken) {

        CaseUpdateRequest asRequest = asRequest(row, steps);

        Set<UUID> existingStepIds = new HashSet<>();
        for (TestStep step : steps) {
            existingStepIds.add(step.getId());
        }

        Outcome outcome = validate(
                asRequest,
                liveSuiteIds,
                existingStepIds,
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
                // Never consulted, third instance of the same shape: declinedConditionIds is null
                // on the request above, so nothing is checked against it. An empty declinable set
                // keeps this a pure function — resolving the real one needs the suite chain and
                // therefore two queries.
                Set.of(),
                // The RETENTION bound, and it is deliberately the stored set itself — "retain all
                // of it" (§192). It cannot be Set.of() the way `declinable` above can: that one is
                // never read for a null request field, this one always is, and an empty set would
                // reconstruct every bulk-touched case as declining nothing. Nor can it be the real
                // set, which is the same query this method exists without. THE CALLER NARROWS IT
                // INSTEAD: CaseBulkService's MOVE_TO_SUITE arm resolves the target folder's
                // attachments ONCE for the whole batch — the target is one suite for every case in
                // the operation — and passes the already-narrowed list as storedDeclinedIds.
                Set.copyOf(storedDeclinedIds),
                storedDeclinedIds,
                automationKeyTaken,
                // Both null, and neither is ever consulted: the reconstruction leaves the two
                // refused fields absent (see above), so the comparison B2 added has nothing to
                // compare. Passing the row's real owner would be equally correct and would put a
                // getter call in a method whose whole property is that it is a pure function of
                // what it was handed.
                null,
                null,
                // A stored row is not an authoring act, so the procedure rule has nothing here to
                // protect — the fourth argument of this shape, after the vocabulary, the library and
                // the declinable set above. It differs from those three in that it cannot be spelled
                // as an empty set, and in what it would cost: since §200 a stub is a legal row, so
                // REQUIRED here would refuse to reconstruct a case the database holds and every
                // authored bulk action would skip it under a message telling its author to save it
                // once — which the update path, where the rule DOES apply, would then refuse.
                ProcedureRule.DEFERRED);
        return outcome instanceof Outcome.Valid valid
                ? Optional.of(valid.edit())
                : Optional.empty();
    }


    /**
     * A stored case, spelled as the request that would store it again unchanged.
     *
     * <p><strong>Two callers, and they are the two halves of the same guarantee.</strong>
     * {@link #of} uses it to reconstruct an edit for a bulk action that changed one field, so the
     * version row it writes describes the whole case. {@link CaseUpdateRequest#overlay} uses it as
     * the <em>base</em> of a {@code PATCH}: what the caller did not mention is what is already
     * stored, which is the whole of B1's leave-alone semantics and is why it is not a second
     * mapping written beside the first.
     *
     * <p>Everything a request may carry that a stored row does not <em>is</em> null here — the five
     * collections whose null already means "leave alone", the two refused fields, and
     * {@code changeComment} — and each of those nulls has its own reason recorded below. The
     * overlay depends on all of them: a base that guessed at a collection would make an absent list
     * on the wire mean "rewrite it with what I read", which is the read-modify-write race the
     * absent-means-leave-alone reading exists to avoid.
     */
    static CaseUpdateRequest asRequest(TestCase row, List<TestStep> steps) {
        return new CaseUpdateRequest(
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
                                step.getExpectedResult()))
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
                // Tags null: "leave them alone", which is what all eight bulk actions do — AddTags
                // writes case_tag directly rather than through this reconstruction. Unlike
                // classification and the conditions above, the stored values are NOT supplied
                // separately, and they do not have to be: tags are not on CaseEdit and therefore
                // not in CaseVersions#hash, so a reconstruction that omits them still hashes as
                // the whole case. That is §95's bulk-hash trap being structurally unavailable here
                // rather than avoided by remembering a line.
                null,
                // The refused two stay null: they are refused on the way IN, and this is a
                // reconstruction of what is already stored, so passing either would make a
                // stored row fail validation for a rule about requests.
                null, null,
                // The two condition lists are null for the SAME reason classification is above —
                // "leave them alone", which is exactly what all eight bulk actions do. The stored
                // attachments are supplied separately, below, so the reconstructed edit still
                // HASHES as the whole case. Passing them here instead would re-validate a stored
                // attachment against the live library, and a case attaching a since-retired
                // condition would fail to reconstruct and be skipped by a MOVE_TO_SUITE that has
                // nothing to do with it. §64's argument, and §68's, for the third time.
                null, null,
                // And the declines, for the fourth. Null is "leave them alone", which is what all
                // eight bulk actions do — including MOVE_TO_SUITE, where it is load-bearing rather
                // than incidental: a move is the one action after which a stored decline may stop
                // reaching the case, and reading absent as "clear" would delete the author's
                // decision as a side effect of relocating the case. The stored set is supplied
                // separately, below, so the reconstruction still hashes as the whole case.
                null);
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
     * Normalizes the declined-attachment set (§187).
     *
     * <h2>It is a set, so it is sorted — and that is the difference from the two lists below</h2>
     *
     * <p>{@link #normalizeClassification}'s treatment rather than {@link #normalizeConditions}',
     * because a decline has no position. An attachment's {@code position} is the order a tester
     * performs it in; turning one off is a fact about the case with no order of its own, and two
     * saves listing the same two declines differently are one state that must hash alike.
     *
     * <h2>The membership rule is NOT §64's union, and the asymmetry is the point</h2>
     *
     * <p>{@code declinable} is every attachment currently reaching this case whose library entry is
     * live — {@link CaseConditions#declinableAttachmentIds}. There is deliberately no
     * "unioned with what this case already declines" half, which is what the two condition lists
     * need, because the failure that half prevents cannot happen here: an id the client could not
     * see is an id the client does not send, and one it does not send is <em>protected</em> rather
     * than dropped. {@link CaseConditions#reconcileOptouts} carries that bound, so the stored row
     * survives without this method ever having to accept it.
     *
     * <p>Put the union in anyway and the rule inverts: a client echoing back a set it was never
     * served would keep alive a state no screen can show. Refusing is right here for the same
     * reason accepting is right there.
     *
     * <h2>The retention bound is applied LAST, and it is not a validation</h2>
     *
     * <p>{@code retained} is every attachment reaching the case's suite <strong>after this save</strong>
     * ({@link CaseConditions.Reaching#all()}), and the result is intersected with it (§192). A save
     * that also moves the case therefore drops the declines the move invalidates rather than
     * refusing them: the client's set describes the folder it was looking at and is checked against
     * <em>that</em> folder's {@code declinable}, so restating a decline while relocating a case is
     * an ordinary request and not a 400. What it is not is a decision that survives the move —
     * {@link CaseConditions#reconcileOptouts} deletes those rows, and this intersection is what
     * makes the hash say so in the same breath. Bounding it here rather than after
     * {@link CaseVersions#decide} is the whole point: the two would otherwise disagree about what
     * the case declines, and the version would record a decline the row does not carry.
     *
     * <h2>Absent means "leave them alone"</h2>
     *
     * <p>The stored set, taken verbatim and unvalidated, for the reason the two lists below give: a
     * bulk action reconstructs a request without mentioning declines, and reading absent as "clear"
     * would have every {@code MOVE_TO_SUITE} silently switch back on a rule an author turned off.
     *
     * <h2>The invisible declines are ADDED BACK, and that is a hash requirement</h2>
     *
     * <p>The result is the submitted set <em>unioned with every stored decline outside
     * {@code declinable}</em> — the rows {@link CaseConditions#reconcileOptouts} protects because
     * no list showed them. Without the union this method would answer two different things about
     * one stored state depending on whether the client sent the field: the full set when it was
     * absent, and the visible subset when it was sent unchanged. {@link CaseVersions#hash} digests
     * this record, so those two spellings are two hashes, and a save that changed nothing would
     * write a version whose diff is empty — the recurring defect the sorting comments on this
     * record's fields exist to prevent, arrived at from a new direction.
     *
     * <p>Stated positively: what is hashed is what the case declines <strong>after</strong> this
     * save, which is the only value that is a function of the stored row rather than of what the
     * caller happened to know about it. It is also what lets {@link #of} pass an empty
     * {@code declinable} and the whole stored set and still agree with the edit path exactly.
     */
    private static List<UUID> normalizeDeclines(
            List<UUID> submitted,
            Set<UUID> declinable,
            Set<UUID> retained,
            List<UUID> stored,
            Map<String, List<String>> errors) {

        if (submitted == null) {
            return stored.stream().distinct().filter(retained::contains).sorted().toList();
        }

        // Checked separately from the union below, so an id the caller could not have been shown is
        // refused rather than absorbed by a stored row that happens to carry it.
        Set<UUID> chosen = new LinkedHashSet<>();
        for (UUID attachmentId : submitted) {
            if (attachmentId == null || !chosen.add(attachmentId)) {
                continue;
            }
            if (!declinable.contains(attachmentId)) {
                // One message for four causes — another case's attachment, another project's, one
                // this case no longer inherits, and one whose library entry was retired.
                // Deliberately not distinguished, on ClassificationVocabulary's grounds: saying
                // which would confirm the existence of a row the caller may not read.
                errors.computeIfAbsent("declinedConditionIds", k -> new ArrayList<>())
                        .add("That condition is not one this case inherits.");
            }
        }

        Set<UUID> effective = new LinkedHashSet<>(chosen);
        for (UUID attachmentId : stored) {
            if (!declinable.contains(attachmentId)) {
                effective.add(attachmentId);
            }
        }

        return effective.stream().filter(retained::contains).sorted().toList();
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
     * reader was never granted — the argument {@code suiteId} makes on a column nobody made it
     * for, and which {@code sharedStepId} made too until §239 retired the shared-step library.
     * Without the stored half, the day a condition is retired every case attaching it becomes
     * unsaveable, and a title fix on an untouched case would be refused over an attachment the
     * author never made and cannot see.
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
     * Rejects the two contract fields this endpoint does not implement — see
     * {@link CaseUpdateRequest}'s header for why refusing beats ignoring.
     *
     * <p>It was five. {@code preconditions} and {@code postconditions} left in §95 and are
     * validated by {@link #normalizeConditions} below; the inline half of each is still refused,
     * but there rather than here, because it is a rule about an entry's shape and not about a field
     * nothing implements. {@code tags} left in §96 and is normalized by {@link #normalizeTags}.
     *
     * <p>{@code automationTags} stays, and its staying is the point: it is a different table
     * ({@code case_automation_tag}) and a different job — the selection tags a CI runner filters on
     * (plan §6c) — so accepting {@code tags} is not a reason to accept it.
     */
    private static void refuseUnsupported(
            CaseUpdateRequest request,
            UUID storedOwnerId,
            List<String> storedAutomationTags,
            Map<String, List<String>> errors) {

        String message = "This field cannot be changed here yet.";
        // ~~Refused when PRESENT.~~ → **B2: refused when it would CHANGE something.** The old rule
        // was right about the capability and wrong about the round trip: both fields are served by
        // the GET, so a caller reading a case and sending it back — the shape this API asks a
        // non-form client to use — was refused for two fields it had not touched, and the only fix
        // available to it was to know which of the fields it had just read to drop again
        // (`core/rewrite.ts` does exactly that, and says so).
        //
        // Echoing what is stored is not a write, so it is not refused. Sending something ELSE still
        // is, by name, because no path writes either column: silently ignoring a changed value is
        // the save-that-lies this whole boundary exists to avoid. On the create path both stored
        // values are null, so any value at all is a change and the answer is unchanged.
        if (request.ownerId() != null && !request.ownerId().equals(storedOwnerId)) {
            errors.computeIfAbsent("ownerId", k -> new ArrayList<>()).add(message);
        }
        if (request.automationTags() != null
                && !sameTags(request.automationTags(), storedAutomationTags)) {
            errors.computeIfAbsent("automationTags", k -> new ArrayList<>()).add(message);
        }
    }

    /**
     * Whether two automation-tag lists say the same thing.
     *
     * <p>Order-insensitive and duplicate-insensitive, because {@code case_automation_tag} is a set
     * with a unique key and the read serves it sorted — a caller echoing the read back must not be
     * refused for having sorted it differently, and a caller sending the same tag twice has still
     * changed nothing. Blank entries are dropped for the same reason the write path would drop
     * them, so a trailing empty string in a hand-built body is not a "change" either.
     */
    private static boolean sameTags(List<String> sent, List<String> stored) {
        return new HashSet<>(normalizeForComparison(sent))
                .equals(new HashSet<>(normalizeForComparison(stored)));
    }

    private static Set<String> normalizeForComparison(List<String> tags) {
        Set<String> normalized = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                String trimmed = trimToNull(tag);
                if (trimmed != null) {
                    normalized.add(trimmed);
                }
            }
        }
        return normalized;
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
                    trimToNull(step.expectedResult())));
        }
        return normalized;
    }

    /**
     * Every condition id the payload names, across both lists — what the caller resolves the live
     * library half of {@code acceptableConditionIds} from.
     *
     * <p>Here rather than at each call site so the two write paths cannot disagree about which ids
     * they looked up, and empty for most cases, which is what keeps the membership query from
     * running at all on a save that attaches nothing. An empty result means the caller makes no
     * query. (It had a twin, {@code referencedSharedStepIds}, until §239 retired the shared-step
     * library.)
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
