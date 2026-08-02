package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CaseOwners} must answer a map that tolerates a null key — the twin of
 * {@code com.tcms.execution.PeopleResolutionNullKeyTest}, which carries the full argument.
 *
 * <p>In short: what comes back must not be {@code Map.of()}, because {@code Map.of().get(null)}
 * throws by contract while a {@code HashMap} answers null. A map whose null-key behaviour depends
 * on whether the page happened to contain anybody named is a 500 that only appears on the data
 * nobody fixtures. The stub directory below hands back the worst case on purpose — since §76 the
 * short-circuit belongs to {@code project.PeopleDirectory}, and this asserts the mapping layer
 * above it does not pass an immutable empty map through.
 *
 * <p>Two tests rather than one because both classes are package-private in different packages, and
 * because {@code execution} may not import {@code repository}.
 */
class CaseOwnersNullKeyTest {

    @Test
    @DisplayName("CaseOwners answers a null-tolerant map when there is nobody to resolve")
    void takesANullKey() {
        var resolved = new CaseOwners((companyId, userIds) -> Map.of())
                .resolve(UUID.randomUUID(), List.of());

        assertThatCode(() -> resolved.get(null)).doesNotThrowAnyException();
        assertThat(resolved.get(null)).isNull();
    }
}
