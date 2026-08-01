package com.tcms.provisioning;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InvitationService;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-issues a membership invitation on an operator's instruction, and hands back the link.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An invitation's token is stored only as a digest ({@code InvitationTokens.hash}), so a
 * link that was never delivered cannot be recovered by anybody — including an operator with
 * superuser on the database. That is a deliberate property, not an oversight:
 * {@code InvitationServiceTest} pins it, because a readable token in that table would make a
 * database read enough to become a client's administrator.
 *
 * <p>The consequence, found on the first real deployment: on an instance with <strong>no SMTP
 * relay</strong>, an invitation raised from the UI was undeliverable. The API response carries
 * no URL and must not; the unconfigured mailer logs a warning without the link; and
 * {@code provision-company} both refuses to replace an outstanding invitation and only ever
 * addresses a company's <em>first</em> administrator. The UI told the invitee to "ask your
 * operator for the activation link" and the operator had no mechanism to produce one. This is
 * that mechanism.
 *
 * <h2>Ordering</h2>
 *
 * <p>The company id is resolved <strong>before</strong> the context opens, and the context
 * wraps the transaction rather than sitting inside it — {@code CompanyAwareTransactionManager}
 * reads the context in {@code doBegin}, so a context opened after the transaction began leaves
 * the RLS GUC unset and every read returns zero rows (handoff §15). Same shape as
 * {@link CompanyProvisioningService#provision}, for the same reason.
 */
@Service
public class IssueInvitationService {

    private static final Logger log = LoggerFactory.getLogger(IssueInvitationService.class);

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final InvitationService invitations;
    private final TransactionTemplate transactions;

    public IssueInvitationService(
            CompanyRepository companies,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            InvitationService invitations,
            TransactionTemplate transactions) {
        this.companies = companies;
        this.users = users;
        this.memberships = memberships;
        this.invitations = invitations;
        this.transactions = transactions;
    }

    /**
     * @param slug         the company the membership belongs to. Required, and not derived
     *                     from the address: one address can be a member of more than one
     *                     company on this instance, and guessing which would issue a link
     *                     into a client the operator did not name.
     * @param email        the invitee
     * @param actorUserId  the operator, or {@code null} for an unattended run — recorded as
     *                     the invitation's issuer
     */
    public IssueInvitationResult issue(String slug, String email, UUID actorUserId) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }

        Optional<Company> company = companies.findBySlug(slug);
        if (company.isEmpty()) {
            return IssueInvitationResult.noSuchCompany();
        }
        UUID companyId = company.get().getId();

        try (var ignored = CompanyContext.open(companyId)) {
            return transactions.execute(status -> doIssue(companyId, email, actorUserId));
        }
    }

    private IssueInvitationResult doIssue(UUID companyId, String email, UUID actorUserId) {
        Optional<AppUser> user = users.findByEmail(email);
        if (user.isEmpty()) {
            return IssueInvitationResult.notAMember(companyId);
        }
        UUID userId = user.get().getId();

        Optional<CompanyMembership> membership =
                memberships.findByCompanyIdAndUserId(companyId, userId);
        if (membership.isEmpty()) {
            // An account that exists but is not a member here. Same outcome as no account at
            // all — see IssueInvitationResult.Outcome.NOT_A_MEMBER for why they are not split.
            return IssueInvitationResult.notAMember(companyId);
        }
        String displayName = membership.get().getDisplayName();
        if (membership.get().isActive()) {
            return IssueInvitationResult.alreadyActive(companyId, userId, displayName);
        }

        InvitationService.IssueOutcome outcome =
                invitations.reissue(companyId, userId, actorUserId);
        if (outcome.acceptUrl().isEmpty()) {
            // reissue() only declines for a membership that is missing or active, both of
            // which are handled above — so reaching here means the two disagree about the
            // same row. Loud, because a silent empty link reads to the operator as "the
            // command worked and printed nothing".
            throw new IllegalStateException(
                    "reissue declined for a membership this command had already accepted; "
                            + "company=" + companyId + " user=" + userId);
        }

        // The URL is NOT logged — it is the credential. Only that one was issued, and to whom.
        log.info("Re-issued the invitation for user {} in company {} on operator instruction; "
                + "any previous token for this membership is now dead.", userId, companyId);

        return IssueInvitationResult.issued(
                companyId, userId, displayName,
                outcome.acceptUrl().orElseThrow(),
                outcome.expiresAt().orElseThrow());
    }
}
