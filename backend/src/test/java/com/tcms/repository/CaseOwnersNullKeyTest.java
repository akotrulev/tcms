package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CaseOwners} must answer a map that tolerates a null key — the twin of
 * {@code com.tcms.execution.PeopleResolutionNullKeyTest}, which carries the full argument.
 *
 * <p>In short: the nothing-to-resolve branch must not return {@code Map.of()}, because
 * {@code Map.of().get(null)} throws by contract while the {@code HashMap} the other branch builds
 * answers null. A map whose null-key behaviour depends on whether the page happened to contain
 * anybody named is a 500 that only appears on the data nobody fixtures.
 *
 * <p>Two tests rather than one because both classes are package-private in different packages, and
 * because {@code execution} may not import {@code repository}.
 */
class CaseOwnersNullKeyTest {

    @Test
    @DisplayName("CaseOwners answers a null-tolerant map when there is nobody to resolve")
    void takesANullKey() {
        var resolved = new CaseOwners(null, null).resolve(UUID.randomUUID(), List.of());

        assertThatCode(() -> resolved.get(null)).doesNotThrowAnyException();
        assertThat(resolved.get(null)).isNull();
    }
}
