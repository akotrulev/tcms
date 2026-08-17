package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.RoleAssignmentRepository.InheritedRoleName;
import com.tcms.identity.RoleAssignmentRepository.ProjectRoleName;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Roles spec §A1.1's override, tested on the rows rather than through a database.
 *
 * <p>No Spring context and no container on purpose: this type takes both queries' rows and answers
 * the cell, so every branch of the rule is reachable from a list literal. Whether the queries return
 * the right rows is a different question, asked where they live.
 */
class EffectiveRoleNamesTest {

    private static final UUID ALICE = UUID.fromString("0000ec00-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("0000ec00-0000-0000-0000-0000000000b1");
    private static final UUID NORTH = UUID.fromString("0000ec00-0000-0000-0000-0000000000f1");
    private static final UUID SOUTH = UUID.fromString("0000ec00-0000-0000-0000-0000000000f2");
    private static final UUID QA_TEAM = UUID.fromString("0000ec00-0000-0000-0000-0000000000e1");

    private static ProjectRoleName named(
            UUID user, UUID project, String role, long permissions, String via, UUID team) {
        return new ProjectRoleName() {
            @Override
            public UUID getUserId() {
                return user;
            }

            @Override
            public UUID getProjectId() {
                return project;
            }

            @Override
            public String getRoleName() {
                return role;
            }

            @Override
            public long getPermissionCount() {
                return permissions;
            }

            @Override
            public String getVia() {
                return via;
            }

            @Override
            public UUID getTeamId() {
                return team;
            }
        };
    }

    private static InheritedRoleName inherited(UUID user, String role, long permissions) {
        return new InheritedRoleName() {
            @Override
            public UUID getUserId() {
                return user;
            }

            @Override
            public String getRoleName() {
                return role;
            }

            @Override
            public long getPermissionCount() {
                return permissions;
            }
        };
    }

    @Nested
    @DisplayName("§A1.1 replaces, it does not union")
    class TheOverride {

        @Test
        @DisplayName("a grant naming the project wins over a stronger company-wide role")
        void namedGrantReplacesTheInheritedOne() {
            // The direction that matters: the inherited role confers MORE permissions and still
            // loses. The switch is WHETHER a grant names the project, never what it confers —
            // "the one place in this model where adding a grant REMOVES a permission".
            var names = EffectiveRoleNames.of(
                    List.of(named(ALICE, NORTH, "Viewer", 2, "DIRECT", null)),
                    List.of(inherited(ALICE, "Test Lead", 31)));

            assertThat(names.on(ALICE, NORTH)).contains("Viewer");
        }

        @Test
        @DisplayName("the company-wide role applies on a project no grant names")
        void theInheritedRoleFillsTheGap() {
            var names = EffectiveRoleNames.of(
                    List.of(named(ALICE, NORTH, "Viewer", 2, "DIRECT", null)),
                    List.of(inherited(ALICE, "Test Lead", 31)));

            assertThat(names.on(ALICE, SOUTH)).contains("Test Lead");
        }

        @Test
        @DisplayName("no grant of either kind is no name — not a placeholder and not an empty string")
        void aCompanyAdminHasNoName() {
            // A Company Admin reaches every project through a COMPANY-scope grant, which neither
            // query returns. `myRole` is null for them by the contract's own definition.
            var names = EffectiveRoleNames.of(List.of(), List.of());

            assertThat(names.on(ALICE, NORTH)).isEmpty();
        }

        @Test
        @DisplayName("one person's override does not reach another's")
        void peopleAreResolvedIndependently() {
            var names = EffectiveRoleNames.of(
                    List.of(named(ALICE, NORTH, "Viewer", 2, "DIRECT", null)),
                    List.of(inherited(BOB, "Test Lead", 31)));

            assertThat(names.on(ALICE, SOUTH)).isEmpty();
            assertThat(names.on(BOB, NORTH)).contains("Test Lead");
        }
    }

    @Nested
    @DisplayName("two grants on one project, one string on the wire")
    class TheRanking {

        @Test
        @DisplayName("the stronger role wins, derived from its permission count")
        void strongerWinsRegardlessOfRowOrder() {
            var weakFirst = EffectiveRoleNames.of(
                    List.of(
                            named(ALICE, NORTH, "Viewer", 2, "DIRECT", null),
                            named(ALICE, NORTH, "Test Lead", 31, "TEAM", QA_TEAM)),
                    List.of());
            var strongFirst = EffectiveRoleNames.of(
                    List.of(
                            named(ALICE, NORTH, "Test Lead", 31, "TEAM", QA_TEAM),
                            named(ALICE, NORTH, "Viewer", 2, "DIRECT", null)),
                    List.of());

            // Row order is a plausible-looking answer that changes between runs. Both orders here,
            // because "first row wins" passes a test written with only one of them.
            assertThat(weakFirst.on(ALICE, NORTH)).contains("Test Lead");
            assertThat(strongFirst.on(ALICE, NORTH)).contains("Test Lead");
        }

        @Test
        @DisplayName("the winning grant's own arm is what `via` reads — not a second ranking")
        void viaComesFromTheGrantThatWon() {
            var names = EffectiveRoleNames.of(
                    List.of(
                            named(ALICE, NORTH, "Viewer", 2, "DIRECT", null),
                            named(ALICE, NORTH, "Test Lead", 31, "TEAM", QA_TEAM)),
                    List.of());

            assertThat(names.namedGrantOn(ALICE, NORTH))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getVia()).isEqualTo("TEAM");
                        assertThat(row.getTeamId()).isEqualTo(QA_TEAM);
                    });
        }

        @Test
        @DisplayName("equally strong roles break on the name, so the answer is stable between requests")
        void tiesAreStable() {
            var oneOrder = EffectiveRoleNames.of(
                    List.of(
                            named(ALICE, NORTH, "Alpha Role", 7, "DIRECT", null),
                            named(ALICE, NORTH, "Zulu Role", 7, "TEAM", QA_TEAM)),
                    List.of());
            var theOther = EffectiveRoleNames.of(
                    List.of(
                            named(ALICE, NORTH, "Zulu Role", 7, "TEAM", QA_TEAM),
                            named(ALICE, NORTH, "Alpha Role", 7, "DIRECT", null)),
                    List.of());

            assertThat(oneOrder.on(ALICE, NORTH)).isEqualTo(theOther.on(ALICE, NORTH));
            assertThat(oneOrder.on(ALICE, NORTH)).contains("Zulu Role");
        }

        @Test
        @DisplayName("two inherited grants rank the same way")
        void inheritedGrantsRankToo() {
            var names = EffectiveRoleNames.of(
                    List.of(),
                    List.of(inherited(ALICE, "Viewer", 2), inherited(ALICE, "Test Lead", 31)));

            assertThat(names.on(ALICE, NORTH)).contains("Test Lead");
        }
    }

    @Nested
    @DisplayName("the shape `Project.myRole` reads")
    class ForPerson {

        @Test
        @DisplayName("omits a project with no name rather than mapping it to null")
        void absentRatherThanNull() {
            var names = EffectiveRoleNames.of(
                    List.of(named(ALICE, NORTH, "Viewer", 2, "DIRECT", null)), List.of());

            assertThat(names.forPerson(ALICE, List.of(NORTH, SOUTH)))
                    .containsExactly(java.util.Map.entry(NORTH, "Viewer"));
        }

        @Test
        @DisplayName("answers only the projects it was asked about — it invents no entries")
        void inventsNothing() {
            // The objection the port's old two-method split was written around: an implementation
            // that folded the inherited default into the map would have to guess which projects the
            // caller is rendering. It is told instead.
            var names = EffectiveRoleNames.of(List.of(), List.of(inherited(ALICE, "Test Lead", 31)));

            assertThat(names.forPerson(ALICE, List.of(NORTH))).containsOnlyKeys(NORTH);
            assertThat(names.forPerson(ALICE, List.of())).isEmpty();
        }
    }
}
