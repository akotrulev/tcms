package com.tcms.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code test_run.release_sort_key} — pure string logic, so no Spring context and no Postgres.
 *
 * <p>Until this class was written, every property of the key was observed only through
 * {@code RunCreateEndpointTest}, which asserts a run is created rather than what it sorts as. The
 * defect V1's comment names is <strong>silent</strong>: {@code "1.10" < "1.9"} under text collation,
 * so the latest-release query answers about an older release with no error anywhere. A test that
 * asserts the padded string is right is worth less than one that asserts the <em>ordering</em> is,
 * so {@link #tenSortsAfterNine} compares the two keys and the two raw strings side by side — it is
 * the test this class exists for and the rest pin the parsing around it.
 *
 * <p><strong>Two of these tests were §150's pins of behaviour the class javadoc described
 * wrongly</strong> —
 * {@link #preReleaseSortsBeforeItsRelease} — repaired in §151 and asserted the right way round
 * here — and {@link #datedBuildParsesRatherThanFallingBack}, whose javadoc claim §151 corrected to
 * match the behaviour. Neither was observable from an endpoint test, which is the point.
 */
@DisplayName("ReleaseSortKey — the padding that makes text collation agree with version order")
class ReleaseSortKeyTest {

    @Test
    @DisplayName("THE invariant: 1.10 sorts after 1.9 as a key, though not as raw text")
    void tenSortsAfterNine() {
        String nine = ReleaseSortKey.of("1.9");
        String ten = ReleaseSortKey.of("1.10");

        // The second assertion is the reason the first one matters: without it, a reader could
        // conclude the padding is decoration. Raw collation genuinely gets this backwards.
        assertThat(ten).isGreaterThan(nine);
        assertThat("1.10").isLessThan("1.9");
    }

    @ParameterizedTest
    @CsvSource({
        "1.9,        00001.00009.00000.1",
        "1.10,       00001.00010.00000.1",
        "4.3.0,      00004.00003.00000.1",
        "v4.3.0,     00004.00003.00000.1",
        "0.0.1,      00000.00000.00001.1",
        "99999.99999.99999, 99999.99999.99999.1",
    })
    @DisplayName("a semver release pads each component to five digits, and a leading v is dropped")
    void padsSemver(String release, String expected) {
        assertThat(ReleaseSortKey.of(release)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "4,     00004.00000.00000.1",
        "4.3,   00004.00003.00000.1",
    })
    @DisplayName("shorthand is a release: an absent component is zero, not unparseable")
    void shorthandIsAVersion(String release, String expected) {
        // 4.3 and 4.3.0 are the same release, and the shorthand is what people type into the field.
        // Routing it to the text bucket would put the most common spelling out of numeric order.
        assertThat(ReleaseSortKey.of(release)).isEqualTo(expected);
    }

    @Test
    @DisplayName("THE §151 invariant: a pre-release sorts BEFORE the release it precedes")
    void preReleaseSortsBeforeItsRelease() {
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isEqualTo("00004.00003.00000.0-rc.1");
        assertThat(ReleaseSortKey.of("4.3.0")).isEqualTo("00004.00003.00000.1");

        // §150 pinned this assertion INVERTED, as the class then behaved: the release's key was a
        // strict prefix of its pre-release's, a prefix collates first, and so "what did the newest
        // release do" answered about the release candidate. Same silent wrong-answer shape as
        // "1.10" < "1.9", arriving through the suffix rather than through the padding.
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isLessThan(ReleaseSortKey.of("4.3.0"));
    }

    @Test
    @DisplayName("the precedence flag is a digit because a ~ sentinel is a no-op under libc collation")
    void precedenceFlagIsADigit() {
        // The load-bearing property, and the reason the fix is not the obvious one. Postgres orders
        // this column, and the instance's collation is en_US.utf8 under the libc provider, which
        // weights punctuation below letters — so Debian's trailing '~' compares BELOW the 'r' of
        // "rc" and changes nothing. Measured: it passes under C and under ICU and fails under the
        // one collation actually in use, which is how it would have shipped green.
        //
        // A digit compares at the primary level in all three, so this assertion is what says the
        // ordering is a property of the key and not of how a cluster happened to be initialised.
        assertThat(ReleaseSortKey.of("4.3.0")).endsWith(".1");
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).contains(".0-");
    }

    @Test
    @DisplayName("build metadata is not a pre-release: 4.3.0+build.7 flags as the release")
    void buildMarkerIsCarriedAndIsNotAPreRelease() {
        // Semver excludes build metadata from precedence entirely, so it must not take the
        // pre-release flag — otherwise a tagged build of 4.3.0 would sort below plain 4.3.0 and
        // below every one of its own release candidates.
        assertThat(ReleaseSortKey.of("4.3.0+build.7")).isEqualTo("00004.00003.00000.1+build.7");
        assertThat(ReleaseSortKey.of("4.3.0+build.7"))
                .isGreaterThan(ReleaseSortKey.of("4.3.0-rc.9"));
    }

    @Test
    @DisplayName("a release candidate ordering, end to end")
    void releaseCandidatesOrderBeforeTheRelease() {
        // The sequence an actual release goes through, asserted as one chain rather than as pairs.
        assertThat(ReleaseSortKey.of("4.2.9")).isLessThan(ReleaseSortKey.of("4.3.0-alpha.1"));
        assertThat(ReleaseSortKey.of("4.3.0-alpha.1")).isLessThan(ReleaseSortKey.of("4.3.0-rc.1"));
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isLessThan(ReleaseSortKey.of("4.3.0-rc.2"));
        assertThat(ReleaseSortKey.of("4.3.0-rc.2")).isLessThan(ReleaseSortKey.of("4.3.0"));
        assertThat(ReleaseSortKey.of("4.3.0")).isLessThan(ReleaseSortKey.of("4.3.1"));
    }

    @Test
    @DisplayName("KNOWN LIMIT: pre-releases compare as text, so rc.10 sorts before rc.9")
    void preReleasePrecedenceAmongPreReleasesIsNotImplemented() {
        // Pinned as a known limit, not as a defect being ignored. This is the "1.10" < "1.9" shape
        // one level down — but confined to the candidates of ONE release, where §150's finding was
        // between a release and its candidate. Fixing it means padding numeric dot-segments of the
        // suffix, which is a second format change and a second backfill; §151 deliberately does one.
        assertThat(ReleaseSortKey.of("4.3.0-rc.10")).isLessThan(ReleaseSortKey.of("4.3.0-rc.9"));

        // What holds regardless, and is the property that matters between releases: both are still
        // below the release itself.
        assertThat(ReleaseSortKey.of("4.3.0-rc.10")).isLessThan(ReleaseSortKey.of("4.3.0"));
        assertThat(ReleaseSortKey.of("4.3.0-rc.9")).isLessThan(ReleaseSortKey.of("4.3.0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nightly", "1.2 patch set", "release candidate", "123456.0.0"})
    @DisplayName("anything that is not a version falls back to itself rather than to null")
    void nonSemverFallsBackToItself(String release) {
        // V1's prescription. Null would put every ad-hoc build in one bucket ORDER BY places
        // arbitrarily, on a column test_run_release_idx leads on. "1.2 patch set" is the
        // interesting row: the numeric prefix is coincidental, and padding it would file a note
        // among real releases. "123456.0.0" is the five-digit ceiling seen from above.
        assertThat(ReleaseSortKey.of(release)).isEqualTo(release);
    }

    @Test
    @DisplayName("padded numerics and text do not interleave")
    void numericsSortAheadOfText() {
        // Digits precede letters in every collation this runs under, so a semver release and a
        // named build stay in two blocks rather than mixing.
        assertThat(ReleaseSortKey.of("99999.99999.99999")).isLessThan(ReleaseSortKey.of("nightly"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("no release version means no position in a release ordering")
    void absentReleaseHasNoKey(String release) {
        // Not "00000.00000.00000": that would file every ad-hoc run under version zero, ahead of
        // the real ones, which is a wrong answer rather than an absent one.
        assertThat(ReleaseSortKey.of(release)).isNull();
    }

    @Test
    @DisplayName("surrounding whitespace is stripped before parsing, not after")
    void stripsBeforeParsing() {
        // A pasted release string with a trailing newline is otherwise unparseable text, and would
        // fall out of numeric order for a reason nobody could see in the UI.
        assertThat(ReleaseSortKey.of("  4.3.0\n")).isEqualTo("00004.00003.00000.1");
    }

    @Test
    @DisplayName("a dated build parses as a padded year plus its suffix — NOT as raw text")
    void datedBuildParsesRatherThanFallingBack() {
        // Pinned because the class javadoc offers "2026-W31" as an example of the text fallback
        // and it is not one: the year matches the major component and "-W31" is accepted as a
        // pre-release suffix. The resulting order is right — dated builds sort by date — but the
        // value is not the raw string, and a caller reading the javadoc would expect it to be.
        assertThat(ReleaseSortKey.of("2026-W31")).isEqualTo("02026.00000.00000.0-W31");
        assertThat(ReleaseSortKey.of("2026-07-30")).isLessThan(ReleaseSortKey.of("2026-08-01"));
    }
}
