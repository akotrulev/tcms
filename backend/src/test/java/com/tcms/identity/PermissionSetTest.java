package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The answer an authorization check reads — pure logic over a real {@link PermissionCatalogue} whose
 * only collaborator, the repository, is stubbed. No Spring context and no Postgres:
 * {@link PermissionEvaluatorTest} covers what the evaluator <em>derives</em> from real grants under
 * RLS, which is the integration lane's question. This covers what the value object refuses to
 * represent, which is not observable from there.
 *
 * <p>Two of these assertions expect a <strong>throw</strong>, and they are the ones worth having.
 * Both failure modes are silent in the direction that matters: an unknown key denies forever and
 * reads as a missing grant, and a {@code NONE} entry in the held map makes {@code has()} answer true
 * for a permission covering no rows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionSet — what a held permission is, and what may not be one")
class PermissionSetTest {

    @Mock private PermissionRepository permissions;

    private PermissionCatalogue catalogue;

    @BeforeEach
    void setUp() {
        catalogue = new PermissionCatalogue(permissions);
    }

    private void catalogueHolds(String... keys) {
        List<Permission> rows = Arrays.stream(keys)
                .map(key -> {
                    Permission permission = mock(Permission.class);
                    when(permission.getKey()).thenReturn(key);
                    return permission;
                })
                .toList();
        when(permissions.findAll()).thenReturn(rows);
    }

    @Test
    @DisplayName("a held permission answers its qualifier; an absent one answers NONE")
    void heldAndAbsent() {
        catalogueHolds("case:edit", "case:delete");

        PermissionSet held = PermissionSet.of(
                catalogue, Map.of("case:edit", PermissionQualifier.ALL));

        assertThat(held.has("case:edit")).isTrue();
        assertThat(held.qualifierOf("case:edit")).isEqualTo(PermissionQualifier.ALL);
        assertThat(held.has("case:delete")).isFalse();
        // NONE rather than null, so no caller has to null-check the answer to an authorization
        // question — the value that means "decides nothing" is in the lattice.
        assertThat(held.qualifierOf("case:delete")).isEqualTo(PermissionQualifier.NONE);
    }

    @Test
    @DisplayName("a NONE qualifier in the held map is refused rather than stored")
    void noneIsAnAbsentEntryNeverAPresentOne() {
        // The defect: has() answers containsKey, so a NONE entry would report a grant that covers
        // no rows as held, and every gate downstream would open on it.
        catalogueHolds("case:edit");

        assertThatThrownBy(() ->
                        PermissionSet.of(catalogue, Map.of("case:edit", PermissionQualifier.NONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown key is refused at construction, not silently denied at the gate")
    void unknownKeyAtConstructionThrows() {
        catalogueHolds("case:edit");

        assertThatThrownBy(() ->
                        PermissionSet.of(catalogue, Map.of("case:edti", PermissionQualifier.ALL)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown key asked at the gate throws rather than answering false")
    void unknownKeyAtTheGateThrows() {
        // A typo'd key that answered false would deny forever, and read to whoever reported it as
        // a grants problem — an administrator can spend a long time granting a permission that
        // does not exist.
        catalogueHolds("case:edit");
        PermissionSet held = PermissionSet.empty(catalogue);

        assertThatThrownBy(() -> held.has("case:edti")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> held.qualifierOf("case:edti"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the empty set holds nothing and covers nothing")
    void emptySet() {
        catalogueHolds("case:edit");
        PermissionSet empty = PermissionSet.empty(catalogue);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(empty.keys()).isEmpty();
        assertThat(empty.coversEverything()).isFalse();
    }

    @Test
    @DisplayName("hasAll and hasAny over several keys")
    void hasAllAndHasAny() {
        catalogueHolds("case:edit", "case:delete", "case:approve");
        PermissionSet held = PermissionSet.of(
                catalogue,
                Map.of(
                        "case:edit", PermissionQualifier.ALL,
                        "case:delete", PermissionQualifier.OWN));

        assertThat(held.hasAll("case:edit", "case:delete")).isTrue();
        assertThat(held.hasAll("case:edit", "case:approve")).isFalse();
        assertThat(held.hasAny("case:approve", "case:edit")).isTrue();
        assertThat(held.hasAny("case:approve")).isFalse();
    }

    @Test
    @DisplayName("hasAny is false with no keys and hasAll is true — the vacuous pair, pinned")
    void vacuousArguments() {
        // Neither is reachable from a controller today, and both are the arm a future caller
        // passing a computed array will hit on an empty one. hasAll() opening the gate is the
        // dangerous half, so it is stated here rather than discovered. No catalogue stub: neither
        // call reaches a key, and MockitoExtension's strict stubs would fail the test for an
        // unused one — which is the extension correctly reporting that this asserts a short
        // circuit, not a lookup.
        PermissionSet held = PermissionSet.empty(catalogue);

        assertThat(held.hasAll()).isTrue();
        assertThat(held.hasAny()).isFalse();
    }

    @Test
    @DisplayName("coversEverything is derived from the catalogue, never from a sentinel")
    void coversEverythingIsDerived() {
        // Plan §6b: there is no wildcard permission. Holding every key IS full coverage, and
        // adding a key to the catalogue must take it away again — which a '*' sentinel could not
        // express, and which is the frontend's expensive lesson restated on this side.
        catalogueHolds("case:edit", "case:delete");

        PermissionSet all = PermissionSet.of(
                catalogue,
                Map.of(
                        "case:edit", PermissionQualifier.ALL,
                        "case:delete", PermissionQualifier.ALL));
        PermissionSet almost = PermissionSet.of(
                catalogue, Map.of("case:edit", PermissionQualifier.ALL));

        assertThat(all.coversEverything()).isTrue();
        assertThat(almost.coversEverything()).isFalse();
    }

    @Test
    @DisplayName("the catalogue is read once and memoized")
    void catalogueIsMemoized() {
        // Seeded reference data that never changes at runtime, consulted on every authorization
        // check — a re-read per check would be a query per gate.
        catalogueHolds("case:edit");

        assertThat(catalogue.contains("case:edit")).isTrue();
        assertThat(catalogue.contains("case:edit")).isTrue();
        assertThat(catalogue.keys()).containsExactly("case:edit");

        verify(permissions, times(1)).findAll();
    }

    @Test
    @DisplayName("an empty catalogue is a migration failure, said as one")
    void emptyCatalogueNamesTheMigration() {
        // Without this, V3 not having run makes every check deny and reads as a grants problem —
        // the diagnostic is the whole value, so it is asserted rather than assumed.
        when(permissions.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> catalogue.keys())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V3__seed_permissions_and_roles.sql");
    }
}
