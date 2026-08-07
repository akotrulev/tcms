package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.InstanceRoleAssignment;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.PermissionEvaluator;
import com.tcms.identity.PermissionSet;
import com.tcms.identity.Role;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Granting Instance Admin — the bootstrap for a tier that was unreachable.
 *
 * <p>What is at stake is not "a row was written". Before this command,
 * {@code instance_role_assignment} had <strong>no writer anywhere in {@code src/main}</strong>
 * — the role was seeded by {@code V3}, the evaluator resolved it as an independent SYSTEM
 * term, {@code RoleGrantService} refused SYSTEM grants and pointed at an operator CLI that
 * did not implement one, and the only way to create an operator was a hand-written
 * {@code INSERT} against live data. The tier was designed, enforced and impossible to hold.
 *
 * <p>The load-bearing property is the last test in {@link Reach}: the grant must confer
 * permissions in a company the account is <em>not a member of</em> and on a project it holds
 * <em>no grant on</em>. A row that writes cleanly but leaves the SYSTEM term dark would pass
 * every other assertion here and deliver nothing — the same fail-closed shape as the
 * repository-transaction defect (§15), where the context was right and the reads were empty.
 *
 * <p>Per the async-harness lesson (§14) this class owns the {@code gia-} slug namespace and
 * provisions its own companies: the container is shared, and a test that assumes it is alone
 * finds a collision and reports it as a leak.
 */
@DisplayName("Granting Instance Admin — the operator tier's only writer")
class GrantInstanceAdminServiceTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private GrantInstanceAdminService granting;

    @Autowired
    private InstanceRoleAssignmentRepository instanceRoleAssignments;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PermissionEvaluator permissions;

    @Autowired
    private Clock clock;

    @Nested
    @DisplayName("Granting")
    class Granting {

        @Test
        @DisplayName("writes a SYSTEM grant for a freshly provisioned administrator")
        void grantsToAProvisionedAdmin() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("gia-basic"));

            GrantInstanceAdminResult result =
                    granting.grant("admin@gia-basic.test", null);

            assertThat(result.outcome()).isEqualTo(GrantInstanceAdminResult.Outcome.GRANTED);
            assertThat(result.userId()).contains(provisioned.adminUserId());

            List<InstanceRoleAssignment> held =
                    instanceRoleAssignments.findByUserId(provisioned.adminUserId());
            assertThat(held).hasSize(1);
            assertThat(held.getFirst().getRoleId()).isEqualTo(Role.INSTANCE_ADMIN_ID);
            assertThat(held.getFirst().getGrantedBy())
                    .as("null is the bootstrapped first operator — there is nobody to attribute it to")
                    .isNull();
            assertThat(held.getFirst().getGrantedAt()).isNotNull();
        }

        @Test
        @DisplayName("records the granting operator when there is one")
        void recordsTheActor() {
            ProvisionCompanyResult first = provisioning.provision(request("gia-actor-a"));
            ProvisionCompanyResult second = provisioning.provision(request("gia-actor-b"));

            granting.grant("admin@gia-actor-b.test", first.adminUserId());

            assertThat(instanceRoleAssignments.findByUserId(second.adminUserId()).getFirst()
                    .getGrantedBy())
                    .isEqualTo(first.adminUserId());
        }

        @Test
        @DisplayName("an account that has not accepted yet CAN be granted, and says so")
        void grantsToAnInvitedAccount() {
            provisioning.provision(request("gia-invited"));

            GrantInstanceAdminResult result =
                    granting.grant("admin@gia-invited.test", null);

            // This is the only sequence that bootstraps an instance: provision-company
            // leaves its first admin INVITED, so refusing that state would leave the
            // command unable to create the very first operator.
            assertThat(result.outcome()).isEqualTo(GrantInstanceAdminResult.Outcome.GRANTED);
            assertThat(result.active())
                    .as("the CLI prints an inert-until-accepted note off this flag")
                    .contains(false);
        }
    }

    @Nested
    @DisplayName("Re-running")
    class ReRunning {

        @Test
        @DisplayName("is a no-op that still reports success, and never writes a second row")
        void isIdempotent() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("gia-again"));

            granting.grant("admin@gia-again.test", null);
            GrantInstanceAdminResult second = granting.grant("admin@gia-again.test", null);

            assertThat(second.outcome())
                    .as("declarative, unlike issue-invitation's ALREADY_ACTIVE — the operator "
                            + "asked for a state, and the state holds")
                    .isEqualTo(GrantInstanceAdminResult.Outcome.ALREADY_HELD);
            assertThat(instanceRoleAssignments.findByUserId(provisioned.adminUserId()))
                    .as("uq_instance_role_assignment makes a second row impossible; this "
                            + "asserts we never ASK for one, so the operator sees a sentence "
                            + "rather than a constraint violation")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("an address with no account grants nothing")
        void refusesAnUnknownAddress() {
            GrantInstanceAdminResult result =
                    granting.grant("nobody@gia-unknown.test", null);

            assertThat(result.outcome())
                    .isEqualTo(GrantInstanceAdminResult.Outcome.NO_SUCH_USER);
            assertThat(result.userId()).isEmpty();
        }

        @Test
        @DisplayName("a DEACTIVATED account is refused, so the grant cannot wake up later")
        void refusesADeactivatedAccount() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("gia-deact"));
            AppUser user = users.findById(provisioned.adminUserId()).orElseThrow();
            user.deactivate(clock.instant());
            users.save(user);

            GrantInstanceAdminResult result = granting.grant("admin@gia-deact.test", null);

            assertThat(result.outcome())
                    .isEqualTo(GrantInstanceAdminResult.Outcome.USER_DEACTIVATED);
            assertThat(instanceRoleAssignments.findByUserId(provisioned.adminUserId()))
                    .as("the grant would be inert today and live the moment anyone "
                            + "reactivated them — so it must not exist at all")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Reach")
    class Reach {

        @Test
        @DisplayName("the grant reaches a company the account is not a member of")
        void reachesAnotherCompanyEntirely() {
            ProvisionCompanyResult home = provisioning.provision(request("gia-reach-home"));
            ProvisionCompanyResult other = provisioning.provision(request("gia-reach-other"));

            // ACTIVE, because the SYSTEM term resolves only for an active account — the
            // grant alone is deliberately not enough.
            AppUser admin = users.findById(home.adminUserId()).orElseThrow();
            admin.activate("hash", clock.instant());
            users.save(admin);

            granting.grant("admin@gia-reach-home.test", null);

            // The whole claim of the tier, in one assertion: permissions inside a company
            // this person has no membership in. Every other gate is shut here — the
            // company term and the project term are both zero — and the SYSTEM term is
            // what survives them.
            PermissionSet inTheOtherCompany;
            try (var ignored = CompanyContext.open(other.companyId())) {
                inTheOtherCompany = permissions.evaluateCompanyWide(home.adminUserId());
            }

            assertThat(inTheOtherCompany.isEmpty())
                    .as("a grant that writes cleanly but leaves the SYSTEM term dark passes "
                            + "every other test in this class and delivers nothing")
                    .isFalse();
            assertThat(inTheOtherCompany.hasAll("company:settings", "company:members"))
                    .isTrue();
        }
    }

    private static ProvisionCompanyRequest request(String slug) {
        return ProvisionCompanyRequest.of(
                slug, slug + " Ltd", "admin@" + slug + ".test", slug + " Admin");
    }
}
