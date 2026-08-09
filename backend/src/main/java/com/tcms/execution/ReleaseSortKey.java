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
 * <h2>{@code %05d.%05d.%05d.<precedence>}, and what happens to everything that is not semver</h2>
 *
 * <p>Zero-padding to five digits makes text collation agree with numeric order up to 99999 per
 * component, which is four orders of magnitude past any real version number. A leading {@code v} is
 * stripped and anything after the patch component — {@code -rc.1}, {@code +build.7} — is appended
 * verbatim after the triple.
 *
 * <h3>The fourth component is a precedence flag, and it is the whole of §151</h3>
 *
 * <p>Semver's rule is that <em>a pre-release has LOWER precedence than the release it precedes</em>:
 * {@code 4.3.0-rc.1} comes before {@code 4.3.0}. Until §151 this class claimed to get that right by
 * appending the suffix and letting collation do the rest, and <strong>it did the opposite</strong> —
 * {@code 00004.00003.00000} is a strict <em>prefix</em> of {@code 00004.00003.00000-rc.1}, a prefix
 * collates first, so the release candidate read as the newer of the two. That is the same silent
 * wrong-answer shape as {@code "1.10" < "1.9"} above, arriving through the suffix instead of through
 * the padding. §150 found it, pinned it as it behaved, and named the repair a slice of its own
 * because the column is written on three tables and every stored row held the old format.
 *
 * <p>So a fourth component is appended before the suffix: <strong>{@code 0} when a pre-release
 * suffix is present, {@code 1} when it is not.</strong>
 *
 * <pre>
 *   4.3.0-rc.1     00004.00003.00000.0-rc.1     pre-release  → flag 0
 *   4.3.0          00004.00003.00000.1          the release  → flag 1
 *   4.3.0+build.7  00004.00003.00000.1+build.7  build metadata is not a pre-release
 * </pre>
 *
 * <h3>Why a DIGIT and not the {@code ~} every other version scheme uses</h3>
 *
 * <p>The obvious fix is Debian's: a sentinel character that sorts above everything, appended to the
 * release so the pre-release's letters fall below it. <strong>Measured against this deployment's
 * database, it does nothing at all.</strong> Postgres orders this column, not Java, and the
 * instance's collation is {@code en_US.utf8} under the libc provider — glibc's multi-level
 * collation, which weights punctuation below letters, so {@code '…-rc.1' < '…~'} is
 * <em>false</em> and the ordering is exactly as broken as before.
 *
 * <p>What makes that worth a paragraph rather than a footnote is that the sentinel <em>passes</em>
 * under {@code C} and under ICU, and fails only under the collation actually in use — so the
 * obvious fix, checked on a differently-initialised box, would have been a silent no-op shipped with
 * a green test. A digit is a strong character in every collation: it compares at the primary level
 * under libc, C and ICU alike, which is what makes this key's ordering a property of the key rather
 * than of how a given cluster was initialised.
 *
 * <p><strong>What this still does not implement is pre-release precedence among pre-releases.</strong>
 * Two suffixes are compared as text, so {@code -rc.10} sorts before {@code -rc.9} — the very defect
 * this class exists to prevent, one level down and confined to a single release's candidates.
 * Deliberately out of scope for §151: it needs numeric-identifier-aware comparison of dot-separated
 * suffix segments, which is a second format change and a second backfill, and the ordering it gets
 * wrong is within one release rather than between releases. {@code ReleaseSortKeyTest} pins it so it
 * is a known limit rather than a surprise.
 *
 * <p>A value that does not parse — {@code "nightly"} — <strong>falls back to itself</strong>, which
 * is V1's prescription and is better than the alternatives:
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
 *
 * <p><strong>{@code "2026-W31"} is NOT an example of that fallback</strong>, though this javadoc
 * offered it as one until §151. It parses: {@code 2026} matches the major component, the optional
 * minor and patch do not match, and {@code -W31} is accepted as a pre-release suffix — so the key is
 * {@code 02026.00000.00000.0-W31}. The resulting <em>order</em> is right, and better than the
 * fallback would have been, because dated builds then sort by date. The documented <em>value</em>
 * was wrong, and a caller reading this paragraph would have expected the raw string back. Kept as it
 * behaves, described as it behaves.
 */
final class ReleaseSortKey {

    /**
     * Major, minor, patch, and whatever follows. Minor and patch are optional: {@code "4"} and
     * {@code "4.3"} are things people type into a release field, and treating them as unparseable
     * would put the most common shorthand into the text bucket.
     */
    private static final Pattern SEMVER =
            Pattern.compile("^v?(\\d{1,5})(?:\\.(\\d{1,5}))?(?:\\.(\\d{1,5}))?(.*)$");

    /**
     * The fourth component: {@code 0} for a pre-release, {@code 1} for the release it precedes. A
     * digit rather than a punctuation sentinel because Postgres orders this column under a libc
     * collation that weights punctuation below letters — see the class javadoc, which records the
     * measurement.
     */
    private static final String PRE_RELEASE = "0";

    private static final String RELEASED = "1";

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
        // The flag goes BETWEEN the triple and the suffix, so that a release's key is never a
        // prefix of its own pre-release's. Carrying the suffix verbatim after it keeps two
        // candidates for one release distinct and adjacent.
        return pad(matched.group(1)) + "." + pad(matched.group(2)) + "." + pad(matched.group(3))
                + "." + precedenceOf(suffix) + suffix;
    }

    /**
     * Build metadata ({@code +build.7}) is not a pre-release: semver excludes it from precedence
     * entirely, so {@code 4.3.0+build.7} is the release and flags as one.
     */
    private static String precedenceOf(String suffix) {
        return suffix.startsWith("-") ? PRE_RELEASE : RELEASED;
    }

    /** An absent component is zero: {@code 4.3} and {@code 4.3.0} are the same release. */
    private static String pad(String component) {
        return String.format("%05d", component == null ? 0 : Integer.parseInt(component));
    }
}
