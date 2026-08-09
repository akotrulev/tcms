package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tcms.tenancy.CompanyContext;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan scope normalization — pure logic. {@link CompanyContext} is a {@code ThreadLocal} scope
 * guard, not a Spring bean, so building the rows needs no context and no Postgres.
 *
 * <p>{@code PlanScope}'s own header states what it is for: <em>"the one definition of what 'the same
 * scope' means"</em>, agreed on by the response the editor reads, the snapshot {@code PlanVersions}
 * hashes and the set {@code PlanUpdateService} writes. Everything that asserted it before this class
 * did so through an endpoint — which observes the response and never the hash, so a normalization
 * that drifted between the two would show as a plan whose version history records saves that changed
 * nothing. {@link #normalizationIsAFixedPoint} is that property asserted directly.
 */
@DisplayName("PlanScope — sorted, deduped, and the same scope both ways round")
class PlanScopeTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-0000000000a0");

    private static final UUID SUITE_A = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID SUITE_B = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final UUID CASE_A = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID CASE_B = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    @Test
    @DisplayName("the empty scope writes no lines")
    void emptyScopeWritesNothing() {
        PlanScope empty = PlanScope.empty();

        assertThat(empty.itemCount()).isZero();
        assertThat(empty.suiteIds()).isEmpty();
        assertThat(empty.caseIds()).isEmpty();
        assertThat(empty.tags()).isEmpty();
        assertThat(empty.sections()).isEmpty();
    }

    @Test
    @DisplayName("ids sort by id and duplicates collapse, so the same set is always one value")
    void sortsAndDedupesIds() {
        // Storage order does not exist — a read returns the rows in whatever order the plan chose
        // that day. Anything hashing this has to see one value for one membership.
        PlanScope scope = PlanScope.of(
                List.of(SUITE_B, SUITE_A, SUITE_B), List.of(CASE_B, CASE_A), List.of(), List.of());

        assertThat(scope.suiteIds()).containsExactly(SUITE_A, SUITE_B);
        assertThat(scope.caseIds()).containsExactly(CASE_A, CASE_B);
    }

    @Test
    @DisplayName("tags sort as text, which is the only order a tag has")
    void sortsAndDedupesTags() {
        PlanScope scope =
                PlanScope.of(List.of(), List.of(), List.of("smoke", "api", "smoke"), List.of());

        assertThat(scope.tags()).containsExactly("api", "smoke");
    }

    @Test
    @DisplayName("a null in any arm is dropped rather than sorted")
    void dropsNulls() {
        // Arrays.asList rather than List.of, which refuses nulls outright — the point is that a
        // collection reaching this from a mapper cannot put a null into a sorted list, where the
        // comparison would throw somewhere far from the cause.
        PlanScope scope = PlanScope.of(
                Arrays.asList(SUITE_A, null), Arrays.asList((UUID) null), Arrays.asList("api", null),
                null);

        assertThat(scope.suiteIds()).containsExactly(SUITE_A);
        assertThat(scope.caseIds()).isEmpty();
        assertThat(scope.tags()).containsExactly("api");
        assertThat(scope.sections()).isEmpty();
    }

    @Test
    @DisplayName("rows read back split into the unsectioned arms and the named sections")
    void readsRowsIntoBothLevels() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            PlanScope scope = PlanScope.ofItems(List.of(
                    PlanScopeItem.ofSuite(PLAN, SUITE_A, null, 0),
                    PlanScopeItem.ofCase(PLAN, CASE_A, "Edit", 4),
                    PlanScopeItem.ofTag(PLAN, "smoke", "Create", 2),
                    PlanScopeItem.ofSuite(PLAN, SUITE_B, "Create", 2)));

            assertThat(scope.suiteIds()).containsExactly(SUITE_A);
            assertThat(scope.caseIds()).isEmpty();
            // Renumbered contiguously from 0 — the stored ordinals were 2 and 4, and a gap left by
            // an earlier edit must not be visible to the client, or two plans with the same
            // sections in the same order would produce two values.
            assertThat(scope.sections())
                    .extracting(PlanScope.Section::name, PlanScope.Section::position)
                    .containsExactly(tuple("Create", 0), tuple("Edit", 1));
            assertThat(scope.sections().getFirst().suiteIds()).containsExactly(SUITE_B);
            assertThat(scope.sections().getFirst().tags()).containsExactly("smoke");
            assertThat(scope.sections().get(1).caseIds()).containsExactly(CASE_A);
        }
    }

    @Test
    @DisplayName("a targetless row is a section held open, not a phantom reference")
    void targetlessRowKeepsAnEmptySection() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            PlanScope scope =
                    PlanScope.ofItems(List.of(PlanScopeItem.ofSection(PLAN, "Navigation", 0)));

            // Reading it as anything else would either drop the heading its author typed on
            // reload, or add a reference the author did not make.
            assertThat(scope.sections()).singleElement().satisfies(section -> {
                assertThat(section.name()).isEqualTo("Navigation");
                assertThat(section.isEmpty()).isTrue();
            });
        }
    }

    @Test
    @DisplayName("itemCount counts rows written — an empty section is one of them")
    void countsRowsRatherThanTargets() {
        // MAX_SCOPE_ITEMS is a bound on what is inserted, so a section holding nothing still
        // costs one line. Counting targets would let a plan of empty sections pass the cap and
        // then write past it.
        try (var ignored = CompanyContext.open(COMPANY)) {
            PlanScope scope = PlanScope.ofItems(List.of(
                    PlanScopeItem.ofSuite(PLAN, SUITE_A, null, 0),
                    PlanScopeItem.ofTag(PLAN, "smoke", null, 0),
                    PlanScopeItem.ofSection(PLAN, "Navigation", 1),
                    PlanScopeItem.ofCase(PLAN, CASE_A, "Edit", 2)));

            assertThat(scope.itemCount()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("the live-id check sees both levels")
    void allIdsSpanBothLevels() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            PlanScope scope = PlanScope.ofItems(List.of(
                    PlanScopeItem.ofSuite(PLAN, SUITE_A, null, 0),
                    PlanScopeItem.ofSuite(PLAN, SUITE_B, "Create", 1),
                    PlanScopeItem.ofCase(PLAN, CASE_A, null, 0),
                    PlanScopeItem.ofCase(PLAN, CASE_B, "Create", 1)));

            // A section's references are as real as an unsectioned one's; a check that missed them
            // would let a plan point at a deleted suite from inside a section only.
            assertThat(scope.allSuiteIds()).containsExactlyInAnyOrder(SUITE_A, SUITE_B);
            assertThat(scope.allCaseIds()).containsExactlyInAnyOrder(CASE_A, CASE_B);
        }
    }

    @Test
    @DisplayName("writing a scope to rows and reading it back yields the same scope")
    void normalizationIsAFixedPoint() {
        // THE test of this class. The response, the hash and the written set all pass through one
        // of these two directions, so a normalization that is not a fixed point means a save that
        // changed nothing writes a version — repository.CaseVersions names that defect.
        try (var ignored = CompanyContext.open(COMPANY)) {
            PlanScope scope = PlanScope.ofItems(List.of(
                    PlanScopeItem.ofSuite(PLAN, SUITE_B, null, 0),
                    PlanScopeItem.ofSuite(PLAN, SUITE_A, null, 0),
                    PlanScopeItem.ofTag(PLAN, "smoke", null, 0),
                    PlanScopeItem.ofSection(PLAN, "Navigation", 3),
                    PlanScopeItem.ofCase(PLAN, CASE_A, "Edit", 1),
                    PlanScopeItem.ofTag(PLAN, "api", "Edit", 1)));

            PlanScope roundTripped = PlanScope.ofItems(PlanScopeItem.rowsOf(PLAN, scope));

            assertThat(roundTripped).isEqualTo(scope);
        }
    }
}
