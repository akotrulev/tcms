package com.tcms.identity;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inviting a colleague into the company — <strong>the last thing standing between a provisioned
 * company and one anybody else can reach.</strong>
 *
 * <p>{@code provision-company} creates exactly one account. {@code POST /role-assignments} (handoff
 * §39) can staff a project and {@code GET /projects/{id}/members} (§54) can show who is on it, and
 * until this class existed there was still <em>no way to put a second person in the company at
 * all</em> — every fixture in the suite reached for hand-written {@code INSERT}s, which is how a
 * missing capability hides behind a passing test.
 *
 * <h2>The gate is {@code company:members}, and it runs before the payload is read</h2>
 *
 * <p>COMPANY scope, so Company Admin and Instance Admin only, exactly as the roles spec §A3
 * defines the permission ("invite, remove, set membership display names"). The verdict comes from
 * {@link AdminReach#companyWide()} — the same term {@link RoleGrantService} uses for a
 * company-wide grant — so the two writes cannot disagree about who administers this company.
 *
 * <p>The gate runs first for the reason {@code RoleGrantService.grant} gives: the work below
 * reports whether an address is already a member here, and running it for a caller who may not
 * invite would turn a 403 endpoint into a directory probe.
 *
 * <h2>The response cannot say whether the account already existed, and that is the hard part</h2>
 *
 * <p>{@link AppUser} is instance-level with an instance-unique email, and its own header names the
 * consequence: <em>an invitation flow must never reveal whether an address already exists</em>.
 * One person legitimately belongs to several client companies (plan §6s), so an endpoint that
 * answered differently for a known address would let any Company Admin enumerate the instance's
 * users one guess at a time — and, worse, learn that a named competitor's staff are on it.
 *
 * <p>So both branches converge before anything is returned:
 *
 * <ul>
 *   <li>the account is found by email or created, and nothing downstream knows which;</li>
 *   <li>the membership is created with <strong>the display name the inviter typed</strong>, never
 *       {@link AppUser#getCanonicalName()} — which is §6u's rule and also the leak, because that
 *       name was chosen by whoever created the account and may be another client's wording;</li>
 *   <li>the rendered {@link UserView} takes its status from the membership, so somebody
 *       {@code ACTIVE} in another company reads {@code INVITED} here.</li>
 * </ul>
 *
 * <p>{@code UserInvitationEndpointTest.Disclosure} drives both cases and compares the responses
 * field by field.
 *
 * <h2>Re-inviting is idempotent, and 201 means exactly "a new link was minted"</h2>
 *
 * <p>The identity of the request is the email address — no {@code Idempotency-Key}, because a
 * retry does not need one to find the row it already made. {@link InvitationService#issueIfPending}
 * already leaves an unexpired invitation alone rather than replacing it, so a second click cannot
 * invalidate the link already sitting in the invitee's inbox.
 *
 * <h2>The {@code company:read} baseline is not granted here</h2>
 *
 * <p>{@link InvitationService#accept} runs {@code tcms_grant_company_read_baseline} at the moment
 * the person actually joins, and its comment names this endpoint as the case it exists for. A
 * third call site (the migration and provisioning are the other two) would be a copy of "who gets
 * the baseline" that has to be kept in step forever, for a row that conveys nothing until it is
 * accepted. {@code UserInvitationEndpointTest.acceptedInviteeSeesTheCompany} proves the decision
 * by driving invite → accept → read rather than by asserting the copy exists.
 */
@Service
public class MembershipInviteService {

    private static final String AUDIT_INVITED = "company_membership.invited";
    private static final String AUDIT_TARGET = "company_membership";

    /**
     * Deliberately permissive: something before an {@code @}, something after it with a dot in it,
     * no whitespace, no second {@code @}. It exists to catch a typo and a pasted display name, not
     * to adjudicate RFC 5322 — a stricter pattern rejects addresses that work, and the only real
     * proof that an address is deliverable is the invitation itself arriving.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** {@code app_user.email} is sized for the SMTP maximum; the contract states the same bound. */
    private static final int EMAIL_MAX = 320;

    private static final int DISPLAY_NAME_MAX = 200;

    private final AdminDirectoryService directory;
    private final InvitationService invitations;
    private final InvitationMailer mailer;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public MembershipInviteService(
            AdminDirectoryService directory,
            InvitationService invitations,
            InvitationMailer mailer,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.invitations = invitations;
        this.mailer = mailer;
        this.users = users;
        this.memberships = memberships;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Invite {

        /** The caller does not hold {@code company:members} in this company. */
        record Forbidden() implements Invite {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Invite {
        }

        /** Already a member, or an unexpired invitation was outstanding — 200, nothing sent. */
        record Unchanged(UserInvitationView view) implements Invite {
        }

        /** A fresh activation link was minted — 201. */
        record Invited(UserInvitationView view) implements Invite {
        }
    }

    /**
     * Invites {@code request.email} into the company the session is open on.
     *
     * <p>Four terminal states and the two successful ones differ only in whether a link was
     * minted, which is what makes {@code 201} mean something a client can act on: it is the only
     * answer after which a message is on its way.
     */
    @Transactional
    public Invite invite(UUID callerId, UserInvitationRequest request) {
        UUID companyId = CompanyContext.require();

        if (!directory.reachOf(callerId).companyWide()) {
            return new Invite.Forbidden();
        }

        Optional<Map<String, List<String>>> invalid = validate(request);
        if (invalid.isPresent()) {
            return new Invite.Invalid(invalid.get());
        }

        String email = request.email().trim();
        String displayName = request.displayName().trim();
        Instant now = clock.instant();

        // Found or created, and nothing below this line knows which. See the class header.
        AppUser account = users.findByEmail(email)
                .orElseGet(() -> users.saveAndFlush(
                        // canonicalName is the instance directory's name and is only ever seen by
                        // an Instance Admin; the name that renders anywhere in the product is the
                        // membership's (§6u). Seeded from the same string because there is nothing
                        // better to seed it from — and it is never read back into a response here.
                        AppUser.invite(UUID.randomUUID(), email, displayName, now)));

        Optional<CompanyMembership> existing =
                memberships.findByCompanyIdAndUserId(companyId, account.getId());

        CompanyMembership membership;
        if (existing.isEmpty()) {
            membership = memberships.saveAndFlush(CompanyMembership.invite(
                    companyId, account.getId(), displayName, callerId, now));
        } else {
            membership = existing.get();
            if (membership.getStatus() == MembershipStatus.ACTIVE) {
                // Nothing to do and nothing to send. The caller's intent — "this person is in this
                // company" — is already true, so this is a 200 rather than a 409, the same reading
                // RoleGrantService takes of a duplicate grant.
                return new Invite.Unchanged(new UserInvitationView(
                        directory.userViewOf(membership, true), false, false, null));
            }
            if (membership.getStatus() == MembershipStatus.REMOVED) {
                // A returning colleague. Without this the row is neither absent nor active and
                // issueIfPending would mint a token acceptance then refuses — see
                // CompanyMembership#reinvite for the dead link that produces.
                membership.reinvite(displayName, callerId, now);
                memberships.saveAndFlush(membership);
            }
        }

        InvitationService.IssueOutcome outcome =
                invitations.issueIfPending(companyId, account.getId(), callerId);

        UserInvitationView view = new UserInvitationView(
                directory.userViewOf(membership, true),
                outcome.issued(),
                // isConfigured(), not "the send succeeded": the send is registered for after
                // commit, so at this point there is nothing to observe but whether there is
                // anywhere to send to. InvitationMailer#isConfigured says why that is the honest
                // thing to report.
                outcome.issued() && mailer.isConfigured(),
                outcome.expiresAt().orElse(null));

        if (!outcome.issued()) {
            // An unexpired invitation was already outstanding and was left alone. Not audited:
            // nothing changed, and an audit row per click would bury the invitation that did.
            return new Invite.Unchanged(view);
        }

        audit(account.getId(), displayName, existing.isPresent(), callerId, now);
        return new Invite.Invited(view);
    }

    // =====================================================================
    // validation
    // =====================================================================

    /**
     * Everything about the payload that can be wrong, as field errors — empty when it is fine.
     *
     * <p>All of it at once, per {@code Problem.errors}: a form that highlights one input at a time
     * makes the user submit twice to learn two things.
     *
     * <p><strong>Nothing here consults the database.</strong> Every rejection describes what the
     * caller submitted. "That address is already a member" is not a validation error and must not
     * become one — it is the {@code 200} above, and phrasing it as a field error would restore the
     * oracle the whole class is arranged to avoid, this time for the caller's own company only by
     * accident of which branch it landed in.
     */
    private static Optional<Map<String, List<String>>> validate(UserInvitationRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String email = request.email() == null ? "" : request.email().trim();
        if (email.isEmpty()) {
            errors.put("email", List.of("Enter the email address to invite."));
        } else if (email.length() > EMAIL_MAX) {
            errors.put("email", List.of("That email address is too long."));
        } else if (!EMAIL.matcher(email).matches()) {
            errors.put("email", List.of("Enter a valid email address."));
        }

        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        if (displayName.isEmpty()) {
            errors.put("displayName", List.of("Enter the name to show for this person."));
        } else if (displayName.length() > DISPLAY_NAME_MAX) {
            errors.put("displayName", List.of(
                    "Keep the name to " + DISPLAY_NAME_MAX + " characters or fewer."));
        }

        return errors.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(errors));
    }

    /**
     * One {@code audit_event} row, written only when a link was actually minted.
     *
     * <p>{@code projectId} is null: joining a company belongs to the company, and inventing a
     * project to fill the column would file it under one client engagement.
     *
     * <p>The invitation link is <strong>not</strong> in it, per {@link InvitationMailer}: the audit
     * trail is read by people who are not the invitee, and a redeemable token there would let any
     * of them walk in as that person. Neither is anything about the underlying account — whether it
     * already existed is exactly the fact the response is arranged not to disclose, and an audit
     * row is a second wire out of this endpoint.
     */
    private void audit(
            UUID invitedUserId,
            String displayName,
            boolean rejoining,
            UUID actorId,
            Instant now) {

        auditEvents.save(AuditEvent.record(
                null,
                actorId,
                AUDIT_INVITED,
                AUDIT_TARGET,
                List.of(invitedUserId),
                "{\"userId\":" + AuditEvent.jsonString(invitedUserId.toString())
                        + ",\"displayName\":" + AuditEvent.jsonString(displayName)
                        + ",\"rejoining\":" + rejoining + "}",
                now));
    }
}
