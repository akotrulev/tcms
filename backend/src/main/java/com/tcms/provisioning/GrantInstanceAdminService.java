package com.tcms.provisioning;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.InstanceRoleAssignment;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.Role;
import com.tcms.identity.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Grants SYSTEM-scope Instance Admin to an existing account.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The tier was designed, enforced and unreachable. {@code Role.INSTANCE_ADMIN_ID} is
 * seeded by {@code V3}, {@code PermissionEvaluator} resolves the SYSTEM term as its own
 * independent term (so it survives the company and project gates that zero everything else),
 * and {@code instance_role_assignment} has existed since {@code V1} — but <strong>no code
 * path in {@code src/main} ever wrote a row into it</strong>. Only three test classes had
 * ever called {@code InstanceRoleAssignment.forUser}. A fresh instance therefore had a
 * complete operator role that nobody could ever hold, and the only way to create one was a
 * hand-written {@code INSERT} against production state.
 *
 * <p>{@code RoleGrantService}'s header already promised this command — "there is no HTTP
 * path to a SYSTEM grant in this slice and there should not be one until an instance-admin
 * surface exists to hold it; <em>the operator CLI is the only way today</em>". It was the
 * only way in the sense that no other way existed, not in the sense that it worked. This is
 * the mechanism that sentence describes.
 *
 * <h2>No company context, deliberately</h2>
 *
 * <p>Every table touched here — {@code app_user}, {@code instance_role_assignment} — is
 * instance-level and carries no {@code company_id} (ERD §0.1 exception list), so this runs
 * with <strong>no company open</strong>. That is not a shortcut around
 * {@link com.tcms.tenancy.CompanyContext}: {@code CompanyAwareTransactionManager.doBegin}
 * treats a context-less transaction as legitimate and leaves the GUC unset, which under RLS
 * means zero rows from any company-owned table reached from here. Opening a context would be
 * worse than unnecessary — it would imply this grant belongs to one client, and an
 * instance-wide power scoped to a single company is a tier the model does not have.
 *
 * <p>Contrast {@link IssueInvitationService}, which must resolve its company id before
 * opening the context and wrap the transaction in it. There is nothing to resolve here.
 *
 * <h2>Granting to an account that has not accepted yet is allowed</h2>
 *
 * <p>{@code provision-company} leaves its first administrator {@code INVITED}, so the
 * realistic bootstrap is: provision a company, grant that address Instance Admin, then
 * redeem the invitation. The grant is inert until the account is {@code ACTIVE} — the
 * evaluator checks status before reading the SYSTEM term — and lights up on acceptance with
 * no second command. Refusing an {@code INVITED} account would break the only sequence that
 * bootstraps an instance. {@code DEACTIVATED} is refused; see
 * {@link GrantInstanceAdminResult.Outcome#USER_DEACTIVATED} for why that one is different.
 */
@Service
public class GrantInstanceAdminService {

    private static final Logger log = LoggerFactory.getLogger(GrantInstanceAdminService.class);

    private final AppUserRepository users;
    private final InstanceRoleAssignmentRepository instanceRoleAssignments;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GrantInstanceAdminService(
            AppUserRepository users,
            InstanceRoleAssignmentRepository instanceRoleAssignments,
            TransactionTemplate transactions,
            Clock clock) {
        this.users = users;
        this.instanceRoleAssignments = instanceRoleAssignments;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param email       the account to promote. It must already exist — this command grants
     *                    a role, it does not create people. On a fresh instance the account
     *                    comes from {@code provision-company}.
     * @param actorUserId the granting operator, or {@code null} for the bootstrapped first
     *                    Instance Admin: there is genuinely nobody to attribute that one to,
     *                    which is why {@code instance_role_assignment.granted_by} is nullable.
     */
    public GrantInstanceAdminResult grant(String email, UUID actorUserId) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        // One transaction around read-then-write: the unique constraint on
        // (user_id, role_id) is the real guarantee, but doing the check in the same
        // transaction as the insert is what lets ALREADY_HELD be a sentence rather than a
        // DataIntegrityViolationException the operator has to interpret.
        return transactions.execute(status -> doGrant(email, actorUserId));
    }

    private GrantInstanceAdminResult doGrant(String email, UUID actorUserId) {
        Optional<AppUser> found = users.findByEmail(email);
        if (found.isEmpty()) {
            return GrantInstanceAdminResult.noSuchUser();
        }
        AppUser user = found.get();
        UUID userId = user.getId();
        String canonicalName = user.getCanonicalName();

        if (user.getStatus() == UserStatus.DEACTIVATED) {
            return GrantInstanceAdminResult.userDeactivated(userId, canonicalName);
        }
        boolean active = user.getStatus() == UserStatus.ACTIVE;

        boolean alreadyHeld = instanceRoleAssignments.findByUserId(userId).stream()
                .anyMatch(a -> Role.INSTANCE_ADMIN_ID.equals(a.getRoleId()));
        if (alreadyHeld) {
            return GrantInstanceAdminResult.alreadyHeld(userId, canonicalName, active);
        }

        Instant now = clock.instant();
        instanceRoleAssignments.save(InstanceRoleAssignment.forUser(
                UUID.randomUUID(), Role.INSTANCE_ADMIN_ID, userId, actorUserId, now));

        // Loud on purpose, and at INFO rather than DEBUG: this is the widest grant the
        // product has — every permission, every company, including reach into client
        // content that the roles spec calls a disclosed and audited capability. An
        // instance's log should be able to answer "when did this account become an
        // operator" without a database read.
        log.info("Granted Instance Admin to user {} (status {}); granted_by={}",
                userId, user.getStatus(), actorUserId);

        return GrantInstanceAdminResult.granted(userId, canonicalName, active);
    }
}
