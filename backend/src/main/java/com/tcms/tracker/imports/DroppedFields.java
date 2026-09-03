package com.tcms.tracker.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which source fields are not imported, and the sentence the report will still be showing a year
 * from now.
 *
 * <h2>One derivation, two responses (D-f)</h2>
 *
 * <p>The preview serves what this returns and the run persists it, one row per field into
 * {@code import_dropped_field}. {@code ImportReportMatchesPreviewTest} asserts the two are equal
 * element for element, which is the module's stated done-when — <em>"the dropped-field list in the
 * report matches the preview's word for word"</em>. Two derivations would be two lists that agree
 * until somebody edits one.
 *
 * <p><strong>The reason is stored on the row rather than looked up when the report is read.</strong>
 * Editing this map must not retroactively rewrite what somebody was told at the time — the same
 * argument {@code issue.issue_key}'s comment makes about composed keys.
 *
 * <h2>Rows, not a paragraph</h2>
 *
 * <p>A year later the question is <em>"how many Story Points values did we drop across every
 * import"</em>, and a rendered sentence cannot answer it. That is 009 {@code t25}'s acceptance
 * criterion and the reason {@code import_dropped_field} is a table.
 */
final class DroppedFields {

    /**
     * The fields this product has a real answer about, and the answer.
     *
     * <p>Everything else the source holds is reported too — see {@link #reasonFor} — but with a
     * generic sentence, because a specific one would be this map claiming to know what somebody
     * else's field was for. Keyed by the source's display name, matched without regard to case:
     * Jira Cloud writes {@code Story Points} and Data Center writes {@code Story point estimate},
     * and a site can rename either.
     */
    private static final Map<String, String> REASONS = new LinkedHashMap<>(Map.of(
            "story points", "estimates here are hours; points have no conversion",
            "story point estimate", "estimates here are hours; points have no conversion",
            "sprint", "releases replace sprints (D6); this run kept nothing in their place",
            "iteration path", "releases replace sprints (D6); this run kept nothing in their place",
            "epic colour", "no equivalent",
            "epic color", "no equivalent",
            "flagged", "use a label or the Blocked status",
            "request type", "this is not a service desk",
            "time tracking", "time entries here belong to a person and a period"));

    /**
     * The sprint row's sentence when {@code labelPerSprint} was taken (D-h).
     *
     * <p>The row stays either way, and that is the decision: an option that made a loss disappear
     * from the list would make the list a function of what was chosen rather than of what was lost.
     * What changes is the sentence, so the report says what survived.
     */
    private static final String SPRINT_KEPT =
            "releases replace sprints (D6); each sprint was kept as a label, which carries no "
                    + "dates, capacity or board";

    /** For a field the source calls custom — the mockup's {@code Custom: "Squad"} row. */
    private static final String NO_CUSTOM_FIELDS = "no custom fields in this product";

    /** For a built-in of the source's that this product has no place for. */
    private static final String NO_EQUIVALENT = "no equivalent in this product";

    /**
     * The names a source gives the field whose values {@code labelPerSprint} keeps.
     *
     * <p>Two names for one concept, because {@link SourceField#name()} is <em>the source's own
     * vocabulary</em> and the two sources do not share it: Jira calls it {@code Sprint} and Azure
     * DevOps calls it {@code Iteration Path}, which is what {@code AzureDevOpsImportSource} reports
     * it under deliberately. Both land on {@code SourceIssue.sprintNames}, so both are kept as
     * labels when the option is taken — and a condition that named only Jira's spelling had an ADO
     * report saying {@code Iteration Path} had <em>"no equivalent in this product"</em> while its
     * values were in fact on the issues as labels. That is precisely the claim D-h exists to
     * prevent, so the set is here rather than inline: a third source adds its spelling once.
     */
    private static final Set<String> SPRINT_FIELD_NAMES = Set.of("sprint", "iteration path");

    private DroppedFields() {
    }

    /**
     * The dropped-field list for one snapshot, in the source's own field order.
     *
     * <p>Order is the source's rather than alphabetical, and it is stable, because the list is
     * persisted with a {@code position} and the report renders it in that order — a list that
     * reordered itself between the preview and the report would fail D-f's equality for a reason
     * that has nothing to do with what was dropped.
     *
     * @param labelPerSprint whether the run kept one label per sprint, which changes the
     *                       {@code Sprint} row's sentence and nothing else. The preview passes
     *                       {@code false}: the option is chosen on the mapping step, after the
     *                       preview has been read.
     */
    static List<DroppedFieldView> of(SourceProjectSnapshot snapshot, boolean labelPerSprint) {
        List<DroppedFieldView> dropped = new ArrayList<>();
        for (SourceField field : snapshot.fields()) {
            dropped.add(new DroppedFieldView(
                    displayName(field),
                    field.type(),
                    field.valueCount(),
                    reasonFor(field, labelPerSprint)));
        }
        return List.copyOf(dropped);
    }

    /**
     * {@code Custom: "Squad"} for a custom field and the bare name for a built-in.
     *
     * <p>The quotes are the mockup's and they earn their place: a field genuinely called
     * {@code Custom} would otherwise be indistinguishable from the prefix this adds.
     */
    private static String displayName(SourceField field) {
        return field.custom() ? "Custom: \"" + field.name() + "\"" : field.name();
    }

    private static String reasonFor(SourceField field, boolean labelPerSprint) {
        String key = field.name().toLowerCase(Locale.ROOT).strip();
        if (labelPerSprint && SPRINT_FIELD_NAMES.contains(key)) {
            return SPRINT_KEPT;
        }
        String known = REASONS.get(key);
        if (known != null) {
            return known;
        }
        return field.custom() ? NO_CUSTOM_FIELDS : NO_EQUIVALENT;
    }
}
