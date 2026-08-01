package com.tcms.identity;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offboarding a colleague — <strong>the other half of {@link MembershipInviteService}</strong>, and
 * the half that matters when somebody leaves.
 *
 * <p>{@code company:members} is defined as "invite, remove, set membership display names" (roles
 * spec §A3). Handoff §55 built the first third and recorded that a departing contractor could be
 * offboarded only by an operator with a psql prompt. This is the second.
 *
 * <h2>The membership is removed, never the account</h2>
 *
 * <p>{@link AppUser} is instance-level and one person legitimately belongs to several client
 * companies (plan §6s), so deleting the account would offboard them from every other client on the
 * instance — a write one company's administrator must not be able to make. It would also orphan
 * the authorship this company's own history depends on. So the membership goes to
 * {@link MembershipStatus#REMOVED} and nothing else is deleted, which is exactly what
 * {@link CompanyMembership}'s header promises: membership is a <em>precondition</em>, so every
 * {@code role_assignment} they hold here becomes inert in one status update rather than needing a
 * hunt across grants.
 *
 * <p>The consequence worth knowing, because it is obvious in the ERD and surprising at an admin
 * screen: those grants are inert rather than gone, so <strong>re-inviting them restores their
 * previous project access</strong>. {@link CompanyMembership#reinvite} says the same thing from
 * the other side.
 *
 * <h2>The session is revoked, and this is the call site {@code AppUser} said did not exist</h2>
 *
 * <p>{@link AppUser#revokeSessions()}'s javadoc names "a company membership change" as a caller
 * that must bump the {@code pv} epoch, and used to record that no such caller existed. This is it.
 *
 * <p>It is <strong>not</strong> the same decision {@link RoleGrantService} took for a grant change,
 * and the difference is the point. A revoked grant is already effective on the target's next
 * request, because the session token carries no grant claims and {@link PermissionEvaluator} reads
 * the database every time — so bumping there would sign somebody out of a session whose permissions
 * are correct. Here the token itself is the problem: nothing in the request path re-reads
 * membership, so {@code CompanyContextFilter} would go on opening this company for a person who is
 * no longer in it, on a signed token that stays valid until it lapses. Their permissions resolve
 * empty, but "authenticated inside a client's tenancy with an empty permission set" is not what
 * "removed" means, and it lasts for hours.
 *
 * <p>The epoch is per <em>account</em>, so this signs them out of their other companies too. That
 * is the accepted direction rather than an oversight: the cost is one sign-in, in companies where
 * nothing about their access changed, and the alternative — a per-membership epoch — puts a second
 * revocation channel in the token for a case that resolves itself in seconds.
 *
 * <h2>The outstanding invitation is cancelled, or the offboarding is undone by the next invite</h2>
 *
 * <p>Removing somebody who has not accepted yet leaves a live activation link in their inbox.
 * {@code InvitationService#accept} refuses it while the membership is {@code REMOVED} — but a later
 * re-invite calls {@link CompanyMembership#reinvite}, which puts the status back to
 * {@code INVITED}, and {@link InvitationService#issueIfPending} then finds the old invitation
 * outstanding, mints nothing and reports that a link is already on its way. The link that then
 * works is the one minted <em>before</em> the offboarding, and nobody was told. So the row is
 * deleted here, and a re-invite always mints fresh.
 *
 * <p>This is the mirror of §55's dead link: there the status made a minted token unusable, here the
 * token outlives the status that made it unusable.
 *
 * <h2>A caller may not remove themselves</h2>
 *
 * <p>Refused as a field error on {@code userId}, the same shape and for the same reason as
 * {@link RoleGrantService.SelfLockout}: the control that caused it is the row's own button.
 *
 * <p>It is also the whole of the "do not leave the company unadministrable" rule, and deliberately
 * not a count of administrators. Only a holder of {@code company:members} reaches this method, and
 * they cannot be the person leaving — so a company always retains at least the administrator who
 * ran the removal. No second implementation of "who administers this company", and nothing to keep
 * in step with the evaluator. The one exception is an Instance Admin, whose SYSTEM grants need no
 * membership here at all ({@code PermissionEvaluator#evaluateInstanceWide}) — they can empty a
 * company and can also repair it, which is what being an Instance Admin is.
 */
@Service
public class MembershipRemovalService {

    private static final Logger log = LoggerFactory.getLogger(MembershipRemovalService.class);

    private static final String AUDIT_REMOVED = "company_membership.removed";
    private static final String AUDIT_TARGET = "company_membership";

    private final AdminDirectoryService directory;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final MembershipInvitationRepository invitations;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public MembershipRemovalService(
            AdminDirectoryService directory,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            MembershipInvitationRepository invitations,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.users = users;
        this.memberships = memberships;
        this.invitations = invitations;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Removal {

        /** The caller does not hold {@code company:members} in this company. */
        record Forbidden() implements Removal {
        }

        /** The caller named themselves — 400, keyed on {@code userId}. */
        record SelfRemoval() implements Removal {
        }

        /**
         * They are not a member of this company any more — <strong>or never were</strong>. Both are
         * 204: the caller's intent is satisfied either way, and answering differently for an id
         * that names nobody here would turn a double-click into an error and this endpoint into a
         * membership oracle for guessed ids.
         */
        record Removed() implements Removal {
        }
    }

    /**
     * Removes {@code userId} from the company the session is open on.
     *
     * <p><strong>The gate runs before anything is read</strong>, the order every write in this
     * module uses: the work below reports whether an id names a member here, and running it for a
     * caller who may not offboard would make a 403 endpoint a directory probe.
     */
    @Transactional
    public Removal remove(UUID callerId, UUID userId) {
        UUID companyId = CompanyContext.require();

        if (!directory.reachOf(callerId).companyWide()) {
            return new Removal.Forbidden();
        }
        if (callerId.equals(userId)) {
            return new Removal.SelfRemoval();
        }

        Optional<CompanyMembership> found = memberships.findByCompanyIdAndUserId(companyId, userId);
        if (found.isEmpty() || found.get().getStatus() == MembershipStatus.REMOVED) {
            // Nothing to do, nothing to revoke, nothing to audit. An audit row per repeated click
            // would bury the removal that actually happened.
            return new Removal.Removed();
        }

        Instant now = clock.instant();
        CompanyMembership membership = found.get();
        String displayName = membership.getDisplayName();
        boolean wasPending = membership.getStatus() == MembershipStatus.INVITED;

        membership.remove();
        memberships.saveAndFlush(membership);

        boolean invitationCancelled = cancelOutstandingInvitation(companyId, userId);

        // The epoch, per the class header. Absent when the account has been deleted out from under
        // the membership, which the schema does not allow and a restore tool might.
        users.findById(userId).ifPresent(account -> {
            account.revokeSessions();
            users.saveAndFlush(account);
        });

        audit(userId, displayName, wasPending, invitationCancelled, callerId, now);
        log.info("User {} removed from company {} by {} (pending: {}, invitation cancelled: {})",
                userId, companyId, callerId, wasPending, invitationCancelled);
        return new Removal.Removed();
    }

    /**
     * Deletes the invitation they never accepted, if there is one — see the class header for the
     * re-admission it otherwise permits.
     *
     * <p>Deleted rather than consumed: {@code consumedAt} means "somebody redeemed this", and
     * writing it here would put a redemption that never happened in the one table the accept path
     * reads to decide whether a token is still good.
     *
     * @return whether a live link was cancelled — a fact the audit trail wants, because it is the
     *         difference between offboarding a colleague and withdrawing an invitation
     */
    private boolean cancelOutstandingInvitation(UUID companyId, UUID userId) {
        Optional<MembershipInvitation> outstanding =
                invitations.findByCompanyIdAndUserIdAndConsumedAtIsNull(companyId, userId);
        if (outstanding.isEmpty()) {
            return false;
        }
        invitations.delete(outstanding.get());
        invitations.flush();
        return true;
    }

    /**
     * One {@code audit_event} row, written only when a membership actually changed.
     *
     * <p>{@code projectId} is null for {@link MembershipInviteService}'s reason: leaving a company
     * belongs to the company, and inventing a project to fill the column would file it under one
     * client engagement.
     *
     * <p>The display name is recorded because the membership row keeps it and the directory does
     * not show it any more — an audit trail naming only a uuid answers "who left?" with a lookup
     * the reader may no longer be able to make.
     */
    private void audit(
            UUID removedUserId,
            String displayName,
            boolean wasPending,
            boolean invitationCancelled,
            UUID actorId,
            Instant now) {

        auditEvents.save(AuditEvent.record(
                null,
                actorId,
                AUDIT_REMOVED,
                AUDIT_TARGET,
                List.of(removedUserId),
                "{\"userId\":" + AuditEvent.jsonString(removedUserId.toString())
                        + ",\"displayName\":" + AuditEvent.jsonString(displayName)
                        + ",\"wasPending\":" + wasPending
                        + ",\"invitationCancelled\":" + invitationCancelled + "}",
                now));
    }
}
