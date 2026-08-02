package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The qualifier merge lattice — pure logic, so no Spring context and no Postgres.
 *
 * <p>This exists because V19 turned a correct one-liner into a rule that has to be stated. Until
 * then {@code strongest} was {@link Enum#compareTo} over a weakest-first declaration, and it was
 * right for a lattice with a single restrictive value: {@code NONE < OWN < ALL} is a genuine total
 * order. Adding {@link PermissionQualifier#PLAN_SCOPE} put a second restriction on a different axis
 * into it, and an ordinal comparison answers that pair by declaration order — which is to say, it
 * takes an authorization decision in a line nobody would review as authorization code.
 *
 * <p>So the interesting assertion here is the one that expects a <strong>throw</strong>. The rest
 * pin the algebra a caller with two grants depends on.
 */
@DisplayName("PermissionQualifier — the strongest-wins merge, and the pair it refuses")
class PermissionQualifierTest {

    @Test
    @DisplayName("ALL absorbs everything — an extra role never takes capability away")
    void allAbsorbs() {
        // The rule this protects: hold case:delete unqualified as a Company Admin and OWN as a
        // project's Test Engineer, and the answer is ALL. Demoting the caller for holding an
        // ADDITIONAL grant would make adding a role remove capability, which is the one direction
        // an authorization merge must never move.
        for (PermissionQualifier other : PermissionQualifier.values()) {
            assertThat(PermissionQualifier.ALL.strongest(other)).isEqualTo(PermissionQualifier.ALL);
            assertThat(other.strongest(PermissionQualifier.ALL)).isEqualTo(PermissionQualifier.ALL);
        }
    }

    @Test
    @DisplayName("NONE is the identity — a term that grants nothing changes nothing")
    void noneIsTheIdentity() {
        // Every evaluation unions three terms (SYSTEM, COMPANY, PROJECT) and most callers hold
        // nothing in at least one of them, so this is the common path rather than an edge case.
        for (PermissionQualifier other : PermissionQualifier.values()) {
            assertThat(PermissionQualifier.NONE.strongest(other)).isEqualTo(other);
            assertThat(other.strongest(PermissionQualifier.NONE)).isEqualTo(other);
        }
    }

    @Test
    @DisplayName("a qualifier merged with itself is itself, including the restrictive ones")
    void idempotent() {
        // Two PROJECT-scope grants of the same restricted permission — directly and through a team
        // — is an ordinary state, not an exotic one, and it must not escalate to ALL.
        for (PermissionQualifier value : PermissionQualifier.values()) {
            assertThat(value.strongest(value)).isEqualTo(value);
        }
    }

    @Test
    @DisplayName("OWN and PLAN_SCOPE refuse to merge rather than silently picking one")
    void twoDifferentRestrictionsThrow() {
        // THE assertion this class exists for. Their union is "rows you wrote OR rows at plan
        // scope", which no value here expresses — so both a narrowing answer and a widening one
        // would be a lie about what the caller may do, and the ordinal comparison this replaced
        // would have returned whichever was declared later.
        //
        // Unreachable against the seeded catalogue, and that is a property of the DATA rather than
        // of this type: SeedDataTest asserts no permission key is granted with two different
        // restrictive qualifiers. This is what happens the day that stops being true — a loud
        // failure at the gate, not a quiet decision inside it.
        assertThatThrownBy(() ->
                PermissionQualifier.OWN.strongest(PermissionQualifier.PLAN_SCOPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restrict different things");

        assertThatThrownBy(() ->
                PermissionQualifier.PLAN_SCOPE.strongest(PermissionQualifier.OWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restrict different things");
    }

    @Test
    @DisplayName("every stored value the CHECK constraint permits is readable, and nothing else is")
    void ofStoredCoversTheConstraint() {
        // Kept in step with V19's CHECK by hand, and the throw below is why that is safe: a value
        // the constraint admits but this switch does not is a startup-time-invisible defect that
        // surfaces as an authorization guess, so ofStored refuses rather than defaulting.
        assertThat(PermissionQualifier.ofStored("ALL")).isEqualTo(PermissionQualifier.ALL);
        assertThat(PermissionQualifier.ofStored("OWN")).isEqualTo(PermissionQualifier.OWN);
        assertThat(PermissionQualifier.ofStored("PLAN_SCOPE"))
                .isEqualTo(PermissionQualifier.PLAN_SCOPE);

        assertThatThrownBy(() -> PermissionQualifier.ofStored("NONE"))
                .as("NONE is never stored — a grant that covers nothing is an absent row")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PermissionQualifier.ofStored("PROJECT_SCOPE"))
                .as("an unimplemented value means the CHECK was dropped or a migration ran ahead")
                .isInstanceOf(IllegalStateException.class);
    }
}
