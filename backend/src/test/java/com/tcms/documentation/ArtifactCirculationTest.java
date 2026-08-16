package com.tcms.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.tenancy.CompanyContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The status endpoint's rule, without a database — <strong>the half the endpoint test structurally
 * cannot reach.</strong>
 *
 * <p>{@code ArtifactEndpointTest} proves the wiring: that the gate consults the row's scope, that
 * the refusal has the shape a form can render, that the audit row lands. What it cannot do cheaply
 * is be <em>total</em>, because each of its cases costs a Spring context, a container and a seeded
 * persona — so the values nobody thought to send are the values nobody sends. §208's finding one
 * module over, applied before it could become one here: an assertion per interesting input records
 * which inputs somebody thought about and says nothing about the next one.
 *
 * <p>Two rules are pinned, and they are the same rule from either side. {@code parseCirculation}
 * refuses {@code SUPERSEDED} as a request value, and {@link Artifact#circulate} refuses it as an
 * argument — because that state holds exactly when {@code superseded_by_id} names a replacement,
 * which is a fact about another row rather than a judgement about this one.
 */
@DisplayName("Archiving an artifact — the two values, and the third that is not one")
class ArtifactCirculationTest {

    private static final UUID COMPANY = UUID.fromString("ac000000-0000-4000-8000-00000000000a");
    private static final UUID ACTOR = UUID.fromString("ac000000-0000-4000-8000-000000000001");
    private static final UUID REPLACEMENT = UUID.fromString("ac000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Nested
    @DisplayName("What the endpoint accepts as a status")
    class Parsing {

        @Test
        @DisplayName("the two circulation values, in the spelling the contract declares")
        void theTwoValues() {
            assertThat(ArtifactWriteService.parseCirculation("ACTIVE"))
                    .isEqualTo(ArtifactStatus.ACTIVE);
            assertThat(ArtifactWriteService.parseCirculation("ARCHIVED"))
                    .isEqualTo(ArtifactStatus.ARCHIVED);
        }

        @Test
        @DisplayName("case and surrounding space are forgiven — a client is not a shell")
        void lenientAboutShape() {
            assertThat(ArtifactWriteService.parseCirculation("  archived "))
                    .isEqualTo(ArtifactStatus.ARCHIVED);
        }

        /**
         * <strong>THE assertion of this file.</strong> {@code SUPERSEDED} is a real member of the
         * enum, spelled correctly, and it is refused — so this cannot be satisfied by a lookup over
         * {@code values()}, which is exactly the implementation somebody will propose.
         */
        @Test
        @DisplayName("SUPERSEDED is refused, because nothing chooses it")
        void supersededIsNotACirculationValue() {
            assertThat(ArtifactWriteService.parseCirculation("SUPERSEDED")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "DRAFT", "PUBLISHED", "DELETED", "active,archived"})
        @DisplayName("anything else is refused rather than guessed at")
        void nothingElse(String candidate) {
            assertThat(ArtifactWriteService.parseCirculation(candidate)).isNull();
        }

        @Test
        @DisplayName("a missing status is refused and not a crash")
        void nullIsRefused() {
            assertThat(ArtifactWriteService.parseCirculation(null)).isNull();
        }
    }

    @Nested
    @DisplayName("What the row itself allows")
    class Moving {

        @ParameterizedTest
        @EnumSource(value = ArtifactStatus.class, names = {"ACTIVE", "ARCHIVED"})
        @DisplayName("both circulation values are reachable, and stamp the actor")
        void circulates(ArtifactStatus target) {
            Artifact artifact = registered();

            artifact.circulate(target, ACTOR, NOW);

            assertThat(artifact.getStatus()).isEqualTo(target);
            assertThat(artifact.getUpdatedBy()).isEqualTo(ACTOR);
            assertThat(artifact.getUpdatedAt()).isEqualTo(NOW);
            assertThat(artifact.getSupersededById())
                    .as("archiving names no replacement — the two states are reached by "
                            + "different acts and only one of them writes a pointer")
                    .isNull();
        }

        @Test
        @DisplayName("SUPERSEDED is refused here too, so a second parser cannot let it through")
        void supersededIsNotAMove() {
            Artifact artifact = registered();

            assertThatThrownBy(() -> artifact.circulate(ArtifactStatus.SUPERSEDED, ACTOR, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(artifact.getStatus())
                    .as("a refused move leaves the row where it was")
                    .isEqualTo(ArtifactStatus.ACTIVE);
        }

        @Test
        @DisplayName("superseding writes the status and the pointer together")
        void supersessionWritesBoth() {
            Artifact artifact = registered();

            artifact.supersededBy(REPLACEMENT, ACTOR, NOW);

            assertThat(artifact.getStatus()).isEqualTo(ArtifactStatus.SUPERSEDED);
            assertThat(artifact.getSupersededById())
                    .as("the status and the pointer are one fact with two spellings — a "
                            + "SUPERSEDED row naming nothing is a claim the register cannot back")
                    .isEqualTo(REPLACEMENT);
            assertThat(artifact.getUpdatedBy()).isEqualTo(ACTOR);
        }
    }

    /**
     * A freshly registered row.
     *
     * <p>The scope guard is what the factory reads its company from, and opening it here is the
     * whole of this file's setup — {@code CompanyContext} is a {@code ThreadLocal}, so a context-free
     * test can hold one without a container. The company is never a parameter, per ERD §0.1.
     */
    private static Artifact registered() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            return Artifact.register(
                    UUID.fromString("ac001000-0000-4000-8000-000000000001"),
                    UUID.fromString("ac002000-0000-4000-8000-000000000001"),
                    ArtifactScope.PROJECT,
                    null,
                    ArtifactKind.EXTERNAL_URL,
                    "Compliance",
                    "Test strategy",
                    null,
                    null,
                    "https://docs.example.test/strategy",
                    null,
                    null,
                    // Not a sign-off: this file drives circulation, and a write-once row would
                    // refuse the acts it is here to observe.
                    false,
                    ACTOR,
                    // No key: this fixture is a row, not a request. `V59`'s column is null for
                    // every artifact registered by a caller that sent no header, which is what a
                    // hand-built row is.
                    null,
                    NOW);
        }
    }
}
