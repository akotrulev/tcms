package com.tcms.provisioning;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What provisioning did — reported per part, not as one boolean.
 *
 * <p>The command is idempotent and re-runnable, which means "it succeeded" is not
 * enough information for the operator running it. Re-running against an existing company
 * to top up a vocabulary added in a later release is a normal, intended use; so is
 * discovering that the slug you meant to create is already taken by someone else's
 * client. Both are successes, and they must not read alike. Each flag below answers
 * "did this run create it, or was it already there".
 *
 * @param companyId        the company, whether created now or found by slug
 * @param companyCreated   false on a re-run
 * @param adminUserId      the first Company Admin's account
 * @param adminUserCreated false when the address already had an account — the expected
 *                         case for a person who works with more than one of your clients
 * @param membershipCreated false when they were already a member of this company
 * @param adminGrantCreated false when the COMPANY-scope grant already existed
 * @param baselineGrantsCreated how many members gained the {@code company:read} baseline
 *                         (V15) on this run — 1 on a fresh company, 0 on a healthy re-run,
 *                         and more than that exactly when the re-run repaired a company
 *                         whose members predate the baseline
 * @param projectId        the first project, when one was requested
 * @param invitationLink   the activation URL, present <strong>only</strong> when this run
 *                         minted the token. Empty when an unexpired invitation was already
 *                         outstanding (its secret is unrecoverable — only a digest is
 *                         stored) and when the administrator has already accepted.
 * @param invitationExpiry when the outstanding invitation lapses, minted by this run or not
 */
public record ProvisionCompanyResult(
        UUID companyId,
        boolean companyCreated,
        UUID adminUserId,
        boolean adminUserCreated,
        boolean membershipCreated,
        boolean adminGrantCreated,
        int baselineGrantsCreated,
        Optional<UUID> projectId,
        boolean projectCreated,
        Optional<String> invitationLink,
        Optional<Instant> invitationExpiry) {

    /**
     * True when this run changed nothing — everything already existed.
     *
     * <p>A freshly minted invitation counts as a change even when every other part existed:
     * re-running after the link lapsed is a real repair, and reporting it as a no-op would
     * tell the operator to look elsewhere for the thing that just got fixed.
     */
    public boolean wasNoOp() {
        // A baseline grant written is a change for the same reason: a re-run that repaired a
        // pre-V15 company's members did something, and reporting "nothing to do" would send the
        // operator looking elsewhere for the fix that just landed.
        return !companyCreated && !adminUserCreated && !membershipCreated && !adminGrantCreated
                && baselineGrantsCreated == 0 && !projectCreated && invitationLink.isEmpty();
    }
}
