package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ExecutionPeople} must answer a map that tolerates a null key.
 *
 * <h2>Why this is a test and not a code comment</h2>
 *
 * <p>The resolution short-circuits when there is nobody to resolve, because an empty collection
 * bound into SQL {@code IN ()} is a syntax error. The obvious value to return there is
 * {@code Map.of()} — and {@code Map.of().get(null)} throws {@link NullPointerException} by
 * contract, while the {@code HashMap} the non-empty branch builds answers null.
 *
 * <p>Since §76 the short-circuit lives one layer down, in {@code project.PeopleDirectory}'s
 * implementation, and this class is what stops that move from quietly relaxing the property: the
 * stub below returns the <strong>worst</strong> case, an immutable {@code Map.of()}, and the
 * assertion is that what comes back out is still null-tolerant. A wrapper that passed the
 * directory's map straight through would fail here.
 *
 * <p>That difference makes the failure <em>data-dependent</em>: a run with one named executor works
 * and a run with none is a 500, from the same code path. That is why it reached a running endpoint
 * (handoff §38) instead of being caught by the tests covering the ordinary case. Every call site
 * already null-checks before looking an id up, so nothing is currently broken — this pins the
 * property so a future call site that forgets gets a null rather than a stack trace.
 *
 * <p>{@code repository.CaseOwners} has the same shape and the same fix; its twin of this test lives
 * in that package because both the class and the method are package-private, which is also why
 * these two tests cannot be one.
 *
 * <p>No Spring context and no database: the membership repository is never touched on this path,
 * which is precisely the branch that was wrong, and passing null for it is the assertion that it
 * is not.
 */
class PeopleResolutionNullKeyTest {

    @Test
    @DisplayName("ExecutionPeople answers a null-tolerant map when there is nobody to resolve")
    void takesANullKey() {
        var resolved = new ExecutionPeople(null, (companyId, userIds) -> Map.of())
                .resolve(UUID.randomUUID(), List.of());

        assertThatCode(() -> resolved.get(null)).doesNotThrowAnyException();
        assertThat(resolved.get(null)).isNull();
    }
}
