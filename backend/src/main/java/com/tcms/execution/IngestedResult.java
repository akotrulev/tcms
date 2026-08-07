package com.tcms.execution;

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
 * @param durationMs how long it took, or null when the dialect did not say. <strong>Not written
 *                   anywhere yet</strong> — {@code test_execution} has no duration column and adding
 *                   one is a schema decision with a reporting consumer behind it, not a side effect
 *                   of a parser. Carried because the parse is the only place it is available, and a
 *                   second pass over the report to recover it later is worse than an unused field.
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
     */
    public String fullName() {
        return className == null || className.isBlank() ? name : className + "#" + name;
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
