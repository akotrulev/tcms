package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link LifecycleLadder#permissionFor} over <strong>all sixteen pairs</strong>, which is the test
 * the class header has described since §75 — <em>"a test can enumerate all sixteen pairs without a
 * database"</em> — and which no file has ever been.
 *
 * <p>That absence is why this class exists at all, and it is §205's shape: the gate had coverage,
 * but only at the two edges somebody thought to drive over HTTP. {@code CaseBulkEndpointTest} and
 * {@code SuiteWriteEndpointTest} between them assert five of the sixteen, each through a container,
 * a Spring context, a seeded persona and a real row — so the eleven nobody named cost nothing to
 * leave out and were invisible from inside either file. A pure unit test over the whole table is
 * ~40 lines and runs in milliseconds, and the reason to have both is that the endpoint tests assert
 * the <em>wiring</em> (that each caller passes its own {@code Keys} and the row's stored status)
 * while this one asserts the <em>rule</em>.
 *
 * <p>No Spring context and no database, for the reason
 * <del>{@code CaseSharedStepReferenceTest}</del> gave before §239 deleted it with the shared-step
 * library: the rule is a pure function of two enum values and a record, which is the whole point of
 * it taking both ends rather than looking anything up.
 *
 * <h2>What §208 changed, and what this pins so it cannot drift back</h2>
 *
 * <p>Retiring an artifact <strong>nobody has signed off</strong> costs the edit key, and reviving a
 * retired one costs the edit key. Before §208 both cost approve, which left a contributor able to
 * delete a colleague's case outright ({@code V51}, §207) and unable to retire it. <strong>Five of
 * the sixteen pairs move</strong> — the two retires from an unsigned state, the two revivals, and
 * the no-op {@code DEPRECATED → DEPRECATED} — and the eleven that do not are asserted in the same
 * table, because a rule change that quietly widened the approve key's reach would otherwise pass.
 */
@DisplayName("The lifecycle ladder's gate — the approve key buys a sign-off, and touching one")
class LifecycleLadderTest {

    private static final LifecycleLadder.Keys KEYS = new LifecycleLadder.Keys("edit", "approve");

    private static String gate(LifecycleStatus current, LifecycleStatus requested) {
        return LifecycleLadder.permissionFor(current, requested, KEYS);
    }

    @Nested
    @DisplayName("the approve key")
    class Approve {

        @Test
        @DisplayName("buys the sign-off, from every state — including the ones decide() refuses")
        void grantingTheSignOff() {
            for (LifecycleStatus current : LifecycleStatus.values()) {
                assertThat(gate(current, LifecycleStatus.APPROVED))
                        .as("%s → APPROVED", current)
                        .isEqualTo("approve");
            }
        }

        /**
         * §75's finding, and the arm a target-decides rule misses. {@code DRAFT} is not an
         * approval-shaped target, so reading the target alone answers the edit key — which every
         * Test Engineer holds — and a filtered selection of two hundred approved cases becomes a
         * one-click rescission of a lead's sign-off.
         */
        @Test
        @DisplayName("buys touching an approved artifact, wherever it is going")
        void leavingAnApproval() {
            assertThat(gate(LifecycleStatus.APPROVED, LifecycleStatus.DRAFT)).isEqualTo("approve");
            assertThat(gate(LifecycleStatus.APPROVED, LifecycleStatus.IN_REVIEW))
                    .isEqualTo("approve");
            assertThat(gate(LifecycleStatus.APPROVED, LifecycleStatus.DEPRECATED))
                    .isEqualTo("approve");
        }
    }

    @Nested
    @DisplayName("the edit key")
    class Edit {

        @Test
        @DisplayName("carries an unsigned artifact forward and back again")
        void throughTheStatesNobodySignedOff() {
            assertThat(gate(LifecycleStatus.DRAFT, LifecycleStatus.IN_REVIEW)).isEqualTo("edit");
            assertThat(gate(LifecycleStatus.IN_REVIEW, LifecycleStatus.DRAFT)).isEqualTo("edit");
        }

        /**
         * §208. The move was legal from a draft and cost a key no contributor holds, which left the
         * dead end {@code decide}'s {@code DEPRECATED}-from-anywhere rule exists to avoid sitting
         * one layer up — reachable by exactly the same ordinary use.
         */
        @Test
        @DisplayName("retires an artifact nobody has signed off")
        void retiringAnUnapprovedArtifact() {
            assertThat(gate(LifecycleStatus.DRAFT, LifecycleStatus.DEPRECATED)).isEqualTo("edit");
            assertThat(gate(LifecycleStatus.IN_REVIEW, LifecycleStatus.DEPRECATED))
                    .isEqualTo("edit");
        }

        /**
         * The symmetry that keeps retiring from being a one-way door. Nothing is unmade here —
         * {@link LifecycleLadder#standingOf} maps {@code DEPRECATED} to {@code OUTSIDE_REVIEW}, so
         * the sign-off, if there ever was one, ended at the retirement and was ended by somebody
         * holding the approve key. What comes back is a draft that must pass review again.
         */
        @Test
        @DisplayName("revives a retired artifact — the ladder cannot see whether it was ever approved")
        void revivingARetiredArtifact() {
            assertThat(gate(LifecycleStatus.DEPRECATED, LifecycleStatus.DRAFT)).isEqualTo("edit");
            // Refused by decide() afterwards, in that order — but the gate runs first and must
            // still answer, or a caller learns about a ladder they may not climb before they learn
            // they may not climb it. §72's placement.
            assertThat(gate(LifecycleStatus.DEPRECATED, LifecycleStatus.IN_REVIEW))
                    .isEqualTo("edit");
        }
    }

    /**
     * The population, both ways, for {@code SeedDataTest.ownIsCarriedByNoGrant}'s reason: an
     * assertion per interesting pair says which pairs somebody thought about, and says nothing about
     * a seventeenth arm appearing or about the approve key quietly spreading. This one is total over
     * the table by construction.
     */
    @Test
    @DisplayName("all sixteen pairs, and exactly seven of them cost the approve key")
    void theWholeTable() {
        List<String> approveKeyed = new ArrayList<>();
        for (LifecycleStatus current : LifecycleStatus.values()) {
            for (LifecycleStatus requested : LifecycleStatus.values()) {
                String key = gate(current, requested);
                assertThat(key).as("%s → %s", current, requested).isIn("edit", "approve");
                if (key.equals("approve")) {
                    approveKeyed.add(current + " → " + requested);
                }
            }
        }

        assertThat(approveKeyed)
                .as("the approve key's whole reach — a seventh member is a decision, not a diff")
                .containsExactlyInAnyOrder(
                        // Granting the sign-off, from anywhere. Three of these four are refused by
                        // decide() afterwards; they are gated first regardless.
                        "DRAFT → APPROVED",
                        "IN_REVIEW → APPROVED",
                        "DEPRECATED → APPROVED",
                        "APPROVED → APPROVED",
                        // Touching an artifact that has one.
                        "APPROVED → DRAFT",
                        "APPROVED → IN_REVIEW",
                        "APPROVED → DEPRECATED");
    }
}
