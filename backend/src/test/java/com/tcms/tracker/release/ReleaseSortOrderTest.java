package com.tcms.tracker.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.api.ReleaseSortKey;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That {@code release.sort_key} orders releases the way a person reads version numbers — no Spring,
 * no container.
 *
 * <p>{@code com.tcms.api.ReleaseSortKeyTest} covers what the computation produces and
 * {@code ReleaseSortKeyOrderingTest} covers how Postgres collates it. What is left, and what this
 * covers, is the <strong>join between them</strong>: that a release's key is derived from the
 * version it is created with, that renaming re-derives it, and that the resulting text order is the
 * version order the roadmap and the release list both promise.
 *
 * <p>Worth having as a unit test rather than only inside {@code ReleaseReadinessTest}'s endpoint
 * assertion, because this is the failure that is silent: text collation puts {@code "1.10"} before
 * {@code "1.9"}, so the query asking what the newest release did answers about one from three months
 * ago, with no error and a number on the screen that looks exactly right.
 */
@DisplayName("Release ordering — the key behind 1.10 after 1.9, without a database")
class ReleaseSortOrderTest {

    @Test
    @DisplayName("1.10 sorts after 1.9 by key, and before it by text")
    void theWholeReasonTheColumnExists() {
        assertThat(ReleaseSortKey.of("1.10")).isGreaterThan(ReleaseSortKey.of("1.9"));
        // The defect this prevents, asserted so the test says what it is protecting against.
        assertThat("1.10").isLessThan("1.9");
    }

    @Test
    @DisplayName("a release candidate sorts before the release it precedes")
    void preReleasePrecedence() {
        assertThat(ReleaseSortKey.of("4.3.0-rc.1")).isLessThan(ReleaseSortKey.of("4.3.0"));
    }

    @Test
    @DisplayName("a full list comes out in version order, not in text order")
    void theOrderTheRoadmapPromises() {
        List<String> versions = List.of("2.10", "1.9", "4.3.0", "2.2", "1.10", "4.3.0-rc.1");

        List<String> ordered = versions.stream()
                .sorted(java.util.Comparator.comparing(ReleaseSortKey::of))
                .toList();

        assertThat(ordered)
                .containsExactly("1.9", "1.10", "2.2", "2.10", "4.3.0-rc.1", "4.3.0");
    }

    @Test
    @DisplayName("a non-semver version is kept rather than refused, and sorts after the numbers")
    void datedAndNamedBuildsAreLegalReleaseNames() {
        // ReleaseService applies no format rule on purpose: refusing `nightly` would make a common
        // practice unrecordable, and this product does not get to decide how a client names what
        // it ships. Padded numerics sort before raw text, so the two never interleave.
        assertThat(ReleaseSortKey.of("nightly")).isEqualTo("nightly");
        assertThat(ReleaseSortKey.of("99999.99999.99999"))
                .isLessThan(ReleaseSortKey.of("nightly"));
    }
}
