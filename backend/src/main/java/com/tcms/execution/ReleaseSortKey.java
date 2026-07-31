package com.tcms.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code test_run.release_sort_key} — the column {@code V1} says is <em>"computed on write from
 * release_version"</em>, computed on write.
 *
 * <h2>The failure this exists to prevent is silent, which is the whole point</h2>
 *
 * <p>V1's own comment: <em>"Without this, '1.10' sorts before '1.9' and every latest-release query
 * breaks SILENTLY (plan §6q)."</em> Text collation compares character by character, so
 * {@code "1.10" < "1.9"} — and the query that asks "what did the newest release do" answers about a
 * release from three months ago, with no error anywhere and a number on the screen that looks
 * exactly like the right one.
 *
 * <p>§38 mapped neither the column nor this class, deliberately: that slice performed no write that
 * could compute the value, and <em>"mapping a column nothing here maintains is how it comes to
 * disagree with the field it derives from"</em>. This is the slice that writes, so this is where it
 * joins.
 *
 * <h2>{@code %05d.%05d.%05d}, and what happens to everything that is not semver</h2>
 *
 * <p>Zero-padding to five digits makes text collation agree with numeric order up to 99999 per
 * component, which is four orders of magnitude past any real version number. A leading {@code v} is
 * stripped and anything after the patch component — {@code -rc.1}, {@code +build.7} — is appended
 * verbatim <em>after</em> the padded triple, so {@code 4.3.0-rc.1} sorts immediately before
 * {@code 4.3.0} exactly as semver requires, without this class having to implement pre-release
 * precedence.
 *
 * <p>A value that does not parse — {@code "nightly"}, {@code "2026-W31"} — <strong>falls back to
 * itself</strong>, which is V1's prescription and is better than the alternatives:
 *
 * <ul>
 *   <li>Refusing the release version would make a valid, common practice (dated or named builds)
 *       impossible to record.</li>
 *   <li>Storing null would put every non-semver run in one unordered bucket that {@code ORDER BY}
 *       places arbitrarily, and {@code test_run_release_idx} leads on this column.</li>
 * </ul>
 *
 * <p>Falling back to the raw string means non-semver releases sort among themselves by collation —
 * which for a dated build is <em>correct</em>, and for a named one is at least stable. Padded
 * numerics sort before raw text because digits precede letters, so a semver release and a
 * {@code nightly} do not interleave.
 */
final class ReleaseSortKey {

    /**
     * Major, minor, patch, and whatever follows. Minor and patch are optional: {@code "4"} and
     * {@code "4.3"} are things people type into a release field, and treating them as unparseable
     * would put the most common shorthand into the text bucket.
     */
    private static final Pattern SEMVER =
            Pattern.compile("^v?(\\d{1,5})(?:\\.(\\d{1,5}))?(?:\\.(\\d{1,5}))?(.*)$");

    private ReleaseSortKey() {
    }

    /**
     * @param releaseVersion the run's release, or null
     * @return the sort key, or null when there is no release — a run with no release version has no
     *         position in a release ordering, and inventing {@code "00000.00000.00000"} for it would
     *         file every ad-hoc run under version zero, ahead of the real ones.
     */
    static String of(String releaseVersion) {
        if (releaseVersion == null || releaseVersion.isBlank()) {
            return null;
        }
        String value = releaseVersion.strip();

        Matcher matched = SEMVER.matcher(value);
        if (!matched.matches()) {
            return value;
        }
        String suffix = matched.group(4);
        // A suffix that is not a pre-release or build marker means the numeric prefix was
        // coincidental — "1.2 patch set" is not version 1.2.0 of anything, and padding it would
        // sort it among real releases. Only the semver separators continue a version.
        if (!suffix.isEmpty() && !suffix.startsWith("-") && !suffix.startsWith("+")) {
            return value;
        }
        return pad(matched.group(1)) + "." + pad(matched.group(2)) + "." + pad(matched.group(3))
                + suffix;
    }

    /** An absent component is zero: {@code 4.3} and {@code 4.3.0} are the same release. */
    private static String pad(String component) {
        return String.format("%05d", component == null ? 0 : Integer.parseInt(component));
    }
}
