package com.tcms.execution;

import com.tcms.api.AutomationKeys;
import java.util.Locale;

/**
 * One test the CI runner reported, normalized out of whichever dialect it arrived in.
 *
 * <p><strong>This is the whole of what the four parsers agree on</strong>, and keeping it this small
 * is deliberate. Every format carries more — Allure has labels and attachments, Cucumber has a step
 * list and tags, TestNG has groups — and every one of those would be a field the other three parsers
 * fill with null. The matcher needs a name and the writer needs an outcome; a field that only one
 * dialect can populate belongs to the slice that has a consumer for it.
 *
 * @param className  the enclosing class, suite or feature, or null when the dialect has no such
 *                   notion. Kept separate from {@link #name} rather than pre-joined because
 *                   {@link #fullName()} is one of the three things the ladder looks up and a parser
 *                   that joined them with its own separator would make the learned mappings of two
 *                   runners disagree about the same test.
 * @param name       the test's own name. Never null — a result naming nothing cannot be matched,
 *                   and the parsers drop such entries rather than carry them to a lookup that
 *                   cannot succeed.
 * @param outcome    what the runner said, as one of four values (see {@link IngestedOutcome}).
 * @param durationMs how long it took, or null when the dialect did not say. <del>Not written
 *                   anywhere yet — {@code test_execution} has no duration column and adding one is
 *                   a schema decision with a reporting consumer behind it, not a side effect of a
 *                   parser.</del> → <strong>superseded 2026-08-22 (§244).</strong> The premise was
 *                   wrong on its first clause and right on its second: {@code duration_ms} was
 *                   there all along ({@code V1__initial_schema.sql}), read in five places and
 *                   summed into the run summary — and no writer ever filled it, so every one of
 *                   those reads answered null. {@code IngestionService#write} now passes this
 *                   value to {@code TestExecution#record}. Carrying it unused was the right call:
 *                   the parse is the only place it is available.
 * @param message    the failure text, stack trace or skip reason, or null. Becomes the execution's
 *                   comment, which is the one place an ingested result can say <em>why</em>.
 */
public record IngestedResult(
        String className, String name, IngestedOutcome outcome, Long durationMs, String message) {

    /**
     * {@code className#name}, or just the name when there is no class.
     *
     * <p><strong>The separator is {@code #} and it is a shared convention, not this record's
     * choice.</strong> §6c names {@code automationKey} as "FQCN#method", so a case whose key was
     * written by a human reading their own test source matches a report parsed here only if both
     * spell the join the same way. Changing it silently breaks every stored key at once, with no
     * error anywhere — every test simply stops matching.
     *
     * <p><strong>§255 T6 gave that convention a home.</strong> The {@code .feature} import in
     * {@code repository} writes the same string when it keys a case, and the two modules may not
     * import each other, so the join moved to {@link AutomationKeys} — a foundation module both can
     * reach. This method is now a call rather than a second spelling of it, which is what makes the
     * paragraph above checkable instead of merely true today.
     */
    public String fullName() {
        return AutomationKeys.join(className, name);
    }

    /**
     * The form the learned-mapping table is keyed on — {@code automation_key_mapping
     * .normalized_test_name}.
     *
     * <p>Lower-cased, trimmed, and internal whitespace collapsed to one space. Nothing more
     * aggressive: stripping punctuation would collapse {@code Order#cancel} and {@code Order cancel}
     * into one key, and a mapping table whose key merges two different tests binds a human's
     * confirmation to the wrong one of them.
     *
     * <p>{@link Locale#ROOT}, not the default locale. Under a Turkish default {@code toLowerCase}
     * maps {@code I} to a dotless {@code ı}, so the same report normalizes differently depending on
     * where the server runs — and the mapping written on one host stops matching on another.
     */
    public String normalizedName() {
        return normalize(fullName());
    }

    /** {@link #normalizedName()}'s rule, exposed so the mapping writer normalizes identically. */
    public static String normalize(String testName) {
        return testName == null ? null : testName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
