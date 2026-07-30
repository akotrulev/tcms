package com.tcms.identity;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues and redeems company invitations — the path from the {@code INVITED} administrator
 * {@code provision-company} leaves behind to an account that can sign in.
 *
 * <h2>One rejection reason, on purpose</h2>
 *
 * <p>Everything that can go wrong with a presented token — unknown, expired, already
 * redeemed, membership since removed, account since deactivated — collapses into the
 * single {@link Rejection#NOT_REDEEMABLE}. That is not laziness, it is the design:
 * distinct answers would let anyone with a token-shaped string learn whether an address is
 * on this instance and which company it belongs to. It is the same rule the rest of the
 * API follows for unreachable display keys — NOT FOUND, never FORBIDDEN, identically, so
 * the response cannot be used as an oracle (plan §6t) — applied to a surface that runs
 * before authentication and is therefore reachable by anyone.
 *
 * <p>The enum having <em>one</em> constant for all five causes is what makes that
 * structural: a later edit cannot split them apart without deliberately adding a constant,
 * which is a visible change rather than an accident.
 *
 * <h2>Where the company context comes from</h2>
 *
 * <p>{@link #accept} reads {@code company_membership}, which is RLS-protected, so it needs
 * a company context — on a path that by definition has no session. The company id comes
 * from the <strong>invitation row</strong>, not from the request: the caller presents a
 * secret, the secret is verified against a stored digest, and the row that verification
 * resolves is server-side data. That is the same shape as reading it from a session token,
 * and it is <em>not</em> the forbidden case of taking a company id from a parameter, where
 * the caller chooses the tenancy and nothing verifies the choice.
 *
 * <p>The context is opened <strong>around</strong> the transaction, never inside it —
 * {@code CompanyAwareTransactionManager} reads it in {@code doBegin}, so a context opened
 * after the transaction began leaves the GUC unset and every read returns zero rows
 * (handoff §15).
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /**
     * Why an invitation was not redeemed. Deliberately coarse — see the class comment.
     * {@link #PASSWORD_UNACCEPTABLE} and {@link #PASSWORD_NOT_REQUIRED} are safe to
     * distinguish because they describe what the caller submitted, not what exists here.
     */
    public enum Rejection {
        /** Unknown, expired, already redeemed, membership removed, or account deactivated. */
        NOT_REDEEMABLE,
        /** The chosen password failed {@link PasswordPolicy}; the reason is included. */
        PASSWORD_UNACCEPTABLE,
        /** The account already has a password — this invitation only adds a membership. */
        PASSWORD_NOT_REQUIRED
    }

    /** The result of redeeming. */
    public sealed interface AcceptOutcome permits Accepted, Rejected {
    }

    /** @param passwordSet false when the account already had one and this only joined a company */
    public record Accepted(UUID userId, UUID companyId, String email, boolean passwordSet)
            implements AcceptOutcome {
    }

    /** @param detail a caller-safe explanation, or null when saying more would be an oracle */
    public record Rejected(Rejection reason, String detail) implements AcceptOutcome {
    }

    /**
     * What an issue attempt did.
     *
     * @param acceptUrl the activation link, present <strong>only</strong> when this call
     *                  minted the token. Empty when an unexpired invitation was already
     *                  outstanding — that secret is unrecoverable by design, which is the
     *                  whole point of storing a digest rather than the token.
     * @param expiresAt when the outstanding invitation lapses, minted now or not
     * @param issued    true when this call created one
     */
    public record IssueOutcome(Optional<String> acceptUrl, Optional<Instant> expiresAt,
                               boolean issued) {

        static IssueOutcome none() {
            return new IssueOutcome(Optional.empty(), Optional.empty(), false);
        }
    }

    private final MembershipInvitationRepository invitations;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final CompanyRepository companies;
    private final InvitationMailer mailer;
    private final InvitationTokens tokens;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final InvitationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InvitationService(
            MembershipInvitationRepository invitations,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            CompanyRepository companies,
            InvitationMailer mailer,
            InvitationTokens tokens,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            InvitationProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.properties = properties;
        this.invitations = invitations;
        this.users = users;
        this.memberships = memberships;
        this.companies = companies;
        this.mailer = mailer;
        this.tokens = tokens;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Issues an invitation for a membership that has not been accepted yet.
     *
     * <p><strong>Must be called inside an open transaction and an open company context</strong>
     * — it is a step of provisioning, not a transaction of its own, so a failure later in
     * that run takes the invitation with it rather than leaving a live token for a company
     * that was rolled back.
     *
     * <p>Three outcomes, and the middle one is the reason a re-run is safe:
     * <ul>
     *   <li>the membership is already ACTIVE — nothing to accept, nothing issued;</li>
     *   <li>an unexpired invitation is outstanding — <strong>left alone</strong>. Minting a
     *       replacement on every re-run would invalidate a link already in someone's inbox,
     *       and {@code provision-company} is explicitly meant to be re-runnable;</li>
     *   <li>no invitation, or only a lapsed one — the lapsed row is removed and a fresh
     *       token minted.</li>
     * </ul>
     */
    public IssueOutcome issueIfPending(UUID companyId, UUID userId, UUID issuedBy) {
        Instant now = clock.instant();

        Optional<CompanyMembership> membership = memberships.findByCompanyIdAndUserId(companyId, userId);
        if (membership.isEmpty() || membership.get().isActive()) {
            return IssueOutcome.none();
        }

        Optional<MembershipInvitation> outstanding =
                invitations.findByCompanyIdAndUserIdAndConsumedAtIsNull(companyId, userId);
        if (outstanding.isPresent()) {
            MembershipInvitation existing = outstanding.get();
            if (existing.isRedeemable(now)) {
                return new IssueOutcome(
                        Optional.empty(), Optional.of(existing.getExpiresAt()), false);
            }
            // Lapsed. Deleted rather than kept, because the partial unique index allows one
            // outstanding row per (company, user) and an expired token is not evidence of
            // anything the instance audit trail does not already record.
            invitations.delete(existing);
            invitations.flush();
        }

        InvitationTokens.NewToken token = tokens.generate();
        Instant expiresAt = now.plus(properties.validity());
        invitations.saveAndFlush(MembershipInvitation.issue(
                UUID.randomUUID(), companyId, userId, token.hash(), expiresAt, issuedBy, now));
        // The URL, not the secret: the secret has exactly one legitimate destination and
        // handing it around as a bare string invites a call site that logs it.
        String acceptUrl = properties.acceptUrl(token.secret());
        sendAfterCommit(companyId, membership.get(), acceptUrl, expiresAt);
        return new IssueOutcome(Optional.of(acceptUrl), Optional.of(expiresAt), true);
    }

    /**
     * Queues the invitation email for <strong>after the transaction commits</strong>.
     *
     * <p>Two independent reasons, either sufficient:
     *
     * <ul>
     *   <li><strong>An SMTP round trip inside a transaction holds a pooled database
     *       connection across a network call</strong> to a third party, for as long as that
     *       party takes to answer. A slow relay would drain the pool.</li>
     *   <li><strong>A mail failure must not roll back a provisioned company.</strong> Sent
     *       inline, an unreachable relay would undo the company, the vocabularies, the
     *       counters and the grant — and the operator would see a provisioning error whose
     *       cause is a mail server.</li>
     * </ul>
     *
     * <p>The reverse mistake matters too: sending <em>before</em> commit would deliver a
     * link to a row that then rolled back, so the invitee gets a token nothing recognises.
     *
     * <p>When there is no transaction — nothing calls it that way today, but a future admin
     * API might — the send happens immediately rather than being dropped. Silently
     * discarding the one message that lets someone in is the worse failure.
     */
    private void sendAfterCommit(
            UUID companyId, CompanyMembership membership, String acceptUrl, Instant expiresAt) {
        String email = users.findById(membership.getUserId())
                .map(AppUser::getEmail)
                .orElse(null);
        if (email == null) {
            log.error("Invitation issued for user {} which no longer exists; not sending",
                    membership.getUserId());
            return;
        }
        String companyName = companies.findById(companyId)
                .map(Company::getDisplayName)
                .orElse("your company");

        InvitationMailer.InvitationMessage message = new InvitationMailer.InvitationMessage(
                email, membership.getDisplayName(), companyName, acceptUrl, expiresAt);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mailer.send(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mailer.send(message);
            }
        });
    }

    /**
     * What a valid token admits its holder to, for the pre-acceptance screen.
     *
     * <p>Returns the invitee's own address and whether they must choose a password — both
     * facts the holder of the secret is entitled to, and neither obtainable without it.
     */
    public Optional<InvitationPreview> preview(String secret) {
        Optional<MembershipInvitation> found = findRedeemable(secret);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MembershipInvitation invitation = found.get();
        try (var ignored = CompanyContext.open(invitation.getCompanyId())) {
            return transactions.execute(status -> describe(invitation));
        }
    }

    private Optional<InvitationPreview> describe(MembershipInvitation invitation) {
        Optional<AppUser> user = users.findById(invitation.getUserId());
        Optional<CompanyMembership> membership = memberships
                .findByCompanyIdAndUserId(invitation.getCompanyId(), invitation.getUserId());
        if (user.isEmpty() || membership.isEmpty()
                || membership.get().getStatus() != MembershipStatus.INVITED
                || user.get().getStatus() == UserStatus.DEACTIVATED) {
            return Optional.empty();
        }
        return Optional.of(new InvitationPreview(
                user.get().getEmail(),
                membership.get().getDisplayName(),
                user.get().getPasswordHash() == null));
    }

    /** @param passwordRequired false when the account already exists with a password */
    public record InvitationPreview(String email, String displayName, boolean passwordRequired) {
    }

    /**
     * Redeems a token: activates the membership, and the account with it when this is the
     * person's first company.
     *
     * @param password the chosen password, or null when the account already has one
     */
    public AcceptOutcome accept(String secret, String password) {
        Optional<MembershipInvitation> found = findRedeemable(secret);
        if (found.isEmpty()) {
            return new Rejected(Rejection.NOT_REDEEMABLE, null);
        }
        UUID companyId = found.get().getCompanyId();
        UUID invitationId = found.get().getId();

        try (var ignored = CompanyContext.open(companyId)) {
            return transactions.execute(status -> redeem(invitationId, password));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Two redemptions of the same token racing: @Version on the invitation means
            // one commits and the other lands here. Reported as not-redeemable, which is
            // exactly what it is by the time the loser looks again.
            log.info("Concurrent redemption of invitation {} lost the race", invitationId);
            return new Rejected(Rejection.NOT_REDEEMABLE, null);
        }
    }

    private AcceptOutcome redeem(UUID invitationId, String password) {
        Instant now = clock.instant();

        // Re-read inside the transaction. The redeemability check that mattered is this
        // one — the earlier read only told us which company to open.
        MembershipInvitation invitation = invitations.findById(invitationId).orElse(null);
        if (invitation == null || !invitation.isRedeemable(now)) {
            return new Rejected(Rejection.NOT_REDEEMABLE, null);
        }

        AppUser user = users.findById(invitation.getUserId()).orElse(null);
        CompanyMembership membership = memberships
                .findByCompanyIdAndUserId(invitation.getCompanyId(), invitation.getUserId())
                .orElse(null);
        if (user == null || membership == null
                || user.getStatus() == UserStatus.DEACTIVATED
                || membership.getStatus() != MembershipStatus.INVITED) {
            // A deactivated account or a removed/already-active membership. Refused with
            // the same answer as an unknown token — see the class comment.
            return new Rejected(Rejection.NOT_REDEEMABLE, null);
        }

        boolean passwordRequired = user.getPasswordHash() == null;
        if (passwordRequired) {
            Optional<String> rejection = passwordPolicy.rejectionReason(password);
            if (rejection.isPresent()) {
                return new Rejected(Rejection.PASSWORD_UNACCEPTABLE, rejection.get());
            }
        } else if (password != null && !password.isEmpty()) {
            // Refused rather than ignored. Silently discarding a password someone typed
            // leaves them believing they changed it — and this endpoint is not the place to
            // change a password, because holding an invitation to a second company must not
            // confer the ability to overwrite the credential of an existing account.
            return new Rejected(Rejection.PASSWORD_NOT_REQUIRED,
                    "This account already has a password. Accept without one, then sign in "
                            + "with your existing password.");
        }

        invitation.consume(now);
        membership.accept(now);
        if (passwordRequired) {
            // activate() bumps the pv epoch itself: INVITED -> ACTIVE changes what this
            // account may do.
            user.activate(passwordEncoder.encode(password), now);
        } else {
            // The account was already active; only its membership changed. That is still an
            // authorization change, so the epoch moves for it too.
            user.revokeSessions();
        }

        log.info("Invitation {} redeemed by user {} for company {} (password set: {})",
                invitationId, user.getId(), invitation.getCompanyId(), passwordRequired);
        return new Accepted(
                user.getId(), invitation.getCompanyId(), user.getEmail(), passwordRequired);
    }

    /**
     * Resolves a presented secret to an unconsumed, unexpired invitation.
     *
     * <p>Runs with <strong>no company context</strong>, which is only possible because
     * {@code membership_invitation} is instance-level (ERD §0.1, decided in {@code V6}).
     */
    private Optional<MembershipInvitation> findRedeemable(String secret) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return Optional.ofNullable(transactions.execute(status ->
                        invitations.findByTokenHash(tokens.hash(secret)).orElse(null)))
                .filter(invitation -> invitation.isRedeemable(now));
    }
}
