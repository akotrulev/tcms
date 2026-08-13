package com.tcms.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized write to one suite — everything {@link Suite} needs and nothing it has to
 * check.
 *
 * <p>Same shape and the same reason as {@link CaseEdit}: the request is normalized in one place
 * before anything acts on it, so no service, entity or query re-derives whether the input was
 * acceptable. A rule enforced half here and half in the entity is a rule with two answers.
 *
 * <p><strong>One entry point since §98, where there were two.</strong> They existed because the
 * rules genuinely differed on the field an author gets wrong: on a create {@code parentId} chose
 * the parent, and on a rename it could only repeat the stored one, so a single method taking a
 * nullable "current suite" would have let the create case silently skip the check the rename
 * depended on. With the tree gone the two validations are character-for-character identical, and
 * keeping them apart would be two places for one rule to drift — which is this class's own opening
 * argument.
 */
record SuiteEdit(
        String name,
        String description,
        // Null for "leave them alone", which the rename dialog sends and the create path never
        // does. Both are normalized by #normalizeTagRules / #normalizeCaseIds before they get here.
        List<String> tagRules,
        List<UUID> caseIds,

        // NEVER null, unlike the two above, and the asymmetry is CaseEdit's rather than an
        // inconsistency: #normalizeConditions resolves an absent list against what is STORED, so
        // "leave them alone" has already been turned into the concrete list by the time it lands
        // here. The membership lists cannot do that — SuiteMembershipWriter takes the null and skips
        // the arm — and conditions cannot either, because reconcile needs both lists to compute
        // what is stale.
        List<UUID> preconditionIds,
        List<UUID> postconditionIds) {

    /**
     * The longest name the tree will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so the cap is a product rule rather than a schema
     * one: a folder label is rendered in a tree rail a few hundred pixels wide, and a name that
     * cannot be read in it is not a name. Shorter than a case title's 300 for that reason.
     */
    static final int MAX_NAME = 150;

    /**
     * The longest tag rule. {@link CaseEdit}'s cap for a tag, and it must stay the same number: a
     * rule longer than a tag can be is a rule that can never match anything, and it would be
     * accepted here and silently useless.
     */
    static final int MAX_TAG = 60;

    /** Sealed so the controller's mapping is exhaustive, like {@link com.tcms.project.GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code SuiteInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(SuiteEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create or a rename — one method, because since §98 they have the same rules.
     *
     * <p>What used to differ is recorded on the record above. The half worth keeping in mind here
     * is why a differing {@code parentId} was <em>refused</em> rather than ignored: dropping a
     * field silently is what lets a client show a suite in its new place, reload, and find it back
     * where it was, with the server having reported success. That reasoning outlives the field.
     *
     * @param liveCaseIds the project's live case ids, for the explicitly-added membership arm. A
     *                    {@code caseId} outside this set is a field error rather than a silent
     *                    drop, for the same reason.
     * @param acceptableConditionIds this project's live library entries, unioned with the ids this
     *                    suite already attaches. Both halves are load-bearing and they fail in
     *                    opposite directions — {@link CaseEdit}'s {@code normalizeConditions} spells
     *                    out which, and this is the third table the same union has been needed on.
     * @param storedPreconditionIds what the suite attaches today, per kind — what an <em>absent</em>
     *                    list resolves to. Taken verbatim and unvalidated: it describes what is
     *                    already on the row rather than something this caller asked for, so
     *                    re-litigating it would let a library retirement refuse a rename that never
     *                    mentioned conditions.
     */
    static Outcome validate(
            SuiteWriteRequest request,
            Set<UUID> liveCaseIds,
            Set<UUID> acceptableConditionIds,
            List<UUID> storedPreconditionIds,
            List<UUID> storedPostconditionIds) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, errors);
        List<String> tagRules = normalizeTagRules(request.tagRules(), errors);
        List<UUID> caseIds = normalizeCaseIds(request.caseIds(), liveCaseIds, errors);
        List<UUID> preconditions = normalizeConditions(
                request.preconditions(), "preconditions", acceptableConditionIds,
                storedPreconditionIds, errors);
        List<UUID> postconditions = normalizeConditions(
                request.postconditions(), "postconditions", acceptableConditionIds,
                storedPostconditionIds, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new SuiteEdit(
                        name,
                        trimToNull(request.description()),
                        tagRules,
                        caseIds,
                        preconditions,
                        postconditions))
                : new Outcome.Invalid(errors);
    }

    /**
     * Normalizes one of the two condition lists — {@link CaseEdit}'s {@code normalizeConditions},
     * transferred, and the rules it enforces are that method's in full.
     *
     * <p>It is a transfer and not an extraction, which is a decision worth stating. The two could
     * share one static method; they are the same six lines over a different stored value. They are
     * kept apart because the shared version would take five parameters to say which of two entities
     * it is validating for, and because the <em>rules</em> being identical is the invariant —
     * {@code SuiteWriteEndpointTest} and {@code CaseUpdateEndpointTest} each pin them against their
     * own endpoint, so the day they diverge is a failing test rather than a silent edit to one
     * caller's behaviour. The one place a shared helper would genuinely have paid — the error
     * messages — is handled by the strings being the same text, which those tests assert.
     *
     * <p>Absent means "leave it alone" and resolves to {@code stored}; empty means "detach
     * everything", which is a thing an author asks for by removing the last row. The distinction is
     * why the field is a {@code List} that may be null on the request record and never null here.
     *
     * @param field the name to report errors under, as spelled on {@code SuiteInput}
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
                // Refused rather than ignored: nothing renders an inline attachment — every
                // CaseConditionRef requires a title and the row has no column for one — so
                // accepting it would be a save that succeeds and a condition nobody can ever see.
                errors.computeIfAbsent(field, key -> new ArrayList<>())
                        .add("Attach a condition from the library. Inline conditions are not "
                                + "supported yet.");
                continue;
            }
            if (entry.conditionId() == null) {
                errors.computeIfAbsent(field, key -> new ArrayList<>()).add("Choose a condition.");
                continue;
            }
            if (!acceptableConditionIds.contains(entry.conditionId())) {
                // One message for three causes — retired, another project's, or nothing at all.
                // Distinguishing them would confirm the existence of a condition the caller may
                // not use.
                errors.computeIfAbsent(field, key -> new ArrayList<>())
                        .add("That condition is not one this project offers.");
                continue;
            }
            distinct.add(entry.conditionId());
        }

        return List.copyOf(distinct);
    }

    /**
     * Every condition id the payload names, across both lists — what the caller resolves the live
     * library half of {@code acceptableConditionIds} from.
     *
     * <p>Empty for the overwhelmingly common suite write, which is a rename mentioning no condition
     * at all, and an empty set is what keeps {@code IN ()} from ever being bound.
     */
    static Set<UUID> referencedConditionIds(SuiteWriteRequest request) {
        Set<UUID> ids = new HashSet<>();
        // Arrays.asList and not List.of: either list is legitimately null — that is what "leave
        // them alone" is spelled as — and List.of throws on a null element.
        for (List<CaseConditionInput> list :
                Arrays.asList(request.preconditions(), request.postconditions())) {

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
     * The suite's tag rules: trimmed, blanks dropped, duplicates collapsed, order kept.
     *
     * <p>Null in, null out — "leave them alone", the one instruction a reconcile must be able to
     * tell from "clear them", and the one the rename dialog sends by not mentioning the field.
     *
     * <p><strong>A tag nothing carries is accepted.</strong> There is no vocabulary to check
     * against, and writing the rule before the cases are tagged is an ordinary way to work — a
     * refusal here would be a validator inventing a workflow order.
     */
    private static List<String> normalizeTagRules(
            List<String> submitted, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }

        List<String> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : submitted) {
            String tag = trimToNull(raw);
            if (tag == null) {
                continue;
            }
            if (tag.length() > MAX_TAG) {
                errors.computeIfAbsent("tagRules", key -> new ArrayList<>())
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
     * The cases added to the suite individually, de-duplicated and bounded to this project.
     *
     * <p>The bound is the point, and it is {@code suiteId}'s argument on {@link CaseEdit} inverted:
     * a case id from a <em>sibling project</em> satisfies {@code suite_case}'s foreign key, the
     * company column and RLS, so without this check it would be stored — and every later read of
     * this suite would resolve a case through a project the reader was never granted.
     *
     * <p>A deleted case is refused the same way, since {@code liveCaseIds} carries only live ones:
     * adding a case that no read returns would put a permanent invisible member in a collection
     * whose count is on screen.
     */
    private static List<UUID> normalizeCaseIds(
            List<UUID> submitted, Set<UUID> liveCaseIds, Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }

        List<UUID> distinct = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID caseId : submitted) {
            if (caseId == null) {
                continue;
            }
            if (!liveCaseIds.contains(caseId)) {
                errors.computeIfAbsent("caseIds", key -> new ArrayList<>())
                        .add("That case is not in this project.");
                continue;
            }
            if (seen.add(caseId)) {
                distinct.add(caseId);
            }
        }
        return List.copyOf(distinct);
    }

    /** The one name rule, so create and rename cannot disagree about what a name is. */
    private static String validName(SuiteWriteRequest request, Map<String, List<String>> errors) {
        String name = trimToNull(request.name());
        if (name == null) {
            errors.computeIfAbsent("name", key -> new ArrayList<>()).add("Enter a name.");
        } else if (name.length() > MAX_NAME) {
            errors.computeIfAbsent("name", key -> new ArrayList<>())
                    .add("Keep the name under " + MAX_NAME + " characters.");
        }
        return name;
    }

    /**
     * Blank is absent.
     *
     * <p>A description submitted as {@code "   "} stored as-is is a description that renders as an
     * empty paragraph and reads as data loss; null renders as nothing, which is what was meant.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
