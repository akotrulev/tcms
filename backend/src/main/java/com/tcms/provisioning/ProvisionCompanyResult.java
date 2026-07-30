package com.tcms.provisioning;

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
 * @param projectId        the first project, when one was requested
 */
public record ProvisionCompanyResult(
        UUID companyId,
        boolean companyCreated,
        UUID adminUserId,
        boolean adminUserCreated,
        boolean membershipCreated,
        boolean adminGrantCreated,
        Optional<UUID> projectId,
        boolean projectCreated) {

    /** True when this run changed nothing — everything already existed. */
    public boolean wasNoOp() {
        return !companyCreated && !adminUserCreated && !membershipCreated && !adminGrantCreated
                && !projectCreated;
    }
}
