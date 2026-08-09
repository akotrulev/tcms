package com.tcms.execution;

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
 * <p><strong>Two of these tests pin behaviour the class javadoc describes wrongly</strong> —
 * {@link #preReleaseRidesAfterTheTriple} and {@link #datedBuildParsesRatherThanFallingBack}. Each
 * says so at the assertion, with why it is pinned rather than fixed. Neither was observable from an
 * endpoint test, which is the point.
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
        "1.9,        00001.00009.00000",
        "1.10,       00001.00010.00000",
        "4.3.0,      00004.00003.00000",
        "v4.3.0,     00004.00003.00000",
        "0.0.1,      00000.00000.00001",
        "99999.99999.99999, 99999.99999.99999",
    })
    @DisplayName("a semver release pads each component to five digits, and a leading v is dropped")
    void padsSemver(String release, String expected) {
        assertThat(ReleaseSortKey.of(release)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "4,     00004.00000.00000",
        "4.3,   00004.00003.00000",
    })
    @DisplayName("shorthand is a release: an absent component is zero, not unparseable")
    void shorthandIsAVersion(String release, String expected) {
        // 4.3 and 4.3.0 are the same release, and the shorthand is what people type into the field.
        // Routing it to the text bucket would put the most common spelling out of numeric order.
        assertThat(ReleaseSortKey.of(release)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a pre-release rides after the padded triple — and therefore sorts AFTER 4.3.0")
    void preReleaseRidesAfterTheTriple() {
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isEqualTo("00004.00003.00000-rc.1");

        // PINNING A DEFECT, DELIBERATELY. The class javadoc claims "4.3.0-rc.1 sorts immediately
        // before 4.3.0 exactly as semver requires", and it does not: the release's key is a
        // strict PREFIX of its pre-release's, and a prefix collates first. So "what did the newest
        // release do" answers about the release candidate — which is precisely the silent
        // wrong-answer shape V1's comment says this column exists to prevent, arriving through the
        // suffix rather than through the padding.
        //
        // Not fixed here, and the reason is not size: release_sort_key is written on three tables
        // (test_run, test_execution, execution_fact), ORDER BYed by Postgres rather than by Java,
        // and every existing row already holds the current format — so the repair is a format
        // change plus a backfill migration plus a decision about which collation the separator is
        // being chosen under. That is a slice, and it needs the handoff entry a slice gets.
        // This assertion is what makes it impossible to fix by accident and unnoticeable no longer.
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isGreaterThan(ReleaseSortKey.of("4.3.0"));
    }

    @Test
    @DisplayName("a build marker is carried verbatim after the padded triple")
    void buildMarkerIsCarried() {
        assertThat(ReleaseSortKey.of("4.3.0+build.7")).isEqualTo("00004.00003.00000+build.7");
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
        assertThat(ReleaseSortKey.of("  4.3.0\n")).isEqualTo("00004.00003.00000");
    }

    @Test
    @DisplayName("a dated build parses as a padded year plus its suffix — NOT as raw text")
    void datedBuildParsesRatherThanFallingBack() {
        // Pinned because the class javadoc offers "2026-W31" as an example of the text fallback
        // and it is not one: the year matches the major component and "-W31" is accepted as a
        // pre-release suffix. The resulting order is right — dated builds sort by date — but the
        // value is not the raw string, and a caller reading the javadoc would expect it to be.
        assertThat(ReleaseSortKey.of("2026-W31")).isEqualTo("02026.00000.00000-W31");
        assertThat(ReleaseSortKey.of("2026-07-30")).isLessThan(ReleaseSortKey.of("2026-08-01"));
    }
}
